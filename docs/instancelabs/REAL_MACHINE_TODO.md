# What still needs the real machine

The engine now follows the bench recipe (`p18_board1`: 18 psi, half a screw turn per dot, 250 ms dwell, 8 purge
dots, F9 Stop), and the simulator acceptance passes on it
(`/home/lumen/instancelabs/sim-acceptance/bench-sync-20261028-003315/results.json`). The simulator cannot show the
items below. Each one says why, and how to check it on the machine. Do them roughly in this order.

## Automatic-registration session (2026-10-29 00:00–00:22, board 3 and the blank)

Branch `il/bench-sync-autoreg` (37fe1af46) in real mode. Data: `~/instancelabs/runs/sw_session2/`:
`register_b3_1.json`, `register_b3_2.json`, `blank_probe1.json`, `probe_300.32_245.5.json`, `looks/` with
`looks/labels.json`, `OpenPnP_session2.log`. (`dispense_session2.db` there is the main file without its WAL: it has
no tables, so the PROBE records are only in the JSON results.) Fixes F1, F2 and F4 are on `il/fix-real1`.

**What worked.** Home when needed (stopped/reconnected, homed in 23 s). Fiducial registration of board 3: 3
fiducials, 1–2 frames each, centred to 0.2–2.4 px. The fit's scale 1.0014 / 0.9940 matches the bench's fit of the
same board (1.0016 / 0.9935). The second Register saw the same seat (fiducials moved 0.003–0.03 mm).

- **F1, the camera look refused bare spots.** It refused 4 of 12 bare spots: glossy blank in the dark zone between
  the ring light's reflections (the whole disc ~22 from the ring's median: 89–97 % "foreign", `blank_probe1.json`
  refused all 3 spots at X 320–324, Y 245.5) and board 3's scratched mask (54–60 %). It still refused every paste
  dot, the mounting-hole ring and the board edge.
  - Fixed: the look compares the spot with a smooth colour field fitted to the ring, raises its threshold for small
    patches on a rough surface, and counts only solid patches; wide patches (0.4 mm across) 22 or more from the field
    count whatever the texture, so board 3's rough mask cannot hide paste, a hole or an edge. All 20 real frames are
    test fixtures (`src/test/resources/…/vision/real/`): bare spots read 0–1.3 %, paste, holes and edges 31–87 %.
  - Not seen on a mask as rough as board 3's at (37, 37): paste within ~35 of the mask (a 0.7 mm dot 40 out reads
    12 %), and an edge across the spot only just (6 %, board 3's texture laid over the edge frame).
  - On the machine: probe the blank at X 322–328, Y 245.5 again, and register board 3 with a probe. Each PROBE
    record's look now also holds `rawShare`, `threshold` and `wideShare`; send any wrong call with its frame.
- **F2, the board probe chose corner targets on a mounting hole.** The plan had no dots (`dots: []`), so the
  corners came from the outline 2 mm in. Target (2, 2) and its alternates (4, 2) and (3, 3.73), 2 mm apart, all saw
  ftp's corner mounting hole (`looks/b3reg_corner_2_2_alt_openpnp.png`: a 5.6 mm copper ring around about (5.0, 4.5)).
  The bench's spots (8, 8) … (67, 67) worked.
  - Fixed: without dots the corners sit 8 mm in; without board art a mounting hole is assumed in each corner; the
    alternates step 5 mm and spread in direction (2 mm only where 5 mm is not free), 4 by default.
  - On the machine: a saved `dispense-config.json` keeps its `vacuumProbe.alternates` (the session ran with 2): set
    it to 4. With 2, the two alternates leave in opposite directions.
- **F3, the loose, bowed blank.** At X 300.32, Y 245.5 N1 sealed slowly from Z 6.5 and did not let go at 6.0. The
  probe refused (“N1 did not let go of the surface within 1.5 s”, `probe_300.32_245.5.json`): correct. Hold the
  blank down (clamp or tape at its edges, clear of the purge row) before probing or dispensing on it again.
- **F4, FID1 was accepted 9.24 mm from its expected position with `searchRadiusMm` 8.** The board had moved
  (turned 3.9°). Visited from home, FID2 and FID3 came first, and the radius was measured from FID1's prediction (the
  similarity of FID2 and FID3), 8.7 mm from its expected position; the mark sat 0.53 mm from that prediction.
  - Fixed: the radius bounds the distance from the expected position. A board seated farther than that off its
    nominal now stops with “FID1 not found within 8.0 mm of X …”: correct the board X/Y, or widen
    `fiducialCheck.searchRadiusMm` (up to 12). AUTO_REGISTER_DESIGN §3.3 and §3.7 still say “from the prediction”.
- **F5, the needle anchor was typed, not measured.** `POST /api/needle/anchor` at 00:13:19 took a typed value.
  Before dispensing, touch off on the blank and record the anchor from the measured contact (item 7).

## First real-machine session (2026-10-28 02:20–03:05, copper blank only)

The fork ran in real mode on a **copy** of the config (`~/instancelabs/realcfg-sw-20261028`, with the patched
`machine.xml`); the live `~/.openpnp2` is untouched, so items 1 and 2 still apply. Photos and data:
`~/instancelabs/runs/sw_engine_blank/` (`engine_blank_runs.jpg`, `run.json`, `run2.json`, `sw_probe_blank*.txt`).

| item | result |
|---|---|
| 1 (patched `machine.xml`) | Works on the copy: visual homing on the fiducial at 217.081, 200.223. Not installed live yet. |
| 3 (real mode) | Started and homed; web UI and interlocks worked. |
| 4 (Stop) | 1 of 4 cases done: web STOP during a pressure hold vented the valve, switched the pump off and disabled the machine (F9). Still to do: OpenPnP's Stop button, and both during a board run; vent time not measured. |
| 6 (B step count) | **Confirmed.** `Count B` was 1154124 before and after OpenPnP's home (`G92 A0 B0`), so `G92` leaves it alone. Run 2 started at 1177324 = run 1's start + 23200 steps (first purge dot 1600 + 7 × 800 + 20 × 800). Work dots started at 297.9° (298° rounded to a whole step). |
| 7, 8 (frame, probes) | Done on the blank. N1 contacts 5.75–5.80 over X 288–305, Y 242.5–249.5. The blank is **bowed**: 5.75 at Y 254 down to 5.50 at Y 270, so a plane over that span fails the 0.04 mm residual (I4 refused it, correctly). Probe close to where the dots go. |
| 11 (XY trim) | Not settled. Without trim the dots landed (+0.15, −0.04) mm off target; with the bench trim (+0.086, −0.010) still (+0.087, −0.017), scatter 0.084 mm. OpenPnP lands about +0.09 mm further in X than the bench script, likely its one-sided backlash approach. Next: check the backlash settings, or set the trim to about +0.17 X and check again. |
| 13 (engine run) | Two runs on the blank, not a board: 8 purge + 20 dots (status OK, 5,041 DPH dots only) and 8 purge + 16 dots (OK, 4,925 DPH). Dots on bare copper: 1.02–1.28 mm² (mean 1.15, CV 8 %) and 0.95 mm² (CV 12 %). Bare copper wets differently from pads, so compare areas only on a board. The first work dot came out larger (the cross from the purge row at travel Z); watch for it on a board. |

1. **Install the regenerated `machine.xml` patch, with OpenPnP closed.**
   - Why: OpenPnP rewrites `machine.xml` when it exits, so an edit made while it runs is lost. The patch now sets the N2
     head offset to (25.291, −75.926) (was 23.615, −63.947), the homing fiducial to (217.081, 200.223), `M906 B580` on
     connect, `DISABLE_COMMAND` = F9 (`M410, M107 P3, M107 P2, M107 P1, M107 P0, M18 B`), and `VAC2` at S255 with the
     valve switched off first.
   - How: follow `dist/README.md` (the live file's sha256 must be `5cc805eb…`). After homing, the camera must sit on
     the homing fiducial and the DRO read 217.081, 200.223. Then jog the top camera to FID1 and compare with
     `runs/board_registration.json`.
   - Also check the top camera's units per pixel: 0.01068 × 0.01073 mm/px in `machine.xml`, while the bench used
     0.01074 × 0.01106. The 3 % gap in Y changes dot areas read through OpenPnP by about 3 %, and changes the
     clean-pad box by 2–3 px. Measure it once by moving the camera a known distance (for example 1 mm) over a
     fiducial and counting the pixels.

2. **Install a schema-3 `dispense-config.json`.**
   - Why: `config/dispense-config.json` is still schema 2. It loads, and the motion values are migrated, but it keeps
     `valveSettleMs 1500` where the bench uses 3000.
   - How: set the valve settle to 3000 ms in the web UI settings, or start from the engine defaults (no file).
     `GET /api/config` should show `schemaVersion 3` and `valveSettleMs 3000`.

3. **Start the fork in real mode.**
   - Why: the simulator replaces the serial port and the cameras.
   - How: run `dist/openpnp-il/openpnp-il.sh` with no other OpenPnP running. Check that:
     - the log has no errors;
     - the serial port is found by its USB serial number;
     - the web UI opens at <http://127.0.0.1:8765/>;
     - the Interlocks list shows I1 as "ok" (OpenPnP's Stop sends the full F9 sequence).

4. **Formal Stop test.**
   - Why: the simulator shows the order of the G-code, but not when the valve really vents. Stock firmware holds
     `M410`'s "ok" for about 1 s, so the valve-off line after it reaches the board about 1 s late.
   - How: test four cases: web STOP and OpenPnP's Stop button, each during a pressure hold and during a board run.
     Each time, check that:
     - the valve vents (listen), and note the time from the press to the vent (expect about 1 s on stock firmware);
     - nothing moves after the press;
     - the pump is off;
     - the screw turns freely by hand (`M18 B`);
     - the machine needs homing again;
     - a stopped run ends `ABORTED`.
   - During a run, two "Error actuating VAC1/VAC2" dialogs are known and expected.

5. **Flash the il/safety firmware (not flashed yet).**
   - Why: it adds the host watchdog (`M990`), so the engine's keepalive switches the pneumatics off if the host dies,
     and it vents inside `M410` instead of 1 s later.
   - How: follow `firmware/FLASH.md` (back up first, BOOT+RESET into DFU, flash), then run `firmware/verify_fw.py`.
     `M115` must report `Cap:HOST_WATCHDOG:1`, and the next run must show `keepaliveActive: true`.

6. **Confirm the B step count.**
   - Why: the screw angle comes from the `Count … B:` field of `M114`. The simulator does not report it (the engine
     uses 0 and warns). `machine.xml`'s stored `reported-axes` already shows `Count … B:799` from this firmware. Still
     unconfirmed: whether `G92 B0` leaves the count alone (the engine sends it at every session start and end). If
     `G92` resets the count, the phase lock is wrong after every session.
   - How: send `M114` and note `Count B`. Send `G92 B0` and `M114` again: the position B must read 0 and `Count B`
     must be unchanged. Do not turn the screw without pressure to test it (it pulls in air). Instead, after the
     first engine session, `Count B` must equal the run's `bCountAtStart` plus the steps the session turned: the
     first purge dot's steps + 167 × 800.

7. **Record the needle frame and touch off.**
   - Why: the needle contact comes from the bench relation "needle contact raw Z = 65.59 − N1 contact Z"
     (offset −2.59 mm), which has to hold on today's mount.
   - How:
     1. Touch the blank with N1 and note its Z (c).
     2. Send `POST /api/needle/anchor {"surfaceZ": c, "needleContactRawSum": 65.59}`.
     3. Run the touch-off on the blank. The contact it finds should be within about 0.05 mm of the anchor.
   - Expected dispense heights: raw Z = 65.49 − c, about 59.9–60.2 on this board (bench `p18_board1`: 59.93–60.13).

8. **Probe the board and the blank.**
   - Why: every dot takes its Z from the probed planes. A board surface is tied to the registration it was probed
     on, so a re-seat needs new probes (I4).
   - How, for the board: after the fiducial check, plan the board, then probe five N1 points at board (8, 8),
     (67, 8), (67, 67), (8, 67) and (37, 37). Store them with `PUT /api/surfaces/board` including that plan's
     `planId`, then plan again.
   - How, for the blank: probe at least 3 N1 points around the purge row (largest residual ≤ 0.04 mm) and store them
     with `PUT /api/surfaces/blank`.

9. **Set up the purge row.**
   - Why: each board uses 8 fresh spots. The bench row (y 237.5, x 292–327, pitch 2.5) is already used up to x 327
     (`runs/purge_spots.json`).
   - How: wipe the blank, then:
     - `PUT /api/config {"purgeRow": {"x0Mm": 292, "y0Mm": 237.5, "pitchXMm": 2.5, "spots": 15}}`;
     - `POST /api/purge-row/reset`.
   - Status should show 15 spots left (1 board, since each board uses 8).

10. **Confirm or measure the start angle.**
    - Why: with half turns, the dots alternate between starting at 298° and 118°, and the two halves still differ by
      about 12 %. Where to split the turn is still open. The angle is also lost when the controller is
      power-cycled.
    - How: after every connect, confirm it (`POST /api/screw/start-angle`) if the controller was not power-cycled.
      Otherwise measure it again. To settle the split point, lay dot pairs on the blank at a few start angles, and
      keep the angle whose two halves match best (by area or by weight).

11. **Needle XY trim.**
    - Why: `disp.py` now uses the needle offset (25.377, −75.936). That is the `machine.xml` offset plus the p18
      landing correction (0.086, −0.010).
    - How: if the bench confirms it, send
      `PUT /api/config {"needleXyTrim": {"dxMm": 0.086, "dyMm": -0.010, "note": "p18_board1 landings"}}`.
      Then check the after-images of a board: the mean dot-to-pad offset should be under about 0.03 mm.

12. **Check the clean-pad guard against the bench.**
    - Why: OpenPnP's top-camera frames are flipped in X and Y and use a different mm-per-pixel than the bench's
      frames, and the guard was tuned on the bench's frames.
    - How: capture clean and dirty pads through OpenPnP (`POST /api/plan/{id}/clean-check`) and through `disp.py`.
      Run `vision.PadCleanTool` on both sets. For each pad, the two bright shares should agree within about 0.03,
      and every clean/dirty verdict must match.
    - Also time the check. It visits all 160 pads and waits out the top camera's settle at each one (FixedTime
      1000 ms in `machine.xml`). Expect about 3 minutes per board before the pressure goes on, which is not in the
      DPH. A shorter settle may be enough.

13. **First engine board run against the bench.**
    - Why: the bench `p18_board1` result is the target: 160/160 pads, mean dot area 0.714 mm², CV 13 %, 5,490 DPH.
    - Prediction from the simulated stream: 109.4 s for the 160 dots, i.e. 5,264 DPH, by the same model that
      predicts the bench run 2.7 % too slow (107.8 s against the measured 104.9 s). So expect about 5,400 DPH.
      Two things make the engine slightly slower than `disp.py`:
      - it crosses from the purge row at travel Z (raw 31.5, not the bench's 49.5): +0.7 s per board;
      - OpenPnP's backlash approach adds a second, short XY move per dot: +1.5 s per board.
    - How:
      1. Set the regulator to 18 psi.
      2. Make sure the pads and the purge spots are clean, and leave the clean-pad check on.
      3. Run the board.
      4. Compare the dot area and CV (after-images), and the run's `dphMotion`, with the numbers above.
