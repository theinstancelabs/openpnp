package org.openpnp.instancelabs.dispense;

import org.openpnp.spi.Machine;

/**
 * The tip camera of the machine (tip setups design §22.4): OpenPnP's first camera looking up, at its
 * own location, captured with OpenPnP's light-settle-capture ({@code CameraCapture.captureLit(camera,
 * null)}), as bottom vision does.
 */
public final class OpenPnpTipCamera implements TipCamera {
    private final Machine machine;

    /** The tip camera of this machine (its cameras are looked up at every call: a configuration may change them). */
    public OpenPnpTipCamera(Machine machine) {
        this.machine = machine;
    }

    /** True when the machine has a camera looking up. */
    @Override
    public boolean isAvailable() {
        throw new UnsupportedOperationException("§22 ts-engine: not built yet");
    }

    /** The up-looking camera's location in mm {x, y, z}. */
    @Override
    public double[] locationMm() {
        throw new UnsupportedOperationException("§22 ts-engine: not built yet");
    }

    /** A lit frame of the up-looking camera with its units per pixel at the focal plane. */
    @Override
    public DotImager.Frame capture() throws Exception {
        throw new UnsupportedOperationException("§22 ts-engine: not built yet");
    }
}
