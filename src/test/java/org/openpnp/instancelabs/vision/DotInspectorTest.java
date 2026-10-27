package org.openpnp.instancelabs.vision;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.openpnp.instancelabs.vision.Synth.Background;
import org.openpnp.instancelabs.vision.Synth.Deposit;
import org.openpnp.instancelabs.vision.Synth.Shot;

/**
 * Accuracy and false-positive tests on synthetic images. Every test prints its measured errors
 * (prefix "[dot-vision]") so the numbers can be quoted.
 */
public class DotInspectorTest {
    static final double[] MM_PER_PX = {0.02, 0.035, 0.05};
    static final double[] DIAMETERS = {0.3, 0.5, 0.8, 1.2};

    /** Dot positions: 4 x 2 grid that coincides with the FR4 pad centres. */
    static final double[][] GRID = {
            {-4.2, 1.5}, {-1.4, 1.5}, {1.4, 1.5}, {4.2, 1.5},
            {-4.2, -1.5}, {-1.4, -1.5}, {1.4, -1.5}, {4.2, -1.5}};

    static final class Truth {
        final double x, y, d;

        Truth(double x, double y, double d) {
            this.x = x;
            this.y = y;
            this.d = d;
        }
    }

    static List<Truth> layout(Background bg, long seed) {
        Random r = new Random(seed);
        double jitter = bg == Background.FR4 ? 0.1 : 0.3;
        List<Truth> t = new ArrayList<>();
        for (int i = 0; i < GRID.length; i++) {
            t.add(new Truth(GRID[i][0] + jitter * (2 * r.nextDouble() - 1),
                    GRID[i][1] + jitter * (2 * r.nextDouble() - 1), DIAMETERS[i % DIAMETERS.length]));
        }
        return t;
    }

    static Shot base(Background bg, double mmPerPx) {
        Shot s = new Shot();
        s.background = bg;
        s.mmPerPx = mmPerPx;
        s.noiseSeed = 11;
        return s;
    }

    static Shot after(Shot before, List<Truth> dots) {
        Shot a = before.copy();
        a.noiseSeed = before.noiseSeed + 1000;
        for (Truth t : dots) {
            a.deposits.add(Synth.disk(t.x, t.y, t.d));
        }
        return a;
    }

    /** Worst-case errors over a set of runs. */
    static final class Stats {
        double maxDiamErrPct, maxDiamErrPx, maxCentroidErrPx, sumAbsDiamErrPct;
        int n;
    }

    /**
     * Matches expected dots to detected ones, asserts the tolerances and that nothing else was
     * found, and folds the errors into stats.
     */
    static void check(String label, List<Truth> truth, List<Dot> found, double mmPerPx,
            Stats stats) {
        List<Dot> unmatched = new ArrayList<>(found);
        for (Truth t : truth) {
            Dot best = null;
            double bestDist = Double.MAX_VALUE;
            for (Dot d : unmatched) {
                double dist = Math.hypot(d.xMm - t.x, d.yMm - t.y);
                if (dist < bestDist) {
                    bestDist = dist;
                    best = d;
                }
            }
            if (best == null || bestDist > t.d / 2) {
                fail(label + ": dot d=" + t.d + " at (" + t.x + "," + t.y + ") not found; found "
                        + found);
            }
            unmatched.remove(best);
            double centroidPx = bestDist / mmPerPx;
            double diamErr = Math.abs(best.diameterMm - t.d);
            double diamErrPct = 100 * diamErr / t.d;
            double diamErrPx = diamErr / mmPerPx;
            System.out.println(String.format(Locale.ROOT,
                    "[dot-vision] %-28s d=%.2fmm meas=%.4fmm err=%5.2f%% (%.2fpx) centroid=%.3fpx circ=%.3f",
                    label, t.d, best.diameterMm, diamErrPct, diamErrPx, centroidPx,
                    best.circularity));
            double tol = Math.max(0.05 * t.d, 1.5 * mmPerPx);
            assertTrue(diamErr <= tol, label + ": diameter error " + diamErr + " mm > " + tol
                    + " for d=" + t.d);
            assertTrue(centroidPx < 1.0, label + ": centroid error " + centroidPx + " px");
            stats.maxDiamErrPct = Math.max(stats.maxDiamErrPct, diamErrPct);
            stats.maxDiamErrPx = Math.max(stats.maxDiamErrPx, diamErrPx);
            stats.maxCentroidErrPx = Math.max(stats.maxCentroidErrPx, centroidPx);
            stats.sumAbsDiamErrPct += diamErrPct;
            stats.n++;
        }
        assertTrue(unmatched.isEmpty(), label + ": false positives " + unmatched);
    }

    static void summary(String name, Stats s) {
        System.out.println(String.format(Locale.ROOT,
                "[dot-vision] SUMMARY %s: n=%d maxDiamErr=%.2f%% (%.2fpx) meanDiamErr=%.2f%% maxCentroidErr=%.3fpx",
                name, s.n, s.maxDiamErrPct, s.maxDiamErrPx, s.sumAbsDiamErrPct / Math.max(1, s.n),
                s.maxCentroidErrPx));
    }

    @Test
    public void accuracyAcrossBackgroundsAndScales() {
        DotInspector inspector = new DotInspector(new DotInspectorSettings());
        Stats all = new Stats();
        long seed = 1;
        for (Background bg : Background.values()) {
            for (double mm : MM_PER_PX) {
                Shot b = base(bg, mm);
                List<Truth> truth = layout(bg, seed++);
                Shot a = after(b, truth);
                // Slight exposure change between the two frames as well.
                a.gain = 1.04;
                a.offset = -3;
                List<Dot> found = inspector.inspect(Synth.render(b), Synth.render(a), mm, mm);
                check(bg + "@" + mm, truth, found, mm, all);
            }
        }
        summary("accuracy (3 backgrounds x 3 scales x 8 dots, gain 1.04 offset -3)", all);
    }

    @Test
    public void accuracyWithSmallShiftBetweenFrames() {
        DotInspector inspector = new DotInspector(new DotInspectorSettings());
        Stats all = new Stats();
        double[][] shifts = {{1.3, -0.7}, {-0.4, 2.2}};
        long seed = 100;
        for (Background bg : Background.values()) {
            for (double[] sh : shifts) {
                double mm = 0.035;
                Shot b = base(bg, mm);
                List<Truth> truth = layout(bg, seed++);
                Shot a = after(b, truth);
                a.shiftXPx = sh[0];
                a.shiftYPx = sh[1];
                // The camera moved, so the dots appear moved in "after"; the inspector aligns
                // "after" onto "before" and reports them in the before frame, i.e. at the scene
                // coordinates.
                List<Truth> expected = truth;
                try (DotInspector.Analysis an =
                        inspector.analyze(Synth.render(b), Synth.render(a))) {
                    System.out.println(String.format(Locale.ROOT,
                            "[dot-vision] shift %s true=(%.2f,%.2f) est=(%.3f,%.3f) ecc=%.4f applied=%s",
                            bg, sh[0], sh[1], an.shiftXPx, an.shiftYPx, an.eccCorrelation,
                            an.shiftApplied));
                    assertTrue(an.shiftApplied, "shift not applied for " + bg);
                    assertEquals(sh[0], an.shiftXPx, 0.15);
                    assertEquals(sh[1], an.shiftYPx, 0.15);
                }
                List<Dot> found = inspector.inspect(Synth.render(b), Synth.render(a), mm, mm);
                check(bg + " shift(" + sh[0] + "," + sh[1] + ")", expected, found, mm, all);
            }
        }
        summary("shifted frames", all);
    }

    @Test
    public void shiftedTexturedFramesWithoutRegistrationWouldFalseTrigger() {
        // Documents why registration is on by default: 1.3 px of re-positioning error on copper.
        DotInspectorSettings s = new DotInspectorSettings();
        s.registerShift = false;
        Shot b = base(Background.COPPER, 0.035);
        Shot a = b.copy();
        a.noiseSeed = 77;
        a.shiftXPx = 1.3;
        a.shiftYPx = -0.7;
        BufferedImage bi = Synth.render(b);
        BufferedImage ai = Synth.render(a);
        int without = new DotInspector(s).inspect(bi, ai, 0.035, 0.035).size();
        int with = new DotInspector(new DotInspectorSettings()).inspect(bi, ai, 0.035, 0.035).size();
        System.out.println("[dot-vision] copper shifted 1.3/-0.7 px, no dots: false positives without "
                + "registration=" + without + ", with=" + with);
        assertEquals(0, with);
    }

    @Test
    public void noFalsePositivesOnNoiseOnlyPairs() {
        DotInspector inspector = new DotInspector(new DotInspectorSettings());
        for (Background bg : Background.values()) {
            for (double mm : MM_PER_PX) {
                Shot b = base(bg, mm);
                Shot a = b.copy();
                a.noiseSeed = 4242;
                List<Dot> found = inspector.inspect(Synth.render(b), Synth.render(a), mm, mm);
                System.out.println("[dot-vision] noise-only " + bg + "@" + mm + ": " + found.size()
                        + " dots");
                assertTrue(found.isEmpty(), bg + "@" + mm + " noise-only: " + found);
                assertEquals(0.0, inspector.padCoverage(Synth.render(b), Synth.render(a), mm, mm,
                        Synth.PAD_X0, Synth.PAD_Y0, Synth.PAD_W, Synth.PAD_H, 0), 1e-9);
            }
        }
    }

    @Test
    public void noFalsePositivesOnGlobalBrightnessShift() {
        DotInspector inspector = new DotInspector(new DotInspectorSettings());
        double[][] changes = {
                // gain, offset, gradX, gradY
                {1.10, 8, 0, 0}, {0.90, -6, 0, 0}, {1.0, 15, 0, 0}, {1.06, 0, 6, -5}};
        for (Background bg : Background.values()) {
            for (double mm : MM_PER_PX) {
                for (double[] c : changes) {
                    Shot b = base(bg, mm);
                    Shot a = b.copy();
                    a.noiseSeed = 99;
                    a.gain = c[0];
                    a.offset = c[1];
                    a.gradX = c[2];
                    a.gradY = c[3];
                    List<Dot> found = inspector.inspect(Synth.render(b), Synth.render(a), mm, mm);
                    String label = String.format(Locale.ROOT, "%s@%s gain=%.2f off=%.0f grad=(%.0f,%.0f)",
                            bg, mm, c[0], c[1], c[2], c[3]);
                    System.out.println("[dot-vision] brightness " + label + ": " + found.size()
                            + " dots");
                    assertTrue(found.isEmpty(), label + ": " + found);
                }
            }
        }
    }

    @Test
    public void grayscaleImagesWork() {
        DotInspector inspector = new DotInspector(new DotInspectorSettings());
        Stats all = new Stats();
        for (double mm : MM_PER_PX) {
            Shot b = base(Background.PAPER, mm);
            b.gray = true;
            List<Truth> truth = layout(Background.PAPER, 500 + (long) (mm * 1000));
            Shot a = after(b, truth);
            a.gain = 0.95;
            List<Dot> found = inspector.inspect(Synth.render(b), Synth.render(a), mm, mm);
            check("PAPER gray@" + mm, truth, found, mm, all);
        }
        summary("grayscale paper", all);
    }

    @Test
    public void padCoverage() {
        DotInspector inspector = new DotInspector(new DotInspectorSettings());
        double maxErr = 0;
        for (double mm : MM_PER_PX) {
            for (double rot : new double[] {0, 30, 90}) {
                double rad = Math.toRadians(rot);
                // Pad centre (board frame PAD_X0, PAD_Y0) rotated into the image frame.
                double cx = Synth.PAD_X0 * Math.cos(rad) - Synth.PAD_Y0 * Math.sin(rad);
                double cy = Synth.PAD_X0 * Math.sin(rad) + Synth.PAD_Y0 * Math.cos(rad);
                // Pad-local offset (u, v) -> image frame.
                double ux = Math.cos(rad), uy = Math.sin(rad);
                double vx = -Math.sin(rad), vy = Math.cos(rad);
                List<List<Deposit>> cases = new ArrayList<>();
                List<String> names = new ArrayList<>();
                names.add("none");
                cases.add(new ArrayList<>());
                names.add("full print");
                cases.add(List.of(Synth.rect(cx, cy, Synth.PAD_W, Synth.PAD_H, rot)));
                names.add("half print");
                cases.add(List.of(Synth.rect(cx - 0.2 * ux, cy - 0.2 * uy, 0.4, Synth.PAD_H, rot)));
                names.add("dot 0.6 centred");
                cases.add(List.of(Synth.disk(cx, cy, 0.6)));
                names.add("dot 0.7 off-centre");
                cases.add(List.of(Synth.disk(cx + 0.3 * ux + 0.25 * vx, cy + 0.3 * uy + 0.25 * vy, 0.7)));
                names.add("two dots 0.45");
                cases.add(List.of(Synth.disk(cx + 0.24 * vx, cy + 0.24 * vy, 0.45),
                        Synth.disk(cx - 0.24 * vx, cy - 0.24 * vy, 0.45)));
                for (int k = 0; k < cases.size(); k++) {
                    Shot b = base(Background.FR4, mm);
                    b.boardRotDeg = rot;
                    Shot a = b.copy();
                    a.noiseSeed = 555;
                    a.deposits.addAll(cases.get(k));
                    a.gain = 1.03;
                    double truth = Synth.trueCoverage(cases.get(k), cx, cy, Synth.PAD_W,
                            Synth.PAD_H, rot);
                    double got = inspector.padCoverage(Synth.render(b), Synth.render(a), mm, mm,
                            cx, cy, Synth.PAD_W, Synth.PAD_H, rot);
                    double err = Math.abs(got - truth);
                    maxErr = Math.max(maxErr, err);
                    System.out.println(String.format(Locale.ROOT,
                            "[dot-vision] coverage @%.3f rot=%3.0f %-20s true=%.4f meas=%.4f err=%.4f",
                            mm, rot, names.get(k), truth, got, err));
                    assertEquals(truth, got, 0.05, names.get(k) + " @" + mm + " rot " + rot);
                }
            }
        }
        System.out.println(String.format(Locale.ROOT,
                "[dot-vision] SUMMARY coverage: max abs error %.4f", maxErr));
    }

    @Test
    public void filtersBySizeAndCircularity() {
        DotInspectorSettings s = new DotInspectorSettings();
        DotInspector inspector = new DotInspector(s);
        double mm = 0.035;
        Shot b = base(Background.PAPER, mm);
        Shot a = b.copy();
        a.noiseSeed = 3;
        a.deposits.add(Synth.disk(-3, 0, 0.6)); // kept
        a.deposits.add(Synth.disk(3, 2, 2.6)); // too large
        a.deposits.add(Synth.rect(2, -2, 2.0, 0.25, 20)); // a smear: not circular
        a.deposits.add(Synth.disk(-1, -2.5, 0.10)); // too small
        List<Dot> found = inspector.inspect(Synth.render(b), Synth.render(a), mm, mm);
        System.out.println("[dot-vision] filter test found " + found);
        assertEquals(1, found.size(), found.toString());
        assertEquals(-3, found.get(0).xMm, mm);
        assertEquals(0, found.get(0).yMm, mm);
        assertEquals(Math.PI * 0.09, found.get(0).areaMm2, 0.1 * Math.PI * 0.09);
        assertTrue(found.get(0).circularity > 0.8, found.toString());

        s.maxDiameterMm = 3.0;
        s.minCircularity = 0.0;
        s.minDiameterMm = 0.0;
        assertEquals(4, inspector.inspect(Synth.render(b), Synth.render(a), mm, mm).size());
    }

    @Test
    public void anisotropicPixelsAndAxisSigns() {
        // Dot up and to the right must report +x, +y; mm/px may differ per axis.
        DotInspector inspector = new DotInspector(new DotInspectorSettings());
        Shot b = base(Background.PAPER, 0.03);
        Shot a = after(b, List.of(new Truth(2.0, 1.5, 0.8)));
        List<Dot> found = inspector.inspect(Synth.render(b), Synth.render(a), 0.03, 0.03);
        assertEquals(1, found.size());
        assertEquals(2.0, found.get(0).xMm, 0.03);
        assertEquals(1.5, found.get(0).yMm, 0.03);
        // Same pixels, different claimed scale in y: y and area scale accordingly.
        List<Dot> aniso = inspector.inspect(Synth.render(b), Synth.render(a), 0.03, 0.06);
        assertEquals(3.0, aniso.get(0).yMm, 0.06);
        assertEquals(2 * found.get(0).areaMm2, aniso.get(0).areaMm2, 0.02 * found.get(0).areaMm2);
    }

    @Test
    public void rejectsBadArguments() {
        DotInspector inspector = new DotInspector(new DotInspectorSettings());
        BufferedImage x = new BufferedImage(64, 48, BufferedImage.TYPE_3BYTE_BGR);
        BufferedImage y = new BufferedImage(65, 48, BufferedImage.TYPE_3BYTE_BGR);
        assertThrows(IllegalArgumentException.class, () -> inspector.inspect(x, y, 0.03, 0.03));
        assertThrows(IllegalArgumentException.class, () -> inspector.inspect(x, x, 0, 0.03));
        assertThrows(IllegalArgumentException.class, () -> inspector.inspect(null, x, 0.03, 0.03));
        assertThrows(IllegalArgumentException.class,
                () -> inspector.padCoverage(x, x, 0.03, 0.03, 5, 0, 1, 1, 0));
        assertTrue(inspector.inspect(x, x, 0.03, 0.03).isEmpty());
    }

    @Test
    public void reportsTiming() {
        DotInspector inspector = new DotInspector(new DotInspectorSettings());
        for (int[] size : new int[][] {{640, 480}, {1280, 960}}) {
            Shot b = base(Background.FR4, 0.035);
            b.width = size[0];
            b.height = size[1];
            Shot a = after(b, layout(Background.FR4, 3));
            BufferedImage bi = Synth.render(b);
            BufferedImage ai = Synth.render(a);
            inspector.inspect(bi, ai, 0.035, 0.035); // warm-up
            int runs = 5;
            long t0 = System.nanoTime();
            for (int i = 0; i < runs; i++) {
                assertEquals(8, inspector.inspect(bi, ai, 0.035, 0.035).size());
            }
            double ms = (System.nanoTime() - t0) / 1e6 / runs;
            System.out.println(String.format(Locale.ROOT,
                    "[dot-vision] timing %dx%d, 8 dots: %.1f ms per inspect()", size[0], size[1],
                    ms));
            assertTrue(ms < 5000, "inspect() took " + ms + " ms");
        }
    }
}
