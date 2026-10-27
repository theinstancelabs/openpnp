package org.openpnp.instancelabs.vision;

/**
 * VISION CONTRACT STUB (feat/dispense). The real implementation is owned by the vision branch;
 * integration should drop this file in favour of it. Shape matches the shared contract exactly.
 */
public final class Dot {
    /** centroid offset from image centre, +x right, +y up, mm */
    public final double xMm, yMm;
    /** equivalent-circle diameter */
    public final double diameterMm;
    public final double areaMm2;
    /** 4*pi*A/P^2 */
    public final double circularity;

    public Dot(double xMm, double yMm, double diameterMm, double areaMm2, double circularity) {
        this.xMm = xMm;
        this.yMm = yMm;
        this.diameterMm = diameterMm;
        this.areaMm2 = areaMm2;
        this.circularity = circularity;
    }
}
