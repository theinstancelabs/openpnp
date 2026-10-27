package org.openpnp.instancelabs.dispense;

/** Reasons a pad is not (or only provisionally) dispensable. */
public enum PadFlag {
    /** No pad type matched the package. Blocking. */
    NO_PAD_TYPE(true),
    /** Pad type status below DispenseConfig.minPadTypeStatus (plan §5.7 rule). Blocking. */
    STATUS_BELOW_MIN(true),
    /** Would need a dot smaller than the smallest characterised rung (plan §5.6). Blocking. */
    DOT_BELOW_LADDER(true),
    /** No grid up to maxDotsPerPad fits the pad within the ladder range. Blocking. */
    NO_FIT(true),
    /** Ladder diameters are not monotonic in rotation; refuse to interpolate. Blocking. */
    LADDER_NOT_MONOTONIC(true),
    /** Pad layout cannot be built (footprint has no pads and the type is footprint-only). Blocking. */
    NO_GEOMETRY(true),
    /** No dot ladder yet: rotation from q only, one dot per pad. Warning. */
    NO_LADDER(false),
    /** Calibration in use is provisional (not from the scale). Warning. */
    PROVISIONAL_CALIBRATION(false);

    public final boolean blocking;

    PadFlag(boolean blocking) {
        this.blocking = blocking;
    }
}
