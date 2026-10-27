package org.openpnp.instancelabs.vision;

import java.awt.image.BufferedImage;

import org.openpnp.spi.Camera;

/**
 * VISION CONTRACT STUB (feat/dispense). Drop in favour of the vision branch implementation.
 */
public final class CameraCapture {
    private CameraCapture() {
    }

    /**
     * settle, then capture from an OpenPnP Camera (uses camera.settleAndCapture()); returns image +
     * units-per-pixel in mm
     */
    public static Capture capture(Camera cam) throws Exception {
        throw new UnsupportedOperationException("CameraCapture is a contract stub on feat/dispense");
    }

    public static final class Capture {
        public final BufferedImage image;
        public final double mmPerPxX, mmPerPxY;

        Capture(BufferedImage image, double mmPerPxX, double mmPerPxY) {
            this.image = image;
            this.mmPerPxX = mmPerPxX;
            this.mmPerPxY = mmPerPxY;
        }
    }
}
