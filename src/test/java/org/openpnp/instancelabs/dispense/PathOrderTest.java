package org.openpnp.instancelabs.dispense;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Random;

import org.junit.jupiter.api.Test;

/** Nearest neighbour + 2-opt ordering. */
public class PathOrderTest {
    @Test
    public void collinearPointsAreVisitedInOrder() {
        double[] xs = {5, 1, 3, 2, 4};
        double[] ys = {0, 0, 0, 0, 0};
        PathOrder.Result r = PathOrder.order(xs, ys, 0, 0, 50);
        assertArrayEquals(new int[] {1, 3, 2, 4, 0}, r.order);
        assertEquals(5.0, r.length, 1e-12);
    }

    @Test
    public void twoOptRemovesCrossings() {
        // NN from the origin: (1,0) -> (2,1) -> (2,-1)... produces a crossing that 2-opt removes.
        double[] xs = {1, 2, 2, 3.2, 3.2, 4.5};
        double[] ys = {0, 1, -1, 1.2, -1.2, 0};
        PathOrder.Result r = PathOrder.order(xs, ys, 0, 0, 50);
        assertTrue(r.length <= r.nearestNeighbourLength + 1e-9);
    }

    @Test
    public void twoOptImprovesRandomBoards() {
        Random rnd = new Random(7);
        int improved = 0;
        double saved = 0;
        for (int trial = 0; trial < 10; trial++) {
            int n = 160;
            double[] xs = new double[n], ys = new double[n];
            for (int i = 0; i < n; i++) {
                xs[i] = rnd.nextDouble() * 75;
                ys[i] = rnd.nextDouble() * 75;
            }
            PathOrder.Result r = PathOrder.order(xs, ys, 0, 0, 50);
            if (r.length < r.nearestNeighbourLength - 1e-6) {
                improved++;
            }
            saved += (r.nearestNeighbourLength - r.length) / r.nearestNeighbourLength;
        }
        assertEquals(10, improved);
        assertTrue(saved / 10 > 0.02, "mean 2-opt saving " + saved / 10);
    }

    @Test
    public void randomBoardsVisitEveryDotOnceAndNeverGetWorse() {
        Random rnd = new Random(42);
        for (int trial = 0; trial < 20; trial++) {
            int n = 20 + rnd.nextInt(150);
            double[] xs = new double[n], ys = new double[n];
            for (int i = 0; i < n; i++) {
                xs[i] = rnd.nextDouble() * 75;
                ys[i] = rnd.nextDouble() * 75;
            }
            PathOrder.Result r = PathOrder.order(xs, ys, -10, -10, 50);
            int[] sorted = r.order.clone();
            Arrays.sort(sorted);
            for (int i = 0; i < n; i++) {
                assertEquals(i, sorted[i]);
            }
            assertTrue(r.length <= r.nearestNeighbourLength + 1e-9);
            assertEquals(r.length, PathOrder.length(r.order, xs, ys, -10, -10), 1e-9);
            // deterministic
            assertArrayEquals(r.order, PathOrder.order(xs, ys, -10, -10, 50).order);
        }
    }

    @Test
    public void emptyAndSingle() {
        assertEquals(0, PathOrder.order(new double[0], new double[0], 0, 0, 5).order.length);
        assertArrayEquals(new int[] {0}, PathOrder.order(new double[] {3}, new double[] {4}, 0, 0, 5).order);
    }
}
