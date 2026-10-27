package org.openpnp.instancelabs.dispense;

/** Pad type characterisation status, ordered (plan §5.7). */
public enum PadTypeStatus {
    NOT_STARTED("not-started"),
    PROVISIONAL("provisional"),
    CHARACTERIZED("characterized"),
    VALIDATED("validated");

    public final String dbName;

    PadTypeStatus(String dbName) {
        this.dbName = dbName;
    }

    public boolean atLeast(PadTypeStatus other) {
        return ordinal() >= other.ordinal();
    }

    public static PadTypeStatus fromDb(String s) {
        if (s == null) {
            return NOT_STARTED;
        }
        for (PadTypeStatus v : values()) {
            if (v.dbName.equalsIgnoreCase(s.trim()) || v.name().equalsIgnoreCase(s.trim())) {
                return v;
            }
        }
        throw new IllegalArgumentException("Unknown pad type status: " + s);
    }
}
