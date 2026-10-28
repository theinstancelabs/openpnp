package org.openpnp.instancelabs.dispense;

import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import org.openpnp.ConfigurationListener;
import org.openpnp.instancelabs.vision.Dot;
import org.openpnp.machine.reference.ReferenceMachine;
import org.openpnp.machine.reference.axis.ReferenceControllerAxis;
import org.openpnp.model.AxesLocation;
import org.openpnp.model.Board;
import org.openpnp.model.BoardLocation;
import org.openpnp.model.Configuration;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.spi.Camera;
import org.openpnp.spi.Head;
import org.openpnp.spi.Machine;
import org.openpnp.spi.MachineListener;
import org.openpnp.spi.MotionPlanner.CompletionType;
import org.openpnp.spi.Nozzle;
import org.openpnp.spi.NozzleTip;
import org.pmw.tinylog.Logger;

/**
 * Entry point of the Instance Labs dispense engine. One instance per OpenPnP configuration,
 * created when the configuration has loaded ({@link #install(Configuration)} is called from
 * Main). Null-safe: if the dispenser nozzle is missing the service exists but reports
 * {@link #isAvailable()} false and refuses to run.
 *
 * <p>All machine work runs on OpenPnP's machine thread (Machine.submit) and returns a Future. Only
 * one dispense task runs at a time. See docs/instancelabs/DISPENSE_API.md.</p>
 */
public final class DispenseService {
    private static volatile DispenseService instance;
    /** Longest the Stop path waits for an engine line that passed its abort check to be written. */
    static final long STOP_WRITE_GRACE_MS = 50;

    /** A run refused before any G-code that moves or switches anything was sent. */
    public static class RunRefusedException extends Exception {
        private static final long serialVersionUID = 1L;

        public RunRefusedException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final Configuration configuration;
    private final File configDir;
    private final DispenseDb db;
    private volatile DispenseConfig cfg;
    private volatile Nozzle nozzle;
    private volatile String unavailableReason;
    private final AtomicBoolean bZeroed = new AtomicBoolean(false);
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private volatile DispenseExecutor activeExecutor;
    private volatile RunResult activeRun;
    private volatile RunResult lastRun;
    private final List<DispenseListener> listeners = new CopyOnWriteArrayList<>();
    private volatile BarrelPressureSensor pressureSensor;
    private volatile DotImager dotImagerOverride;
    private MachineListener machineListener;

    // ------------------------------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------------------------------

    /**
     * Registers the service to start once the configuration is complete. Safe to call more than
     * once; never throws (a failure only makes the service unavailable).
     */
    public static void install(Configuration configuration) {
        configuration.addListener(new ConfigurationListener.Adapter() {
            @Override
            public void configurationComplete(Configuration c) {
                try {
                    start(c);
                }
                catch (Throwable t) {
                    Logger.error(t, "Instance Labs dispense service failed to start");
                }
            }
        });
    }

    /** Starts (or restarts) the service for a loaded configuration. */
    public static synchronized DispenseService start(Configuration configuration) throws SQLException, IOException {
        if (instance != null) {
            instance.shutdown();
        }
        instance = new DispenseService(configuration);
        Logger.info("Instance Labs dispense service started: {}", instance.isAvailable() ? "available"
                : "unavailable (" + instance.unavailableReason + ")");
        return instance;
    }

    /** The running service, or null before the configuration has loaded. */
    public static DispenseService get() {
        return instance;
    }

    private DispenseService(Configuration configuration) throws SQLException, IOException {
        this.configuration = configuration;
        this.configDir = configuration.getConfigurationDirectory();
        this.cfg = DispenseConfig.load(configDir);
        this.db = DispenseDb.open(configDir);
        resolveNozzle();
        Machine machine = configuration.getMachine();
        if (machine != null) {
            machineListener = new MachineListener.Adapter() {
                @Override
                public void machineHomed(Machine m, boolean isHomed) {
                    bZeroed.set(false);
                }

                @Override
                public void machineEnabled(Machine m) {
                    bZeroed.set(false);
                }

                @Override
                public void machineAboutToBeDisabled(Machine m, String reason) {
                    // Runs on the Stop thread right before the driver's DISABLE_COMMAND (M410, valve,
                    // pump, ...): stop the stream, then let a line that already passed its abort check
                    // reach the wire first, so no engine motion line can follow the M410.
                    abort("machine disabled: " + reason);
                    DispenseExecutor e = activeExecutor;
                    if (e != null) {
                        e.awaitLineWritten(STOP_WRITE_GRACE_MS);
                    }
                }

                @Override
                public void machineDisabled(Machine m, String reason) {
                    bZeroed.set(false);
                    abort("machine disabled: " + reason);
                }
            };
            machine.addListener(machineListener);
        }
    }

    public synchronized void shutdown() {
        abort("service shutdown");
        Machine machine = configuration.getMachine();
        if (machine != null && machineListener != null) {
            machine.removeListener(machineListener);
        }
        try {
            db.close();
        }
        catch (SQLException e) {
            Logger.warn(e);
        }
    }

    private void resolveNozzle() {
        nozzle = null;
        unavailableReason = null;
        Machine machine = configuration.getMachine();
        if (!(machine instanceof ReferenceMachine)) {
            unavailableReason = "machine is not a ReferenceMachine";
            return;
        }
        for (Head h : machine.getHeads()) {
            for (Nozzle n : h.getNozzles()) {
                if (cfg.nozzle.equals(n.getId())) {
                    nozzle = n;
                }
            }
        }
        if (nozzle == null) {
            for (Head h : machine.getHeads()) {
                for (Nozzle n : h.getNozzles()) {
                    if (cfg.nozzle.equals(n.getName())) {
                        nozzle = n;
                    }
                }
            }
        }
        if (nozzle == null) {
            unavailableReason = "no dispenser nozzle '" + cfg.nozzle + "' in the machine";
        }
    }

    // ------------------------------------------------------------------------------------------
    // State and configuration
    // ------------------------------------------------------------------------------------------

    public boolean isAvailable() {
        return nozzle != null;
    }

    public String getUnavailableReason() {
        return unavailableReason;
    }

    public Nozzle getDispenserNozzle() {
        return nozzle;
    }

    public DispenseDb getDb() {
        return db;
    }

    public File getConfigDir() {
        return configDir;
    }

    /** A copy of the current configuration. */
    public DispenseConfig getConfig() {
        return cfg.copy();
    }

    /** Validates, saves to dispense-config.json and applies a new configuration. */
    public synchronized void updateConfig(DispenseConfig newCfg) throws IOException {
        List<String> problems = newCfg.validate();
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException("invalid dispense config: " + problems);
        }
        DispenseConfig c = newCfg.copy();
        c.save(configDir);
        cfg = c;
        resolveNozzle();
        db.event(null, "INFO", "CONFIG", "configuration updated");
    }

    public synchronized void setLocation(String name, double x, double y, double z) throws IOException {
        DispenseConfig c = cfg.copy();
        c.locations.put(name, new DispenseConfig.NamedLocation(x, y, z));
        updateConfig(c);
    }

    /** I4: a needle change invalidates the needle Z offset until the next touch-off. */
    public synchronized void markNeedleChanged() throws IOException {
        DispenseConfig c = cfg.copy();
        c.needleChangedAtMs = System.currentTimeMillis();
        updateConfig(c);
        db.event(null, "INFO", "I4", "needle changed; touch-off required");
    }

    /** Sets the needle Z offset (manual entry or touch-off result) and records it. */
    public synchronized void setNeedleZOffset(double offsetMm, String method, String details)
            throws IOException, SQLException {
        DispenseConfig c = cfg.copy();
        double prev = c.needleZOffsetMm;
        c.needleZOffsetMm = offsetMm;
        c.needleZOffsetSetAtMs = Math.max(System.currentTimeMillis(),
                c.needleChangedAtMs == null ? 0 : c.needleChangedAtMs);
        updateConfig(c);
        db.insertTouchOff(method, offsetMm, prev, details, null);
    }

    /** Whether B has been zeroed (G92 B0) since the last home / reconnect (I2). */
    public boolean isBZeroed() {
        return bZeroed.get();
    }

    public boolean isBusy() {
        return busy.get();
    }

    public RunResult getActiveRun() {
        return activeRun;
    }

    public RunResult getLastRun() {
        return lastRun;
    }

    public void addListener(DispenseListener l) {
        listeners.add(l);
    }

    public void removeListener(DispenseListener l) {
        listeners.remove(l);
    }

    public void setPressureSensor(BarrelPressureSensor s) {
        pressureSensor = s;
    }

    /** Overrides the camera/vision used for test dots and calibrations (null = top camera). */
    public void setDotImager(DotImager imager) {
        dotImagerOverride = imager;
    }

    /** Thread-safe: stops the active stream before its next line; pneumatics are switched off (I1). */
    public void abort(String reason) {
        DispenseExecutor e = activeExecutor;
        if (e != null) {
            e.requestAbort(reason);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Data
    // ------------------------------------------------------------------------------------------

    public List<PadType> getPadTypes() throws SQLException {
        return db.currentPadTypes();
    }

    /** Appends a new version of a pad type (new type if the key is new). */
    public PadType savePadType(PadType p) throws SQLException {
        PadType stored = db.insertPadTypeVersion(p);
        db.event(null, "INFO", "PAD_TYPE", "saved " + stored);
        return stored;
    }

    /** Appends a new version of a per-type dot layout rule. */
    public PadRule savePadRule(PadRule r) throws SQLException {
        return db.insertPadRule(r);
    }

    public Calibration getCurrentCalibration() throws SQLException {
        return db.currentCalibration(System.currentTimeMillis());
    }

    public List<DotLadderRow> getDotLadder(long calibrationId) throws SQLException {
        return db.dotLadder(calibrationId);
    }

    public Calibration recordScaleShots(ScaleCalibration.ShotsEntry e) throws SQLException {
        Calibration c = ScaleCalibration.recordShots(db, e);
        db.event(null, "INFO", "CALIBRATION", "scale shots -> " + c);
        return c;
    }

    public DotLadderRow recordScaleDots(ScaleCalibration.DotsEntry e) throws SQLException {
        DotLadderRow r = ScaleCalibration.recordDots(db, e);
        db.event(null, "INFO", "CALIBRATION", "scale dots: calibration #" + e.calibrationId + " rotation "
                + e.rotationDeg + " -> " + r.mgPerDotMean + " mg/dot");
        return r;
    }

    // ------------------------------------------------------------------------------------------
    // Planning
    // ------------------------------------------------------------------------------------------

    public DispensePlanner newPlanner() throws SQLException {
        Calibration cal = getCurrentCalibration();
        return new DispensePlanner(cfg, new PadTypeResolver(db.currentPadTypes(), db.packageMappings()), cal,
                db.dotLadder(cal.id)).withRules(db.currentPadRules());
    }

    /** Plans a board location; the path starts at the waste location (or N2's current XY). */
    public DispensePlan planBoard(BoardLocation bl) throws Exception {
        requireAvailable();
        double[] start = planStart();
        DispensePlan plan = newPlanner().planBoard(bl, start[0], start[1]);
        db.insertPadPlan(plan);
        db.event(null, "INFO", "PLAN", plan.summary());
        return plan;
    }

    /** Loads a board file and plans it at the given board location (top side up). */
    public DispensePlan planBoardFile(File boardFile, Location boardLocation) throws Exception {
        Board board = configuration.getBoard(boardFile);
        BoardLocation bl = new BoardLocation(board);
        bl.setLocation(boardLocation);
        return planBoard(bl);
    }

    private double[] planStart() {
        DispenseConfig.NamedLocation w = cfg.location(cfg.wasteLocationName);
        if (cfg.primeAndTestDot && w != null) {
            return new double[] {w.x, w.y};
        }
        Location l = nozzle.getLocation().convertToUnits(LengthUnit.Millimeters);
        return new double[] {l.getX(), l.getY()};
    }

    /** Pre-flight interlocks for a plan (I3, I4, I5, I7 estimate, I9, plan rules). */
    public InterlockReport checkInterlocks(DispensePlan plan, double estimatedPressurisedSeconds) {
        return Interlocks.check(interlockInputs(cfg, plan, estimatedPressurisedSeconds));
    }

    private Interlocks.Inputs interlockInputs(DispenseConfig c, DispensePlan plan, double est) {
        Interlocks.Inputs in = new Interlocks.Inputs();
        Machine m = configuration.getMachine();
        in.cfg = c;
        in.machineEnabled = m != null && m.isEnabled();
        in.machineHomed = m != null && m.isHomed();
        in.nozzleName = nozzle == null ? cfg.nozzle : nozzle.getName();
        in.compatibleNozzleTips = new ArrayList<>();
        if (nozzle != null) {
            for (NozzleTip t : nozzle.getCompatibleNozzleTips()) {
                in.compatibleNozzleTips.add(t.getName());
            }
            in.loadedNozzleTip = nozzle.getNozzleTip() == null ? null : nozzle.getNozzleTip().getName();
        }
        in.pressureSensor = pressureSensor;
        in.plan = plan;
        in.estimatedPressurisedSeconds = est;
        in.opticalCheckAvailable = imager() != null && imager().isAvailable();
        return in;
    }

    // ------------------------------------------------------------------------------------------
    // Execution
    // ------------------------------------------------------------------------------------------

    public Future<RunResult> runBoard(BoardLocation bl) throws Exception {
        return runPlan(planBoard(bl));
    }

    /** Streams a plan on the machine thread. The Future never throws for dispense failures; see status. */
    public Future<RunResult> runPlan(DispensePlan plan) throws Exception {
        requireAvailable();
        RunResult r = newRun("board", plan.planId, plan.boardName, plan.calibrationId, plan.dots.size());
        return submit(r, () -> {
            DispenseConfig c = cfg;
            Session s = new Session(c, r);
            boolean optical = c.primeAndTestDot && c.testDotOpticalCheck;
            GcodeProgram full = GcodeStreamBuilder.forPlan(c, s.mapper, plan, bZeroed.get(), s.keepalive,
                    GcodeStreamBuilder.Part.FULL);
            r.interlocks = Interlocks.check(interlockInputs(c, plan, full.estimatedPressurisedSeconds));
            if (!r.interlocks.ok()) {
                return s.refuse(r.interlocks.toString());
            }
            if (!optical) {
                s.runSegment(() -> full, "dispensing");
                return s.ok();
            }
            DispenseConfig.NamedLocation w = c.location(c.wasteLocationName);
            double tx = w.x + c.testDotOffsetXMm, ty = w.y + c.testDotOffsetYMm;
            phase(r, "I9 test dot: before image");
            DotImager im = imager();
            DotImager.Frame before = im.capture(tx, ty);
            s.runSegment(() -> GcodeStreamBuilder.forPlan(c, s.mapper, plan, bZeroed.get(), s.keepalive,
                    GcodeStreamBuilder.Part.PRIME_AND_TEST), "prime + test dot");
            phase(r, "I9 test dot: after image");
            DotImager.Frame after = im.capture(tx, ty);
            Double expected = plan.dots.isEmpty() ? null : plan.dots.get(0).expectedDiameterMm;
            String fail = TestDotCheck.evaluate(im.inspect(before, after), expected, c.testDotMinDiameterRatio,
                    new org.openpnp.instancelabs.vision.DotInspectorSettings().minDiameterMm, 1.0);
            if (fail != null) {
                return s.fail(fail);
            }
            s.runSegment(() -> GcodeStreamBuilder.forPlan(c, s.mapper, plan, true, s.keepalive,
                    GcodeStreamBuilder.Part.DOTS), "dispensing");
            return s.ok();
        });
    }

    /** Dispenses `count` shots of rotationDeg at a named location (calibration cup), for the scale. */
    public Future<RunResult> runShots(String locationName, double rotationDeg, int count) throws Exception {
        requireAvailable();
        DispenseConfig.NamedLocation loc = requireLocation(locationName);
        RunResult r = newRun("shots", null, locationName, getCurrentCalibration().id, count);
        return submit(r, () -> {
            DispenseConfig c = cfg;
            Session s = new Session(c, r);
            List<GcodeStreamBuilder.DotSpec> specs = new ArrayList<>();
            double z = loc.z + c.needleZOffsetMm + c.needleGapMm;
            for (int i = 0; i < count; i++) {
                specs.add(new GcodeStreamBuilder.DotSpec(loc.x, loc.y, z, z + c.hopHeightMm, rotationDeg, i, "shot"));
            }
            GcodeProgram p = GcodeStreamBuilder.forDots(c, s.mapper, specs, bZeroed.get(), s.keepalive, false,
                    "shots x" + count + " @" + locationName);
            if (!s.preflightCalibration(p, false)) {
                return r;
            }
            s.runSegment(() -> p, "shots");
            return s.ok();
        });
    }

    /** Optical dot ladder at the blank location (plan §5.6); appends dot_ladder rows. */
    public Future<DotLadderCalibration.Result> runDotLadder(DotLadderCalibration.Request req) throws Exception {
        requireAvailable();
        DispenseConfig.NamedLocation blank = requireLocation(
                req.locationName == null ? cfg.blankLocationName : req.locationName);
        Calibration cal = getCurrentCalibration();
        int n = req.rotationsDeg.length * req.repeats;
        RunResult r = newRun("dot-ladder", null, "blank", cal.id, n);
        DotLadderCalibration.Result res = new DotLadderCalibration.Result();
        res.calibrationId = cal.id;
        res.run = r;
        return submitResult(r, res, () -> {
            DispenseConfig c = cfg;
            Session s = new Session(c, r);
            List<GcodeStreamBuilder.DotSpec> specs = DotLadderCalibration.layout(req, blank, c);
            GcodeProgram p = GcodeStreamBuilder.forDots(c, s.mapper, specs, bZeroed.get(), s.keepalive,
                    req.primeAtWaste, "dot ladder");
            if (!s.preflightCalibration(p, true)) {
                return;
            }
            List<Dot> found = s.imageDots(specs, req.primeAtWaste, req.pitchMm / 2);
            res.rows.addAll(DotLadderCalibration.analyze(cal.id, req, specs, found, c.needleGapMm, res.notes));
            for (DotLadderRow row : res.rows) {
                db.insertDotLadderRow(row);
            }
            s.ok();
        });
    }

    public static class TouchOffResult {
        public NeedleTouchOff.Analysis analysis;
        public Double previousOffsetMm;
        public Double newOffsetMm;
        public boolean applied;
        public RunResult run;
    }

    /** Needle touch-off by dot ladder at stepped gaps (plan §6.1). Applies the new offset if found and asked. */
    public Future<TouchOffResult> runNeedleTouchOff(NeedleTouchOff.Request req, boolean apply) throws Exception {
        requireAvailable();
        DispenseConfig.NamedLocation blank = requireLocation(
                req.locationName == null ? cfg.blankLocationName : req.locationName);
        RunResult r = newRun("touch-off", null, "blank", getCurrentCalibration().id, req.steps);
        TouchOffResult res = new TouchOffResult();
        res.run = r;
        return submitResult(r, res, () -> {
            DispenseConfig c = cfg;
            Session s = new Session(c, r);
            List<GcodeStreamBuilder.DotSpec> specs = NeedleTouchOff.layout(req, blank, c);
            GcodeProgram p = GcodeStreamBuilder.forDots(c, s.mapper, specs, bZeroed.get(), s.keepalive,
                    req.primeAtWaste, "needle touch-off");
            if (!s.preflightCalibration(p, false)) {
                return;
            }
            List<Dot> found = s.imageDots(specs, req.primeAtWaste, req.pitchMm / 2);
            double[] gaps = NeedleTouchOff.gaps(req);
            Double[] d = new Double[gaps.length];
            for (int i = 0; i < d.length; i++) {
                d[i] = found.get(i) == null ? null : found.get(i).diameterMm;
            }
            res.analysis = NeedleTouchOff.analyze(gaps, d, req.jumpRatio);
            res.previousOffsetMm = c.needleZOffsetMm;
            String details = new com.google.gson.Gson().toJson(res.analysis);
            if (res.analysis.found()) {
                res.newOffsetMm = c.needleZOffsetMm + res.analysis.contactGapMm;
                if (apply) {
                    setNeedleZOffset(res.newOffsetMm, "dot-ladder-touch-off", details);
                    res.applied = true;
                }
                else {
                    db.insertTouchOff("dot-ladder-touch-off (not applied)", res.newOffsetMm, c.needleZOffsetMm,
                            details, null);
                }
            }
            r.message = res.analysis.reason;
            s.ok();
        });
    }

    // ------------------------------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------------------------------

    private void requireAvailable() {
        if (nozzle == null) {
            throw new IllegalStateException("dispense service unavailable: " + unavailableReason);
        }
    }

    private DispenseConfig.NamedLocation requireLocation(String name) {
        DispenseConfig.NamedLocation l = cfg.location(name);
        if (l == null) {
            throw new IllegalStateException("location '" + name + "' is not set");
        }
        return l;
    }

    private DotImager imager() {
        DotImager o = dotImagerOverride;
        if (o != null) {
            return o;
        }
        try {
            Machine m = configuration.getMachine();
            Camera cam = m == null || m.getDefaultHead() == null ? null : m.getDefaultHead().getDefaultCamera();
            return cam == null ? null : new OpenPnpDotImager(cam, null);
        }
        catch (Exception e) {
            return null;
        }
    }

    private RunResult newRun(String kind, String planId, String board, long calibrationId, int dots) {
        RunResult r = new RunResult();
        r.runId = java.util.UUID.randomUUID().toString();
        r.kind = kind;
        r.planId = planId;
        r.boardName = board;
        r.calibrationId = calibrationId;
        r.dotsPlanned = dots;
        return r;
    }

    private Future<RunResult> submit(RunResult r, Callable<RunResult> body) throws Exception {
        if (!busy.compareAndSet(false, true)) {
            throw new IllegalStateException("a dispense task is already running");
        }
        try {
            return configuration.getMachine().submit(() -> {
                try {
                    return body.call();
                }
                catch (Exception e) {
                    if (r.status == RunResult.Status.RUNNING) {
                        r.status = e instanceof RunRefusedException ? RunResult.Status.REFUSED : RunResult.Status.ERROR;
                        r.message = String.valueOf(e.getMessage());
                    }
                    Logger.error(e, "dispense {} failed", r.kind);
                    return r;
                }
                finally {
                    finish(r);
                }
            });
        }
        catch (Exception e) {
            busy.set(false);
            throw e;
        }
    }

    @FunctionalInterface
    private interface Body {
        void run() throws Exception;
    }

    private <T> Future<T> submitResult(RunResult r, T result, Body body) throws Exception {
        if (!busy.compareAndSet(false, true)) {
            throw new IllegalStateException("a dispense task is already running");
        }
        try {
            return configuration.getMachine().submit(() -> {
                try {
                    body.run();
                }
                catch (Exception e) {
                    if (r.status == RunResult.Status.RUNNING) {
                        r.status = e instanceof RunRefusedException ? RunResult.Status.REFUSED : RunResult.Status.ERROR;
                        r.message = String.valueOf(e.getMessage());
                    }
                    Logger.error(e, "dispense {} failed", r.kind);
                }
                finally {
                    finish(r);
                }
                return result;
            });
        }
        catch (Exception e) {
            busy.set(false);
            throw e;
        }
    }

    private void finish(RunResult r) {
        if (r.status == RunResult.Status.RUNNING) {
            r.status = RunResult.Status.ERROR;
            r.message = r.message == null ? "run ended without a status" : r.message;
        }
        r.endedAtMs = System.currentTimeMillis();
        try {
            db.insertRun(r);
        }
        catch (Exception e) {
            Logger.error(e, "could not store dispense run {}", r.runId);
        }
        db.event(r.runId, r.status == RunResult.Status.OK ? "INFO" : "WARN", "RUN_END", r.toString());
        activeExecutor = null;
        activeRun = null;
        lastRun = r;
        busy.set(false);
        for (DispenseListener l : listeners) {
            try {
                l.onFinished(r);
            }
            catch (Exception e) {
                Logger.warn(e, "dispense listener failed");
            }
        }
        Logger.info("dispense: {}", r);
    }

    private void phase(RunResult r, String phase) {
        db.event(r.runId, "INFO", "PHASE", phase);
        for (DispenseListener l : listeners) {
            try {
                l.onPhase(r, phase);
            }
            catch (Exception e) {
                Logger.warn(e, "dispense listener failed");
            }
        }
    }

    /** Fan-out listener passed to the executor. */
    private final DispenseListener fanOut = new DispenseListener() {
        @Override
        public void onDot(RunResult run, RunResult.DotTiming t, int done, int total) {
            for (DispenseListener l : listeners) {
                l.onDot(run, t, done, total);
            }
        }
    };

    /** One task on the machine thread: OpenPnP motion settled, mapper, channel, executor. */
    private final class Session {
        final DispenseConfig c;
        final RunResult r;
        final OpenPnpRawMapper mapper;
        final DriverGcodeChannel channel;
        final DispenseExecutor exec;
        final boolean keepalive;
        boolean streamed;

        Session(DispenseConfig c, RunResult r) throws Exception {
            this.c = c;
            this.r = r;
            r.startedAtMs = System.currentTimeMillis();
            activeRun = r;
            ReferenceMachine m = (ReferenceMachine) configuration.getMachine();
            phase(r, "waiting for OpenPnP motion to complete");
            // Everything OpenPnP queued must be done before the raw stream starts.
            m.getMotionPlanner().waitForCompletion(null, CompletionType.WaitForStillstand);
            mapper = new OpenPnpRawMapper(m, nozzle, c.bAxisName);
            channel = new DriverGcodeChannel(mapper.getDriver());
            exec = new DispenseExecutor(c, channel, mapper.letterB(), System::currentTimeMillis);
            activeExecutor = exec;
            keepalive = c.keepaliveEnabled && probeKeepalive();
            r.keepaliveActive = keepalive;
        }

        /**
         * M115: does the firmware have the host watchdog? Only a timeout means "no capability". Any
         * other failure, in particular a controller error that arrived before the run (a 'start'
         * after a reset, an Error: line), refuses the run: B must be re-zeroed and the position
         * frame may be gone, so B is marked un-zeroed and the machine un-homed.
         */
        private boolean probeKeepalive() throws RunRefusedException {
            try {
                for (String line : channel.sendAndCollect(ControllerCommands.CAPABILITY_QUERY, c.commandTimeoutMs)) {
                    if (line.contains(ControllerCommands.WATCHDOG_CAPABILITY)) {
                        return true;
                    }
                }
                return false;
            }
            catch (Exception e) {
                String msg = String.valueOf(e.getMessage());
                if (msg.contains("timeout waiting for response")) {
                    r.warnings.add("capability query timed out (no host watchdog assumed): " + msg);
                    return false;
                }
                bZeroed.set(false);
                Machine m = configuration.getMachine();
                if (m instanceof ReferenceMachine) {
                    ((ReferenceMachine) m).setHomed(false);
                }
                throw new RunRefusedException("the controller reported an error before the run (" + msg
                        + "). Nothing was sent; B will be re-zeroed and the machine must be homed again.", e);
            }
        }

        /** Interlocks for calibration/shots runs (no plan rules, no I9; I4 optional). */
        boolean preflightCalibration(GcodeProgram p, boolean requireTouchOff) {
            DispenseConfig ic = c.copy();
            ic.primeAndTestDot = false;
            r.interlocks = Interlocks.check(interlockInputs(ic, null, p.estimatedPressurisedSeconds));
            if (!requireTouchOff) {
                r.interlocks.violations.removeIf(v -> v.code.equals("I4"));
            }
            if (!r.interlocks.ok()) {
                refuse(r.interlocks.toString());
                return false;
            }
            return true;
        }

        /**
         * Settle OpenPnP motion, build (from the now-current axis state), validate, stream, re-sync
         * OpenPnP. On failure the I1 shutdown has already been sent; recover state.
         */
        void runSegment(Callable<GcodeProgram> build, String phaseName) throws Exception {
            // OpenPnP may have moved in between (camera for I9/calibration images): settle first.
            configuration.getMachine().getMotionPlanner().waitForCompletion(null, CompletionType.WaitForStillstand);
            GcodeProgram p = build.call();
            List<String> problems = GcodeStreamValidator.validate(p, c, mapper.letterB());
            if (!problems.isEmpty()) {
                throw new IllegalStateException("generated G-code failed validation: " + problems);
            }
            if (exec.isAbortRequested()) {
                throw new DispenseExecutor.AbortedException("aborted before " + phaseName);
            }
            phase(r, phaseName);
            streamed = true;
            try {
                exec.stream(p, r, fanOut);
            }
            catch (Exception e) {
                recover();
                r.status = exec.isAbortRequested() || e instanceof DispenseExecutor.AbortedException
                        ? RunResult.Status.ABORTED : RunResult.Status.ERROR;
                r.message = String.valueOf(e.getMessage());
                db.event(r.runId, "ERROR", "I1", "stream stopped, pneumatics off sent: " + r.message);
                throw e;
            }
            mapper.resync(p.finalPlanned, p.finalDriver, p.finalB);
            bZeroed.set(true);
        }

        /** Before/after images of every spec around one pressure session; returns the dot per spec. */
        List<Dot> imageDots(List<GcodeStreamBuilder.DotSpec> specs, boolean primeAtWaste, double radius)
                throws Exception {
            DotImager im = imager();
            if (im == null || !im.isAvailable()) {
                throw new IllegalStateException("no top camera / dot vision available");
            }
            phase(r, "before images");
            List<DotImager.Frame> before = new ArrayList<>();
            for (GcodeStreamBuilder.DotSpec d : specs) {
                before.add(im.capture(d.x, d.y));
            }
            runSegment(() -> GcodeStreamBuilder.forDots(c, mapper, specs, bZeroed.get(), keepalive, primeAtWaste,
                    "calibration dots"), "dispensing calibration dots");
            phase(r, "after images");
            List<Dot> found = new ArrayList<>();
            for (int i = 0; i < specs.size(); i++) {
                DotImager.Frame after = im.capture(specs.get(i).x, specs.get(i).y);
                found.add(TestDotCheck.nearest(im.inspect(before.get(i), after), radius));
            }
            return found;
        }

        /** After an aborted/failed stream: read back the controller position, un-home, re-zero B later. */
        void recover() {
            bZeroed.set(false);
            try {
                channel.send("M400", -1);
                AxesLocation rep = mapper.getDriver().getReportedLocation(c.commandTimeoutMs);
                ReferenceControllerAxis[] xyz = mapper.xyzAxes();
                double[] pos = new double[3];
                for (int i = 0; i < 3; i++) {
                    pos[i] = rep.getCoordinate(xyz[i], LengthUnit.Millimeters);
                }
                mapper.resync(pos, pos, rep.getCoordinate(mapper.bAxis()));
            }
            catch (Exception e) {
                r.warnings.add("position read-back after stop failed: " + e.getMessage());
                Logger.warn(e, "dispense: position read-back after stop failed");
            }
            // M410 may lose steps; force a re-home before further motion.
            Machine m = configuration.getMachine();
            if (m instanceof ReferenceMachine) {
                ((ReferenceMachine) m).setHomed(false);
            }
        }

        RunResult refuse(String why) {
            r.status = RunResult.Status.REFUSED;
            r.message = why;
            return r;
        }

        RunResult fail(String why) {
            r.status = RunResult.Status.ERROR;
            r.message = why;
            return r;
        }

        RunResult ok() {
            if (r.status == RunResult.Status.RUNNING) {
                r.status = RunResult.Status.OK;
            }
            return r;
        }
    }
}
