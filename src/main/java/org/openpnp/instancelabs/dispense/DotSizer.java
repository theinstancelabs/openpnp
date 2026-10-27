package org.openpnp.instancelabs.dispense;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * Pad -> dots (plan §5.6 "Mass -> dots"): one dot if it fits, otherwise a grid at a pitch of about
 * the measured dot diameter; a pad that would need a dot smaller than the smallest characterised
 * rung is flagged, never extrapolated.
 */
public final class DotSizer {
    public static final class Result {
        public int nx = 1;
        public int ny = 1;
        public double rotationPerDotDeg;
        public Double predictedDiameterMm;
        public Double predictedHeightMm;
        public final EnumSet<PadFlag> flags = EnumSet.noneOf(PadFlag.class);
        /** Dot centres in the pad frame (mm, pad X along w). Empty if blocked. */
        public final List<double[]> offsets = new ArrayList<>();

        public int dotCount() {
            return offsets.size();
        }
    }

    private final double qMm3PerRev;
    private final DotLadder ladder;
    private final int maxDots;
    private final double fitFactor;
    private final double densityMgMm3;

    public DotSizer(double qMm3PerRev, DotLadder ladder, int maxDots, double fitFactor,
            double densityMgMm3) {
        this.qMm3PerRev = qMm3PerRev;
        this.ladder = ladder == null ? DotLadder.empty() : ladder;
        this.maxDots = Math.max(1, maxDots);
        this.fitFactor = fitFactor;
        this.densityMgMm3 = densityMgMm3;
    }

    public Result size(double wMm, double hMm, double volumeMm3) {
        return size(wMm, hMm, volumeMm3, null);
    }

    /** As {@link #size(double, double, double)} with an optional per-type rule. */
    public Result size(double wMm, double hMm, double volumeMm3, PadRule rule) {
        int maxDots = rule != null && rule.maxDots != null ? Math.max(1, rule.maxDots) : this.maxDots;
        double fitFactor = rule != null && rule.fitFactor != null ? rule.fitFactor : this.fitFactor;
        int minDots = rule != null && rule.largestSingleDotPadMm2 != null
                && wMm * hMm > rule.largestSingleDotPadMm2 ? 2 : 1;
        Result r = new Result();
        if (ladder.isEmpty()) {
            // No characterised dot sizes yet: one dot per pad from q alone; flagged so the caller
            // can see the dot size is unverified (provisional).
            r.flags.add(PadFlag.NO_LADDER);
            r.rotationPerDotDeg = DotMath.rotationDegForVolume(volumeMm3, qMm3PerRev);
            r.offsets.add(new double[] {0, 0});
            return r;
        }
        if (!ladder.isMonotonic()) {
            r.flags.add(PadFlag.LADDER_NOT_MONOTONIC);
            r.rotationPerDotDeg = DotMath.rotationDegForVolume(volumeMm3, qMm3PerRev);
            return r;
        }
        for (int n = minDots; n <= maxDots; n++) {
            double rot = DotMath.rotationDegForVolume(volumeMm3 / n, qMm3PerRev);
            if (rot < ladder.minRotationDeg() - 1e-9) {
                r.flags.add(PadFlag.DOT_BELOW_LADDER);
                r.rotationPerDotDeg = rot;
                r.offsets.clear();
                return r;
            }
            if (rot > ladder.maxRotationDeg() + 1e-9) {
                continue;
            }
            Double d = ladder.diameterAt(rot);
            int[] grid = bestGrid(n, wMm, hMm);
            double cell = Math.min(wMm / grid[0], hMm / grid[1]);
            if (d != null && d <= cell * fitFactor + 1e-9) {
                r.nx = grid[0];
                r.ny = grid[1];
                r.rotationPerDotDeg = rot;
                r.predictedDiameterMm = d;
                r.predictedHeightMm = DotMath.sphericalCapHeightMm(dotVolume(rot), d);
                for (int iy = 0; iy < r.ny; iy++) {
                    for (int ix = 0; ix < r.nx; ix++) {
                        r.offsets.add(new double[] {-wMm / 2 + wMm / r.nx * (ix + 0.5),
                                -hMm / 2 + hMm / r.ny * (iy + 0.5)});
                    }
                }
                return r;
            }
        }
        r.flags.add(PadFlag.NO_FIT);
        r.rotationPerDotDeg = DotMath.rotationDegForVolume(volumeMm3, qMm3PerRev);
        return r;
    }

    /** Volume of one dot at this rotation; uses the ladder's measured mass when present. */
    private double dotVolume(double rot) {
        for (DotLadder.Rung rung : ladder.rungs()) {
            if (Math.abs(rung.rotationDeg - rot) < 1e-9 && rung.row != null
                    && rung.row.mgPerDotMean != null && densityMgMm3 > 0) {
                return rung.row.mgPerDotMean / densityMgMm3;
            }
        }
        return DotMath.volumeForRotation(rot, qMm3PerRev);
    }

    /** nx * ny = n maximising the short side of a cell (ties -> more columns along the long side). */
    static int[] bestGrid(int n, double w, double h) {
        int[] best = {n, 1};
        double bestCell = -1;
        for (int nx = 1; nx <= n; nx++) {
            if (n % nx != 0) {
                continue;
            }
            int ny = n / nx;
            double cell = Math.min(w / nx, h / ny);
            boolean better = cell > bestCell + 1e-12
                    || (Math.abs(cell - bestCell) <= 1e-12 && (w >= h ? nx > best[0] : ny > best[1]));
            if (better) {
                bestCell = cell;
                best = new int[] {nx, ny};
            }
        }
        return best;
    }
}
