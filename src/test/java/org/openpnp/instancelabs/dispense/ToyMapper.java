package org.openpnp.instancelabs.dispense;

/**
 * Deterministic RawMapper for golden tests: the live N2 geometry written out by hand
 * (raw X = x - 23.615, raw Y = y + 63.947, raw Z = 63 - z for the see-saw), no backlash.
 */
public class ToyMapper implements RawMapper {
    double[] nozzle;
    double b;
    AxisBacklash[] backlash = {AxisBacklash.none(), AxisBacklash.none(), AxisBacklash.none()};

    public ToyMapper(double x, double y, double z, double b) {
        this.nozzle = new double[] {x, y, z};
        this.b = b;
    }

    @Override
    public String letterX() {
        return "X";
    }

    @Override
    public String letterY() {
        return "Y";
    }

    @Override
    public String letterZ() {
        return "Z";
    }

    @Override
    public String letterB() {
        return "B";
    }

    @Override
    public RawPoint toRaw(double x, double y, double z) {
        return new RawPoint(x - 23.615, y + 63.947, 63 - z);
    }

    @Override
    public AxisBacklash[] backlash() {
        return backlash;
    }

    @Override
    public double[] currentPlanned() {
        RawPoint p = toRaw(nozzle[0], nozzle[1], nozzle[2]);
        return new double[] {p.x, p.y, p.z};
    }

    @Override
    public double[] currentDriver() {
        return currentPlanned();
    }

    @Override
    public double currentB() {
        return b;
    }

    @Override
    public double[] currentNozzleLocation() {
        return nozzle.clone();
    }
}
