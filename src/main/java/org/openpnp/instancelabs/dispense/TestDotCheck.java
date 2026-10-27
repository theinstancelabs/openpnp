package org.openpnp.instancelabs.dispense;

import java.util.List;

import org.openpnp.instancelabs.vision.Dot;

/** I9 optical acceptance of the test dot. Deterministic thresholds. */
public final class TestDotCheck {
    private TestDotCheck() {
    }

    /** The detected dot nearest the frame centre within searchRadiusMm, or null. */
    public static Dot nearest(List<Dot> dots, double searchRadiusMm) {
        Dot best = null;
        double bestD = Double.MAX_VALUE;
        if (dots != null) {
            for (Dot d : dots) {
                double r = Math.hypot(d.xMm, d.yMm);
                if (r <= searchRadiusMm && r < bestD) {
                    best = d;
                    bestD = r;
                }
            }
        }
        return best;
    }

    /**
     * @return null if the test dot passes, otherwise the reason it failed.
     */
    public static String evaluate(List<Dot> dots, Double expectedDiameterMm, double minRatio,
            double minDiameterMm, double searchRadiusMm) {
        Dot d = nearest(dots, searchRadiusMm);
        if (d == null) {
            return "I9: no test dot found within " + searchRadiusMm + " mm of the expected position";
        }
        double min = expectedDiameterMm != null ? expectedDiameterMm * minRatio : minDiameterMm;
        if (d.diameterMm < min) {
            return String.format(java.util.Locale.ROOT, "I9: test dot diameter %.3f mm below the minimum %.3f mm",
                    d.diameterMm, min);
        }
        return null;
    }
}
