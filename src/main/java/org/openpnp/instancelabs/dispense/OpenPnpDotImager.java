package org.openpnp.instancelabs.dispense;

import java.util.List;

import org.openpnp.instancelabs.vision.CameraCapture;
import org.openpnp.instancelabs.vision.Dot;
import org.openpnp.instancelabs.vision.DotInspector;
import org.openpnp.instancelabs.vision.DotInspectorSettings;
import org.openpnp.model.Length;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.spi.Camera;
import org.openpnp.util.MovableUtils;

/** {@link DotImager} using the down-looking camera and the vision contract. */
public class OpenPnpDotImager implements DotImager {
    private final Camera camera;
    private final DotInspectorSettings settings;

    public OpenPnpDotImager(Camera camera, DotInspectorSettings settings) {
        this.camera = camera;
        this.settings = settings == null ? new DotInspectorSettings() : settings;
    }

    @Override
    public boolean isAvailable() {
        return camera != null;
    }

    @Override
    public Frame capture(double xMm, double yMm) throws Exception {
        Location cur = camera.getLocation().convertToUnits(LengthUnit.Millimeters);
        MovableUtils.moveToLocationAtSafeZ(camera,
                new Location(LengthUnit.Millimeters, xMm, yMm, cur.getZ(), cur.getRotation()));
        CameraCapture.Capture c = CameraCapture.capture(camera);
        return new Frame(c.image, c.mmPerPxX, c.mmPerPxY, xMm, yMm);
    }

    /** The same move, then OpenPnP's light-settle-capture (the light actuator of the camera on). */
    @Override
    public Frame captureLit(double xMm, double yMm, Double surfaceZMm) throws Exception {
        Location cur = camera.getLocation().convertToUnits(LengthUnit.Millimeters);
        MovableUtils.moveToLocationAtSafeZ(camera,
                new Location(LengthUnit.Millimeters, xMm, yMm, cur.getZ(), cur.getRotation()));
        CameraCapture.Capture c = CameraCapture.captureLit(camera,
                surfaceZMm == null ? null : new Length(surfaceZMm, LengthUnit.Millimeters));
        return new Frame(c.image, c.mmPerPxX, c.mmPerPxY, xMm, yMm);
    }

    @Override
    public List<Dot> inspect(Frame before, Frame after) throws Exception {
        return new DotInspector(settings).inspect(before.image, after.image, after.mmPerPxX, after.mmPerPxY);
    }
}
