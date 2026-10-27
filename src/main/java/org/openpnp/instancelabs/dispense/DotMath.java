package org.openpnp.instancelabs.dispense;

/** Deterministic paste arithmetic (plan §5.6, §6.1). No fitted or learned model. */
public final class DotMath {
    private DotMath() {
    }

    /** Area of a rounded rectangle, roundness = corner radius as % of half the short side. */
    public static double padAreaMm2(double w, double h, double roundnessPercent) {
        double r = Math.min(w, h) / 2.0 * Math.max(0, Math.min(100, roundnessPercent)) / 100.0;
        return w * h - (4.0 - Math.PI) * r * r;
    }

    /** V = A * t * k (mm^3). */
    public static double targetVolumeMm3(double areaMm2, double tMm, double k) {
        return areaMm2 * tMm * k;
    }

    /** Auger rotation (deg of B) for a volume, given q in mm^3/rev. */
    public static double rotationDegForVolume(double volumeMm3, double qMm3PerRev) {
        if (qMm3PerRev <= 0) {
            throw new IllegalArgumentException("q must be > 0");
        }
        return volumeMm3 / qMm3PerRev * 360.0;
    }

    public static double volumeForRotation(double rotationDeg, double qMm3PerRev) {
        return rotationDeg / 360.0 * qMm3PerRev;
    }

    /** q (mm^3/rev) from a scale measurement: total mass of count items of rotationDeg each. */
    public static double qFromMass(double totalMassMg, int count, double rotationDegEach,
            double densityMgMm3) {
        if (count <= 0 || rotationDegEach <= 0 || densityMgMm3 <= 0) {
            throw new IllegalArgumentException("count, rotation and density must be > 0");
        }
        double volumePerItem = totalMassMg / count / densityMgMm3;
        return volumePerItem / (rotationDegEach / 360.0);
    }

    /**
     * Height of a spherical cap of volume V and base diameter d: V = pi h (3a^2 + h^2) / 6, a = d/2.
     * Solved by bisection (f is strictly increasing in h >= 0).
     */
    public static double sphericalCapHeightMm(double volumeMm3, double diameterMm) {
        if (volumeMm3 <= 0 || diameterMm <= 0) {
            return 0;
        }
        double a = diameterMm / 2.0;
        double lo = 0;
        double hi = Math.max(diameterMm, Math.cbrt(6 * volumeMm3 / Math.PI)) + 1;
        for (int i = 0; i < 200; i++) {
            double mid = (lo + hi) / 2;
            double v = Math.PI * mid * (3 * a * a + mid * mid) / 6.0;
            if (v < volumeMm3) {
                lo = mid;
            }
            else {
                hi = mid;
            }
        }
        return (lo + hi) / 2;
    }

    public static double mean(double[] v) {
        double s = 0;
        for (double x : v) {
            s += x;
        }
        return v.length == 0 ? Double.NaN : s / v.length;
    }

    /** Sample standard deviation (n-1). */
    public static double sd(double[] v) {
        if (v.length < 2) {
            return Double.NaN;
        }
        double m = mean(v);
        double s = 0;
        for (double x : v) {
            s += (x - m) * (x - m);
        }
        return Math.sqrt(s / (v.length - 1));
    }

    public static double cv(double[] v) {
        double m = mean(v);
        return v.length < 2 || m == 0 ? Double.NaN : sd(v) / m;
    }
}
