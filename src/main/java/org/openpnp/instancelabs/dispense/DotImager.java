package org.openpnp.instancelabs.dispense;

import java.awt.image.BufferedImage;
import java.util.List;

import org.openpnp.instancelabs.vision.Dot;

/**
 * Before/after imaging of dots with the top camera. The OpenPnP implementation uses the vision
 * contract (CameraCapture + DotInspector); tests substitute fakes.
 */
public interface DotImager {
    final class Frame {
        public final BufferedImage image;
        public final double mmPerPxX, mmPerPxY;
        /** Machine location the camera was centred on. */
        public final double xMm, yMm;

        public Frame(BufferedImage image, double mmPerPxX, double mmPerPxY, double xMm, double yMm) {
            this.image = image;
            this.mmPerPxX = mmPerPxX;
            this.mmPerPxY = mmPerPxY;
            this.xMm = xMm;
            this.yMm = yMm;
        }
    }

    /** Whether a camera and dot vision are available. */
    boolean isAvailable();

    /** Centres the top camera on machine (x, y) at safe Z, settles and captures. */
    Frame capture(double xMm, double yMm) throws Exception;

    /** New blobs between two frames from the same camera position (offsets from frame centre). */
    List<Dot> inspect(Frame before, Frame after) throws Exception;
}
