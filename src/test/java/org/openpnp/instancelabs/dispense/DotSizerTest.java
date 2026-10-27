package org.openpnp.instancelabs.dispense;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/** Ladder interpolation and pad -> dots (never extrapolate). */
public class DotSizerTest {
    static List<DotLadderRow> ladder(double[][] rotDia) {
        List<DotLadderRow> l = new ArrayList<>();
        long id = 1;
        for (double[] r : rotDia) {
            DotLadderRow row = new DotLadderRow(1, r[0], 10, r[1], 0.05, Math.PI * r[1] * r[1] / 4, 0.1);
            row.id = id++;
            l.add(row);
        }
        return l;
    }

    @Test
    public void ladderInterpolatesOnlyInsideRange() {
        DotLadder l = DotLadder.of(ladder(new double[][] {{20, 0.40}, {40, 0.55}, {60, 0.70}}));
        assertEquals(0.475, l.diameterAt(30), 1e-12);
        assertEquals(0.40, l.diameterAt(20), 1e-12);
        assertEquals(0.70, l.diameterAt(60), 1e-12);
        assertNull(l.diameterAt(19.9));
        assertNull(l.diameterAt(60.1));
        assertTrue(l.isMonotonic());
    }

    @Test
    public void newestRowPerRotationWins() {
        List<DotLadderRow> rows = ladder(new double[][] {{20, 0.40}, {40, 0.55}, {40, 0.60}});
        DotLadder l = DotLadder.of(rows);
        assertEquals(2, l.rungs().size());
        assertEquals(0.60, l.diameterAt(40), 1e-12);
    }

    @Test
    public void nonMonotonicLadderRefusesToInterpolate() {
        DotLadder l = DotLadder.of(ladder(new double[][] {{20, 0.50}, {40, 0.45}}));
        assertFalse(l.isMonotonic());
        assertNull(l.diameterAt(30));
        DotSizer.Result r = new DotSizer(0.47, l, 4, 1.0, 3.7).size(0.8, 0.95, 0.076);
        assertTrue(r.flags.contains(PadFlag.LADDER_NOT_MONOTONIC));
        assertEquals(0, r.dotCount());
    }

    @Test
    public void noLadderGivesOneProvisionalDot() {
        DotSizer.Result r = new DotSizer(0.47, DotLadder.empty(), 4, 1.0, 3.7).size(0.8, 0.95, 0.076);
        assertEquals(1, r.dotCount());
        assertTrue(r.flags.contains(PadFlag.NO_LADDER));
        assertFalse(PadFlag.NO_LADDER.blocking);
        assertEquals(58.2128, r.rotationPerDotDeg, 1e-4);
    }

    @Test
    public void singleDotWhenItFits() {
        DotLadder l = DotLadder.of(ladder(new double[][] {{20, 0.40}, {60, 0.70}}));
        DotSizer.Result r = new DotSizer(0.47, l, 4, 1.0, 3.7).size(0.8, 0.95, 0.076);
        assertEquals(1, r.dotCount());
        assertTrue(r.flags.isEmpty());
        assertNotNull(r.predictedDiameterMm);
        assertTrue(r.predictedDiameterMm <= 0.8);
        assertNotNull(r.predictedHeightMm);
        assertEquals(0, r.offsets.get(0)[0], 1e-12);
    }

    @Test
    public void largePadGetsGridAlongLongSide() {
        // 1206-like pad 1.125 x 1.75, V = 0.197 mm3 -> 150.8 deg as one dot (above ladder max 90)
        DotLadder l = DotLadder.of(ladder(new double[][] {{20, 0.40}, {60, 0.70}, {90, 0.85}}));
        double v = DotMath.targetVolumeMm3(1.125 * 1.75, 0.10, 1.0);
        DotSizer.Result r = new DotSizer(0.47, l, 6, 1.0, 3.7).size(1.125, 1.75, v);
        assertEquals(2, r.dotCount());
        assertEquals(1, r.nx);
        assertEquals(2, r.ny);
        assertEquals(-1.75 / 4, r.offsets.get(0)[1], 1e-12);
        assertEquals(1.75 / 4, r.offsets.get(1)[1], 1e-12);
        assertEquals(r.rotationPerDotDeg * 2, DotMath.rotationDegForVolume(v, 0.47), 1e-9);
    }

    @Test
    public void tooSmallPadIsFlaggedNotExtrapolated() {
        // 0201-ish pad: 0.3 x 0.3 -> 0.009 mm3 -> 6.9 deg, below the smallest rung (20 deg)
        DotLadder l = DotLadder.of(ladder(new double[][] {{20, 0.40}, {60, 0.70}}));
        DotSizer.Result r = new DotSizer(0.47, l, 4, 1.0, 3.7).size(0.3, 0.3, 0.009);
        assertTrue(r.flags.contains(PadFlag.DOT_BELOW_LADDER));
        assertTrue(PadFlag.DOT_BELOW_LADDER.blocking);
        assertEquals(0, r.dotCount());
    }

    @Test
    public void noFitWhenDotsTooWide() {
        // narrow pad where even the smallest admissible dot is wider than the pad
        DotLadder l = DotLadder.of(ladder(new double[][] {{20, 0.60}, {60, 0.90}}));
        DotSizer.Result r = new DotSizer(0.47, l, 2, 1.0, 3.7).size(0.3, 2.0, DotMath.volumeForRotation(50, 0.47));
        assertTrue(r.flags.contains(PadFlag.NO_FIT) || r.flags.contains(PadFlag.DOT_BELOW_LADDER));
        assertEquals(0, r.dotCount());
    }

    @Test
    public void padRuleForcesGridAndLimitsDots() {
        DotLadder l = DotLadder.of(ladder(new double[][] {{20, 0.40}, {60, 0.70}}));
        DotSizer sizer = new DotSizer(0.47, l, 4, 1.0, 3.7);
        // largestSingleDotPad 0.5 mm2 < 0.76 mm2 -> at least two dots of 29.1 deg
        DotSizer.Result r = sizer.size(0.8, 0.95, 0.076, new PadRule("chip-0603", null, null, 0.5));
        assertEquals(2, r.dotCount());
        assertEquals(58.2128 / 2, r.rotationPerDotDeg, 1e-4);
        // maxDots 1 on a pad that needs 2 -> NO_FIT
        double v = DotMath.targetVolumeMm3(1.125 * 1.75, 0.10, 1.0);
        assertTrue(sizer.size(1.125, 1.75, v, new PadRule("chip-1206", 1, null, null)).flags.contains(PadFlag.NO_FIT));
    }

    @Test
    public void bestGrid() {
        assertEquals(2, DotSizer.bestGrid(2, 2.0, 1.0)[0]);
        assertEquals(1, DotSizer.bestGrid(2, 1.0, 2.0)[0]);
        int[] g = DotSizer.bestGrid(4, 2.0, 2.0);
        assertEquals(2, g[0]);
        assertEquals(2, g[1]);
    }
}
