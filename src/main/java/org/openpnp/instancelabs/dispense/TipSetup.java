package org.openpnp.instancelabs.dispense;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * One version of a tip setup (tip setups design §22.1): a dispenser and the needle on it, with what
 * the engine needs of the needle (bore, outer diameter, form, smallest dot) and the words of every
 * text. Rows of {@code tip_setups} are append-only; a setup's current definition is its highest
 * version. Its key is the tip key every pad type version and dose record names ({@link TipSpec#key}),
 * so the records made before schema 3 name their setup unchanged.
 *
 * <p>A setup is <b>complete</b> when it has its ID, OD, form and taper (not {@code unknown}); only a
 * complete setup can be mounted. Dispenser, gauge, form and taper never change once the setup has a
 * pad type version, a dose record or a mount (another needle is another setup).</p>
 */
public class TipSetup {
    public static final String FORM_BENT = "bent";
    public static final String FORM_STRAIGHT = "straight";
    public static final String FORM_UNKNOWN = "unknown";
    public static final String TAPER_PLASTIC = "tapered-plastic";
    public static final String TAPER_STEEL = "straight-steel";
    public static final String TAPER_UNKNOWN = "unknown";
    /** odSource: measured, or assumed (shown "from the chart"). */
    public static final String OD_MEASURED = "measured";
    public static final String OD_ASSUMED = "assumed";
    /** Which machine direction the bend points with the tip on. */
    public static final List<String> BEND_TOWARD = Collections.unmodifiableList(Arrays.asList("+x", "-x", "+y", "-y"));
    /** {@link #model} of the seeded setup: §1's model (P/DoseModel) describes it. */
    public static final String MODEL_BENCH = "bench-22g";
    public static final String DEFAULT_DISPENSER = "Vieweg DV-5005";
    /** Not a setup key: a route ({@code POST /api/tip-setups/undo-mount}). */
    public static final String RESERVED_KEY = "undo-mount";

    public long rowId;
    /** Identity, = the tip key on records ({@link TipSpec#KEY}); never changes. */
    public String key;
    public int version;
    /** The words in every text ("not dispensable with the 25G straight"): 1-40 characters, unique among live setups. */
    public String name;
    /** The dispenser the tip sits on: 1-60 characters; another dispenser is another setup. */
    public String dispenser = DEFAULT_DISPENSER;
    /** Part number (at most 60 characters) or null. */
    public String tipModel;
    /** Needle gauge 10-34, or null. */
    public Integer gauge;
    /** Bore at the end (mm, 0.05-2.0); null on placeholders. */
    public Double idMm;
    /** Outer diameter at the end (mm, above the ID, at most 3.0); null on placeholders. */
    public Double odMm;
    /** measured | assumed. */
    public String odSource;
    /** bent | straight | unknown (placeholders only). */
    public String form;
    /** Bend angle 1-90 deg when bent; null when straight or not recorded. */
    public Double bendDeg;
    /** +x | -x | +y | -y where the bend points with the tip on; null when straight or not recorded. */
    public String bendToward;
    /** tapered-plastic | straight-steel | unknown (placeholders only). */
    public String taper;
    /** Tube length from the hub (mm, 3-60), or null (not recorded). */
    public Double lengthMm;
    /** The smallest dot the tip takes reliably: 400, 800 or 1600 steps (R1). */
    public int minDotSteps = 400;
    /** {@link #MODEL_BENCH} when §1's model describes this tip (the seed only), else null; never set by hand. */
    public String model;
    /** Free text, at most 500 characters. */
    public String notes;
    /** A retired setup cannot be mounted (it can be brought back). */
    public boolean retired;
    /** Why this version was written (a correction, the settings file at the update, ...). */
    public String why;
    public long createdAtMs;
    public String operator;

    /** The problems of these fields with their texts (§22.1 "allowed"), empty when none: the engine-side check. */
    public List<String> problems() {
        throw new UnsupportedOperationException("§22 ts-engine: not built yet");
    }

    /** True when the setup has ID, OD, form and taper (not unknown): only a complete setup can be mounted. */
    public boolean isComplete() {
        throw new UnsupportedOperationException("§22 ts-engine: not built yet");
    }

    /**
     * One line for the views, e.g. "ADL AD22TTA-B · 22G · bent 30° · tapered plastic · ID 0.41 mm · OD 0.72 mm
     * (from the chart)".
     */
    public String summary() {
        throw new UnsupportedOperationException("§22 ts-engine: not built yet");
    }

    /** The engine's view of this version as {@code cfg.tip} (key, name, ID, OD, smallest dot, version, model, form). */
    public TipSpec toSpec() {
        throw new UnsupportedOperationException("§22 ts-engine: not built yet");
    }

    /** A field-by-field copy (a new version starts from the current one). */
    public TipSetup copy() {
        throw new UnsupportedOperationException("§22 ts-engine: not built yet");
    }

    /** The seeded setup of schema 3 (§22.1): ad22tta-b, "22G bent taper", ADL AD22TTA-B, model bench-22g. */
    public static TipSetup seed() {
        throw new UnsupportedOperationException("§22 ts-engine: not built yet");
    }

    /**
     * A placeholder for a tip key that older records name (§22.1 migration step 5): name = the key, form and taper
     * unknown, no ID or OD, the notes asking for its bore; it cannot be mounted until it is completed.
     */
    public static TipSetup placeholder(String key) {
        throw new UnsupportedOperationException("§22 ts-engine: not built yet");
    }
}
