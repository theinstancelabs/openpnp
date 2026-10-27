package org.openpnp.instancelabs.dispense;

import org.openpnp.machine.reference.axis.ReferenceControllerAxis;
import org.openpnp.machine.reference.axis.ReferenceControllerAxis.BacklashCompensationMethod;

/** Snapshot of one controller axis' backlash settings (read from the OpenPnP axis). */
public final class AxisBacklash {
    public final BacklashCompensationMethod method;
    public final double offsetMm;
    public final double speedFactor;
    public final double sneakUpOffsetMm;
    public final double resolution;

    public AxisBacklash(BacklashCompensationMethod method, double offsetMm, double speedFactor,
            double sneakUpOffsetMm, double resolution) {
        this.method = method == null ? BacklashCompensationMethod.None : method;
        this.offsetMm = offsetMm;
        this.speedFactor = speedFactor;
        this.sneakUpOffsetMm = sneakUpOffsetMm;
        this.resolution = resolution > 0 ? resolution : 1e-4;
    }

    public static AxisBacklash none() {
        return new AxisBacklash(BacklashCompensationMethod.None, 0, 1, 0, 1e-4);
    }

    public static AxisBacklash of(ReferenceControllerAxis a) {
        return new AxisBacklash(a.getBacklashCompensationMethod(),
                a.getBacklashOffset().convertToUnits(org.openpnp.model.LengthUnit.Millimeters).getValue(),
                a.getBacklashSpeedFactor(),
                a.getSneakUpOffset().convertToUnits(org.openpnp.model.LengthUnit.Millimeters).getValue(),
                a.getResolution());
    }

    /** Same rule as AbstractControllerAxis.coordinatesMatch (resolution ticks). */
    public boolean matches(double a, double b) {
        return Math.round(a / resolution) == Math.round(b / resolution);
    }
}
