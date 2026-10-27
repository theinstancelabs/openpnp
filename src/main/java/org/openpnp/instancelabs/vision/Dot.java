package org.openpnp.instancelabs.vision;

import java.util.Locale;

/**
 * A blob of new material (a paste dot) found by {@link DotInspector}.
 *
 * Coordinates are the centroid offset from the image centre in millimetres, +x right, +y up
 * (OpenPnP machine convention; the image centre follows
 * {@code VisionUtils.getPixelCenterOffsets}, i.e. pixel index minus width/2).
 */
public final class Dot {
    /** Centroid offset from the image centre, mm, +x right. */
    public final double xMm;
    /** Centroid offset from the image centre, mm, +y up. */
    public final double yMm;
    /** Equivalent-circle diameter, mm: 2 * sqrt(area / pi). */
    public final double diameterMm;
    /** Blob area, mm^2. */
    public final double areaMm2;
    /** 4 * pi * A / P^2 of the blob outline, 0..1 (1 = perfect circle). */
    public final double circularity;

    public Dot(double xMm, double yMm, double diameterMm, double areaMm2, double circularity) {
        this.xMm = xMm;
        this.yMm = yMm;
        this.diameterMm = diameterMm;
        this.areaMm2 = areaMm2;
        this.circularity = circularity;
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "Dot[x=%.4f, y=%.4f, d=%.4f, A=%.5f, circ=%.3f]", xMm, yMm,
                diameterMm, areaMm2, circularity);
    }
}
