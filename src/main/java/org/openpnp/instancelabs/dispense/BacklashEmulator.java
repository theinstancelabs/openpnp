package org.openpnp.instancelabs.dispense;

import java.util.ArrayList;
import java.util.List;

import org.openpnp.machine.reference.axis.ReferenceControllerAxis.BacklashCompensationMethod;

/**
 * Reproduces, for the X/Y/Z controller axes, the moves that
 * AbstractMotionPlanner.createBacklashCompensatedMotion() (OpenPnP 2.7,
 * machine/reference/driver/AbstractMotionPlanner.java:466-640) would emit for a planned raw target.
 * The equivalence is checked against OpenPnP's own moveTo output in DispenseTransformTest.
 */
public final class BacklashEmulator {
    public static final class Move {
        /** Driver coordinates (including any backlash offset) of X, Y, Z. */
        public final double[] driver;
        /** 1.0 for a normal move; the backlash speed factor for the slow final approach. */
        public final double speedFactor;

        Move(double[] driver, double speedFactor) {
            this.driver = driver;
            this.speedFactor = speedFactor;
        }
    }

    private final AxisBacklash[] axes;
    private final boolean enabled;
    private final double[] planned = new double[3];
    private final double[] lastOffset = new double[3];

    /**
     * @param planned current planned raw coordinates (OpenPnP axis coordinates, without offset)
     * @param lastOffset current directional backlash offsets (driver - planned)
     */
    public BacklashEmulator(AxisBacklash[] axes, double[] planned, double[] lastOffset, boolean enabled) {
        this.axes = axes.clone();
        this.enabled = enabled;
        System.arraycopy(planned, 0, this.planned, 0, 3);
        System.arraycopy(lastOffset, 0, this.lastOffset, 0, 3);
    }

    public double[] planned() {
        return planned.clone();
    }

    public double[] lastOffset() {
        return lastOffset.clone();
    }

    public double[] driver() {
        double[] d = new double[3];
        for (int i = 0; i < 3; i++) {
            d[i] = planned[i] + lastOffset[i];
        }
        return d;
    }

    /** Plans a move to raw target t (planned coordinates) and returns the driver-level moves. */
    public List<Move> moveTo(double[] t) {
        double[] bcCur = driver();
        double[] bcNew = new double[3];
        double[] neu = new double[3];
        boolean extra = false;
        double slow = 1.0;
        for (int i = 0; i < 3; i++) {
            AxisBacklash a = axes[i];
            boolean moving = !a.matches(planned[i], t[i]);
            if (!moving) {
                bcNew[i] = t[i] + lastOffset[i];
                neu[i] = t[i] + lastOffset[i];
                continue;
            }
            double seg = t[i] - planned[i];
            bcNew[i] = t[i];
            neu[i] = t[i];
            if (!enabled || a.offsetMm == 0 || a.method == BacklashCompensationMethod.None) {
                lastOffset[i] = 0;
            }
            else if (a.method.isDirectionalMethod()) {
                double eff = Math.signum(seg) == Math.signum(a.offsetMm) ? a.offsetMm : 0;
                if (a.method == BacklashCompensationMethod.DirectionalSneakUp) {
                    extra = true;
                    double sneak = a.sneakUpOffsetMm * Math.signum(seg) - eff;
                    bcNew[i] = t[i] - sneak;
                    if (Math.signum(lastOffset[i]) == Math.signum(eff)) {
                        double disp = a.matches(bcCur[i], bcNew[i]) ? 0 : bcNew[i] - bcCur[i];
                        if (Math.signum(disp) != Math.signum(seg)) {
                            bcNew[i] = bcCur[i];
                        }
                    }
                    slow = Math.min(slow, a.speedFactor);
                }
                else {
                    bcNew[i] = t[i] + eff;
                }
                neu[i] = t[i] + eff;
                lastOffset[i] = eff;
            }
            else {
                lastOffset[i] = 0;
                if (a.method == BacklashCompensationMethod.OneSidedPositioning
                        || (a.method == BacklashCompensationMethod.OneSidedOptimizedPositioning
                                && Math.signum(seg) == Math.signum(a.offsetMm))) {
                    bcNew[i] = t[i] + a.offsetMm;
                    extra = true;
                    slow = Math.min(slow, a.speedFactor);
                }
            }
        }
        System.arraycopy(t, 0, planned, 0, 3);
        List<Move> moves = new ArrayList<>();
        if (extra) {
            moves.add(new Move(bcNew, 1.0));
            moves.add(new Move(neu, slow));
        }
        else {
            moves.add(new Move(neu, 1.0));
        }
        return moves;
    }
}
