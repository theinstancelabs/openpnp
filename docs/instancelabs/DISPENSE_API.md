# Instance Labs dispense engine — Java API (`org.openpnp.instancelabs.dispense`)

Branch `feat/dispense` (from `il/main` = OpenPnP 2.7 test `f14b822`). Spec: `docs/plan-2026-10-27-evidence-backed-roadmap.md`
§4.4–4.6, §5.1–5.7, §6.1 (in the openpnp-revamp project). This file is the contract for the web API layer.
Every machine action runs on OpenPnP's machine thread and returns a `java.util.concurrent.Future`; only one dispense
task runs at a time (`IllegalStateException("a dispense task is already running")` otherwise).

## 1. Lifecycle

| Signature | Notes |
|---|---|
| `public static void DispenseService.install(Configuration configuration)` | Called from `org.openpnp.Main` right after `Configuration.initialize()`. Registers a `ConfigurationListener`; the service starts on `configurationComplete`. Never throws. |
| `public static synchronized DispenseService DispenseService.start(Configuration configuration) throws SQLException, IOException` | (Re)starts the service. Used by `install` and tests. |
| `public static DispenseService DispenseService.get()` | `null` before the configuration has loaded. |
| `public synchronized void shutdown()` | Aborts any run, removes the machine listener, closes the DB. |
| `public boolean isAvailable()` / `public String getUnavailableReason()` | Null-safe: if the dispenser nozzle (config `nozzle`, default `N2`, matched by id then name) does not exist, the service exists but every run method throws `IllegalStateException`. |
| `public Nozzle getDispenserNozzle()` | May be null. |

## 2. Configuration (`<configDir>/instancelabs/dispense-config.json`, never machine.xml)

| Signature | Notes |
|---|---|
| `public DispenseConfig getConfig()` | A copy (Gson-serialisable POJO, public fields). |
| `public synchronized void updateConfig(DispenseConfig newCfg) throws IOException` | Validates (`DispenseConfig.validate()`: pump/valve ON must be `M106 P<n> S255`, no PWM), writes atomically, applies. `IllegalArgumentException` on invalid input. |
| `public synchronized void setLocation(String name, double x, double y, double z) throws IOException` | Named N2 locations (`waste`, `blank`, calibration cup, …). `z` = surface Z of the item at that place. |
| `public synchronized void markNeedleChanged() throws IOException` | I4: invalidates the needle Z offset until the next touch-off. |
| `public synchronized void setNeedleZOffset(double offsetMm, String method, String details) throws IOException, SQLException` | Manual offset entry or touch-off result; appends a `needle_touchoff` row. |
| `public boolean isBZeroed()` | I2 state: `G92 B0` sent since the last home / enable / disable. |

`DispenseConfig` fields (defaults): `nozzle "N2"`, `bAxisName "b"`, `pumpOnGcode "M106 P2 S255"`, `pumpOffGcode "M107 P2"`,
`valveOnGcode "M106 P3 S255"`, `valveOffGcode "M107 P3"`, `pumpToValveSettleMs 500`, `valveSettleMs 300`,
`locations {}`, `wasteLocationName "waste"`, `blankLocationName "blank"`, `needleGapMm 0.15`, `hopHeightMm 1.0`,
`travelSafeZMm 31.5`, `needleZOffsetMm 0`, `needleZOffsetSetAtMs null`, `needleChangedAtMs null`,
`travelFeedMmS 200`, `travelAccelMmS2 1000`, `zDownFeedMmS 50`, `zLiftFeedMmS 25`, `zLiftAccelMmS2 200`,
`bFeedDegS 360`, `bAccelDegS2 2000`, `bMaxFeedDegS 720`, `retractDeg 5`, `retractFeedDegS 360`, `dwellMs 50`,
`bExtrudeSign +1`, `honorBacklash true`, `abortDisablesB true`, `commandTimeoutMs 10000`,
`primeAndTestDot true` (I9), `primeRotationDeg 360`, `rePrimeRotationDeg 90`, `testDotOffsetXMm 2`, `testDotOffsetYMm 0`,
`testDotOpticalCheck true`, `testDotMinDiameterRatio 0.6`, `keepaliveEnabled true`, `capabilityQueryGcode "M115"`,
`keepaliveCapabilityToken "Cap:HOST_WATCHDOG:1"`, `keepaliveArmGcode "M990 S3000"`, `keepalivePingGcode "M990"`,
`keepaliveDisarmGcode "M990 S0"`, `keepaliveIntervalMs 1000`, `maxContinuousOnTimeS 600` (I7),
`pressureInterlockEnabled false`, `pressureMinPsi 1`, `pressureMaxPsi 18` (I5), `minPadTypeStatus "characterized"`,
`refuseFlaggedPads true`, `pasteThicknessMm 0.10`, `pasteDensityMgMm3 3.7`, `provisionalQMm3PerRev 0.47`,
`maxDotsPerPad 6`, `dotFitFactor 1.0`, `includeDisabledPlacements true`, `twoOptMaxPasses 50`.

Coordinates: all X/Y/Z are OpenPnP machine coordinates of the N2 needle tip (what `N2.moveTo()` takes). Dispense Z =
surface Z + `needleZOffsetMm` + `needleGapMm`; hop Z = dispense Z + `hopHeightMm`. B is degrees of the auger.

## 3. Data (`<configDir>/instancelabs/dispense.db`, SQLite, every table append-only via triggers)

| Signature | Notes |
|---|---|
| `public DispenseDb getDb()` | Direct access; `DispenseDb.query(String sql, Object... args)` returns `List<Map<String,Object>>`. `recentRuns(int)`, `recentEvents(int)`. |
| `public List<PadType> getPadTypes() throws SQLException` | Current version of each type, creation order. Seeds (§5.7): chip-0402/0603/0805/1206, sod-123, sot-23, soic, tssop, qfp, qfn-dfn-perimeter, qfn-centre (k 0.5), custom; all `not-started`. |
| `public PadType savePadType(PadType p) throws SQLException` | Appends version n+1 (new key = new type, i.e. "New pad type"). Status: `not-started → provisional → characterized → validated`. |
| `public PadRule savePadRule(PadRule r) throws SQLException` | Per-type `maxDots`, `fitFactor`, `largestSingleDotPadMm2` (null = config default). |
| `public Calibration getCurrentCalibration() throws SQLException` | Newest non-expired `characterized` record, else newest record. Seed #1: provisional q = 0.47 mm³/rev. |
| `public List<DotLadderRow> getDotLadder(long calibrationId) throws SQLException` | |
| `public Calibration recordScaleShots(ScaleCalibration.ShotsEntry e) throws SQLException` | Manual scale entry (§5.2): per-shot masses (→ q mean, CV, n) or total mass + count. `markCharacterized` sets the status. |
| `public DotLadderRow recordScaleDots(ScaleCalibration.DotsEntry e) throws SQLException` | Dot batch on a coupon: mg/dot, SE = √2·s_scale/n, spherical-cap height. |

Tables: `pad_types`, `pad_rules`, `package_pad_type` (package → type overrides, `DispenseDb.mapPackage(String, String)`),
`calibration`, `dot_ladder`, `pad_plan`, `runs` (per-run timings, dots, `dph_motion`, `dph_overall`), `dot_log`
(per-dot host timestamps), `events`, `needle_touchoff`, `scale_entries`, view `pad_types_current`.

## 4. Planning

| Signature | Notes |
|---|---|
| `public DispensePlan planBoard(BoardLocation bl) throws Exception` | Pads from `packages.xml` footprint pads if present, else from the pad type layout. Uses `Utils2D.calculateBoardPlacementLocation`, i.e. the fiducial-corrected local→global transform when a fiducial check has set one. Bottom-up boards: `IllegalArgumentException`. Stores `pad_plan` rows. |
| `public DispensePlan planBoardFile(File boardFile, Location boardLocation) throws Exception` | Loads a `.board.xml` and plans it top-side up at `boardLocation`. |
| `public DispensePlanner newPlanner() throws SQLException` | `DispensePlanner.plan(String boardName, List<PadGeometry> pads, double startX, double startY)` for custom pad lists. |
| `public InterlockReport checkInterlocks(DispensePlan plan, double estimatedPressurisedSeconds)` | Pre-flight only; `runPlan` re-checks with the program's own estimate. |

Chain per pad (§5.6): A (rounded rect) → V = A·t·k → rotation = V/q·360°. With a dot ladder: smallest n ≤ maxDots whose
per-dot rotation lies inside the measured ladder range and whose interpolated diameter fits the grid cell; below the
smallest rung → `DOT_BELOW_LADDER` (blocking, never extrapolated). Without a ladder: one dot, flag `NO_LADDER` (warning).
Order: nearest neighbour from the start (waste location, or N2's XY) + 2-opt. `DispensePlan.dots` carry `index`, `padId`,
`xMm`, `yMm`, `surfaceZMm`, `dispenseZMm`, `hopZMm`, `rotationDeg`, `expectedDiameterMm`; the plan carries `calibrationId`.
Blocking flags: `NO_PAD_TYPE`, `STATUS_BELOW_MIN` (§5.7 rule), `DOT_BELOW_LADDER`, `NO_FIT`, `LADDER_NOT_MONOTONIC`, `NO_GEOMETRY`.

## 5. Execution

| Signature | Notes |
|---|---|
| `public Future<RunResult> runBoard(BoardLocation bl) throws Exception` | `planBoard` + `runPlan`. |
| `public Future<RunResult> runPlan(DispensePlan plan) throws Exception` | Never completes exceptionally for dispense failures: inspect `RunResult.status` (`OK`, `ABORTED`, `ERROR`, `REFUSED`), `message`, `interlocks`. |
| `public Future<RunResult> runShots(String locationName, double rotationDeg, int count) throws Exception` | Scale workflow helper (shots into a cup). |
| `public void abort(String reason)` | Thread-safe. The stream stops before its next line and sends the I1 sequence. Also triggered by OpenPnP Stop/disable. |
| `public boolean isBusy()`, `public RunResult getActiveRun()`, `public RunResult getLastRun()` | |
| `public void addListener(DispenseListener l)` / `removeListener` | `onPhase(RunResult, String)`, `onDot(RunResult, RunResult.DotTiming, int done, int total)`, `onFinished(RunResult)`; called on the machine thread. |
| `public void setPressureSensor(BarrelPressureSensor s)` | I5 hook (`Double readPsi()`); no sensor fitted yet. |
| `public void setDotImager(DotImager imager)` | Override the camera/vision (default: top camera + `CameraCapture`/`DotInspector`). |

What `runPlan` does, in order:
1. Waits for OpenPnP motion to complete (`MotionPlanner.waitForCompletion(null, WaitForStillstand)`).
2. Sends `M115`; the host-watchdog keepalive is used only if a response line contains `Cap:HOST_WATCHDOG:1`.
3. Builds the G-code block(s) through `OpenPnpRawMapper` (= `nozzle.toRaw(nozzle.toHeadLocation(loc))`, soft limits via
   `MotionPlanner.isValidLocation`) and `BacklashEmulator` (reproduces `AbstractMotionPlanner.createBacklashCompensatedMotion`);
   checks interlocks; validates the block with `GcodeStreamValidator`.
4. With `testDotOpticalCheck`: image the test-dot spot, stream *session A* (prime + test dot), image again, check (I9), then
   *session B* (re-prime + all dots). Otherwise one session with prime + test dot + dots.
5. Streams line by line via `GcodeDriver.sendCommand()` (the driver's own flow control/error regex); no M400 per dot.
6. Re-syncs OpenPnP: X/Y/Z planned and driver coordinates, the motion planner's directional-backlash state, B = 0.
7. Stores `runs` + `dot_log`, fires `onFinished`.

Session block (see `src/test/resources/dispense/golden-3pad*.gcode`):
```
G92 B0                 ; only if B not zeroed since home/reconnect (I2)
M201 B<bAccel> / M203 B<bMaxFeed> / M204 S<travelAccel>
M990 S3000             ; only with the capability; then M990 every keepaliveIntervalMs, long G4s split
G0 X.. Y..             ; travel at travelSafeZ (+ backlash approach moves as OpenPnP would make them)
M106 P2 S255 / G4 P<pumpToValveSettleMs> / M106 P3 S255 / G4 P<valveSettleMs>
per dot: G0 X Y (hop Z) / G1 Z<gap> / G1 B<feed end> / G4 P<dwell> / G1 B<feed end - retract> /
         M204 S<zLiftAccel> / G1 Z<hop> / M204 S<travelAccel>
M400 / M410 / M107 P3 / M107 P2 / M990 S0 / G92 B0 / G1 Z<travelSafeZ> / M400
```
B is absolute from the session's `G92 B0`; each feed ends exactly `rotationDeg` past the previous feed end, so the
suck-back is taken up by the next feed and the net extrusion per dot equals the planned rotation.
Abort / error / Stop (I1): `M410`, `M107 P3`, `M107 P2`, `M18 B`, (`M990 S0`); then `M400` + `M114` read-back, OpenPnP
re-synced to the reported position, machine marked un-homed (M410 may lose steps), B marked not zeroed.

`RunResult`: `runId`, `kind` (`board`/`shots`/`dot-ladder`/`touch-off`), `status`, `message`, `startedAtMs`, `endedAtMs`,
`dotsPlanned`, `dotsDone`, `firstDotStartMs`, `lastDotEndMs`, `dotTimings` (`dotIndex`, `padId`, `rotationDeg`, `startMs`,
`endMs`), `keepaliveActive`, `keepalivePings`, `linesSent`, `streamedLines`, `warnings`, `interlocks`,
`Double dphMotion()` = dots / (lastDotEnd − firstDotStart) · 3600, `Double dphOverall()` = dots / (end − start) · 3600.
Timestamps are host-side: a dot's `endMs` is when its last line was acknowledged (queued in Marlin, not executed);
`lastDotEndMs` is the completion of the `M400` that follows the last dot (true completion).

## 6. Calibration

| Signature | Notes |
|---|---|
| `public Future<DotLadderCalibration.Result> runDotLadder(DotLadderCalibration.Request req) throws Exception` | Request: `rotationsDeg` (default 30/45/58/75/90), `repeats` 5, `pitchMm` 1.5, `locationName` (default blank), `primeAtWaste`. Before images of every spot (pressure off) → one pressure session → after images → `DotInspector` → one `dot_ladder` row per rotation (n, mean diameter/area, CV) on the current calibration. Requires I4. |
| `public Future<DispenseService.TouchOffResult> runNeedleTouchOff(NeedleTouchOff.Request req, boolean apply) throws Exception` | §6.1 option 1. Gaps `startGapMm` 0.125 down by `stepMm` 0.025, `steps` 6, relative to the current offset; contact = midpoint between the last normal dot and the first squashed (`> (1+jumpRatio)·reference`) or missing one; new offset = old + contact. `apply` writes it (sets `needleZOffsetSetAtMs`, clears I4). |
| `recordScaleShots` / `recordScaleDots` | See §3. |

Pure helpers usable without a machine: `NeedleTouchOff.analyze(double[] gapsMm, Double[] diametersMm, double jumpRatio)`,
`DotLadderCalibration.analyze(...)`, `TestDotCheck.evaluate(List<Dot>, Double expectedDiameterMm, double minRatio, double minDiameterMm, double searchRadiusMm)`,
`DotMath.*`, `GcodeStreamBuilder.forPlan(DispenseConfig, RawMapper, DispensePlan, boolean bZeroed, boolean keepalive, GcodeStreamBuilder.Part)`.

## 7. Interlocks (§5.5)

| Id | Where | Behaviour |
|---|---|---|
| I1 | `DispenseExecutor.shutdown` | Outputs off on any error, abort, I7 trip, OpenPnP Stop/disable (machine listener). Best effort: each line attempted. |
| I2 | `GcodeStreamBuilder` + `GcodeStreamValidator` | No B move before `G92 B0`; flag cleared on home, enable, disable, abort. |
| I3 | `Interlocks` | Refuses if N2 has compatible nozzle tips or a loaded tip (names reported). |
| I4 | `Interlocks` | Refuses unless `needleZOffsetSetAtMs >= needleChangedAtMs`. Board Z-map containment not implemented (no Z-map yet). |
| I5 | `Interlocks` | Pressure window via `BarrelPressureSensor`; disabled by default. |
| I7 | `Interlocks` (estimate) + `DispenseExecutor` (runtime) | Max continuous pump+valve on-time. |
| I9 | `runPlan` | Prime + test dot on the waste location each board; optical check with `testDotOpticalCheck`. |

## 8. Known limits (measured or read, not guessed)

- Marlin `G4` calls `planner.synchronize()` (`Marlin/src/gcode/motion/G4.cpp:36` on `il/lumen-rev05`), so `dwellMs > 0`
  drains the motion queue once per dot. `dwellMs = 0` removes the per-dot G4 entirely.
- `M201`/`M203` for B persist in Marlin RAM until reset (not saved without `M500`).
- `M990` semantics are the shared contract; the firmware side is not on `il/lumen-rev05`. Pings are sent between lines, so a
  single command that blocks longer than the watchdog window (planner full, long move) delays the next ping.
- `org.openpnp.instancelabs.vision` in this branch is a contract stub (separate commit "vision contract stubs"): optical
  I9, dot ladder and touch-off throw until the real vision classes are merged.
- No HTTP endpoints here; this is the Java service the web layer calls.
