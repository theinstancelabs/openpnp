package org.openpnp.instancelabs.dispense;

/**
 * Maps dispenser-nozzle (N2) machine coordinates to raw controller coordinates. The OpenPnP
 * implementation delegates to OpenPnP's own transform chain; tests may supply simple ones.
 */
public interface RawMapper {
    String letterX();

    String letterY();

    String letterZ();

    String letterB();

    /** Raw planned (non-backlash-offset) controller X/Y/Z for N2 at machine (x, y, z). */
    RawPoint toRaw(double x, double y, double z) throws Exception;

    /** Backlash settings of the X, Y, Z controller axes. */
    AxisBacklash[] backlash();

    /** Current planned raw X/Y/Z (OpenPnP axis coordinates). */
    double[] currentPlanned();

    /** Current driver raw X/Y/Z (what the controller was last sent, incl. backlash offsets). */
    double[] currentDriver();

    /** Current raw B (driver coordinate, degrees). */
    double currentB();

    /** Current N2 machine location {x, y, z} (mm). */
    double[] currentNozzleLocation();
}
