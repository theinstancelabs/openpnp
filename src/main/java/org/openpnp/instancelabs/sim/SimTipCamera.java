package org.openpnp.instancelabs.sim;

import java.awt.image.BufferedImage;

import org.openpnp.machine.reference.camera.SimulatedUpCamera;

/**
 * The SIM bottom camera (tip setups design §22.8): installed in a SIM session with {@code --xml-set
 * ".//camera[@looking='Up']@class=org.openpnp.instancelabs.sim.SimTipCamera"}, as the top camera is.
 * Its frame is {@value #WIDTH_PX} × {@value #HEIGHT_PX} px at {@value #MM_PER_PX} mm/px, dark grey;
 * when N2 is in view it draws the physical tip end-on (read in-process from the engine): a grey disc of
 * its OD with a black bore of its ID, an amber ring for a tapered plastic tip, a grey capsule toward
 * the hub for a bent one, blurred by its height above the focal plane, tagged {@value #TAG}. With N2
 * elsewhere the frame is empty, so a test proves the head went there.
 */
public class SimTipCamera extends SimulatedUpCamera {
    public static final int WIDTH_PX = 1280, HEIGHT_PX = 960;
    public static final double MM_PER_PX = 0.02;
    /** The frame's grey (BGR 25, 25, 25). */
    public static final int BACKGROUND = 25;
    public static final String TAG = "SIM bottom camera";

    /** The frame described above (the physical tip when N2 is over the camera, else empty). */
    @Override
    public BufferedImage internalCapture() {
        throw new UnsupportedOperationException("§22 ts-engine: not built yet");
    }
}
