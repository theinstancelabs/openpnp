package org.openpnp.instancelabs.vision;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.openpnp.instancelabs.dispense.DispenseExecutor;
import org.openpnp.instancelabs.dispense.DotImager;
import org.openpnp.instancelabs.vision.FiducialLocator.Failure;
import org.openpnp.instancelabs.vision.FiducialLocator.Fit;
import org.openpnp.instancelabs.vision.FiducialLocator.Found;
import org.openpnp.instancelabs.vision.FiducialLocator.Settings;
import org.openpnp.instancelabs.vision.FiducialLocator.Target;

/**
 * Search, centring and fit against a fake top camera that draws fiducials at true machine
 * positions. Each run prints its captures with the prefix "[fid-locate]".
 */
public class FiducialLocatorTest {
    static final double SX = FidScene.BENCH_X, SY = FidScene.BENCH_Y;

    /** ftp.board.xml fiducials (tools/register.py FID_BOARD). */
    static final double[][] FTP = {{62.4465812, 4.69103014}, {12.5, 70.0}, {62.5, 70.0}};
    static final String[] NAMES = {"FID1", "FID2", "FID3"};

    /**
     * Bench affines, board -> machine, rows [m00 m01 m02; m10 m11 m12] as in
     * runs/board_registration_*.json.
     */
    static final double[][] R7CHK = {{1.0010600000000005, 0.026175953773832035, 295.5854332358317},
            {-0.0313599999999999, 0.9942382394449237, 114.82032323885532}};
    static final double[][] REFLOW1 = {{0.9977999999999997, 0.07672297897365642, 295.29889147184406},
            {-0.08071999999999957, 0.9913234292980159, 115.17935994913886}};
    static final double[][] BOARD2 = {{1.0010600000000005, -0.006132012567133021, 295.92599087969927},
            {0.0013799999999998818, 0.9947167505675919, 114.61657746026857}};

    static AffineTransform affine(double[][] m) {
        return new AffineTransform(m[0][0], m[1][0], m[0][1], m[1][1], m[0][2], m[1][2]);
    }

    static double[] apply(AffineTransform t, double x, double y) {
        Point2D p = t.transform(new Point2D.Double(x, y), null);
        return new double[] {p.getX(), p.getY()};
    }

    /** Draws 1 mm fiducials (3 mm opening) at true machine positions around the camera. */
    static final class FakeCamera implements FiducialLocator.FrameSource {
        final List<double[]> marks = new ArrayList<>();
        final List<double[]> captures = new ArrayList<>();
        /** The board is drawn at trueX/Y x (1 + scaleError) mm per pixel. */
        double scaleError;
        double trueX = SX, trueY = SY;
        /** The scale the frames report, mm per pixel. */
        double reportedX = SX, reportedY = SY;
        /** Every capture moves the marks by +-jitterMm in X, alternating. */
        double jitterMm;
        int w = FidScene.W, h = FidScene.H;

        FakeCamera mark(double x, double y) {
            marks.add(new double[] {x, y});
            return this;
        }

        @Override
        public DotImager.Frame capture(double x, double y) {
            captures.add(new double[] {x, y});
            double j = (captures.size() % 2 == 0 ? -1 : 1) * jitterMm;
            BufferedImage img = FidScene.render(w, h, trueX * (1 + scaleError),
                    trueY * (1 + scaleError),
                    g -> {
                        for (double[] m : marks) {
                            FidScene.fiducial(g, m[0] + j - x, m[1] - y, 1.0, 3.0);
                        }
                    });
            System.out.println(String.format(Locale.ROOT, "[fid-locate] capture %d at %.3f, %.3f",
                    captures.size(), x, y));
            return new DotImager.Frame(img, reportedX, reportedY, x, y);
        }
    }

    static Target target(String name, double bx, double by, double ex, double ey) {
        return new Target(name, bx, by, ex, ey, 1.0);
    }

    static FiducialLocator locator(FakeCamera cam, List<String> steps) {
        return new FiducialLocator(new Settings(), cam, () -> false, null, steps::add);
    }

    @Test
    public void convergesInAtMostTwoMoves() throws Exception {
        for (double err : new double[] {0, 0.03, -0.03}) {
            FakeCamera cam = new FakeCamera().mark(302.0, 154.4);
            cam.scaleError = err;
            List<String> steps = new ArrayList<>();
            Found f = locator(cam, steps).locateAll(
                    Arrays.asList(target("FID1", 10, 10, 300.0, 150.0))).get(0);
            System.out.println("[fid-locate] scale error " + err + ": " + f.iterations
                    + " looks, " + steps);
            assertEquals(302.0, f.x, 0.005);
            assertEquals(154.4, f.y, 0.005);
            assertEquals(Math.hypot(2.0, 4.4), f.offsetFromExpectedMm, 0.005);
            assertTrue(f.iterations <= 3, "at most 2 moves after the first look");
            assertEquals(err == 0 ? 2 : 3, f.iterations, "a scale error costs one more move");
            assertTrue(f.lastOffsetPx < 3);
            assertEquals(1, f.frames, "found in the first search frame");
            assertEquals(cam.captures.size(), f.frames + f.iterations - 1);
            assertEquals("fiducials: FID1 (1 of 1)", steps.get(0));
            if (steps.size() > 1) {
                assertEquals("fiducials: FID1 (1 of 1) · move 2", steps.get(1));
            }
        }
    }

    @Test
    public void searchTilesAtTheDefaults() {
        double hy = FidScene.H * SY / 2 - 1.2;
        List<double[]> t = FiducialLocator.searchTiles(FidScene.W, FidScene.H, SX, SY, 1.2, 8.0);
        assertEquals(3, t.size());
        assertArrayEquals(new double[] {0, 0}, t.get(0), 1e-12);
        assertArrayEquals(new double[] {0, 2 * hy}, t.get(1), 1e-12);
        assertArrayEquals(new double[] {0, -2 * hy}, t.get(2), 1e-12);
        assertEquals(1, FiducialLocator.searchTiles(FidScene.W, FidScene.H, SX, SY, 1.2, 4.0)
                .size(), "the first frame covers a 4 mm circle");
        assertEquals(3, FiducialLocator.searchFrameCount(FidScene.W, FidScene.H, SX, SY, 1.2,
                8.0));
        assertEquals(9, FiducialLocator.searchFrameCount(FidScene.W, FidScene.H, SX, SY, 1.2,
                12.0), "12 mm (the largest setting) needs the 3 x 3 frames, the cap");
        List<double[]> small = FiducialLocator.searchTiles(640, 480, SX, SY, 1.2, 8.0);
        assertTrue(FiducialLocator.searchFrameCount(640, 480, SX, SY, 1.2, 8.0) > 9);
        assertEquals(FiducialLocator.MAX_SEARCH_FRAMES, small.size(), "capped at 9");
        for (int i = 1; i < small.size(); i++) {
            assertTrue(Math.hypot(small.get(i)[0], small.get(i)[1]) >= Math.hypot(
                    small.get(i - 1)[0], small.get(i - 1)[1]) - 1e-9, "nearest first");
        }
    }

    @Test
    public void searchTilesFindFarMarks() throws Exception {
        double tile = 2 * (FidScene.H * SY / 2 - 1.2);
        // 7 mm off in +Y: outside frame 1 (half height 5.97 mm), found in frame 2.
        FakeCamera up = new FakeCamera().mark(300.3, 157.0);
        List<String> steps = new ArrayList<>();
        Found f = locator(up, steps).locateAll(Arrays.asList(target("FID1", 0, 0, 300, 150)))
                .get(0);
        assertEquals(300.3, f.x, 0.005);
        assertEquals(157.0, f.y, 0.005);
        assertEquals(2, f.frames);
        assertArrayEquals(new double[] {300, 150 + tile}, up.captures.get(1), 1e-9);
        assertEquals("fiducials: FID1 (1 of 1) · frame 2 of 3", steps.get(1));
        // -7 mm: frame 3.
        FakeCamera down = new FakeCamera().mark(299.5, 143.0);
        Found g = locator(down, new ArrayList<>())
                .locateAll(Arrays.asList(target("FID1", 0, 0, 300, 150))).get(0);
        assertEquals(143.0, g.y, 0.005);
        assertEquals(3, g.frames);
        assertArrayEquals(new double[] {300, 150 - tile}, down.captures.get(2), 1e-9);
        // 9 mm off: frame 2 sees it, but it lies outside the 8 mm search radius.
        FakeCamera far = new FakeCamera().mark(300.0, 159.0);
        Failure e = assertThrows(Failure.class, () -> locator(far, new ArrayList<>())
                .locateAll(Arrays.asList(target("FID2", 0, 0, 300, 150))));
        System.out.println("[fid-locate] " + e.getMessage());
        assertEquals(Failure.NOT_FOUND, e.code);
        assertEquals("FID2", e.target.name);
        assertEquals(3, far.captures.size());
        assertEquals("REG: FID2 not found within 8.0 mm of X 300.00, Y 150.00 (3 camera frames)."
                + " Is FID2 clean and uncovered, and does the board sit roughly where it was"
                + " planned? Check the board X/Y in the Board step (Use camera X/Y on the board"
                + " origin), then register again.", e.getMessage());
        assertEquals(e.getMessage(), e.messageFor(false));
        assertTrue(e.messageFor(true).endsWith("(Use camera X/Y on the board origin), or run"
                + " OpenPnP's fiducial check on this job board, then register again."),
                e.messageFor(true));
    }

    /** The bench re-seats, from their previous registrations' predictions. */
    @Test
    public void benchReseats() throws Exception {
        // runs/board_registration_board2.json vs its previous (reflow1): 4.60 / 5.18 / 6.91 mm.
        reseat("board2", REFLOW1, BOARD2, new double[] {4.60, 5.18, 6.91});
        // runs/board_registration_reflow1.json vs its previous (r7chk): rotation -1.8 -> -4.6 deg.
        reseat("reflow1", R7CHK, REFLOW1, new double[] {2.75, 3.24, 4.23});
    }

    private void reseat(String name, double[][] previous, double[][] now, double[] moved)
            throws Exception {
        AffineTransform prev = affine(previous);
        AffineTransform truth = affine(now);
        FakeCamera cam = new FakeCamera();
        // Frames report OpenPnP's Top camera scale (machine.xml) while the board is drawn at the
        // bench's measured scale: 0.6 % off in X, 3 % in Y (REAL_MACHINE_TODO).
        cam.reportedX = 0.01068376;
        cam.reportedY = 0.01072961;
        List<Target> targets = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            double[] t = apply(truth, FTP[i][0], FTP[i][1]);
            double[] e = apply(prev, FTP[i][0], FTP[i][1]);
            cam.mark(t[0], t[1]);
            targets.add(target(NAMES[i], FTP[i][0], FTP[i][1], e[0], e[1]));
        }
        List<String> found = new ArrayList<>();
        FiducialLocator loc = new FiducialLocator(new Settings(), cam, () -> false,
                f -> found.add(f.target.name), null);
        List<Found> got = loc.locateAll(targets);
        assertEquals(Arrays.asList("FID1", "FID3", "FID2"), found, "nearest first from FID1");
        for (int i = 0; i < 3; i++) {
            Found f = got.get(i);
            assertSame(targets.get(i), f.target, "results in the order of the targets");
            double[] t = apply(truth, FTP[i][0], FTP[i][1]);
            assertEquals(t[0], f.x, 0.005, name + " " + NAMES[i]);
            assertEquals(t[1], f.y, 0.005, name + " " + NAMES[i]);
            assertEquals(moved[i], f.offsetFromExpectedMm, 0.01);
            System.out.println(String.format(Locale.ROOT,
                    "[fid-locate] %s %s moved %.2f mm: %d frames, %d looks, last %.2f px", name,
                    NAMES[i], f.offsetFromExpectedMm, f.frames, f.iterations, f.lastOffsetPx));
        }
        Fit fit = FiducialLocator.fit(got, Fit.of(prev, "affine-3", null), new Settings());
        assertEquals("affine-3", fit.method);
        double[] m = new double[6];
        double[] e = new double[6];
        fit.boardToMachine.getMatrix(m);
        truth.getMatrix(e);
        assertArrayEquals(Arrays.copyOf(e, 4), Arrays.copyOf(m, 4), 2e-4, name);
        assertEquals(e[4], m[4], 0.02);
        assertEquals(e[5], m[5], 0.02);
    }

    /**
     * A re-seat the 8 mm search reaches only through the prediction updates: moved (-5, -3) mm and
     * turned 6 deg about FID1, FID3 and FID2 lie 12.3 and 14.4 mm from their first predictions.
     * FID1's offset brings FID3 within reach (the turn still leaves it 6.8 mm off); FID2 is still
     * 8.6 mm off after that translation, and the similarity of FID1 and FID3 puts it into its
     * first frame.
     */
    @Test
    public void predictionsFollowTheFirstFinds() throws Exception {
        AffineTransform prev = affine(BOARD2);
        double[] fid1 = apply(prev, FTP[0][0], FTP[0][1]);
        AffineTransform truth = AffineTransform.getTranslateInstance(-5, -3);
        truth.rotate(Math.toRadians(6), fid1[0], fid1[1]);
        truth.concatenate(prev);
        FakeCamera cam = new FakeCamera();
        List<Target> targets = new ArrayList<>();
        double[][] t = new double[3][];
        double[][] e = new double[3][];
        for (int i = 0; i < 3; i++) {
            t[i] = apply(truth, FTP[i][0], FTP[i][1]);
            e[i] = apply(prev, FTP[i][0], FTP[i][1]);
            cam.mark(t[i][0], t[i][1]);
            targets.add(target(NAMES[i], FTP[i][0], FTP[i][1], e[i][0], e[i][1]));
        }
        double dx = t[0][0] - e[0][0];
        double dy = t[0][1] - e[0][1];
        assertTrue(Math.hypot(t[2][0] - e[2][0], t[2][1] - e[2][1]) > 8, "FID3 needs the update");
        assertTrue(Math.hypot(t[2][0] - e[2][0] - dx, t[2][1] - e[2][1] - dy) < 8);
        assertTrue(Math.hypot(t[1][0] - e[1][0] - dx, t[1][1] - e[1][1] - dy) > 8,
                "FID2 needs more than the translation");
        List<String> order = new ArrayList<>();
        List<Found> got = new FiducialLocator(new Settings(), cam, () -> false,
                f -> order.add(f.target.name), null).locateAll(targets);
        assertEquals(Arrays.asList("FID1", "FID3", "FID2"), order);
        for (int i = 0; i < 3; i++) {
            assertEquals(t[i][0], got.get(i).x, 0.005, NAMES[i]);
            assertEquals(t[i][1], got.get(i).y, 0.005, NAMES[i]);
        }
        Found fid2 = got.get(1);
        assertEquals(1, fid2.frames, "FID2 predicted by the similarity of FID1 and FID3");
        double[] firstLook = cam.captures.get(cam.captures.size() - fid2.iterations);
        assertTrue(Math.hypot(firstLook[0] - t[1][0], firstLook[1] - t[1][1]) < 1.0,
                Arrays.toString(firstLook));
        Fit previous = Fit.of(prev, "affine-3", null);
        Fit fit = FiducialLocator.fit(got, previous, new Settings());
        assertEquals(previous.rotationDeg + 6, fit.rotationDeg, 0.01);
    }

    @Test
    public void notCentredFails() {
        FakeCamera cam = new FakeCamera().mark(302.0, 151.0);
        cam.jitterMm = 0.1;
        Failure e = assertThrows(Failure.class, () -> locator(cam, new ArrayList<>())
                .locateAll(Arrays.asList(target("FID1", 0, 0, 300, 150))));
        System.out.println("[fid-locate] " + e.getMessage());
        assertEquals(Failure.NOT_CENTRED, e.code);
        assertEquals(3, cam.captures.size());
        assertTrue(e.getMessage().matches("REG: FID1 did not hold still in the camera \\(still"
                + " \\d+\\.\\d px off after 3 moves\\): wipe FID1 \\(paste or dust on it\\?\\) and"
                + " register again"), e.getMessage());
    }

    @Test
    public void ambiguousFails() {
        FakeCamera cam = new FakeCamera().mark(301.0, 150.0).mark(298.0, 151.0);
        Failure e = assertThrows(Failure.class, () -> locator(cam, new ArrayList<>())
                .locateAll(Arrays.asList(target("FID2", 0, 0, 300, 150))));
        assertEquals(Failure.AMBIGUOUS, e.code);
        assertEquals("REG: another round mark near FID2 (within 8.0 mm of X 300.00, Y 150.00):"
                + " look with the camera there, then register again", e.getMessage());
    }

    @Test
    public void abortBetweenCaptures() {
        FakeCamera cam = new FakeCamera().mark(302.0, 151.0);
        AtomicInteger asked = new AtomicInteger();
        FiducialLocator loc = new FiducialLocator(new Settings(), cam,
                () -> asked.incrementAndGet() > 1, null, null);
        DispenseExecutor.AbortedException e = assertThrows(DispenseExecutor.AbortedException.class,
                () -> loc.locateAll(Arrays.asList(target("FID1", 0, 0, 300, 150))));
        assertEquals("stopped during the fiducial check", e.getMessage());
        assertEquals(1, cam.captures.size(), "stops before the second capture");
        FakeCamera idle = new FakeCamera();
        assertThrows(DispenseExecutor.AbortedException.class,
                () -> new FiducialLocator(new Settings(), idle, () -> true, null, null)
                        .locateAll(Arrays.asList(target("FID1", 0, 0, 300, 150))));
        assertEquals(0, idle.captures.size());
    }

    // ------------------------------------------------------------------------------------------
    // Fit

    static List<Found> found(AffineTransform t, double[][] board, String... names) {
        List<Found> out = new ArrayList<>();
        for (int i = 0; i < board.length; i++) {
            Found f = new Found();
            f.target = target(names[i], board[i][0], board[i][1], 0, 0);
            double[] m = apply(t, board[i][0], board[i][1]);
            f.x = m[0];
            f.y = m[1];
            out.add(f);
        }
        return out;
    }

    @Test
    public void fitThreeIsExact() throws Exception {
        AffineTransform a = affine(BOARD2);
        Fit fit = FiducialLocator.fit(found(a, FTP, NAMES), null, new Settings());
        double[] m = new double[6];
        double[] e = new double[6];
        fit.boardToMachine.getMatrix(m);
        a.getMatrix(e);
        assertArrayEquals(e, m, 1e-9);
        assertEquals("affine-3", fit.method);
        assertTrue(fit.maxResidualMm < 1e-9);
        Fit d = Fit.of(a, "affine-3", null);
        assertEquals(d.scaleX, fit.scaleX, 1e-12);
        assertEquals(d.scaleY, fit.scaleY, 1e-12);
        assertEquals(d.shearDeg, fit.shearDeg, 1e-9);
        // The bench's machine distortion (runs/board_registration*.json: X 1.0011-1.0016,
        // Y 0.9942-0.9947, shear -0.20 .. -0.28 deg).
        assertEquals(1.00106, fit.scaleX, 1e-4);
        assertEquals(0.99472, fit.scaleY, 1e-4);
        assertEquals(-0.27, fit.shearDeg, 0.01);
        assertEquals(0.08, fit.rotationDeg, 0.01);
    }

    @Test
    public void fitFourIsLeastSquares() throws Exception {
        AffineTransform a = affine(BOARD2);
        double[][] board = {FTP[0], FTP[1], FTP[2], {37.0, 37.0}};
        List<Found> f = found(a, board, "FID1", "FID2", "FID3", "FID4");
        f.get(3).x += 0.02;
        Fit fit = FiducialLocator.fit(f, null, new Settings());
        assertEquals("affine-lsq-4", fit.method);
        assertEquals(4, fit.residualsMm.length);
        assertTrue(fit.maxResidualMm > 0.005 && fit.maxResidualMm <= 0.05, "" + fit.maxResidualMm);
        f.get(3).x += 0.2;
        Failure e = assertThrows(Failure.class, () -> FiducialLocator.fit(f, null, new Settings()));
        System.out.println("[fid-locate] " + e.getMessage());
        assertEquals(Failure.FIT, e.code);
        assertTrue(e.getMessage().matches("REG: FID4 is 0\\.\\d\\d mm off where the other"
                + " fiducials put it \\(limit 0\\.05 mm\\): it was probably found on the wrong"
                + " mark"), e.getMessage());
    }

    @Test
    public void fitTwoIsSimilarity() throws Exception {
        AffineTransform a = new AffineTransform();
        a.translate(300, 120);
        a.rotate(Math.toRadians(-4.6));
        a.scale(1.002, 1.002);
        double[][] board = {FTP[1], FTP[0]};
        Fit fit = FiducialLocator.fit(found(a, board, "FID2", "FID1"), null, new Settings());
        assertEquals("similarity-2", fit.method);
        assertEquals(1.002, fit.scaleX, 1e-9);
        assertEquals(fit.scaleX, fit.scaleY, 1e-9);
        assertEquals(-4.6, fit.rotationDeg, 1e-9);
        assertEquals(0, fit.shearDeg, 1e-9);
        AffineTransform big = new AffineTransform(a);
        big.scale(1.012 / 1.002, 1.012 / 1.002);
        Failure e = assertThrows(Failure.class, () -> FiducialLocator.fit(
                found(big, board, "FID2", "FID1"), null, new Settings()));
        assertEquals(Failure.FIT, e.code);
    }

    @Test
    public void absoluteLimits() {
        double[][] board = FTP;
        // 1.5 % scale in X.
        AffineTransform scale = new AffineTransform(1.015, 0, 0, 1.0, 300, 115);
        Failure e = assertThrows(Failure.class,
                () -> FiducialLocator.fit(found(scale, board, NAMES), null, new Settings()));
        System.out.println("[fid-locate] " + e.getMessage());
        assertEquals(Failure.FIT, e.code);
        assertTrue(e.getMessage().startsWith("REG: the fiducials do not fit the board: FID2 to"
                + " FID3 measure 50.75 mm, the board file says 50.00 mm. One was probably found"
                + " on the wrong mark: look with the camera at FID2 and FID3, then register"
                + " again."), e.getMessage());
        // 0.8 deg of shear.
        AffineTransform shear = new AffineTransform(1, 0, Math.tan(Math.toRadians(0.8)), 1, 300,
                115);
        assertEquals(Failure.FIT, assertThrows(Failure.class,
                () -> FiducialLocator.fit(found(shear, board, NAMES), null, new Settings())).code);
        // The bench's own distortion passes.
        AffineTransform ok = new AffineTransform(1.0099, 0, Math.tan(Math.toRadians(-0.45)),
                0.9902, 300, 115);
        assertThrowsNothing(() -> FiducialLocator.fit(found(ok, board, NAMES), null,
                new Settings()));
    }

    @Test
    public void limitsAgainstThePreviousRegistration() throws Exception {
        AffineTransform prev = affine(BOARD2);
        Fit previous = Fit.of(prev, "affine-3", 1761670000000L);
        // Re-seated: moved 5 mm and turned 2 deg, the same machine distortion.
        AffineTransform seat = AffineTransform.getRotateInstance(Math.toRadians(2), 330, 150);
        seat.translate(5, -3);
        seat.concatenate(prev);
        Fit fit = FiducialLocator.fit(found(seat, FTP, NAMES), previous, new Settings());
        assertEquals(Math.toDegrees(Math.atan2(prev.getShearY(), prev.getScaleX())) + 2,
                fit.rotationDeg, 1e-6);
        // One fiducial found 0.3 mm off: within the absolute limits, not against the last seat.
        List<Found> f = found(prev, FTP, NAMES);
        f.get(2).x += 0.3;
        FiducialLocator.fit(f, null, new Settings());
        Failure e = assertThrows(Failure.class, () -> FiducialLocator.fit(f, previous,
                new Settings()));
        System.out.println("[fid-locate] " + e.getMessage());
        assertEquals(Failure.FIT, e.code);
        assertTrue(e.getMessage().matches("REG: the fiducials do not fit this board's last"
                + " registration \\(\\d\\d:\\d\\d\\): FID2 to FID3 measure 50\\.35 mm, 50\\.05 mm"
                + " then\\. One was probably found on the wrong mark: look with the camera, then"
                + " register again\\."), e.getMessage());
        // 0.3 mm in Y on FID2 turns the shear by about 0.34 deg (-0.27 -> +0.07: still within
        // the absolute 0.5 deg).
        List<Found> g = found(prev, FTP, NAMES);
        g.get(1).y -= 0.3;
        Fit sheared = FiducialLocator.fit(g, null, new Settings());
        assertEquals(previous.shearDeg + 0.34, sheared.shearDeg, 0.02);
        Failure s = assertThrows(Failure.class,
                () -> FiducialLocator.fit(g, previous, new Settings()));
        assertTrue(s.getMessage().startsWith(
                "REG: the fiducials do not fit this board's last registration"), s.getMessage());
    }

    @Test
    public void tooFewFound() {
        Failure e = assertThrows(Failure.class, () -> FiducialLocator.fit(
                found(affine(BOARD2), new double[][] {FTP[0]}, "FID1"), null, new Settings()));
        assertEquals(Failure.FEW, e.code);
        assertEquals("REG: 1 fiducial found (FID1); Register board needs at least 2. Check the"
                + " board X/Y in the Board step (Use camera X/Y on the board origin) and probe by"
                + " hand (Do it by hand).", e.getMessage());
        Settings three = new Settings();
        three.minFiducials = 3;
        assertEquals(Failure.FEW, assertThrows(Failure.class, () -> FiducialLocator.fit(
                found(affine(BOARD2), new double[][] {FTP[0], FTP[1]}, "FID1", "FID2"), null,
                three)).code);
    }

    interface Body {
        void run() throws Exception;
    }

    static void assertThrowsNothing(Body b) {
        try {
            b.run();
        }
        catch (Exception e) {
            throw new AssertionError("unexpected: " + e.getMessage(), e);
        }
    }
}
