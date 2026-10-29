package org.openpnp.instancelabs.dispense;

import java.awt.geom.AffineTransform;

import org.openpnp.model.BoardLocation;

/** Register board: home if needed, find the fiducials, fit the board -> machine affine, probe, plan again. */
public final class RegisterRequest {
    /** auto = probe unless a same seat keeps its surface; always; never (registration only). */
    public enum ProbeMode {
        AUTO, ALWAYS, NEVER
    }

    /** Plans the board again with a stored registration (the API's plan store); returns the new plan id. */
    public interface Replanner {
        String replan(BoardRegistration reg) throws Exception;
    }

    /** The plan "Register board" was pressed on. */
    public DispensePlan plan;
    /** The board at its nominal seat, without our registration. */
    public BoardLocation nominal;
    public String boardKey;
    public BoardRegistration.Nominal nominalSeat;
    /** Inverse of the plan's own board -> machine transform (dots -> board mm). */
    public AffineTransform planToBoard;
    /** File board: the source z; job board: the board's global Z. */
    public double nominalZ;
    public ProbeMode probe = ProbeMode.AUTO;
    public Replanner replanner;
}
