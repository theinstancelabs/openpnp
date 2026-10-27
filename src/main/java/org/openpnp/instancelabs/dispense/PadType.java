package org.openpnp.instancelabs.dispense;

/**
 * One version of a pad type (plan §5.7). Rows are append-only; the current definition of a type is
 * its highest version.
 */
public class PadType {
    /** Pad layout used when a package has no footprint pads in packages.xml. */
    public enum Layout {
        /** One pad at the part origin. */
        SINGLE("single"),
        /** Two pads at +/- pitch/2 along the part X axis (chip R/C/L/LED, SOD). */
        TWO_TERMINAL("two-terminal"),
        /** SOT-23-3: pads 1,2 at (-pitch/2, -/+0.95), pad 3 at (+pitch/2, 0). */
        SOT23_3("sot23-3"),
        /** Pad positions must come from the package footprint; this row only carries k/t/size. */
        FOOTPRINT_ONLY("footprint-only");

        public final String dbName;

        Layout(String dbName) {
            this.dbName = dbName;
        }

        public static Layout fromDb(String s) {
            for (Layout l : values()) {
                if (l.dbName.equalsIgnoreCase(s) || l.name().equalsIgnoreCase(s)) {
                    return l;
                }
            }
            throw new IllegalArgumentException("Unknown pad layout: " + s);
        }
    }

    public long rowId;
    /** Stable identity across versions, e.g. "chip-0603". */
    public String typeKey;
    public int version;
    public String family;
    public String name;
    /** Pad size along the part X axis / Y axis. */
    public double padWMm;
    public double padHMm;
    /** OpenPnP-style roundness 0..100 % of the corner radius (100 = obround/circle). */
    public double padRoundness;
    public Layout layout = Layout.TWO_TERMINAL;
    /** Centre-to-centre pad pitch for TWO_TERMINAL / SOT23_3 layouts. */
    public Double pitchMm;
    /** Equivalent stencil thickness t (mm). */
    public double tMm = 0.10;
    /** Paste factor k (1.0 default, < 1 for e.g. QFN centre pads). */
    public double k = 1.0;
    public PadTypeStatus status = PadTypeStatus.NOT_STARTED;
    /** Java regex matched (find, case-insensitive) against OpenPnP package ids; may be null. */
    public String packagePattern;
    public String notes;
    public long createdAtMs;
    public String operator;

    public PadType() {
    }

    public PadType(String typeKey, String family, String name, double padWMm, double padHMm,
            Layout layout, Double pitchMm, double k, String packagePattern, String notes) {
        this.typeKey = typeKey;
        this.family = family;
        this.name = name;
        this.padWMm = padWMm;
        this.padHMm = padHMm;
        this.layout = layout;
        this.pitchMm = pitchMm;
        this.k = k;
        this.packagePattern = packagePattern;
        this.notes = notes;
    }

    public PadType copy() {
        PadType p = new PadType();
        p.rowId = rowId;
        p.typeKey = typeKey;
        p.version = version;
        p.family = family;
        p.name = name;
        p.padWMm = padWMm;
        p.padHMm = padHMm;
        p.padRoundness = padRoundness;
        p.layout = layout;
        p.pitchMm = pitchMm;
        p.tMm = tMm;
        p.k = k;
        p.status = status;
        p.packagePattern = packagePattern;
        p.notes = notes;
        p.createdAtMs = createdAtMs;
        p.operator = operator;
        return p;
    }

    @Override
    public String toString() {
        return typeKey + " v" + version + " (" + family + " / " + name + ", " + status.dbName + ")";
    }
}
