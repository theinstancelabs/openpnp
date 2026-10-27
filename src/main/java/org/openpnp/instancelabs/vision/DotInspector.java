package org.openpnp.instancelabs.vision;

import java.awt.image.BufferedImage;
import java.util.List;

/**
 * VISION CONTRACT STUB (feat/dispense). Drop in favour of the vision branch implementation.
 * Every method throws until the real implementation is merged.
 */
public final class DotInspector {
    @SuppressWarnings("unused")
    private final DotInspectorSettings settings;

    public DotInspector(DotInspectorSettings s) {
        this.settings = s;
    }

    /** before/after taken from the same camera position; returns blobs that appeared. */
    public List<Dot> inspect(BufferedImage before, BufferedImage after, double mmPerPxX,
            double mmPerPxY) {
        throw new UnsupportedOperationException("DotInspector is a contract stub on feat/dispense");
    }

    /**
     * fraction 0..1 of each pad rectangle (centre/size/rotation in mm relative to image centre)
     * covered by new material.
     */
    public double padCoverage(BufferedImage before, BufferedImage after, double mmPerPxX,
            double mmPerPxY, double cxMm, double cyMm, double wMm, double hMm, double rotDeg) {
        throw new UnsupportedOperationException("DotInspector is a contract stub on feat/dispense");
    }
}
