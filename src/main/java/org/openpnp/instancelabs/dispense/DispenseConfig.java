package org.openpnp.instancelabs.dispense;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/**
 * Dispenser configuration. Persisted as JSON in {@code <configDir>/instancelabs/dispense-config.json},
 * deliberately NOT in machine.xml so the OpenPnP machine configuration is never touched.
 *
 * <p>All lengths are millimetres in OpenPnP machine coordinates of the dispenser nozzle (N2) unless
 * stated otherwise. Rotations of the auger axis are degrees of the B axis (Marlin J axis, 4.44
 * steps/deg). Defaults follow plan §4.5, §5.1, §5.6 and §6.1.</p>
 *
 * <p>Fields are public for Gson and the web API. Use {@link #copy()} before mutating a shared
 * instance.</p>
 */
public class DispenseConfig {
    public static final String FILE_NAME = "dispense-config.json";
    public static final String SUBDIR = "instancelabs";

    /**
     * Version of this file's schema. 2: the pneumatic and watchdog G-code strings were removed (they
     * are constants in ControllerCommands); older files load, the removed keys are ignored.
     */
    public int schemaVersion = 2;

    // --- identity of the dispenser in the OpenPnP machine -----------------------------------
    /** Nozzle carrying the auger. Matched against nozzle id first, then name. */
    public String nozzle = "N2";
    /** OpenPnP axis name of the auger motor ("b" = Marlin J axis, G-code letter B). */
    public String bAxisName = "b";

    // --- pneumatics (plan §4.1, §4.2a: pump ON/OFF only, never PWM; valve S255 only) ---------
    // The pump/valve G-code is fixed in ControllerCommands (M106 P2 S255 / M107 P2, M106 P3 S255 /
    // M107 P3): it is not a setting, so it cannot be PWM'd, swapped or left on by a settings edit.
    /** Pump ON -> wait -> valve ON (plan §4.5 recommendation 1). */
    public int pumpToValveSettleMs = 500;
    /** Valve ON -> settle before the first rotation (measured in M8). */
    public int valveSettleMs = 300;

    // --- named locations (x, y: N2 nozzle coordinates; z: surface Z in OpenPnP's frame, i.e. the
    // N1 nozzle Z touching that surface, the frame of OpenPnP's board Z) -------------------------
    public Map<String, NamedLocation> locations = new LinkedHashMap<>();
    /** Waste / prime pad location name (plan §4.5, I9). */
    public String wasteLocationName = "waste";
    /** Blank coupon (glass/copper) for dot-ladder calibration and needle touch-off. */
    public String blankLocationName = "blank";

    // --- Z geometry --------------------------------------------------------------------------
    /** Commanded needle-to-surface gap while dispensing (plan §6.1: 30-50 % of 0.41 mm ID). */
    public double needleGapMm = 0.15;
    /** Hop height above the dispense Z between dots on the same board. */
    public double hopHeightMm = 1.0;
    /** N2 Z used for travel to/from the board, waste and blank. 31.5 = see-saw balanced (LIVE home). */
    public double travelSafeZMm = 31.5;
    /**
     * Needle Z offset: N2 Z at which the needle tip touches a surface whose OpenPnP Z (N1 contact
     * Z) is s equals s + needleZOffsetMm. Bootstrapped by {@link #needleAnchor}, refined by touch-offs.
     */
    public double needleZOffsetMm = 0.0;
    /** Epoch ms of the last needle touch-off; null = never (I4). */
    public Long needleZOffsetSetAtMs = null;
    /** Epoch ms of the last needle change; I4 requires a touch-off after it. */
    public Long needleChangedAtMs = null;
    /**
     * I4 Z frame: one surface touched by N1 and by the needle (review finding: nothing tied the
     * surface Z to the frame of the needle offset). Set only by POST /api/needle/anchor.
     */
    public NeedleAnchor needleAnchor = null;
    /**
     * Probed surfaces in OpenPnP's frame (N1 contact Z), by name: {@link #boardSurfaceName} for
     * board runs (per-dot Z from its plane), {@link #blankLocationName} for the dot ladder and the
     * touch-off. Set only through /api/surfaces.
     */
    public Map<String, SurfaceMap> surfaces = new LinkedHashMap<>();
    public String boardSurfaceName = "board";
    /** I4: waste, blank and every surface point must lie within this of the anchor surface (mm). */
    public double zFrameToleranceMm = 1.0;
    /** I4: largest plane-fit residual of a surface map (gap budget; must be below the gap). */
    public double surfaceMaxResidualMm = 0.075;
    /** I4: a dot may lie at most this far outside the probed area (convex hull of the points). */
    public double surfaceMarginMm = 2.0;
    /** I4: touch-offs and manual entries may move the offset at most this far from the anchor's. */
    public double needleOffsetMaxDriftMm = 1.0;
    /** Extra height of the prime at the waste pad while the needle is not touched off yet. */
    public double touchOffPrimeExtraClearanceMm = 0.5;

    // --- motion --------------------------------------------------------------------------------
    public double travelFeedMmS = 200;
    public double travelAccelMmS2 = 1000;
    public double zDownFeedMmS = 50;
    public double zLiftFeedMmS = 25;
    /** Dispense-lift acceleration, lower than placement (DS:DFS §8: filled-material separation). */
    public double zLiftAccelMmS2 = 200;
    /** Auger feed rate while dispensing, deg/s of B. */
    public double bFeedDegS = 360;
    /** Auger acceleration sent with M201 (deg/s^2). */
    public double bAccelDegS2 = 2000;
    /** Auger max feed sent with M203 (deg/s); must be >= bFeedDegS and retractFeedDegS. */
    public double bMaxFeedDegS = 720;
    /** Suck-back after each dot (deg). */
    public double retractDeg = 5;
    public double retractFeedDegS = 360;
    /**
     * Dwell after the feed rotation, before suck-back (ms). NOTE: Marlin's G4 calls
     * planner.synchronize() (Marlin/src/gcode/motion/G4.cpp:36), so any non-zero dwell drains the
     * motion queue once per dot. 0 = no G4 emitted (full look-ahead streaming).
     */
    public int dwellMs = 50;
    /** +1 if positive B extrudes, -1 if negative B extrudes. */
    public int bExtrudeSign = 1;
    /** Follow the axes' backlash compensation settings when streaming (as OpenPnP would). */
    public boolean honorBacklash = true;
    /** Disable the B driver (M18 B) on abort (plan §4.4). */
    public boolean abortDisablesB = true;
    /** Timeout for one streamed command acknowledgement. */
    public long commandTimeoutMs = 10000;

    // --- prime / test dot (I9) -----------------------------------------------------------------
    /** I9: prime and test dot on the waste location before each board. */
    public boolean primeAndTestDot = true;
    public double primeRotationDeg = 360;
    /** Re-prime rotation when pressure is re-applied after the optical test-dot check. */
    public double rePrimeRotationDeg = 90;
    /** Test dot position relative to the waste location (mm). */
    public double testDotOffsetXMm = 2.0;
    public double testDotOffsetYMm = 0.0;
    /** I9 optical check of the test dot with the top camera (requires vision). */
    public boolean testDotOpticalCheck = true;
    /** Minimum acceptable test-dot diameter as a fraction of the expected diameter. */
    public double testDotMinDiameterRatio = 0.6;

    // --- host watchdog keepalive (only used if M115 reports Cap:HOST_WATCHDOG:1) ----------------
    // Query, arm, ping and disarm commands are fixed in ControllerCommands (M115, M990 S<window>,
    // M990, M990 S0); only the timing is a setting.
    public boolean keepaliveEnabled = true;
    /** Firmware watchdog window sent with M990 S (ms); at least 2 x keepaliveIntervalMs. */
    public int keepaliveWindowMs = 3000;
    public int keepaliveIntervalMs = 1000;

    // --- interlocks ----------------------------------------------------------------------------
    /** I7: maximum continuous pump+valve on-time per session (s). */
    public double maxContinuousOnTimeS = 600;
    /** I5: barrel pressure window (no sensor yet; disabled by default). */
    public boolean pressureInterlockEnabled = false;
    public double pressureMinPsi = 1.0;
    public double pressureMaxPsi = 18.0;
    /** §5.7 rule: refuse pads whose type status is below this (not-started..validated). */
    public String minPadTypeStatus = PadTypeStatus.CHARACTERIZED.dbName;
    /** Refuse the board if any pad is flagged (true), or skip flagged pads (false). */
    public boolean refuseFlaggedPads = true;

    // --- planning ------------------------------------------------------------------------------
    /** Equivalent stencil thickness t (plan §5.6; your choice 0.10 mm). */
    public double pasteThicknessMm = 0.10;
    /** Paste density, mg/mm^3 (INFERRED 3.7, measure in M21). */
    public double pasteDensityMgMm3 = 3.7;
    /** Provisional auger displacement, mm^3/rev (DS:DFS Fig. 3 read-off, plan §5.6). */
    public double provisionalQMm3PerRev = 0.47;
    public int maxDotsPerPad = 6;
    /** A dot fits a cell if its diameter <= cell short side * dotFitFactor. */
    public double dotFitFactor = 1.0;
    /** Include placements disabled for pick-and-place (paste is still wanted on their pads). */
    public boolean includeDisabledPlacements = true;
    public int twoOptMaxPasses = 50;

    public static class NamedLocation {
        public double x, y, z;

        public NamedLocation() {
        }

        public NamedLocation(double x, double y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        @Override
        public String toString() {
            return String.format("(%.3f, %.3f, %.3f)", x, y, z);
        }
    }

    /** N1 contact and needle contact on one surface: the needle Z frame (I4). */
    public static class NeedleAnchor {
        /** N1 nozzle Z (OpenPnP frame) with N1 touching the reference surface. */
        public double surfaceZMm;
        /** N2 Z with the needle tip touching the same surface. */
        public double needleContactZMm;
        /** Where (informational). */
        public Double xMm, yMm;
        public long setAtMs;

        public NeedleAnchor() {
        }

        public NeedleAnchor(double surfaceZMm, double needleContactZMm, Double xMm, Double yMm, long setAtMs) {
            this.surfaceZMm = surfaceZMm;
            this.needleContactZMm = needleContactZMm;
            this.xMm = xMm;
            this.yMm = yMm;
            this.setAtMs = setAtMs;
        }

        public double offsetMm() {
            return needleContactZMm - surfaceZMm;
        }
    }

    /** One probed point: machine X/Y, Z = N1 contact Z (OpenPnP frame). */
    public static class SurfacePoint {
        public double x, y, z;

        public SurfacePoint() {
        }

        public SurfacePoint(double x, double y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    /** A probed surface (board Z-map or blank plane). */
    public static class SurfaceMap {
        public java.util.List<SurfacePoint> points = new java.util.ArrayList<>();
        public Long setAtMs;
        public String note;
    }

    public NamedLocation location(String name) {
        return locations == null ? null : locations.get(name);
    }

    public SurfaceMap surface(String name) {
        return surfaces == null || name == null ? null : surfaces.get(name);
    }

    /** Needle frame anchored since the last needle change (I4). */
    public boolean isNeedleAnchored() {
        return needleAnchor != null && (needleChangedAtMs == null || needleAnchor.setAtMs >= needleChangedAtMs);
    }

    /** Offset touched off (or entered) since the last needle change and since the last anchor (I4). */
    public boolean isNeedleTouchedOff() {
        if (needleZOffsetSetAtMs == null) {
            return false;
        }
        if (needleAnchor != null && needleZOffsetSetAtMs < needleAnchor.setAtMs) {
            return false;
        }
        return needleChangedAtMs == null || needleZOffsetSetAtMs >= needleChangedAtMs;
    }

    /**
     * Lowest N2 Z the needle may ever be commanded to (I4 hard floor): the anchor surface minus the
     * frame tolerance, plus the offset, minus the deepest touch-off gap (0.1 mm). NaN without anchor.
     */
    public double needleFloorZMm() {
        if (needleAnchor == null) {
            return Double.NaN;
        }
        return needleAnchor.surfaceZMm - zFrameToleranceMm + needleZOffsetMm - 0.1;
    }

    public PadTypeStatus minPadTypeStatus() {
        return PadTypeStatus.fromDb(minPadTypeStatus);
    }

    private static Gson gson() {
        return new GsonBuilder().setPrettyPrinting().serializeNulls().create();
    }

    public String toJson() {
        return gson().toJson(this);
    }

    public static DispenseConfig fromJson(String json) {
        DispenseConfig c = gson().fromJson(json, DispenseConfig.class);
        if (c == null) {
            c = new DispenseConfig();
        }
        if (c.locations == null) {
            c.locations = new LinkedHashMap<>();
        }
        if (c.surfaces == null) {
            c.surfaces = new LinkedHashMap<>();
        }
        return c;
    }

    public DispenseConfig copy() {
        return fromJson(toJson());
    }

    public static File fileFor(File configDir) {
        return new File(new File(configDir, SUBDIR), FILE_NAME);
    }

    /** Loads the config, or returns defaults if the file does not exist. */
    public static DispenseConfig load(File configDir) throws IOException {
        File f = fileFor(configDir);
        if (!f.exists()) {
            return new DispenseConfig();
        }
        return fromJson(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
    }

    /** Atomically writes the config (temp file + rename). */
    public void save(File configDir) throws IOException {
        File f = fileFor(configDir);
        f.getParentFile().mkdirs();
        File tmp = new File(f.getParentFile(), FILE_NAME + ".tmp");
        Files.write(tmp.toPath(), toJson().getBytes(StandardCharsets.UTF_8));
        Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
    }

    /** Basic sanity checks; returns a list of problems (empty if OK). */
    public java.util.List<String> validate() {
        java.util.List<String> p = new java.util.ArrayList<>();
        // Pump and valve G-code: constants (ControllerCommands), nothing to validate here.
        // Z geometry (review finding: hop/travel values could move a nozzle down on the see-saw).
        // Machine-dependent bounds (travel Z inside the Z axis safe zone, hop <= travel) are checked
        // against the live axes when a program is built (GcodeStreamBuilder).
        if (!(needleGapMm > 0 && needleGapMm <= 1.0)) {
            p.add("needleGapMm must be > 0 and <= 1.0");
        }
        if (!(hopHeightMm > 0 && hopHeightMm <= 10)) {
            p.add("hopHeightMm must be > 0 and <= 10");
        }
        if (!Double.isFinite(travelSafeZMm)) {
            p.add("travelSafeZMm must be a number");
        }
        if (!(zFrameToleranceMm > 0 && zFrameToleranceMm <= 3)) {
            p.add("zFrameToleranceMm must be > 0 and <= 3 (it must catch a needle-frame mix-up of ~5 mm)");
        }
        if (!(surfaceMaxResidualMm > 0 && surfaceMaxResidualMm < needleGapMm)) {
            p.add("surfaceMaxResidualMm must be > 0 and below needleGapMm");
        }
        if (!(surfaceMarginMm >= 0 && surfaceMarginMm <= 20)) {
            p.add("surfaceMarginMm must be 0 to 20");
        }
        if (!(needleOffsetMaxDriftMm > 0 && needleOffsetMaxDriftMm <= 2)) {
            p.add("needleOffsetMaxDriftMm must be > 0 and <= 2");
        }
        if (!(touchOffPrimeExtraClearanceMm >= 0 && touchOffPrimeExtraClearanceMm <= 5)) {
            p.add("touchOffPrimeExtraClearanceMm must be 0 to 5");
        }
        if (bFeedDegS <= 0 || retractFeedDegS <= 0 || bMaxFeedDegS < Math.max(bFeedDegS, retractFeedDegS)) {
            p.add("B feeds must be > 0 and bMaxFeedDegS >= max(bFeedDegS, retractFeedDegS)");
        }
        if (bExtrudeSign != 1 && bExtrudeSign != -1) {
            p.add("bExtrudeSign must be +1 or -1");
        }
        if (zLiftAccelMmS2 <= 0 || travelAccelMmS2 <= 0 || bAccelDegS2 <= 0) {
            p.add("accelerations must be > 0");
        }
        if (retractDeg < 0 || dwellMs < 0 || pumpToValveSettleMs < 0 || valveSettleMs < 0) {
            p.add("retract/dwell/settle values must be >= 0");
        }
        if (keepaliveIntervalMs < 100) {
            p.add("keepaliveIntervalMs must be >= 100");
        }
        if (keepaliveWindowMs < Math.max(ControllerCommands.KEEPALIVE_MIN_MS, 2L * keepaliveIntervalMs)
                || keepaliveWindowMs > 60000) {
            p.add("keepaliveWindowMs must be at least 2 x keepaliveIntervalMs and at most 60000");
        }
        if (pasteThicknessMm <= 0 || pasteDensityMgMm3 <= 0 || provisionalQMm3PerRev <= 0) {
            p.add("paste thickness, density and q must be > 0");
        }
        if (maxDotsPerPad < 1) {
            p.add("maxDotsPerPad must be >= 1");
        }
        return p;
    }
}
