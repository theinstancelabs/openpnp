# Instance Labs web API and web UI (`org.openpnp.instancelabs.api`)

Branch `feat/web-ui`, on top of `feat/dispense` (engine contract: `docs/instancelabs/DISPENSE_API.md`).
Spec: `docs/plan-2026-10-27-evidence-backed-roadmap.md` §3.3 (local API inside the OpenPnP process, web UI on
localhost), §4.4–4.5 (Stop, on/off order), §5.5 (interlocks), §5.7 (Profiles tab), §8 items 7–10.

## 1. Where it runs

- Started by one line in `org.openpnp.Main` (`ApiServer.install(configuration)`), right after the engine's
  `DispenseService.install`. Both start on OpenPnP's `configurationComplete`.
- URL: **http://127.0.0.1:8765/** (UI) and `/api/...` (JSON). JDK `com.sun.net.httpserver`, Gson, no new dependency.
- Settings: `<configDir>/instancelabs/web-api.json` (never written by the API):
  `{"port": 8765, "lanAccess": false, "enabled": true, "threads": 8}`.
  Overrides: system properties `instancelabs.api.port|lan|enabled`, then env `OPENPNP_IL_API_PORT|LAN|ENABLED`.
- `lanAccess: true` binds all interfaces (e.g. a tablet next to the machine). There is **no authentication**: turn it
  on only on a trusted network. Off by default.
- If the port is taken, the API logs an error and OpenPnP continues without it.

**Browser safety.** No CORS headers are ever sent. The `Host` header must be `127.0.0.1`/`localhost`/`[::1]` unless
LAN access is on (DNS-rebinding guard). Requests other than GET/HEAD with a foreign `Origin`, or
`Sec-Fetch-Site` other than `same-origin`/`none`, get 403 (CSRF guard), so a web page elsewhere cannot press buttons.
`curl -X POST http://127.0.0.1:8765/api/stop` works. The UI is served with a strict CSP (`script-src 'self'`).

## 2. Errors and refusals

Every error is JSON: `{"error": "<slug>", "reason": "<plain language>", ...details}`.

| Status | `error` | When |
|---|---|---|
| 400 | `bad-request` | invalid field, out-of-range value, bad JSON |
| 403 | `forbidden` | Host / Origin / Sec-Fetch-Site guard |
| 404 | `not-found` | unknown endpoint, plan, pad type, calibration, no camera |
| 405 | `method-not-allowed` | |
| 409 | `refused` | machine state or an interlock refuses; `interlocks: {ok, violations:[{code,message}], notes}` included when the engine's interlocks refused |
| 503 | `unavailable` | engine not running, dispenser unavailable (no N2), camera gave no frame |

Every mutating machine action goes through the engine (`DispenseService`) and its interlocks. The API runs the
engine's pre-flight check synchronously so a refusal comes back at once with the reason; the engine re-checks when
it runs (authoritative). Runs return `202 {"started": true}` immediately; add `"wait": true` to block until the run
ends (max 10 min) and get `"result"` (RunResult view).

## 3. Endpoints

| Method + path | Body | Does |
|---|---|---|
| `GET /api/status` | | Everything the UI polls (every 0.7 s): `server`, `machine` (enabled = connected, homed, busy, drivers, nozzle/camera positions, axes incl. B), `dispense` (available, busy, `bZeroed` (I2), needle (touched off, offset, gap), places, prime/test-dot defaults, current calibration), `pressure` (hold state, auto-off time, keepalive), `run` (live progress: label, phase, dots done/planned, done dot indexes, live DPH), `lastRun` (incl. `dphMotion`, `dphOverall`), `interlocks` (MACHINE, CONFIG, I1–I9 with state ok/blocked/warning/off/info and plain text) |
| `POST /api/stop` | | **Always allowed.** Engine `abort()` (it sends M410, valve off, pump off, M18 B before its next line), vents a pressure hold, then OpenPnP's Stop path (`machine.setEnabled(false)`: emergency thread if the machine thread is busy, as OpenPnP's own Stop button). If the driver's DISABLE_COMMAND does not vent, STOP first gives the engine up to 1 s to send its own lines. Returns the list of actions. |
| `POST /api/machine/enable` | | OpenPnP Start (connects the driver). |
| `POST /api/machine/home` | `{wait?}` | OpenPnP home. Refused while the engine runs or pressure is held. |
| `POST /api/pressure/start` | | Manual pressure hold: pump ON, wait `pumpToValveSettleMs`, valve ON, wait `valveSettleMs` (plan §4.5; host-side waits, cancellable). Pre-flight = engine interlocks without I4/I9. A controller error pending at the `M115` probe refuses it. |
| `POST /api/pressure/stop` | | Valve OFF (barrel vents), then pump OFF. |
| `POST /api/prime` | `{rotationDeg?, wait?}` | Engine `runShots(waste, rotation, 1)`: a complete session (pressurise, prime, vent). Default `primeRotationDeg`. Full I4. |
| `POST /api/test-dot` | `{rotationDeg?, wait?}` | Engine `runShots("waste-test-dot", rotation, 1)`; that place is kept = waste + `testDotOffset`. Default rotation = one 0603 pad (`chip-0603`) at the current q (≈ 58°). Full I4. |
| `GET /api/locations` · `PUT /api/locations/{name}` | `{x, y, z}` | Named places: x/y of N2, z = surface Z in OpenPnP's frame (the N1 nozzle Z touching it). `waste-test-dot` is derived and cannot be set. |
| `POST /api/needle/changed` | | I4: blocks dispensing until the needle Z frame is recorded again and the needle is touched off. |
| `POST /api/needle/anchor` | `{surfaceZ, needleContactZ, x?, y?}` | I4 needle Z frame: N1 contact Z and needle (N2) contact Z on the same surface; offset := difference. A touch-off (or a confirmed offset) is still needed. |
| `POST /api/needle/offset` | `{offsetMm, note?}` | Manual needle Z offset (records a `needle_touchoff` row; clears the touched-off part of I4 if within `needleOffsetMaxDriftMm` of the frame). |
| `GET /api/surfaces` · `PUT /api/surfaces/{name}` · `DELETE /api/surfaces/{name}` | `{points: [{x,y,z}] or [[x,y,z]], note?}` | Probed surfaces in OpenPnP's frame (N1 contact heights), >= 3 points spanning an area; returns the plane fit (coefficients, slope, residuals, Z range). `board`: every dot's Z on a board run. `blank`: dot ladder and touch-off. A zmap.json `points` list is accepted as is. |
| `GET /api/config` · `PUT /api/config` | partial `DispenseConfig` | Partial update, validated by the engine. Pump/valve/watchdog G-code are fixed (not settings: unknown keys). The needle frame, offset state and surfaces cannot be changed here. Refused while a run is active. |
| `GET /api/boards` | | `job`: board locations of the job open in OpenPnP's window (position, side, fiducial transform status; empty headless); `files`: OpenPnP's board list (`boards.xml`). |
| `GET /api/packages` | | Packages with footprint pads in mm (for "pick from a footprint"). |
| `POST /api/plan` | `{source:"job", boardLocationId}` or `{source:"file", file, x, y, z, rotation?}` (z required) | Engine `planBoard` / `planBoardFile`. Returns `planId`, `summary` (pads, blocked pads, dots, total rotation, path, kinematic time estimate (lower bound, no acceleration) and `estimatedDphUpperBound`), `pads` (geometry, type, status, volume, mass, dots, rotation, flags, blocked), `dots` (order, X/Y, dispense/hop Z, rotation), `warnings`, `interlocks` (pre-flight incl. §5.7 rule). The estimate builds the exact program the engine will stream, so soft-limit problems show up here. |
| `GET /api/plan/{planId}` | | A plan from this session (last 20 kept in memory). |
| `POST /api/run` | `{planId, maxDots?, padIds?, wait?}` | Engine `runPlan`. `maxDots`: only the first N dots in path order; `padIds`: only those pads. A subset is a new plan (`<planId>-subset-…`, stored in `pad_plan`), so `runs`/`dot_log` record exactly what was dispensed. Refused if the calibration, needle offset, gap or hop changed since planning, if the board's location, fiducial transform or placements changed in OpenPnP (board fingerprint), or if the board surface was re-probed (plan again). |
| `GET /api/runs?limit=` · `GET /api/runs/last` · `GET /api/events?limit=` | | `runs` and `events` tables; the last run with dot timings and streamed G-code. |
| `GET /api/calibrations` | | Current calibration, all calibrations, dot ladder of the current one, last 20 touch-offs. |
| `GET /api/calibrations/{id}/ladder` | | |
| `POST /api/calibrations/scale-shots` | `{rotationDegPerShot, perShotMassMg? (list or "a, b, c"), totalMassMg?, count?, densityMgMm3?, tip?, cartridge?, pasteLot?, setPressurePsi?, barrelTempC?, barrelPressurePsi?, pasteHoursRt?, validForHours?, operator?, notes?, markCharacterized?}` | Engine `recordScaleShots` → new calibration (q, CV, n). |
| `POST /api/calibrations/scale-dots` | `{calibrationId?, rotationDeg, count, totalMassMg, scaleSdMg?, densityMgMm3?, diameterMeanMm?, diameterCv?}` | Engine `recordScaleDots` → dot-ladder row (mg/dot ± SE). |
| `POST /api/calibrations/shots-run` | `{locationName, rotationDeg, count (1–200), wait?}` | Engine `runShots` (shots into a cup for the scale). |
| `POST /api/calibrations/dot-ladder` | `{rotationsDeg, repeats, pitchMm, locationName?, primeAtWaste?, wait?}` | Engine `runDotLadder` (requires I4 incl. the blank surface, and dot vision). |
| `POST /api/calibrations/touch-off` | `{startGapMm, stepMm, steps, rotationDeg, pitchMm?, locationName?, primeAtWaste?, jumpRatio?, apply?, wait?}` | Engine `runNeedleTouchOff` (the named I4 waiver; needs the needle frame and the blank surface). Refused if the lowest gap is below −0.1 mm. |
| `GET /api/pad-types` | | Grouped by family in the §5.7 order (Chip, Small-outline discretes, Leaded ICs, No-lead ICs, Custom, others, Retired); per type: current version, status, pad, t, k, single-dot rotation at the current q, pad rule, versions, last validated, whether the §5.7 rule lets it be dispensed. |
| `POST /api/pad-types` | `{family, name, padWMm, padHMm, padRoundness?, layout?, pitchMm?, tMm?, k?, status?, packagePattern?, notes?, typeKey?}` | New type (key from family + name if not given) → 201. |
| `GET /api/pad-types/{key}` | | Current + full history. |
| `PUT /api/pad-types/{key}` | any of the fields above | Appends version n+1 (nothing is overwritten). |
| `DELETE /api/pad-types/{key}` | | **Retire** (append-only): new version in family "Retired", status not-started, no package pattern, so it is never matched or dispensed; history kept. |
| `PUT /api/pad-types/{key}/rule` | `{maxDots?, fitFactor?, largestSingleDotPadMm2?, notes?}` | Engine `savePadRule` (versioned). |
| `GET /api/camera/top.jpg?w=` | | JPEG from the head's default (top) camera via `captureTransformed()`: no motion, no light actuation, no scripts; 10 s timeout. |
| `GET /api` | | Route list. |

## 4. Pressure hold (Dispense tab)

The engine pressurises inside each run. The hold is the manual "pressure on" for reading the regulator gauge:

- On: pump, wait, valve, settle (fixed G-code, `S255` only). Runs on OpenPnP's machine thread after motion has
  stopped, one line at a time with host-side waits; the state is checked before every line, so a Stop, Pressure off or
  web STOP during the switch-on vents at once and the valve is never switched on after it. If `M115` reports
  `Cap:HOST_WATCHDOG:1`, it arms `M990 S<keepaliveWindowMs>` and pings `M990` every second through the machine thread
  (if that thread is busy longer than the window, the firmware vents: fail-safe).
- Off: valve (vents the barrel), then pump, then `M990 S0` if armed, each sent again if a stale controller error refused
  it. The hold is marked off only once the valve OFF went out; otherwise it reports `vent_failed`, keeps the watchdog
  armed, stops pinging (the firmware then vents by itself) and Pressure off / STOP try again.
- **I7**: switched off automatically after `maxContinuousOnTimeS`.
- **I1**: vented by the web STOP, and by OpenPnP's own Stop/disable (machine listener, before the driver disconnects).
- Prime, test dot, runs and calibrations are refused while a hold is on (they switch the pressure on and off
  themselves; this keeps one owner of the on-time).

## 5. Web UI

Vanilla HTML/CSS/JS in `src/main/resources/org/openpnp/instancelabs/web/` (no build step). The header (sticky) shows
connection, homed, pressure and engine state, Start, Home and a large **STOP**. Tabs:

- **Run**: board (open job, board file, or other file + position), Plan (SVG preview: pads coloured ready/warning/blocked,
  dots in path order, done dots filled live), summary with the time/DPH estimate, interlocks, flagged pads; Run (optionally
  only the first N dots), live progress and DPH, last run DPH (dots only / everything).
- **Dispense**: pressure hold, prime, test dot, top-camera snapshot, places (with "Use N2 X/Y", "Use N1 Z (tip touching)"
  and, once the needle frame is set, "Use N2 Z (tip touching)"), needle (changed, needle Z frame from N1 and needle
  contact, manual offset), surfaces (board / blank points: add N1's position, paste a list or zmap.json points; plane fit
  shown), interlock list, settings (+ full JSON editor).
- **Calibrate**: scale shots (dispense, then enter masses), dot ladder, scale dot batch, needle touch-off, records.
- **Profiles**: pad types by family with status, pad, t, k, dot plan, calibration, last validated, notes; New pad type
  (incl. pick from a footprint), Edit, Set up / Validate (open Calibrate with the type's dot size and the steps), History, Retire.

Jargon is explained in tooltips (`title`/`abbr`).

## 6. Tests

- `mvn -T1 test -Dtest='ApiServerSimTest,ReviewSafetySimTest'` (part of `mvn test`): 17 + 6 tests on the engine's simulated machine
  (OpenPnP GcodeDriver over localhost TCP to `RecordingGcodeServer`), real `DispenseService`, real `ApiServer` on an
  ephemeral port.
- `src/test/web/run-webui-sim-test.sh`: Playwright (headless Chromium) against this jar running in OpenPnP **sim**
  through openpnp-uinav (config copy, simulated controller and cameras, Xvfb, device stubs). Needs the jar
  (`mvn -q -T1 -DskipTests package`), openpnp-uinav built, and Playwright in `$PLAYWRIGHT_DIR`
  (`npm i playwright && npx playwright install chromium-headless-shell`, browsers in `$PLAYWRIGHT_BROWSERS_PATH`).

## 7. Known limits

- On `il/integration` dot vision is the real inspector from `feat/dot-vision`. In the OpenPnP simulator the top camera
  is a static image, so untick "Check the test dot with the camera" there (no dot can appear in a static image).
- A board placed by hand (file + position) gets no fiducial correction; use a job board in OpenPnP for that.
  `planBoardFile` adds the file to OpenPnP's board list, which OpenPnP writes to `boards.xml` when it saves.
- Job boards are listed only while OpenPnP's window runs (the job lives in the Swing job panel).
- Plans live in memory (last 20) for "Run what you previewed"; every plan is also stored in `pad_plan`.
- Progress is polled (0.7 s), not pushed.
- DPH from the simulator is not a physical number (the simulated controller has its own timing model).
- Paste working life (I8) is not tracked by the engine; shown as "off".
