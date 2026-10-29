package org.openpnp.instancelabs.dispense;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Where N1 touches: bare spots clear of pads, fiducials and the edge, covering the dots; the blank's row. */
public class ProbeTargetsTest {
    static BoardFeatures ftp;
    static List<double[]> dots;

    @BeforeAll
    public static void setup() throws Exception {
        BoardFeaturesTest.setup();
        ftp = BoardFeatures.of(BoardFeaturesTest.ftp(), BoardFeaturesTest.resolver());
        dots = new ArrayList<>();
        for (BoardFeatures.Pad p : ftp.pads) {
            dots.add(new double[] {p.x, p.y}); // one profile dot per 0603 pad
        }
    }

    static void assertClear(BoardFeatures f, double x, double y, double keepOut, String what) {
        assertTrue(x >= ProbeTargets.EDGE_MM - 1e-9 && x <= f.widthMm - ProbeTargets.EDGE_MM + 1e-9
                && y >= ProbeTargets.EDGE_MM - 1e-9 && y <= f.heightMm - ProbeTargets.EDGE_MM + 1e-9,
                what + " at least 2 mm inside the edge");
        for (BoardFeatures.Pad p : f.pads) {
            assertTrue(ProbeTargets.padDistance(p, x, y) >= keepOut - 1e-9, what + " " + ProbeTargets.padDistance(p,
                    x, y) + " mm from pad " + p.id);
        }
        for (BoardFeatures.Fiducial fd : f.fiducials) {
            assertTrue(Math.hypot(x - fd.x, y - fd.y) >= fd.maskDiameterMm / 2 + keepOut - 1e-9,
                    what + " near " + fd.name);
        }
    }

    @Test
    public void ftpGetsFiveBareSpotsThatCoverEveryDot() throws Exception {
        DispenseConfig c = new DispenseConfig();
        List<VacuumProber.Target> t = ProbeTargets.forBoard(ftp, dots, c);
        assertEquals(5, t.size(), "four corners and the centre; no coverage points");
        for (VacuumProber.Target x : t) {
            assertClear(ftp, x.bx, x.by, 1.4, "target " + x.n);
            for (double[] a : x.alternatesBoard) {
                assertClear(ftp, a[0], a[1], 1.4, "alternate of " + x.n);
            }
        }
        List<double[]> pts = new ArrayList<>();
        for (VacuumProber.Target x : t) {
            pts.add(new double[] {x.bx, x.by});
        }
        for (double[] d : dots) {
            assertTrue(ProbeTargets.distanceOutside(pts, d) <= c.surfaceMarginMm - 0.5 + 1e-9, Arrays.toString(d));
        }
        // fiducials keep 2.9 mm (1.5 mask radius + 1.4)
        for (VacuumProber.Target x : t) {
            for (BoardFeatures.Fiducial fd : ftp.fiducials) {
                assertTrue(Math.hypot(x.bx - fd.x, x.by - fd.y) >= 2.9 - 1e-9);
            }
        }
        // deterministic
        List<VacuumProber.Target> again = ProbeTargets.forBoard(ftp, dots, c);
        for (int i = 0; i < t.size(); i++) {
            assertEquals(t.get(i).bx, again.get(i).bx);
            assertEquals(t.get(i).by, again.get(i).by);
            assertEquals(t.get(i).alternatesBoard.size(), again.get(i).alternatesBoard.size());
        }
        assertEquals(1, t.get(0).n);
        assertEquals(5, t.get(4).n);
    }

    @Test
    public void alternatesAreTwoMillimetresApart() throws Exception {
        DispenseConfig c = new DispenseConfig();
        for (VacuumProber.Target x : ProbeTargets.forBoard(ftp, dots, c)) {
            assertEquals(2, x.alternatesBoard.size(), "target " + x.n);
            List<double[]> all = new ArrayList<>(x.alternatesBoard);
            all.add(new double[] {x.bx, x.by});
            for (int i = 0; i < all.size(); i++) {
                for (int j = i + 1; j < all.size(); j++) {
                    assertTrue(Math.hypot(all.get(i)[0] - all.get(j)[0], all.get(i)[1] - all.get(j)[1]) >= 2.0 - 1e-9);
                }
            }
        }
        c.vacuumProbe.alternates = 0;
        assertTrue(ProbeTargets.forBoard(ftp, dots, c).get(0).alternatesBoard.isEmpty());
    }

    @Test
    public void boardSpotsReplaceTheIdealPoints() throws Exception {
        DispenseConfig c = new DispenseConfig();
        c.vacuumProbe.boardSpots.put("ftp.board.xml", Arrays.asList(new double[] {8, 8}, new double[] {67, 8},
                new double[] {67, 67}, new double[] {8, 67}, new double[] {37, 37}));
        List<VacuumProber.Target> t = ProbeTargets.forBoard(ftp, dots, c);
        // the bench's spots are bare on ftp: kept as they are, in their order
        double[][] bench = {{8, 8}, {67, 8}, {67, 67}, {8, 67}, {37, 37}};
        for (int i = 0; i < 5; i++) {
            assertEquals(bench[i][0], t.get(i).bx, 1e-9);
            assertEquals(bench[i][1], t.get(i).by, 1e-9);
        }
        // their hull leaves the outer pads 3.075 mm outside (inside the 3.5 mm I4 margin, over margin - 0.5):
        // coverage points are added for those dots only
        assertTrue(t.size() > 5 && t.size() <= ProbeTargets.MAX_TARGETS, "targets " + t.size());
        List<double[]> pts = new ArrayList<>();
        for (VacuumProber.Target x : t) {
            pts.add(new double[] {x.bx, x.by});
            assertClear(ftp, x.bx, x.by, 1.4, "target " + x.n);
        }
        for (double[] d : dots) {
            assertTrue(ProbeTargets.distanceOutside(pts, d) <= c.surfaceMarginMm - 0.5 + 1e-9, Arrays.toString(d));
        }
    }

    static BoardFeatures bare(double w, double h) {
        BoardFeatures f = new BoardFeatures();
        f.boardName = "bare";
        f.fileName = "bare.board.xml";
        f.widthMm = w;
        f.heightMm = h;
        return f;
    }

    @Test
    public void coveragePointsOnlyForUncoveredDots() throws Exception {
        BoardFeatures f = bare(100, 100);
        DispenseConfig c = new DispenseConfig();
        c.vacuumProbe.boardSpots.put("bare.board.xml", Arrays.asList(new double[] {30, 30}, new double[] {70, 30},
                new double[] {70, 70}, new double[] {30, 70}));
        List<double[]> d = Arrays.asList(new double[] {10, 10}, new double[] {50, 50}, new double[] {60, 60},
                new double[] {90, 90});
        List<VacuumProber.Target> t = ProbeTargets.forBoard(f, d, c);
        assertEquals(6, t.size(), "one coverage point per uncovered dot");
        // pushed 1 mm outward from the dots' centroid (52.5, 52.5)
        double k = 1 / Math.sqrt(2);
        assertEquals(10 - k, t.get(4).bx, 0.13, "the worst first (ties: the first dot)");
        assertEquals(10 - k, t.get(4).by, 0.13);
        assertEquals(90 + k, t.get(5).bx, 0.13);
        List<double[]> pts = new ArrayList<>();
        for (VacuumProber.Target x : t) {
            pts.add(new double[] {x.bx, x.by});
        }
        for (double[] dot : d) {
            assertTrue(ProbeTargets.distanceOutside(pts, dot) <= 3.0 + 1e-9, Arrays.toString(dot));
        }
        // a ring of 12 dots needs more than 9 points
        List<double[]> ring = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            ring.add(new double[] {50 + 40 * Math.cos(Math.PI * i / 6), 50 + 40 * Math.sin(Math.PI * i / 6)});
        }
        c.vacuumProbe.boardSpots.put("bare.board.xml", Arrays.asList(new double[] {45, 45}, new double[] {55, 45},
                new double[] {50, 55}));
        DispenseService.RunRefusedException e = assertThrows(DispenseService.RunRefusedException.class,
                () -> ProbeTargets.forBoard(f, ring, c));
        assertEquals("PROBE: the dots cannot be covered with 9 probe points: probe by hand", e.getMessage());
    }

    @Test
    public void extraKeepOutCirclesMoveATarget() throws Exception {
        DispenseConfig c = new DispenseConfig();
        List<VacuumProber.Target> t = ProbeTargets.forBoard(ftp, dots, c);
        VacuumProber.Target centre = t.get(4);
        // a via right under the centre target (board art, later): the target moves off it
        List<ProbeTargets.KeepOut> via = Arrays.asList(new ProbeTargets.KeepOut(centre.bx, centre.by, 0.3));
        List<VacuumProber.Target> moved = ProbeTargets.forBoard(ftp, dots, c, via);
        double d = Math.hypot(moved.get(4).bx - centre.bx, moved.get(4).by - centre.by);
        assertTrue(d >= 0.3 + 1.4 - 1e-9, "kept 1.4 mm from the via's edge: " + d);
        for (double[] a : moved.get(4).alternatesBoard) {
            assertTrue(Math.hypot(a[0] - centre.bx, a[1] - centre.by) >= 1.7 - 1e-9);
        }
        assertNotEquals(centre.bx + "," + centre.by, moved.get(4).bx + "," + moved.get(4).by);
    }

    @Test
    public void aBoardFullOfPadsIsRefused() {
        BoardFeatures f = new BoardFeatures();
        f.boardName = "dense";
        f.widthMm = 30;
        f.heightMm = 30;
        for (double x = 1; x < 30; x += 1.5) {
            for (double y = 1; y < 30; y += 1.5) {
                BoardFeatures.Pad p = new BoardFeatures.Pad();
                p.id = "P" + x + "," + y;
                p.x = x;
                p.y = y;
                p.wMm = 0.8;
                p.hMm = 0.8;
                p.known = true;
                f.pads.add(p);
            }
        }
        List<double[]> d = Arrays.asList(new double[] {5, 5}, new double[] {25, 25});
        DispenseService.RunRefusedException e = assertThrows(DispenseService.RunRefusedException.class,
                () -> ProbeTargets.forBoard(f, d, new DispenseConfig()));
        assertEquals("PROBE: no bare spot within 6.0 mm of board X 5.0, Y 5.0 (pads, fiducials or the board edge all "
                + "round): probe by hand", e.getMessage());
    }

    @Test
    public void mappedThroughTheRegistration() throws Exception {
        AffineTransform a = new AffineTransform(new double[] {1.00112, -0.0057, 0.00103, 0.9946, 295.99, 114.52});
        List<VacuumProber.Target> b = ProbeTargets.forBoard(ftp, dots, new DispenseConfig());
        List<VacuumProber.Target> m = ProbeTargets.map(b, a);
        for (int i = 0; i < b.size(); i++) {
            Point2D p = a.transform(new Point2D.Double(b.get(i).bx, b.get(i).by), null);
            assertEquals(p.getX(), m.get(i).x, 1e-9);
            assertEquals(p.getY(), m.get(i).y, 1e-9);
            assertEquals(b.get(i).bx, m.get(i).bx);
            Point2D q = a.transform(new Point2D.Double(b.get(i).alternatesBoard.get(0)[0],
                    b.get(i).alternatesBoard.get(0)[1]), null);
            assertEquals(q.getX(), m.get(i).alternates.get(0)[0], 1e-9);
        }
        // the plan's dots back to board mm through the inverse of the plan's transform
        DispensePlan plan = new DispensePlan();
        DispensePlan.PlannedDot dot = new DispensePlan.PlannedDot();
        Point2D p = a.transform(new Point2D.Double(37.5, 42.5), null);
        dot.xMm = p.getX();
        dot.yMm = p.getY();
        plan.dots.add(dot);
        double[] back = ProbeTargets.dotsBoard(plan, a.createInverse()).get(0);
        assertEquals(37.5, back[0], 1e-9);
        assertEquals(42.5, back[1], 1e-9);
    }

    static DispenseConfig rowConfig() {
        DispenseConfig c = new DispenseConfig();
        c.purgeRow.x0Mm = 300.0;
        c.purgeRow.y0Mm = 240.0;
        c.purgeRow.pitchXMm = 2.5;
        c.purgeRow.spots = 20;
        c.purgeRow.usedSpots = 8;
        c.locations.put("blank", new DispenseConfig.NamedLocation(322.0, 236.0, 5.62));
        c.locations.put("waste", new DispenseConfig.NamedLocation(280.0, 236.0, 5.6));
        return c;
    }

    @Test
    public void rowTargetsAvoidUsedSpotsAndCoverTheRow() throws Exception {
        DispenseConfig c = rowConfig();
        List<VacuumProber.Target> t = ProbeTargets.forRow(c, "blank");
        assertEquals(6, t.size(), "ends and middle, either side");
        List<double[]> pts = new ArrayList<>();
        for (VacuumProber.Target x : t) {
            assertEquals(null, x.bx);
            pts.add(new double[] {x.x, x.y});
            for (int i = 0; i < c.purgeRow.usedSpots; i++) {
                double[] s = c.purgeRow.spot(i);
                assertTrue(Math.hypot(x.x - s[0], x.y - s[1]) >= 1.9 - 1e-9, "target " + x.n + " near used spot " + i);
                for (double[] a : x.alternates) {
                    assertTrue(Math.hypot(a[0] - s[0], a[1] - s[1]) >= 1.9 - 1e-9);
                }
            }
            assertTrue(x.alternates.size() <= 2);
            for (double[] a : x.alternates) {
                assertEquals(x.y, a[1], 1e-9, "alternates move along the row");
                assertEquals(2.0, Math.abs(a[0] - x.x), 1e-9);
            }
        }
        for (int i = 0; i < c.purgeRow.spots; i++) {
            assertTrue(ProbeTargets.distanceOutside(pts, c.purgeRow.spot(i)) <= c.surfaceMarginMm - 0.5 + 1e-9);
        }
        assertTrue(ProbeTargets.distanceOutside(pts, new double[] {322.0, 236.0}) <= c.surfaceMarginMm - 0.5 + 1e-9,
                "the blank place is covered");
        // y either side of the row (t0 - 2, t1 + 2): the blank place at 236 widens the lower side
        assertEquals(234.0, t.get(0).y, 1e-9);
        assertEquals(242.0, t.get(5).y, 1e-9);
    }

    @Test
    public void aShortRowIsProbedAtItsEndsAtLeastSixMillimetresApart() throws Exception {
        DispenseConfig c = rowConfig();
        c.purgeRow.spots = 2;
        c.purgeRow.usedSpots = 0;
        c.locations.remove("blank");
        List<VacuumProber.Target> t = ProbeTargets.forRow(c, "blank");
        assertEquals(4, t.size());
        SurfacePlane.fit(Arrays.asList(new DispenseConfig.SurfacePoint(t.get(0).x, t.get(0).y, 0),
                new DispenseConfig.SurfacePoint(t.get(1).x, t.get(1).y, 0),
                new DispenseConfig.SurfacePoint(t.get(2).x, t.get(2).y, 0))); // spans an area
        c.purgeRow.spots = 0;
        assertThrows(DispenseService.RunRefusedException.class, () -> ProbeTargets.forRow(c, "blank"));
    }

    @Test
    public void explicitTargetsHaveAlternatesInX() {
        List<VacuumProber.Target> t = ProbeTargets.explicit(Arrays.asList(new double[] {330, 240},
                new double[] {350, 240}, new double[] {340, 250}), 2);
        assertEquals(3, t.size());
        assertEquals(332, t.get(0).alternates.get(0)[0], 1e-9);
        assertEquals(328, t.get(0).alternates.get(1)[0], 1e-9);
        assertEquals(240, t.get(0).alternates.get(1)[1], 1e-9);
        assertEquals(1, ProbeTargets.explicit(Arrays.asList(new double[] {1, 2}), 1).get(0).alternates.size());
    }
}
