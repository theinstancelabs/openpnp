package org.openpnp.instancelabs.dispense;

/**
 * Open-path dot ordering from a fixed start point: nearest neighbour, then 2-opt segment reversal
 * until no improvement (or maxPasses). Deterministic (ties resolve to the lowest index).
 */
public final class PathOrder {
    private PathOrder() {
    }

    public static final class Result {
        public final int[] order;
        public final double nearestNeighbourLength;
        public final double length;
        public final int passes;

        Result(int[] order, double nn, double length, int passes) {
            this.order = order;
            this.nearestNeighbourLength = nn;
            this.length = length;
            this.passes = passes;
        }
    }

    public static Result order(double[] xs, double[] ys, double startX, double startY, int maxPasses) {
        int n = xs.length;
        int[] tour = new int[n];
        boolean[] used = new boolean[n];
        double cx = startX, cy = startY;
        for (int k = 0; k < n; k++) {
            int best = -1;
            double bestD = Double.MAX_VALUE;
            for (int i = 0; i < n; i++) {
                if (!used[i]) {
                    double d = Math.hypot(xs[i] - cx, ys[i] - cy);
                    if (d < bestD - 1e-12) {
                        bestD = d;
                        best = i;
                    }
                }
            }
            used[best] = true;
            tour[k] = best;
            cx = xs[best];
            cy = ys[best];
        }
        double nnLen = length(tour, xs, ys, startX, startY);
        int passes = 0;
        boolean improved = n > 2;
        while (improved && passes < maxPasses) {
            improved = false;
            passes++;
            for (int i = 0; i < n - 1; i++) {
                double px = i == 0 ? startX : xs[tour[i - 1]];
                double py = i == 0 ? startY : ys[tour[i - 1]];
                for (int k = i + 1; k < n; k++) {
                    int a = tour[i], b = tour[k];
                    double before = Math.hypot(xs[a] - px, ys[a] - py);
                    double after = Math.hypot(xs[b] - px, ys[b] - py);
                    if (k + 1 < n) {
                        int c = tour[k + 1];
                        before += Math.hypot(xs[c] - xs[b], ys[c] - ys[b]);
                        after += Math.hypot(xs[c] - xs[a], ys[c] - ys[a]);
                    }
                    if (after < before - 1e-9) {
                        for (int lo = i, hi = k; lo < hi; lo++, hi--) {
                            int t = tour[lo];
                            tour[lo] = tour[hi];
                            tour[hi] = t;
                        }
                        improved = true;
                    }
                }
            }
        }
        return new Result(tour, nnLen, length(tour, xs, ys, startX, startY), passes);
    }

    public static double length(int[] tour, double[] xs, double[] ys, double sx, double sy) {
        double len = 0, cx = sx, cy = sy;
        for (int i : tour) {
            len += Math.hypot(xs[i] - cx, ys[i] - cy);
            cx = xs[i];
            cy = ys[i];
        }
        return len;
    }
}
