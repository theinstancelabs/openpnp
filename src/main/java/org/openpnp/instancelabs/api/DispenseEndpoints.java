package org.openpnp.instancelabs.api;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;

import org.openpnp.Main;
import org.openpnp.gui.MainFrame;
import org.openpnp.instancelabs.dispense.Calibration;
import org.openpnp.instancelabs.dispense.DispenseConfig;
import org.openpnp.instancelabs.dispense.DispensePlan;
import org.openpnp.instancelabs.dispense.DispenseService;
import org.openpnp.instancelabs.dispense.DotMath;
import org.openpnp.instancelabs.dispense.GcodeProgram;
import org.openpnp.instancelabs.dispense.GcodeStreamBuilder;
import org.openpnp.instancelabs.dispense.InterlockReport;
import org.openpnp.instancelabs.dispense.OpenPnpRawMapper;
import org.openpnp.instancelabs.dispense.PadType;
import org.openpnp.instancelabs.dispense.RunResult;
import org.openpnp.machine.reference.ReferenceMachine;
import org.openpnp.model.Board;
import org.openpnp.model.BoardLocation;
import org.openpnp.model.Footprint;
import org.openpnp.model.Job;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.model.Package;
import org.openpnp.spi.Machine;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Status, manual pressure hold, prime, test dot, named places, needle, engine settings, board plan
 * and run, run history.
 */
final class DispenseEndpoints {
    /** Named location the test dot is dispensed at: waste + the configured test-dot offset. */
    static final String TEST_DOT_LOCATION = "waste-test-dot";
    /** Settings that only the needle endpoints may change (they record a needle_touchoff row). */
    static final java.util.Set<String> PROTECTED_SETTINGS = new java.util.HashSet<>(java.util.Arrays.asList(
            "needleZOffsetMm", "needleZOffsetSetAtMs", "needleChangedAtMs", "schemaVersion"));
    /** Pad type whose single-dot rotation is the default test-dot size (plan §5.6 first target). */
    static final String TEST_DOT_PAD_TYPE = "chip-0603";

    private final ApiContext ctx;
    private final MachineEndpoints machine;

    DispenseEndpoints(ApiContext ctx, MachineEndpoints machine) {
        this.ctx = ctx;
        this.machine = machine;
    }

    void register(ApiServer s) {
        s.route("GET", "/api/status", r -> status(s));
        s.route("POST", "/api/pressure/start", r -> pressureStart());
        s.route("POST", "/api/pressure/stop", r -> ctx.pressure.stop("Pressure off pressed"));
        s.route("POST", "/api/prime", this::prime);
        s.route("POST", "/api/test-dot", this::testDot);
        s.route("GET", "/api/locations", r -> ctx.svc().getConfig().locations);
        s.route("PUT", "/api/locations/([A-Za-z0-9_-]{1,40})", this::setLocation);
        s.route("POST", "/api/needle/changed", r -> needleChanged());
        s.route("POST", "/api/needle/offset", this::needleOffset);
        s.route("GET", "/api/config", r -> ctx.svc().getConfig());
        s.route("PUT", "/api/config", this::putConfig);
        s.route("GET", "/api/boards", r -> boards());
        s.route("GET", "/api/packages", r -> packages());
        s.route("POST", "/api/plan", this::plan);
        s.route("GET", "/api/plan/([A-Za-z0-9-]+)", this::getPlan);
        s.route("POST", "/api/run", this::run);
        s.route("GET", "/api/runs", r -> ctx.svc().getDb().recentRuns(r.queryInt("limit", 20, 1, 500)));
        s.route("GET", "/api/runs/last", r -> lastRun());
        s.route("GET", "/api/events", r -> ctx.svc().getDb().recentEvents(r.queryInt("limit", 50, 1, 1000)));
        s.route("GET", "/api", r -> Views.map("routes", s.describeRoutes()));
    }

    // ------------------------------------------------------------------------------------------
    // Status (polled by the UI)
    // ------------------------------------------------------------------------------------------

    Object status(ApiServer s) throws Exception {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("server", Views.map("version", Main.getVersion(), "timeMs", System.currentTimeMillis(), "port",
                s.getPort(), "lanAccess", s.getSettings().lanAccess));
        out.put("machine", machine.machineStatus());
        DispenseService svc = ctx.svcOrNull();
        Map<String, Object> d = new LinkedHashMap<>();
        out.put("dispense", d);
        Map<String, Object> pressure = ctx.pressure.snapshot();
        pressure.put("engineRunActive", svc != null && svc.isBusy());
        out.put("pressure", pressure);
        out.put("run", ctx.tracker.snapshot());
        if (svc == null) {
            d.put("running", false);
            d.put("available", false);
            d.put("unavailableReason", "the dispense engine is not running");
            return out;
        }
        DispenseConfig cfg = svc.getConfig();
        d.put("running", true);
        d.put("available", svc.isAvailable());
        d.put("unavailableReason", svc.getUnavailableReason());
        d.put("busy", svc.isBusy());
        d.put("nozzle", cfg.nozzle);
        d.put("bZeroed", svc.isBZeroed());
        d.put("needle", Views.map("touchedOff", cfg.isNeedleTouchedOff(), "zOffsetMm", cfg.needleZOffsetMm,
                "setAtMs", cfg.needleZOffsetSetAtMs, "changedAtMs", cfg.needleChangedAtMs, "gapMm",
                cfg.needleGapMm));
        d.put("locations", cfg.locations);
        d.put("wasteLocationName", cfg.wasteLocationName);
        d.put("blankLocationName", cfg.blankLocationName);
        d.put("primeRotationDeg", cfg.primeRotationDeg);
        d.put("testDot", Views.map("offsetXMm", cfg.testDotOffsetXMm, "offsetYMm", cfg.testDotOffsetYMm,
                "opticalCheck", cfg.testDotOpticalCheck, "defaultRotationDeg", defaultTestDotRotation(svc)));
        d.put("maxContinuousOnTimeS", cfg.maxContinuousOnTimeS);
        d.put("minPadTypeStatus", cfg.minPadTypeStatus);
        Calibration cal = svc.getCurrentCalibration();
        d.put("calibration", cal == null ? null
                : Views.map("id", cal.id, "qMm3PerRev", cal.qMm3PerRev, "status", cal.status, "method", cal.method));
        RunResult last = svc.getLastRun();
        out.put("lastRun", Views.run(last, false));
        out.put("interlocks", interlockList(svc, cfg));
        return out;
    }

    /** Plain-language state of each §5.5 interlock (I1..I9) plus machine and config checks. */
    List<Map<String, Object>> interlockList(DispenseService svc, DispenseConfig cfg) {
        InterlockReport r = svc.checkInterlocks(null, 0);
        List<Map<String, Object>> l = new ArrayList<>();
        l.add(item("MACHINE", "Machine started and homed", r, "ok", "started and homed"));
        l.add(item("CONFIG", "Dispenser settings valid (pump and valve full-on only)", r, "ok", "valid"));
        Map<String, Object> stopCheck = machine.stopCommandCheck(cfg);
        boolean stopOk = stopCheck != null && Boolean.TRUE.equals(stopCheck.get("ok"));
        l.add(Views.map("id", "I1", "name", "Stop switches the pneumatics off", "state", stopOk ? "ok" : "warning",
                "text", stopOk ? "dispense runs stop their own pneumatics, and OpenPnP's Stop sends M410 + valve off + pump off"
                        : "dispense runs and this page's STOP switch the pneumatics off, but OpenPnP's own Stop button does not: "
                                + "add the plan §4.4 DISABLE_COMMAND (M410, " + cfg.valveOffGcode + ", "
                                + cfg.pumpOffGcode + ", ..., M18 B) to the driver",
                "detail", stopCheck));
        l.add(Views.map("id", "I2", "name", "Auger (B) zeroed before it moves", "state", "ok", "text",
                svc.isBZeroed() ? "B zeroed since the last home/start"
                        : "B is zeroed (G92 B0) at the start of the next run"));
        l.add(item("I3", "Dispenser nozzle can never pick or discard", r, "ok", cfg.nozzle + " has no nozzle tips"));
        l.add(item("I4", "Needle touched off since the last needle change", r, "ok",
                "needle Z offset " + cfg.needleZOffsetMm + " mm"));
        if (cfg.pressureInterlockEnabled) {
            l.add(item("I5", "Barrel pressure inside the window", r, "ok", "inside the window"));
        }
        else {
            l.add(Views.map("id", "I5", "name", "Barrel pressure inside the window", "state", "off", "text",
                    "off: no barrel pressure sensor is fitted yet"));
        }
        l.add(Views.map("id", "I6", "name", "Pump cannot exceed the auger's 20 psi", "state", "info", "text",
                "hardware (deferred): the regulator sets the pressure; optional buck cap at <= 18 psi dead-head"));
        l.add(Views.map("id", "I7", "name", "Maximum continuous pressure time", "state", "ok", "text",
                "pressure is switched off after " + Math.round(cfg.maxContinuousOnTimeS) + " s"));
        l.add(Views.map("id", "I8", "name", "Paste within its working life", "state", "off", "text",
                "not tracked yet: check the paste date yourself"));
        if (cfg.primeAndTestDot) {
            l.add(item("I9", "Prime and test dot before each board", r, "ok",
                    "prime at '" + cfg.wasteLocationName + "' + test dot" + (cfg.testDotOpticalCheck
                            ? " checked by the camera" : " (no camera check)")));
        }
        else {
            l.add(Views.map("id", "I9", "name", "Prime and test dot before each board", "state", "off", "text",
                    "switched off in the dispenser settings"));
        }
        return l;
    }

    private static Map<String, Object> item(String id, String name, InterlockReport r, String okState, String okText) {
        List<String> msgs = new ArrayList<>();
        for (InterlockReport.Violation v : r.violations) {
            if (v.code.equals(id)) {
                msgs.add(v.message);
            }
        }
        return Views.map("id", id, "name", name, "state", msgs.isEmpty() ? okState : "blocked", "text",
                msgs.isEmpty() ? okText : String.join("; ", msgs));
    }

    /** Single-dot rotation for the 0603 pad with the current calibration (plan §5.6: ~58 deg). */
    static Double defaultTestDotRotation(DispenseService svc) {
        try {
            PadType t = svc.getDb().currentPadType(TEST_DOT_PAD_TYPE);
            Calibration cal = svc.getCurrentCalibration();
            if (t == null || cal == null || cal.qMm3PerRev <= 0) {
                return null;
            }
            double v = DotMath.targetVolumeMm3(DotMath.padAreaMm2(t.padWMm, t.padHMm, t.padRoundness), t.tMm, t.k);
            return Math.round(DotMath.rotationDegForVolume(v, cal.qMm3PerRev) * 10) / 10.0;
        }
        catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------------------------------
    // Pressure, prime, test dot
    // ------------------------------------------------------------------------------------------

    Object pressureStart() throws Exception {
        DispenseService svc = ctx.availableSvc();
        ctx.requireEngineIdle(svc);
        if (ctx.pressure.isActive()) {
            throw ApiException.refused("the pressure is already " + ctx.pressure.getState().name().toLowerCase());
        }
        ApiContext.refuseUnlessOk(ctx.preflightNoPlan(svc, false), "Pressure on");
        return ctx.pressure.start(svc);
    }

    Object prime(ApiServer.Request r) throws Exception {
        JsonObject b = r.json();
        DispenseService svc = ctx.availableSvc();
        DispenseConfig cfg = svc.getConfig();
        double rot = Json.num(b, "rotationDeg", cfg.primeRotationDeg);
        checkRotation(rot, 3600);
        DispenseConfig.NamedLocation waste = requireWaste(cfg);
        startChecks(svc, false, "Prime");
        ctx.tracker.expect("prime");
        Future<RunResult> f = svc.runShots(cfg.wasteLocationName, rot, 1);
        return ApiContext.awaitIf(Json.bool(b, "wait", false), f, rr -> Views.run(rr, false),
                Views.map("kind", "prime", "rotationDeg", rot, "location", waste));
    }

    Object testDot(ApiServer.Request r) throws Exception {
        JsonObject b = r.json();
        DispenseService svc = ctx.availableSvc();
        DispenseConfig cfg = svc.getConfig();
        Double def = defaultTestDotRotation(svc);
        double rot = Json.num(b, "rotationDeg", def == null ? 58.0 : def);
        checkRotation(rot, 720);
        DispenseConfig.NamedLocation waste = requireWaste(cfg);
        startChecks(svc, false, "Test dot");
        DispenseConfig.NamedLocation spot = new DispenseConfig.NamedLocation(waste.x + cfg.testDotOffsetXMm,
                waste.y + cfg.testDotOffsetYMm, waste.z);
        DispenseConfig.NamedLocation cur = cfg.location(TEST_DOT_LOCATION);
        if (cur == null || cur.x != spot.x || cur.y != spot.y || cur.z != spot.z) {
            // The engine dispenses shots at named locations; keep this one derived from the waste place.
            svc.setLocation(TEST_DOT_LOCATION, spot.x, spot.y, spot.z);
        }
        ctx.tracker.expect("test dot");
        Future<RunResult> f = svc.runShots(TEST_DOT_LOCATION, rot, 1);
        return ApiContext.awaitIf(Json.bool(b, "wait", false), f, rr -> Views.run(rr, false),
                Views.map("kind", "test dot", "rotationDeg", rot, "location", spot));
    }

    /** Engine idle, no manual hold, interlocks (as the engine applies them to shots/calibration). */
    void startChecks(DispenseService svc, boolean requireTouchOff, String what) throws ApiException {
        ctx.requireEngineIdle(svc);
        ctx.requireNoPressureHold();
        ApiContext.refuseUnlessOk(ctx.preflightNoPlan(svc, requireTouchOff), what);
    }

    static void checkRotation(double rot, double max) throws ApiException {
        if (rot <= 0 || rot > max) {
            throw ApiException.badRequest("rotation must be more than 0 and at most " + max + " degrees of the auger");
        }
    }

    static DispenseConfig.NamedLocation requireWaste(DispenseConfig cfg) throws ApiException {
        DispenseConfig.NamedLocation w = cfg.location(cfg.wasteLocationName);
        if (w == null) {
            throw ApiException.refused("set the waste place first (Dispense tab, Places: '" + cfg.wasteLocationName + "')");
        }
        return w;
    }

    // ------------------------------------------------------------------------------------------
    // Places, needle, settings
    // ------------------------------------------------------------------------------------------

    Object setLocation(ApiServer.Request r) throws Exception {
        JsonObject b = r.json();
        DispenseService svc = ctx.svc();
        String name = r.group(1);
        if (TEST_DOT_LOCATION.equals(name)) {
            throw ApiException.badRequest("'" + TEST_DOT_LOCATION + "' follows the waste place; set the waste place instead");
        }
        double x = Json.reqNum(b, "x"), y = Json.reqNum(b, "y"), z = Json.reqNum(b, "z");
        svc.setLocation(name, x, y, z);
        return svc.getConfig().locations;
    }

    Object needleChanged() throws Exception {
        DispenseService svc = ctx.svc();
        svc.markNeedleChanged();
        return Views.map("needleChanged", true, "note", "dispensing is blocked (I4) until the needle is touched off");
    }

    Object needleOffset(ApiServer.Request r) throws Exception {
        JsonObject b = r.json();
        DispenseService svc = ctx.svc();
        double off = Json.reqNum(b, "offsetMm");
        if (Math.abs(off) > 20) {
            throw ApiException.badRequest("needle Z offset must be within +/- 20 mm");
        }
        svc.setNeedleZOffset(off, "manual", Json.str(b, "note", "entered in the web UI"));
        return Views.map("zOffsetMm", off, "touchedOff", svc.getConfig().isNeedleTouchedOff());
    }

    /** Partial update: only the fields given replace the current settings. */
    Object putConfig(ApiServer.Request r) throws Exception {
        JsonObject patch = r.json();
        DispenseService svc = ctx.svc();
        if (svc.isBusy()) {
            throw ApiException.refused("settings cannot change while a dispense task runs");
        }
        JsonObject cur = Json.GSON.toJsonTree(svc.getConfig()).getAsJsonObject();
        for (Map.Entry<String, JsonElement> e : patch.entrySet()) {
            if (!cur.has(e.getKey())) {
                throw ApiException.badRequest("unknown setting '" + e.getKey() + "'");
            }
            if (PROTECTED_SETTINGS.contains(e.getKey())) {
                if (!cur.get(e.getKey()).equals(e.getValue())) {
                    throw ApiException.badRequest("'" + e.getKey() + "' is the needle touch-off state (I4): change it "
                            + "with the Needle card or a touch-off, which also record it");
                }
                continue; // unchanged round-trip from the full settings editor
            }
            cur.add(e.getKey(), e.getValue());
        }
        DispenseConfig next = Json.fromJson(cur, DispenseConfig.class);
        if (next.locations == null) {
            next.locations = new LinkedHashMap<>();
        }
        svc.updateConfig(next); // validates; IllegalArgumentException -> 400 with the problems
        return svc.getConfig();
    }

    // ------------------------------------------------------------------------------------------
    // Boards, packages
    // ------------------------------------------------------------------------------------------

    Object boards() {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> job = new ArrayList<>();
        Job j = currentJob();
        if (j != null) {
            for (BoardLocation bl : j.getBoardLocations()) {
                Board b = bl.getBoard();
                Location l = bl.getGlobalLocation().convertToUnits(LengthUnit.Millimeters);
                job.add(Views.map("boardLocationId", bl.getId(), "name", b == null ? bl.getId() : b.getName(), "file",
                        b == null || b.getFile() == null ? null : b.getFile().getAbsolutePath(), "x", l.getX(), "y",
                        l.getY(), "z", l.getZ(), "rotation", l.getRotation(), "side",
                        String.valueOf(bl.getGlobalSide()), "enabled", bl.isEnabled(), "fiducialTransform",
                        String.valueOf(bl.getPlacementsTransformStatus())));
            }
        }
        out.put("jobAvailable", j != null);
        out.put("job", job);
        List<Map<String, Object>> files = new ArrayList<>();
        for (Board b : ctx.configuration.getBoards()) {
            files.add(Views.map("name", b.getName(), "file", b.getFile() == null ? null : b.getFile().getAbsolutePath(),
                    "placements", b.getPlacements().size()));
        }
        out.put("files", files);
        return out;
    }

    /** The job open in OpenPnP's window (null when headless). */
    static Job currentJob() {
        try {
            MainFrame f = MainFrame.get();
            return f == null || f.getJobTab() == null ? null : f.getJobTab().getJob();
        }
        catch (RuntimeException e) {
            return null;
        }
    }

    Object packages() {
        List<Map<String, Object>> l = new ArrayList<>();
        for (Package p : ctx.configuration.getPackages()) {
            Footprint fp = p.getFootprint();
            List<Map<String, Object>> pads = new ArrayList<>();
            if (fp != null) {
                double toMm = new org.openpnp.model.Length(1, fp.getUnits()).convertToUnits(LengthUnit.Millimeters)
                        .getValue();
                for (Footprint.Pad pad : fp.getPads()) {
                    pads.add(Views.map("name", pad.getName(), "x", pad.getX() * toMm, "y", pad.getY() * toMm, "w",
                            pad.getWidth() * toMm, "h", pad.getHeight() * toMm, "rotation", pad.getRotation(),
                            "roundness", pad.getRoundness()));
                }
            }
            l.add(Views.map("id", p.getId(), "description", p.getDescription(), "pads", pads));
        }
        return l;
    }

    // ------------------------------------------------------------------------------------------
    // Plan and run
    // ------------------------------------------------------------------------------------------

    Object plan(ApiServer.Request r) throws Exception {
        JsonObject b = r.json();
        DispenseService svc = ctx.availableSvc();
        String source = Json.str(b, "source", "file");
        DispensePlan plan;
        Map<String, Object> src;
        if ("job".equals(source)) {
            String id = Json.str(b, "boardLocationId", null);
            BoardLocation bl = findJobBoard(id);
            plan = svc.planBoard(bl);
            src = Views.map("source", "job", "boardLocationId", id);
        }
        else if ("file".equals(source)) {
            String path = Json.str(b, "file", null);
            if (path == null) {
                throw ApiException.badRequest("'file' (a .board.xml path) is required");
            }
            File f = new File(path);
            if (!f.isAbsolute() || !f.getName().endsWith(".board.xml") || !f.isFile()) {
                throw ApiException.badRequest("'" + path + "' is not an existing absolute .board.xml file");
            }
            double x = Json.reqNum(b, "x"), y = Json.reqNum(b, "y"), z = Json.num(b, "z", 0.0),
                    rot = Json.num(b, "rotation", 0.0);
            plan = svc.planBoardFile(f, new Location(LengthUnit.Millimeters, x, y, z, rot));
            src = Views.map("source", "file", "file", f.getAbsolutePath(), "x", x, "y", y, "z", z, "rotation", rot);
        }
        else {
            throw ApiException.badRequest("'source' must be 'job' or 'file'");
        }
        PlanStore.Entry e = new PlanStore.Entry(plan, src);
        estimate(svc, e);
        ctx.plans.put(e);
        return Views.plan(e, ctx.preflightPlan(svc, plan, nz(e.estimatedPressurisedSeconds)));
    }

    Object getPlan(ApiServer.Request r) throws Exception {
        DispenseService svc = ctx.availableSvc();
        PlanStore.Entry e = requirePlan(r.group(1));
        return Views.plan(e, ctx.preflightPlan(svc, e.plan, nz(e.estimatedPressurisedSeconds)));
    }

    private PlanStore.Entry requirePlan(String id) throws ApiException {
        PlanStore.Entry e = id == null ? null : ctx.plans.get(id);
        if (e == null) {
            throw ApiException.notFound("plan '" + id + "' is not known (plans are kept for this session only): plan the board again");
        }
        return e;
    }

    private static double nz(Double d) {
        return d == null ? 0 : d;
    }

    /**
     * Kinematic estimate of the whole program, built exactly as the engine will build it (also
     * checks every dot against the soft limits). Read-only.
     */
    void estimate(DispenseService svc, PlanStore.Entry e) {
        try {
            Machine m = ctx.machine();
            DispenseConfig cfg = svc.getConfig();
            OpenPnpRawMapper mapper = new OpenPnpRawMapper((ReferenceMachine) m, svc.getDispenserNozzle(), cfg.bAxisName);
            GcodeProgram p = GcodeStreamBuilder.forPlan(cfg, mapper, e.plan, svc.isBZeroed(), false,
                    GcodeStreamBuilder.Part.FULL);
            e.estimatedSeconds = p.estimatedSeconds;
            e.estimatedPressurisedSeconds = p.estimatedPressurisedSeconds;
        }
        catch (Exception ex) {
            e.estimateError = MachineEndpoints.rootMessage(ex);
        }
    }

    private BoardLocation findJobBoard(String id) throws ApiException {
        Job j = currentJob();
        if (j == null) {
            throw ApiException.refused("no OpenPnP job is open (the window is not running): choose a board file instead");
        }
        for (BoardLocation bl : j.getBoardLocations()) {
            if (bl.getId().equals(id)) {
                return bl;
            }
        }
        throw ApiException.notFound("board '" + id + "' is not in the open job");
    }

    Object run(ApiServer.Request r) throws Exception {
        JsonObject b = r.json();
        DispenseService svc = ctx.availableSvc();
        ctx.requireEngineIdle(svc);
        ctx.requireNoPressureHold();
        PlanStore.Entry e = requirePlan(Json.str(b, "planId", null));
        DispenseConfig cfg = svc.getConfig();
        DispensePlan full = e.plan;
        Calibration cal = svc.getCurrentCalibration();
        if (cal == null || cal.id != full.calibrationId || cfg.needleZOffsetMm != full.needleZOffsetMm
                || cfg.needleGapMm != full.needleGapMm || cfg.hopHeightMm != full.hopHeightMm) {
            throw ApiException.refused("the calibration, needle offset, gap or hop height changed since this plan was "
                    + "made (its dot heights would be wrong): plan the board again");
        }
        DispensePlan p = PlanStore.subset(full, Json.strings(b, "padIds"), Json.integer(b, "maxDots", null));
        PlanStore.Entry runEntry = e;
        if (p != full) {
            svc.getDb().insertPadPlan(p); // the subset is recorded like any plan
            runEntry = new PlanStore.Entry(p, e.source);
            estimate(svc, runEntry);
            ctx.plans.put(runEntry);
        }
        InterlockReport pre = ctx.preflightPlan(svc, p, nz(runEntry.estimatedPressurisedSeconds));
        ApiContext.refuseUnlessOk(pre, "Run");
        ctx.tracker.expect("board");
        Future<RunResult> f = svc.runPlan(p);
        return ApiContext.awaitIf(Json.bool(b, "wait", false), f, rr -> Views.run(rr, false),
                Views.map("kind", "board", "planId", p.planId, "parentPlanId", p == full ? null : full.planId, "dots",
                        p.dots.size(), "pads", p.pads.size()));
    }

    Object lastRun() throws ApiException {
        DispenseService svc = ctx.svc();
        return Views.map("run", Views.run(svc.getLastRun(), true));
    }
}
