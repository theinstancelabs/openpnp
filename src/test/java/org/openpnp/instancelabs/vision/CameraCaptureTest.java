package org.openpnp.instancelabs.vision;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.awt.image.BufferedImage;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.openpnp.model.Length;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.spi.Camera;

/** CameraCapture against a stub Camera (java.lang.reflect.Proxy): no device is opened. */
public class CameraCaptureTest {
    static Camera stub(BufferedImage image, Location upp, Location uppAtZ, List<String> calls) {
        return (Camera) Proxy.newProxyInstance(Camera.class.getClassLoader(),
                new Class<?>[] {Camera.class}, (proxy, method, args) -> {
                    calls.add(method.getName() + (args == null ? "" : "/" + args.length));
                    switch (method.getName()) {
                        case "settleAndCapture":
                            return image;
                        case "getUnitsPerPixel":
                            return (args == null || args.length == 0) ? upp : uppAtZ;
                        case "getName":
                            return "stub";
                        default:
                            throw new UnsupportedOperationException(method.getName());
                    }
                });
    }

    @Test
    public void convertsUnitsPerPixelToMillimetres() throws Exception {
        BufferedImage img = new BufferedImage(8, 6, BufferedImage.TYPE_3BYTE_BGR);
        List<String> calls = new ArrayList<>();
        // 0.001 in/px = 0.0254 mm/px, 0.0015 in/px = 0.0381 mm/px.
        Camera cam = stub(img, new Location(LengthUnit.Inches, 0.001, 0.0015, 0, 0), null, calls);
        CameraCapture.Capture c = CameraCapture.capture(cam);
        assertSame(img, c.image);
        assertEquals(0.0254, c.mmPerPxX, 1e-12);
        assertEquals(0.0381, c.mmPerPxY, 1e-12);
        // The no-arg default settleAndCapture() is what is invoked.
        assertEquals("settleAndCapture", calls.get(0));
    }

    @Test
    public void usesScaleAtGivenZ() throws Exception {
        BufferedImage img = new BufferedImage(8, 6, BufferedImage.TYPE_3BYTE_BGR);
        List<String> calls = new ArrayList<>();
        Camera cam = stub(img, new Location(LengthUnit.Millimeters, 0.03, 0.03, 0, 0),
                new Location(LengthUnit.Millimeters, 0.031, -0.032, 0, 0), calls);
        CameraCapture.Capture c =
                CameraCapture.capture(cam, new Length(1.6, LengthUnit.Millimeters));
        assertEquals(0.031, c.mmPerPxX, 1e-12);
        assertEquals(0.032, c.mmPerPxY, 1e-12);
    }

    @Test
    public void rejectsMissingImageOrScale() {
        List<String> calls = new ArrayList<>();
        BufferedImage img = new BufferedImage(8, 6, BufferedImage.TYPE_3BYTE_BGR);
        assertThrows(Exception.class, () -> CameraCapture.capture(
                stub(null, new Location(LengthUnit.Millimeters, 0.03, 0.03, 0, 0), null, calls)));
        assertThrows(IllegalStateException.class, () -> CameraCapture.capture(
                stub(img, new Location(LengthUnit.Millimeters, 0, 0, 0, 0), null, calls)));
    }
}
