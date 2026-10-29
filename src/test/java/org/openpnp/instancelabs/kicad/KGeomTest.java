package org.openpnp.instancelabs.kicad;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/** Plane geometry for the parser and the art (design §6.2). */
public class KGeomTest {
    @Test
    public void arcFromThreePointsBothWays() {
        double[] ccw = KGeom.arc3(1, 0, 0, 1, -1, 0);
        assertArrayEquals(new double[] {0, 0, 1, 0, Math.PI}, ccw, 1e-12);
        double[] cw = KGeom.arc3(1, 0, 0, -1, -1, 0);
        assertEquals(-Math.PI, cw[4], 1e-12);
        double[] big = KGeom.arc3(10, 5, 5, 10, 0, 5); // centre (5, 5), r 5
        assertEquals(5, big[0], 1e-12);
        assertEquals(5, big[1], 1e-12);
        assertEquals(5, big[2], 1e-12);
        assertNull(KGeom.arc3(0, 0, 1, 1, 2, 2), "collinear");
        double[] full = KGeom.arc3(1, 0, -1, 0, 1, 0);
        assertEquals(2 * Math.PI, full[4], 1e-12);
    }

    @Test
    public void kicad5ArcIsClockwiseOnScreenForAPositiveAngle() {
        // centre (0, 0), start (1, 0), +90°: in KiCad's Y-down frame the end is (0, 1), i.e. below on screen
        double[] a = KGeom.arcKicad5(0, 0, 1, 0, 90);
        assertEquals(0, a[4], 1e-12);
        assertEquals(1, a[5], 1e-12);
        assertEquals(Math.sqrt(0.5), a[2], 1e-12);
        assertEquals(Math.sqrt(0.5), a[3], 1e-12);
    }

    @Test
    public void exactExtentsAcrossTheAxes() {
        // a quarter from 45° to 135° bulges to y = r at 90°
        double s = Math.sqrt(0.5);
        double[] box = KGeom.arcExtents(s, s, 0, 1, -s, s);
        assertArrayEquals(new double[] {-s, s, s, 1}, box, 1e-12);
        // three quarters through 180° and 270°
        double[] b2 = KGeom.arcExtents(1, 0, -1, 0, 0, 1);
        assertArrayEquals(new double[] {-1, -1, 1, 1}, b2, 1e-12);
        // the other way round the same three points: only 0 → 90
        double[] b3 = KGeom.arcExtents(1, 0, s, s, 0, 1);
        assertArrayEquals(new double[] {0, 0, 1, 1}, b3, 1e-12);
    }

    @Test
    public void flatteningChordErrorAndTheOneDegreeCap() {
        KGeom.Pts out = new KGeom.Pts();
        assertTrue(KGeom.flattenArc(10, 0, 0, 10, -10, 0, 0.01, out, true));
        assertEquals(10, out.x(0), 0);
        assertEquals(-10, out.x(out.size() - 1), 0);
        for (int i = 0; i + 1 < out.size(); i++) {
            double mx = (out.x(i) + out.x(i + 1)) / 2;
            double my = (out.y(i) + out.y(i + 1)) / 2;
            assertTrue(10 - Math.hypot(mx, my) <= 0.01 + 1e-12, "chord error");
        }
        // radius 2,000 mm at 0.01 mm would need ~1,400 segments per circle: capped at 1° steps
        assertTrue(KGeom.arcSegments(2000, 2 * Math.PI, 0.01) <= 360);
        assertEquals(360, KGeom.arcSegments(2000, 2 * Math.PI, 0.01));
        assertTrue(KGeom.circle(0, 0, 0.05, 0.01).length / 2 >= 8, "small circles stay round");
    }

    @Test
    public void chainingThroughTheHashGrid() {
        // 10,000 segments of a 1 mm grid staircase, joined with 0.005 mm gaps, given in shuffled order
        int n = 10_000;
        List<double[]> pieces = new ArrayList<>();
        double x = 0;
        double y = 0;
        for (int i = 0; i < n; i++) {
            double nx = i % 2 == 0 ? x + 1 : x;
            double ny = i % 2 == 0 ? y : y + 1;
            pieces.add(new double[] {x, y, nx, ny});
            x = nx + 0.005;
            y = ny;
        }
        // close it back to the start
        pieces.add(new double[] {x, y, x, 0});
        pieces.add(new double[] {x, 0, 0, 0});
        java.util.Collections.shuffle(pieces, new java.util.Random(1));
        long t0 = System.nanoTime();
        List<KGeom.Chain> chains = KGeom.chain(pieces, null, 0.01);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertEquals(1, chains.size());
        assertTrue(chains.get(0).closed);
        assertEquals(n + 2, chains.get(0).pieces.size());
        assertTrue(ms <= 200, ms + " ms");
    }

    @Test
    public void openChainsStayOpen() {
        List<double[]> pieces = new ArrayList<>();
        pieces.add(new double[] {0, 0, 10, 0});
        pieces.add(new double[] {10, 0, 10, 10});
        pieces.add(new double[] {10, 10, 0, 10});
        pieces.add(new double[] {0, 10, 0, 0.5}); // a 0.5 mm gap
        List<KGeom.Chain> c = KGeom.chain(pieces, null, 0.01);
        assertEquals(1, c.size());
        assertTrue(!c.get(0).closed);
    }

    @Test
    public void iterativeDouglasPeucker() {
        int n = 1_000_000;
        double[] p = new double[2 * n];
        for (int i = 0; i < n; i++) {
            p[2 * i] = i * 0.001;
            p[2 * i + 1] = Math.sin(i * 0.001) * 0.001 * (i % 7); // noise on a line
        }
        double[] s = KGeom.simplify(p, 0.01, false); // no stack overflow
        assertTrue(s.length < p.length);
        assertEquals(0, s[0], 0);
        assertEquals(p[p.length - 2], s[s.length - 2], 0);

        double[] circle = new double[2000];
        for (int i = 0; i < 1000; i++) {
            circle[2 * i] = 10 * Math.cos(2 * Math.PI * i / 1000);
            circle[2 * i + 1] = 10 * Math.sin(2 * Math.PI * i / 1000);
        }
        double[] c = KGeom.simplify(circle, 0.01, true);
        assertTrue(c.length / 2 < 1000 && c.length / 2 >= 3);
        for (int i = 0; i < 1000; i++) { // every dropped point lies within the tolerance of the result
            double best = Double.POSITIVE_INFINITY;
            int m = c.length / 2;
            for (int k = 0; k < m; k++) {
                int j = (k + 1) % m;
                best = Math.min(best, KGeom.segmentDistance(circle[2 * i], circle[2 * i + 1], c[2 * k], c[2 * k + 1],
                        c[2 * j], c[2 * j + 1]));
            }
            assertTrue(best <= 0.01 + 1e-9, "point " + i + " " + best);
        }
    }

    @Test
    public void minimumAreaRectangle() {
        // a 3 × 1 rectangle turned 30° about (2, 1)
        double t = Math.toRadians(30);
        double[] p = new double[8];
        double[][] corners = {{-1.5, -0.5}, {1.5, -0.5}, {1.5, 0.5}, {-1.5, 0.5}};
        for (int i = 0; i < 4; i++) {
            p[2 * i] = 2 + corners[i][0] * Math.cos(t) - corners[i][1] * Math.sin(t);
            p[2 * i + 1] = 1 + corners[i][0] * Math.sin(t) + corners[i][1] * Math.cos(t);
        }
        double[] r = KGeom.minAreaRect(p);
        assertArrayEquals(new double[] {2, 1, 3, 1, 30}, r, 1e-9);
        // a symmetric trapezoid: its box (the base is the long side)
        double[] trap = {-2, 0, 2, 0, 1, 1, -1, 1};
        double[] q = KGeom.minAreaRect(trap);
        assertEquals(4, q[2], 1e-9);
        assertEquals(1, q[3], 1e-9);
        assertEquals(0, q[4], 1e-9);
    }

    @Test
    public void polygonHelpers() {
        double[] sq = {0, 0, 2, 0, 2, 2, 0, 2};
        assertEquals(4, KGeom.polygonArea(sq), 1e-12);
        assertTrue(KGeom.pointInPolygon(1, 1, sq));
        assertTrue(!KGeom.pointInPolygon(3, 1, sq));
        assertTrue(KGeom.isConvex(sq));
        assertTrue(!KGeom.isConvex(new double[] {0, 0, 2, 0, 1, 0.5, 2, 2, 0, 2}));
        assertEquals(0.0, KGeom.round6(-1e-9), 0);
        assertEquals(1 / 0.0, 1 / KGeom.round6(-1e-9), 0, "no negative zero");
        assertEquals(180, KGeom.normDeg(-180), 0);
        assertEquals(-90, KGeom.normDeg(270), 0);
    }
}
