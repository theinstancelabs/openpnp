#!/usr/bin/env python3
"""il_ui_session.py — a SIM-mode OpenPnP with the web UI ready to look at (UI work, screenshots, QA).

  python3 src/test/sim/il_ui_session.py start --jar target/openpnp-gui-0.0.1-alpha-SNAPSHOT.jar --name ui-qa1
  python3 src/test/sim/il_ui_session.py start --name ui-qa2 --kicad src/test/resources/kicad/ftp.kicad_pcb.gz --register
  python3 src/test/sim/il_ui_session.py stop --name ui-qa1

start: launches openpnp-uinav in SIM mode (never the serial port or cameras), enables and homes the simulated
machine, and prepares it like the sim acceptance does (needle Z frame from K = 65.59, blank surface and purge row,
start angle confirmed, chip-0603 characterized, the simulator's probe timings), then plans ~/Desktop/ftp.board.xml
with the board surface tied to the plan. The SIM top camera draws the board where the SIM scene says it truly sits:
0.60 / -0.45 mm and +0.20 deg off the planned seat, scaled 1.002 / 0.997 and sheared 0.1 deg (AUTO_REGISTER_DESIGN.md
§9.5), so the planned dots miss the pads until the board is registered.

  --register     plan, then Register board (home if needed, the fiducials, the N1 probe; about a minute) instead of
                 tying the bench's surface points: the plan made then is on the true pads
  --kicad FILE   upload this KiCad board (.kicad_pcb, or .kicad_pcb.gz) through the web API (preview, then save,
                 replacing an import of the same name in the session copy) and plan it instead of the bench board;
                 the SIM scene then shows it
  --clean-check  keep the clean-pad guard on (the SIM camera draws the pads, so it sees the true seat)
  --no-plan      do not plan

Prints one JSON line: {"session", "url", "port", "planId", "board", "config"} (config: the session's copy, where
il_sim_scene.py amend/write changes the SIM scene) and, with --register,
"registration": {"status", "seat", "movedMm", "newPlanId", "message"}. The session keeps running until 'stop'.
"""
import argparse
import gzip
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import il_sim_acceptance as acc  # noqa: E402
import il_sim_scene as scn  # noqa: E402

# Where the board truly sits (AUTO_REGISTER_DESIGN.md §9.5): the planned seat, re-seated, scaled and sheared.
TRUE_SEAT = scn.reseat(scn.seat(acc.BOARD_LOC["x"], acc.BOARD_LOC["y"], acc.BOARD_LOC["rotation"], 1.002, 0.997, 0.1),
                       0.60, -0.45, 0.20)


def scene_of(board, config_dir):
    return scn.scene(board, TRUE_SEAT, acc.BOARD_PLANE, config_dir=config_dir, vacuum=scn.FAST_VACUUM)


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("what", choices=["start", "stop"])
    ap.add_argument("--name", default="ui", help="session name (prefix il-ui- is added)")
    ap.add_argument("--clean-check", action="store_true", help="keep the clean-pad guard on")
    ap.add_argument("--no-plan", action="store_true", help="do not plan the board")
    ap.add_argument("--register", action="store_true", help="plan, then Register board (about a minute)")
    ap.add_argument("--kicad", help="upload this KiCad board (.kicad_pcb[.gz]) and plan it instead of the bench board")
    for k, v in acc.DEF.items():
        ap.add_argument("--" + k.replace("_", "-"), dest=k, default=v)
    a = ap.parse_args(argv)
    a.prefix = "il-ui-"
    a.jar = os.path.abspath(a.jar)
    if a.what == "stop":
        os.system("%s --session %s stop >/dev/null 2>&1" % (acc.UINAV, a.prefix + a.name))
        print(json.dumps({"stopped": a.prefix + a.name}))
        return 0
    a.out = os.path.join("/tmp", "il-ui-session-" + a.name)
    os.makedirs(a.out, exist_ok=True)
    s = acc.Session(a, a.name, scene=scene_of)
    acc.setup_machine(s)
    s.api("PUT", "/api/config", dict(scn.FAST_TIMINGS, cleanPadCheck={"enabled": bool(a.clean_check)}))
    board = acc.session_board(s)
    if a.kicad:
        raw = open(a.kicad, "rb").read()
        data = gzip.decompress(raw) if a.kicad.endswith(".gz") else raw
        file_name = os.path.basename(a.kicad)[:-3] if a.kicad.endswith(".gz") else os.path.basename(a.kicad)
        name = os.path.splitext(file_name)[0]
        q = acc.urllib.parse.quote
        pv = acc.upload(s, "/api/boards/import?file=" + q(file_name), data)
        saved = acc.upload(s, "/api/boards/import/save?file=%s&name=%s&replace=1&sha256=%s&decisions=%s"
                           % (q(file_name), q(name), pv["file"]["sha256"], q(pv["decisions"])), data)
        board = saved["file"]
        scn.write(s.cfg, scene_of(board, s.cfg))
    out = {"session": s.name, "url": s.base + "/", "port": s.port, "planId": None, "board": board, "config": s.cfg}
    if not a.no_plan:
        if a.register:
            acc.log("Register board: home if needed, 3 fiducials, 5 N1 probe points (about a minute in the SIM)")
            p = s.api("POST", "/api/plan", dict(acc.BOARD_LOC, source="file", file=board), timeout=120)
            r = acc.register(s, p["planId"])
            out["registration"] = {k: r.get(k) for k in ("status", "seat", "movedMm", "newPlanId", "message")}
            out["planId"] = r.get("newPlanId") or p["planId"]
        else:
            out["planId"] = acc.plan_board(s, board)[0]["planId"]
    print(json.dumps(out))
    return 0


if __name__ == "__main__":
    sys.exit(main())
