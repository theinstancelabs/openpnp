package org.openpnp.instancelabs.dispense;

/** Raw controller coordinates (driver units, mm) of the X, Y and Z controller axes. */
public final class RawPoint {
    public final double x, y, z;

    public RawPoint(double x, double y, double z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public double get(int axis) {
        return axis == 0 ? x : axis == 1 ? y : z;
    }

    @Override
    public String toString() {
        return String.format(java.util.Locale.ROOT, "raw(%.4f, %.4f, %.4f)", x, y, z);
    }
}
