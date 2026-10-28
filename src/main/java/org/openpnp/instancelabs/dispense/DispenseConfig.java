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

    // --- named locations (N2 nozzle coordinates; z = surface Z of the item at that place) ----
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
     * Needle Z offset from touch-off: N2 Z at which the needle tip touches a surface whose OpenPnP Z
     * is s equals s + needleZOffsetMm.
     */
    public double needleZOffsetMm = 0.0;
    /** Epoch ms of the last needle touch-off; null = never (I4). */
    public Long needleZOffsetSetAtMs = null;
    /** Epoch ms of the last needle change; I4 requires a touch-off after it. */
    public Long needleChangedAtMs = null;

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

    public NamedLocation location(String name) {
        return locations == null ? null : locations.get(name);
    }

    public boolean isNeedleTouchedOff() {
        if (needleZOffsetSetAtMs == null) {
            return false;
        }
        return needleChangedAtMs == null || needleZOffsetSetAtMs >= needleChangedAtMs;
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
        if (needleGapMm <= 0) {
            p.add("needleGapMm must be > 0");
        }
        if (hopHeightMm <= 0) {
            p.add("hopHeightMm must be > 0");
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
