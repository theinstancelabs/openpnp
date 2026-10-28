#!/usr/bin/env python3
"""Instance Labs simulator acceptance for il/integration (never the real machine).

Every scenario runs this branch's OpenPnP jar through openpnp-uinav in SIM mode: a COPY of the
config dir, the GcodeDriver rewired to OpenPnP's own simulated controller (GcodeServer) behind a
capture proxy, simulated cameras, device stubs first on the classpath, its own Xvfb display.
The config copy is staged from the live dir (read only) with machine.xml replaced by the plan §10
patched copy and the engine's dispense-config.json added.

Scenarios (plan §4.4, §4.5, §8.1; task items 2a, 2b, 3):
  load        patched machine.xml loads; connect / home / Stop G-code; File>Exit saves it back
  stop        STOP via the web API and via the Swing Stop button, during a pressure hold and
              during a long board run: captured G-code shows the sequence, no motion after it
  board       plan + run ~/Desktop/ftp.board.xml (copy): dot count, raw XY/Z of every dot vs
              OpenPnP's own N2 move to that pad, session order, G92 B0, no per-dot M400; and
              the kinematic real-machine time / DPH estimate (tools/instancelabs/dph_estimate.py)

    il_sim_acceptance.py [--out DIR] all | load | stop | board

Sim-only changes on top of the patched copy (reported in results.json):
  * head visual-homing-method=None (the simulated camera has no homing fiducial; the load scenario
    shows the patched HOME_COMMAND itself, with visual homing on);
  * through the web API, in the session DB/config only: waste location, needle Z offset (I4),
    testDotOpticalCheck=false (static simulated image: no dot can appear), chip-0603 status
    'characterized' (§5.7 rule).
Python 3 standard library only.
"""
import argparse
import json
import math
import os
import re
import shutil
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", "..", ".."))
sys.path.insert(0, os.path.join(REPO, "tools", "instancelabs"))
import dph_estimate  # noqa: E402

UINAV_DIR = os.environ.get("UINAV_DIR", "/home/lumen/instancelabs/openpnp-uinav")
sys.path.insert(0, os.path.join(UINAV_DIR, "python"))
import uinav  # noqa: E402

HOME = os.path.expanduser("~")
DEF = {
    "jar": os.path.join(REPO, "target", "openpnp-gui-0.0.1-alpha-SNAPSHOT.jar"),
    "live": os.path.join(HOME, ".openpnp2"),
    "patched": "/home/lumen/instancelabs/config/machine.xml.patched",
    "dispense_config": "/home/lumen/instancelabs/config/dispense-config.json",
    "board": os.path.join(HOME, "Desktop", "ftp.board.xml"),
    "out": "/home/lumen/instancelabs/sim-acceptance/" + time.strftime("%Y%m%d-%H%M%S"),
}
# Board placed where the top camera found it on the real machine (runs/board_registration.json:
# origin 301.646, 115.334; rotation from the affine's first column).
# Z frame (I4) with the bench numbers: board Z, places and surface points are N1 contact heights
# (OpenPnP's frame; runs/zmap.json 5.425..5.70); the needle frame is N1 5.5 / needle 10.4 (offset 4.9),
# so the needle dispenses at raw z1 ~52.45 as on the bench (runs/board_plan_r2.json). Sim only.
BOARD_LOC = {"x": 301.646, "y": 115.334, "z": 5.55, "rotation": -0.5208}
WASTE = {"x": 290.0, "y": 125.0, "z": 5.6}
NEEDLE_FRAME = {"surfaceZ": 5.5, "needleContactZ": 10.4, "note": "sim acceptance: bench relation needle raw = 57.9 - N1"}
ZMAP = "/home/lumen/instancelabs/runs/zmap.json"
SIM_XML_SETS = [".//head@visual-homing-method=None"]
DISABLE_SEQ = ["M410", "M107 P3", "M107 P2", "M107 P1", "M107 P0", "M18 B"]
ENGINE_I1 = ["M410", "M107 P3", "M107 P2", "M18 B"]
UINAV = os.path.join(UINAV_DIR, "bin", "uinav")
MOTION = re.compile(r"^\s*G0*(0|1|2|3|28|29|30)(?![0-9])", re.I)
AXIS_WORD = re.compile(r"\b([XYZAB])\s*(-?\d+(?:\.\d*)?)", re.I)


def log(*a):
    print("[il-sim]", *a, file=sys.stderr, flush=True)


def free_port():
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def strip(line):
    return line.split(";", 1)[0].strip()


def is_motion(line):
    code = strip(line)
    return bool(MOTION.match(code)) and bool(AXIS_WORD.search(code))


def find_subseq(lines, seq, start=0):
    """Indices of seq as an in-order (not necessarily contiguous) subsequence, or None."""
    codes = [strip(l) for l in lines]
    out, j = [], start
    for want in seq:
        while j < len(codes) and codes[j] != want:
            j += 1
        if j >= len(codes):
            return None
        out.append(j)
        j += 1
    return out


def find_seq(lines, seq, start=0):
    codes = [strip(l) for l in lines]
    for i in range(start, len(codes) - len(seq) + 1):
        if codes[i:i + len(seq)] == seq:
            return i
    return -1


# --------------------------------------------------------------------------- staging / session

def stage(a, name):
    """Config copy: live dir (read only) + patched machine.xml + engine config + test script + board."""
    st = os.path.join(a.out, "stage-" + name)
    if os.path.exists(st):
        shutil.rmtree(st)
    shutil.copytree(a.live, st, ignore=shutil.ignore_patterns("backups", "log"))
    shutil.copy2(a.patched, os.path.join(st, "machine.xml"))
    os.makedirs(os.path.join(st, "instancelabs"), exist_ok=True)
    shutil.copy2(a.dispense_config, os.path.join(st, "instancelabs", "dispense-config.json"))
    shutil.copy2(os.path.join(HERE, "il_n2_reference.js"), os.path.join(st, "scripts", "il_n2_reference.js"))
    board_copy = os.path.join(st, os.path.basename(a.board))
    shutil.copy2(a.board, board_copy)
    with open(os.path.join(st, "boards.xml"), "w") as f:
        f.write("<openpnp-boards>\n   <board>%s</board>\n</openpnp-boards>\n" % board_copy)
    return st


class Session:
    def __init__(self, a, name, xml_sets=SIM_XML_SETS):
        self.a, self.name = a, a.prefix + name
        self.stage = stage(a, name)
        self.port = free_port()
        env = dict(os.environ, OPENPNP_IL_API_PORT=str(self.port))
        cmd = [UINAV, "--session", self.name, "launch", "--config", self.stage, "--openpnp-jar", a.jar]
        for x in xml_sets:
            cmd += ["--xml-set", x]
        log("launch", self.name, "api port", self.port)
        out = subprocess.run(cmd, env=env, capture_output=True, text=True, timeout=300)
        if out.returncode != 0:
            raise RuntimeError("uinav launch failed: %s %s" % (out.stdout, out.stderr))
        self.info = json.loads(out.stdout)
        self.sess_dir = uinav.session_dir(self.name)
        self.cfg = self.info["config_dir"]
        self.ui = uinav.Client(session=self.name, timeout=120)
        self.base = "http://127.0.0.1:%d" % self.port
        for _ in range(240):
            try:
                self.api("GET", "/api/status")
                break
            except Exception:
                time.sleep(0.5)
        else:
            raise RuntimeError("web API did not come up on %d" % self.port)

    def api(self, method, path, body=None, timeout=60, ok=(200, 201, 202)):
        data = None if body is None else json.dumps(body).encode()
        req = urllib.request.Request(self.base + path, data=data, method=method)
        if data is not None:
            req.add_header("Content-Type", "application/json")
        try:
            with urllib.request.urlopen(req, timeout=timeout) as r:
                payload, code = r.read(), r.status
        except urllib.error.HTTPError as e:
            payload, code = e.read(), e.code
        try:
            b = json.loads(payload)
        except ValueError:
            b = {"raw": payload.decode(errors="replace")}
        if code not in ok:
            raise RuntimeError("%s %s -> %d %s" % (method, path, code, json.dumps(b)[:800]))
        return b

    def mark(self):
        """Sequence number of the next captured G-code line."""
        return self.ui.get("/gcode", since=10 ** 9)["next"]

    def sent_since(self, n):
        return [e["line"] for e in self.ui.gcode(since=n)["entries"] if e.get("dir", ">") == ">"]

    def wait_status(self, pred, timeout=60, what="status"):
        t0 = time.time()
        last = None
        while time.time() - t0 < timeout:
            last = self.api("GET", "/api/status")
            if pred(last):
                return last
            time.sleep(0.2)
        raise TimeoutError("timed out waiting for %s; last: %s" % (what, json.dumps(last)[:1500]))

    def mcp_button(self, text):
        """The power button of OpenPnP's machine controls (startStopMachineAction); exactly one match."""
        mcps = self.ui.find(type="MachineControlsPanel", any="1")
        if len(mcps) != 1:
            raise RuntimeError("expected one MachineControlsPanel, found %d" % len(mcps))
        hits = self.ui.find(type="JButton", text=text, within=mcps[0]["path"])
        if len(hits) != 1:
            raise RuntimeError("expected one '%s' button, found %s" % (text, [h["path"] for h in hits]))
        return hits[0]

    def dialogs(self):
        return [w for w in self.ui.windows() if w.get("showing") and w.get("class") != "org.openpnp.gui.MainFrame"]

    def dialog_texts(self):
        out = []
        for w in self.dialogs():
            texts = []

            def walk(n):
                if isinstance(n, list):
                    for c in n:
                        walk(c)
                    return
                if not isinstance(n, dict):
                    return
                if n.get("text"):
                    texts.append(n["text"])
                walk(n.get("children", []))
            try:
                walk(self.ui.tree(path=w["path"]))
            except Exception as e:
                texts.append("(tree failed: %s)" % e)
            out.append({"title": w.get("title"), "texts": texts[:12]})
        return out

    def close_dialogs(self):
        for w in self.dialogs():
            try:
                self.ui.act("close_window", path=w["path"])
            except Exception as e:
                log("close dialog failed", e)

    def stop(self):
        subprocess.run([UINAV, "--session", self.name, "stop"], capture_output=True, timeout=60)


def setup_machine(s, characterize=True):
    """Enable, home (sim: no visual homing), sim-only engine settings through the web API."""
    s.api("POST", "/api/machine/enable")
    s.wait_status(lambda st: st["machine"]["enabled"], 30, "enabled")
    s.api("POST", "/api/machine/home", {"wait": True}, timeout=180)
    s.wait_status(lambda st: st["machine"]["homed"], 60, "homed")
    s.api("PUT", "/api/locations/waste", WASTE)
    s.api("POST", "/api/needle/anchor", NEEDLE_FRAME)
    s.api("POST", "/api/needle/offset", {"offsetMm": NEEDLE_FRAME["needleContactZ"] - NEEDLE_FRAME["surfaceZ"],
                                          "note": "sim acceptance (no needle): the frame offset confirmed"})
    s.api("PUT", "/api/surfaces/board", {"points": board_surface_points(), "note": "sim: runs/zmap.json + corners"})
    s.api("PUT", "/api/config", {"testDotOpticalCheck": False})
    if characterize:
        s.api("PUT", "/api/pad-types/chip-0603", {"status": "characterized",
                                                   "notes": "SIM ONLY: marked characterized for the acceptance run"})
    return s.api("GET", "/api/config")


def board_surface_points():
    """runs/zmap.json (9 N1 contacts on this board) plus 4 corner points from its own plane fit.

    The 9 bench points stop ~3 mm short of the outermost pads (x 306.8..372.1 vs 309.7..369.1), which
    I4 refuses (2 mm margin); a real run probes the corners. Sim only: the corners lie on the fitted
    plane, so they change neither the plane nor its residuals.
    """
    z = json.load(open(ZMAP))
    pts = [[p["x"], p["y"], p["z"]] for p in z["points"]]
    n = len(pts)
    mx, my, mz = (sum(p[i] for p in pts) / n for i in range(3))
    sxx = sum((p[0] - mx) ** 2 for p in pts)
    syy = sum((p[1] - my) ** 2 for p in pts)
    sxy = sum((p[0] - mx) * (p[1] - my) for p in pts)
    sxz = sum((p[0] - mx) * (p[2] - mz) for p in pts)
    syz = sum((p[1] - my) * (p[2] - mz) for p in pts)
    det = sxx * syy - sxy * sxy
    b, c = (sxz * syy - syz * sxy) / det, (syz * sxx - sxz * sxy) / det
    a0 = mz - b * mx - c * my
    for bx, by in ((2, 13), (73, 13), (2, 62), (73, 62)):
        gx, gy = to_global(bx, by)
        pts.append([round(gx, 3), round(gy, 3), round(a0 + b * gx + c * gy, 4)])
    return pts


def plan_board(s):
    board = os.path.join(s.cfg, "uinav-boards")
    files = [os.path.join(board, f) for f in sorted(os.listdir(board)) if f.endswith(os.path.basename(s.a.board))]
    body = dict(BOARD_LOC, source="file", file=files[0])
    return s.api("POST", "/api/plan", body, timeout=120), files[0]


# --------------------------------------------------------------------------- scenario: load

def scenario_load(a):
    """Patched machine.xml with NO sim-only XML change: loads, connect/home/Stop G-code, save on exit."""
    s = Session(a, "load", xml_sets=[])
    res = {"session": s.name, "cameras_replaced": s.info["cameras_replaced"]}
    try:
        olog = os.path.join(s.cfg, "log", "OpenPnP.log")
        errs = [l.strip() for l in open(olog) if re.search(r" (ERROR|WARN)", l)]
        benign = ("FileSystemPreferences", "SLF4J")
        res["log_errors"] = [l for l in errs if not any(b in l for b in benign)]
        res["log_errors_benign"] = errs
        res["dialogs_at_start"] = s.dialog_texts()
        st = s.ui.state(commands=True)
        cmds = {c["type"]: c["text"] for c in st["drivers"][0]["commands"] if c.get("head_mountable_id") is None}
        res["driver"] = {k: cmds.get(k) for k in ("CONNECT_COMMAND", "DISABLE_COMMAND", "HOME_COMMAND")}
        res["driver"]["VAC2"] = [c["text"] for c in st["drivers"][0]["commands"]
                                 if c.get("head_mountable_id") == "ACT171086f27295ab60"
                                 and c["type"] == "ACTUATE_BOOLEAN_COMMAND"]
        n0 = s.mark()
        s.api("POST", "/api/machine/enable")
        s.wait_status(lambda x: x["machine"]["enabled"], 30, "enabled")
        res["connect_gcode"] = s.sent_since(n0)
        n1 = s.mark()
        try:
            s.api("POST", "/api/machine/home", {"wait": True}, timeout=180)
            res["home_result"] = "homed"
        except RuntimeError as e:
            res["home_result"] = str(e)
        res["home_gcode"] = s.sent_since(n1)
        n2 = s.mark()
        res["stop_response"] = s.api("POST", "/api/stop")
        s.wait_status(lambda x: not x["machine"]["enabled"], 30, "disabled")
        time.sleep(1.0)
        res["stop_gcode"] = s.sent_since(n2)
        res["dialogs_after_stop"] = s.dialog_texts()
        s.close_dialogs()
        # File>Exit: OpenPnP saves machine.xml on the way out.
        mx = os.path.join(s.cfg, "machine.xml")
        before = os.stat(mx).st_mtime
        try:
            s.ui.act("menu", menu="File>Exit")
        except Exception:
            pass  # the JVM exits while answering
        for _ in range(60):
            if os.stat(mx).st_mtime != before:
                break
            time.sleep(0.5)
        time.sleep(1.0)
        res["saved_on_exit"] = os.stat(mx).st_mtime != before
        saved = open(mx).read()
        checks = {
            "usb-serial-number": 'usb-serial-number="387932683335"' in saved,
            "usb-description": 'usb-description="MARLIN_OPULO_LUMEN_REV5"' in saved,
            "DISABLE_COMMAND": all(x in saved for x in ["<![CDATA[%s]]>" % l for l in DISABLE_SEQ]),
            "HOME G92 A0 B0": "G92 A0 B0" in saved,
            "CONNECT M906 B540": "M906 B540" in saved,
            "VAC2 S255, OFF valve first": "{True:M106 P2 S255}{False:M107 P3};" in saved
                                           and "{True:M106 P3 S255}{False:M107 P2};" in saved and "M106 P3 S180" not in saved,
        }
        root = ET.fromstring(saved)
        for name in ("VAC1", "VAC2"):
            el = [e for e in root.iter("actuator") if e.get("name") == name][0]
            checks["%s ActuateOff" % name] = el.get("disabled-actuation") == "ActuateOff"
        n2el = [e for e in root.iter("nozzle") if e.get("name") == "N2"][0]
        checks["N2 no tips"] = len(list(n2el.find("compatible-nozzle-tip-ids"))) == 0 and \
            n2el.get("current-nozzle-tip-id") is None
        res["saved_config_keeps_patch"] = checks
        res["sim_rewrites_in_saved_copy"] = "communications=tcp (uinav sim), cameras ImageCamera/SimulatedUpCamera (uinav sim)"
        ok = (not res["log_errors"] and not res["dialogs_at_start"] and "M906 B540 ; auger motor (J/B) current, mA (plan 10 item 7)" in res["connect_gcode"]
              and find_seq(res["home_gcode"], ["G28", "G92 A0 B0"]) >= 0 and find_seq(res["stop_gcode"], DISABLE_SEQ) >= 0
              and res["saved_on_exit"] and all(checks.values()))
        res["pass"] = ok
    finally:
        s.stop()
    return res


# --------------------------------------------------------------------------- scenario: stop

def analyze_stop(lines):
    r = {"lines_after_press": lines}
    i410 = next((i for i, l in enumerate(lines) if strip(l) == "M410"), -1)
    r["first_M410_index"] = i410
    # contiguous = the six lines back to back; in order = as a subsequence (the engine's own I1 lines,
    # sent from the machine thread, may interleave with OpenPnP's DISABLE_COMMAND from the Stop thread)
    r["disable_command_contiguous_index"] = find_seq(lines, DISABLE_SEQ)
    r["disable_command_in_order_lines"] = find_subseq(lines, DISABLE_SEQ, max(i410, 0))
    r["engine_I1_in_order_lines"] = find_subseq(lines, ENGINE_I1, max(i410, 0))
    after = lines[i410 + 1:] if i410 >= 0 else lines
    r["motion_after_first_M410"] = [l for l in after if is_motion(l)]
    r["outputs_on_after_first_M410"] = [l for l in after if strip(l).upper().startswith("M106")]
    offs = [strip(l) for l in lines if strip(l) in ("M107 P2", "M107 P3")]
    r["first_off_pair"] = offs[:2]
    r["valve_off_before_pump_off"] = offs[:2] == ["M107 P3", "M107 P2"]
    r["valve_off_present"] = any(strip(l) == "M107 P3" for l in lines)
    r["pump_off_present"] = any(strip(l) == "M107 P2" for l in lines)
    r["auger_disabled"] = any(strip(l) == "M18 B" for l in lines)
    r["pass"] = (i410 >= 0 and r["disable_command_in_order_lines"] is not None and not r["motion_after_first_M410"]
                 and not r["outputs_on_after_first_M410"] and r["valve_off_present"] and r["pump_off_present"]
                 and r["auger_disabled"] and r["valve_off_before_pump_off"])
    return r


def press_stop(s, how):
    if how == "web":
        return s.api("POST", "/api/stop")
    btn = s.mcp_button("Stop")
    sig = s.ui.classify("click", path=btn["path"])
    if "field:startstopmachineaction" not in json.dumps(sig).lower():  # uinav lower-cases signatures
        raise RuntimeError("the Stop button found is not OpenPnP's power button: %s" % json.dumps(sig))
    r = s.ui.act("click", path=btn["path"], timeout_ms=20000)
    r["button"] = {"path": btn["path"], "text": btn.get("text"), "action": btn.get("action"),
                   "guard_signature": sig.get("signature")}
    return r


def scenario_stop_one(a, what, how):
    s = Session(a, "stop-%s-%s" % (what, how))
    res = {"session": s.name, "what": what, "how": how}
    try:
        setup_machine(s)
        if what == "hold":
            n0 = s.mark()
            res["start"] = s.api("POST", "/api/pressure/start")
            s.wait_status(lambda x: (x.get("pressure") or {}).get("on"), 30, "pressure on")
            time.sleep(1.5)  # held: keepalive/I7 timers running
            res["before_press"] = s.sent_since(n0)
        else:
            plan, _ = plan_board(s)
            res["plan_summary"] = plan.get("summary")
            n0 = s.mark()
            res["start"] = s.api("POST", "/api/run", {"planId": plan["planId"]}, ok=(200, 202))
            st = s.wait_status(lambda x: ((x.get("run") or {}).get("dotsDone") or 0) >= 10, 240, "10 dots")
            res["dots_done_at_press"] = st["run"]["dotsDone"]
            res["before_press_tail"] = s.sent_since(n0)[-12:]
        mark = s.mark()
        t_press = time.time()
        res["press_response"] = press_stop(s, how)
        s.wait_status(lambda x: not x["machine"]["enabled"], 60, "machine disabled")
        res["seconds_to_disabled_upper_bound"] = round(time.time() - t_press, 2)  # incl. 0.2 s polling
        if what == "run":
            s.wait_status(lambda x: not x["dispense"]["busy"], 60, "engine idle")
        time.sleep(2.0)
        lines = s.sent_since(mark)
        res.update(analyze_stop(lines))
        st = s.api("GET", "/api/status")
        res["after"] = {"machine_enabled": st["machine"]["enabled"], "homed": st["machine"]["homed"],
                        "pressure": (st.get("pressure") or {}).get("state"),
                        "last_run": {k: (st.get("lastRun") or {}).get(k) for k in ("status", "message", "dotsDone", "dotsPlanned")}}
        res["dialogs"] = s.dialog_texts()
        s.close_dialogs()
        if what == "run":
            res["pass"] = res["pass"] and res["engine_I1_in_order_lines"] is not None \
                and res["after"]["last_run"]["status"] == "ABORTED"
        else:
            res["pass"] = res["pass"] and res["after"]["pressure"] in ("off", None)
    finally:
        s.stop()
    return res


def scenario_stop(a, out, save):
    for what in ("hold", "run"):
        for how in ("web", "swing"):
            try:
                r = scenario_stop_one(a, what, how)
            except Exception as e:
                r = {"what": what, "how": how, "pass": False, "error": repr(e)}
            log("stop", what, how, "PASS" if r["pass"] else "FAIL")
            out.append(r)
            save()
    return out


# --------------------------------------------------------------------------- scenario: board

def pads_from_board(board_file, pitch=1.65):
    """Independent pad centres (board coordinates) for the chip-0603 two-terminal pad type."""
    root = ET.parse(board_file).getroot()
    pads = {}
    for p in root.iter("placement"):
        if p.get("type") != "Placement":
            continue
        loc = p.find("location")
        px, py, r = float(loc.get("x")), float(loc.get("y")), math.radians(float(loc.get("rotation")))
        for name, lx in (("1", -pitch / 2), ("2", pitch / 2)):
            pads["%s.%s" % (p.get("id"), name)] = (px + lx * math.cos(r), py + lx * math.sin(r))
    return pads


def to_global(bx, by):
    r = math.radians(BOARD_LOC["rotation"])
    return (BOARD_LOC["x"] + bx * math.cos(r) - by * math.sin(r), BOARD_LOC["y"] + bx * math.sin(r) + by * math.cos(r))


def modal_walk(lines, start):
    """Yield (index, line, position-dict-after) with modal X/Y/Z/B tracking."""
    pos = dict(start)
    for i, l in enumerate(lines):
        code = strip(l).upper()
        if code.startswith("G92"):
            for k, v in AXIS_WORD.findall(code):
                pos[k.upper()] = float(v)
        elif MOTION.match(code):
            for k, v in AXIS_WORD.findall(code):
                pos[k.upper()] = float(v)
        yield i, l, dict(pos)


def engine_dots(lines, start):
    """Feeds (B increasing) with the raw XYZ at that moment, and the following lift."""
    feeds = []
    prev = dict(start)
    for i, l, pos in modal_walk(lines, start):
        code = strip(l).upper()
        if MOTION.match(code) and "B" in [k.upper() for k, _ in AXIS_WORD.findall(code)]:
            if pos["B"] > prev["B"] + 1e-9:
                feeds.append({"line": i, "x": pos["X"], "y": pos["Y"], "z": pos["Z"], "b_from": prev["B"], "b_to": pos["B"]})
            elif feeds and "retract_line" not in feeds[-1]:
                feeds[-1]["retract_line"] = i
                feeds[-1]["retract_to"] = pos["B"]
        elif MOTION.match(code) and feeds and "retract_line" in feeds[-1] and "lift_z" not in feeds[-1] \
                and any(k.upper() == "Z" for k, _ in AXIS_WORD.findall(code)):
            feeds[-1]["lift_line"] = i
            feeds[-1]["lift_z"] = pos["Z"]
        prev = pos
    return feeds


def scenario_board(a):
    s = Session(a, "board")
    res = {"session": s.name, "board_location": BOARD_LOC, "waste": WASTE}
    try:
        cfg = setup_machine(s)
        res["engine_config_used"] = cfg
        plan, board_file = plan_board(s)
        res["plan_summary"] = plan.get("summary")
        res["plan_interlocks"] = plan.get("interlocks")
        dots = sorted(plan["dots"], key=lambda d: d["index"])
        res["plan_dots"] = len(dots)
        res["plan_pads"] = len(plan["pads"])
        res["plan_blocked_pads"] = sum(1 for p in plan["pads"] if p.get("blocked"))
        res["plan_flags"] = sorted({f for p in plan["pads"] for f in (p.get("flags") or [])})
        m = s.api("GET", "/api/machine")
        ax = {x["letter"]: x["driverCoordinate"] for x in m["axes"] if x.get("letter")}
        start = {k: float(ax.get(k, 0.0)) for k in ("X", "Y", "Z", "B")}
        n0 = s.mark()
        t0 = time.time()
        run = s.api("POST", "/api/run", {"planId": plan["planId"], "wait": True}, timeout=900, ok=(200, 202))
        res["sim_run_seconds"] = round(time.time() - t0, 1)
        result = run.get("result") or {}
        res["run"] = {k: result.get(k) for k in ("status", "message", "dotsPlanned", "dotsDone", "linesSent",
                                                 "dphMotion", "dphOverall", "keepaliveActive", "warnings")}
        last = s.api("GET", "/api/runs/last")["run"]
        streamed = last["streamedLines"]
        captured = s.sent_since(n0)
        # Lines OpenPnP itself sent around the stream (capability query, settle) are reported, not hidden.
        res["captured_equals_streamed"] = captured == streamed
        if not res["captured_equals_streamed"]:
            k = 0
            extra = []
            for l in captured:
                if k < len(streamed) and l == streamed[k]:
                    k += 1
                else:
                    extra.append(l)
            res["captured_extra_lines"] = extra[:40]
            res["streamed_all_received_in_order"] = k == len(streamed)
        with open(os.path.join(a.out, "board-run-streamed.gcode"), "w") as f:
            f.write("\n".join(streamed) + "\n")
        with open(os.path.join(a.out, "board-run-captured.gcode"), "w") as f:
            f.write("\n".join(captured) + "\n")

        feeds = engine_dots(streamed, start)
        res["feeds_total"] = len(feeds)
        board_feeds = feeds[2:]  # prime + test dot first (I9)
        res["board_dots_streamed"] = len(board_feeds)
        res["dot_timings"] = len(last.get("dotTimings") or [])

        # ---- session order, I2, M400
        codes = [strip(l) for l in streamed]
        i_pump_on = codes.index("M106 P2 S255")
        i_valve_on = codes.index("M106 P3 S255")
        i_valve_off = max(i for i, c in enumerate(codes) if c == "M107 P3")
        i_pump_off = max(i for i, c in enumerate(codes) if c == "M107 P2")
        i_g92 = codes.index("G92 B0") if "G92 B0" in codes else -1
        i_first_b = feeds[0]["line"] if feeds else -1
        first_dot_line = board_feeds[0]["line"] if board_feeds else -1
        last_dot_line = board_feeds[-1].get("lift_line", board_feeds[-1]["line"]) if board_feeds else -1
        m400_in_dots = [i for i in range(first_dot_line, last_dot_line + 1) if codes[i] == "M400"]
        res["order"] = {
            "pump_on_line": i_pump_on, "valve_on_line": i_valve_on,
            "between_on": codes[i_pump_on + 1:i_valve_on],
            "valve_off_line": i_valve_off, "pump_off_line": i_pump_off,
            "end_sequence": codes[i_valve_off - 2:i_pump_off + 3],
            "pump_then_valve_on": i_pump_on < i_valve_on,
            "valve_then_pump_off": i_valve_off < i_pump_off,
            "g92_b0_line": i_g92, "first_b_move_line": i_first_b,
            "g92_b0_before_first_b": 0 <= i_g92 < i_first_b,
            "M400_between_first_and_last_dot": len(m400_in_dots),
            "M400_total": codes.count("M400"),
            "G4_total": sum(1 for c in codes if c.startswith("G4")),
        }

        # ---- net rotation per dot = planned rotation
        rot_err = []
        prev_end = feeds[1]["b_to"] if len(feeds) > 1 else 0.0
        for d, f in zip(dots, board_feeds):
            rot_err.append(abs((f["b_to"] - prev_end) - d["rotationDeg"]))
            prev_end = f["b_to"]
        res["rotation_check"] = {"max_abs_err_deg": max(rot_err) if rot_err else None,
                                 "rotation_deg_per_dot": sorted({round(d["rotationDeg"], 4) for d in dots}),
                                 "retract_deg": cfg.get("retractDeg")}

        # ---- independent pad geometry (Python) vs the plan
        pads = pads_from_board(board_file)
        geo = []
        for d in dots:
            bx, by = pads[d["padId"]]
            gx, gy = to_global(bx, by)
            geo.append(math.hypot(gx - d["x"], gy - d["y"]))
        res["plan_vs_python_geometry_max_mm"] = max(geo)

        # ---- OpenPnP's own N2 move to every pad (Scripts menu, il_n2_reference.js)
        dz = dots[0]["dispenseZ"]
        inp = dict(BOARD_LOC, boardFile=board_file, nozzle=cfg["nozzle"], dispenseZ=dz,
                   pads=[{"padId": d["padId"], "bx": pads[d["padId"]][0], "by": pads[d["padId"]][1],
                          "dz": d["dispenseZ"]} for d in dots])
        sdir = os.path.join(s.cfg, "scripts")
        outp = os.path.join(sdir, "il_n2_reference.output.json")
        with open(os.path.join(sdir, "il_n2_reference.input.json"), "w") as f:
            json.dump(inp, f)
        if os.path.exists(outp):
            os.remove(outp)
        if not s.api("GET", "/api/status")["machine"]["homed"]:
            s.api("POST", "/api/machine/home", {"wait": True}, timeout=180)
        m2 = s.api("GET", "/api/machine")
        ax2 = {x["letter"]: x["driverCoordinate"] for x in m2["axes"] if x.get("letter")}
        rstart = {k: float(ax2.get(k, 0.0)) for k in ("X", "Y", "Z", "B")}
        n1 = s.mark()
        s.ui.act("menu", menu="Scripts>il_n2_reference.js", timeout_ms=30000)
        for _ in range(1200):
            if os.path.exists(outp):
                try:
                    ref = json.load(open(outp))
                    if ref.get("done"):
                        break
                except ValueError:
                    pass
            time.sleep(0.5)
        else:
            raise RuntimeError("reference script did not finish; dialogs: %s" % s.dialog_texts())
        ref_lines = s.sent_since(n1)
        with open(os.path.join(a.out, "board-reference-captured.gcode"), "w") as f:
            f.write("\n".join(ref_lines) + "\n")
        refpos = {}
        for _, l, pos in modal_walk(ref_lines, rstart):
            mm = re.match(r"^M118 P(\d+)$", strip(l))
            if mm:
                refpos[int(mm.group(1)) - 1] = pos
        res["reference_markers"] = len(refpos)
        cmp_rows = []
        for k, (d, f) in enumerate(zip(dots, board_feeds)):
            rp = refpos.get(k)
            rs = ref["pads"][k]
            row = {"index": d["index"], "padId": d["padId"],
                   "plan_xy": [d["x"], d["y"]], "python_global_xy": list(to_global(*pads[d["padId"]])),
                   "openpnp_utils2d_xy": [rs["gx"], rs["gy"]],
                   "engine_raw": [f["x"], f["y"], f["z"]], "openpnp_raw": [rp["X"], rp["Y"], rp["Z"]] if rp else None,
                   "engine_lift_raw_z": f.get("lift_z")}
            if rp:
                row["dxy_mm"] = math.hypot(f["x"] - rp["X"], f["y"] - rp["Y"])
                row["dz_mm"] = abs(f["z"] - rp["Z"])
            row["utils2d_vs_python_mm"] = math.hypot(rs["gx"] - row["python_global_xy"][0], rs["gy"] - row["python_global_xy"][1])
            cmp_rows.append(row)
        with open(os.path.join(a.out, "board-dot-comparison.json"), "w") as f:
            json.dump(cmp_rows, f, indent=1)
        dxy = [r["dxy_mm"] for r in cmp_rows if "dxy_mm" in r]
        dzz = [r["dz_mm"] for r in cmp_rows if "dz_mm" in r]
        res["xy_check"] = {"pads_compared": len(dxy), "max_dxy_mm": max(dxy) if dxy else None,
                           "max_dz_mm": max(dzz) if dzz else None,
                           "max_utils2d_vs_python_mm": max(r["utils2d_vs_python_mm"] for r in cmp_rows),
                           "within_0_01": bool(dxy) and len(dxy) == len(dots) and max(dxy) <= 0.01 and max(dzz) <= 0.01}
        # see-saw: z2 = 63 - z1 (machine.xml ReferenceMappedAxis); raw Z of the needle. Every dot has
        # its own Z from the probed board plane (I4), so the check is per dot.
        dz_err, hop_err, ref_err = [], [], []
        for k, (d, f) in enumerate(zip(dots, board_feeds)):
            dz_err.append(abs(f["z"] - (63.0 - d["dispenseZ"])))
            hop_err.append(abs(f.get("lift_z", float("nan")) - (63.0 - d["hopZ"])))
            if refpos.get(k):
                ref_err.append(abs(f["z"] - refpos[k]["Z"]))
        raw = [f["z"] for f in board_feeds]
        res["z_check"] = {"board_location_z": BOARD_LOC["z"], "needle_frame": NEEDLE_FRAME,
                          "needle_offset": cfg["needleZOffsetMm"], "gap": cfg["needleGapMm"],
                          "plan_surface_z_range": [min(d["surfaceZ"] for d in dots), max(d["surfaceZ"] for d in dots)],
                          "engine_raw_dispense_z_range": [min(raw), max(raw)],
                          "bench_raw_dispense_z_range_board_plan_r2": [52.244, 52.461],
                          "max_err_vs_expected_raw_dispense_z": max(dz_err), "max_err_vs_expected_raw_hop_z": max(hop_err),
                          "max_err_vs_openpnp_raw_z": max(ref_err) if ref_err else None,
                          "openpnp_compared": len(ref_err)}
        zc = res["z_check"]
        zc["pass"] = (zc["max_err_vs_expected_raw_dispense_z"] <= 1e-3 and zc["max_err_vs_expected_raw_hop_z"] <= 1e-3
                      and zc["openpnp_compared"] == len(dots) and zc["max_err_vs_openpnp_raw_z"] <= 0.01
                      and 52.0 < min(raw) and max(raw) < 52.8)
        o = res["order"]
        res["pass"] = (res["run"]["status"] == "OK" and res["run"]["dotsDone"] == 160 and res["board_dots_streamed"] == 160
                       and res["xy_check"]["within_0_01"] and zc["pass"] and o["pump_then_valve_on"]
                       and o["valve_then_pump_off"] and o["g92_b0_before_first_b"]
                       and o["M400_between_first_and_last_dot"] == 0 and res["rotation_check"]["max_abs_err_deg"] < 1e-3)
        res["dph"] = estimate_dph(a.patched, streamed, cfg)
    finally:
        s.stop()
    return res


# --------------------------------------------------------------------------- 2c: DPH estimate

def live_limits(machine_xml):
    root = ET.parse(machine_xml).getroot()
    feed, acc = {}, {}
    for axis in root.iter("axis"):
        letter = axis.get("letter")
        if letter in ("X", "Y", "Z") and axis.get("class", "").endswith("ReferenceControllerAxis"):
            feed[letter] = float(axis.find("feedrate-per-second").get("value"))
            acc[letter] = float(axis.find("acceleration-per-second-2").get("value"))
    return feed, acc


def estimate_dph(machine_xml, streamed, cfg, bench_rotation_deg=28.0):
    """Task 2c: real-machine time and DPH from the streamed board program.

    Limits: X/Y/Z feed and acceleration from machine.xml (200 mm/s, 1000 mm/s^2 live); B from the
    engine config, which the stream itself carries (M201 B / M203 B); steps/unit and MINIMAL_STEP_RATE
    of the stock rev05 firmware. Variants are text transforms of the same stream (NOT what the
    engine sends today); every variant keeps the original line index for the per-dot tags.
    """
    feed, acc = live_limits(machine_xml)
    limits = {"maxFeed": dict(feed), "maxAccel": dict(acc), "travelAccel": acc["X"],
              "stepsPerUnit": {"X": 320.0, "Y": 320.0, "Z": 40.0, "B": 4.44}}
    feeds = engine_dots(streamed, {"X": 0.0, "Y": 0.0, "Z": 0.0, "B": 0.0})
    board_feeds = feeds[2:]  # prime, test dot, then the board
    last = board_feeds[-1]["lift_line"]
    # dot k = the lines after dot k-1's lift through dot k's lift; dot 0 starts after the test dot's
    # lift, so it also carries the travel from the waste location to the board (reported apart)
    bounds = [f["lift_line"] for f in board_feeds]
    begin = feeds[1]["lift_line"] + 1

    def tag_of(i):
        if i < begin:
            return "session-start"
        if i > last:
            return "session-end"
        lo, hi = 0, len(bounds) - 1
        while lo < hi:
            mid = (lo + hi) // 2
            if i <= bounds[mid]:
                hi = mid
            else:
                lo = mid + 1
        return lo

    def run(pairs, rt):
        lines = [l for _, l in pairs]
        r = dph_estimate.simulate(lines, limits, rt_ms=rt, tag_fn=lambda i, _l: tag_of(pairs[i][0]))
        dots = [v for k, v in r["tags"].items() if isinstance(k, int) and k > 0]
        n_all = sum(1 for k in r["tags"] if isinstance(k, int))
        tot = sum(d["total"] for d in dots)
        br = {}
        for d in dots:
            for c, v in d.items():
                if c != "total":
                    br[c] = br.get(c, 0.0) + v
        n = len(dots)
        return {"steady_state_dots": n, "seconds_per_dot": round(tot / n, 4), "dph": round(3600 * n / tot),
                "per_dot_breakdown_s": {k: round(v / n, 4) for k, v in sorted(br.items())},
                "dot0_seconds_incl_travel_from_waste": round(r["tags"][0]["total"], 3),
                "board_dots": n_all, "board_total_seconds_incl_session": round(r["seconds"], 2),
                "dph_board_incl_session": round(3600 * n_all / r["seconds"]),
                "session_overhead_s": {k: round(v["total"], 3) for k, v in r["tags"].items() if not isinstance(k, int)}}

    def is_b_only(l):
        c = strip(l).upper()
        return bool(MOTION.match(c)) and bool(re.search(r"\bB-?\d", c)) and not re.search(r"\b[XYZ]-?\d", c)

    base = list(enumerate(streamed))
    rot = sorted({round(f["b_to"] - f["b_from"] - cfg["retractDeg"], 4) for f in board_feeds})
    out = {"model": "tools/instancelabs/dph_estimate.py (Marlin-like trapezoids, junction deviation 0.013 mm, "
                    "G4/M400 drain, MINIMAL_STEP_RATE 120 steps/s, host round trip rt per line)",
           "limits": limits, "board_dots": len(board_feeds), "rotation_per_dot_deg": rot,
           "engine": {k: cfg.get(k) for k in ("travelFeedMmS", "travelAccelMmS2", "zDownFeedMmS", "zLiftFeedMmS",
                                              "zLiftAccelMmS2", "bFeedDegS", "bAccelDegS2", "bMaxFeedDegS",
                                              "retractDeg", "retractFeedDegS", "dwellMs", "hopHeightMm",
                                              "pumpToValveSettleMs", "valveSettleMs", "primeRotationDeg")}}
    for rt in (0.0, 1.5, 3.0):
        out["as_streamed_rt%.1fms" % rt] = run(base, rt)

    # (1) B moves at the engine's bAccelDegS2: Marlin accelerates a B-only move at the current M204 S
    #     (travel acceleration), M201 B is only a cap (planner.cpp:2504 + LIMIT_ACCEL); the engine
    #     sends M204 S<travelAccel>, so today B runs at 1000 deg/s^2, not bAccelDegS2.
    b_acc = "M204 S%.0f" % cfg["bAccelDegS2"]
    t_acc = "M204 S%.0f" % cfg["travelAccelMmS2"]
    v1 = []
    for i, l in base:
        if is_b_only(l):
            v1 += [(i, b_acc), (i, l), (i, t_acc)]
        else:
            v1.append((i, l))
    out["whatif_B_at_bAccel_rt1.5ms"] = run(v1, 1.5)
    # (2) + dispense lifts at the travel acceleration instead of zLiftAccelMmS2 (plan §10 item 9 lowered it)
    lift = "M204 S%.0f" % cfg["zLiftAccelMmS2"]
    v2 = [(i, t_acc if strip(l) == lift else l) for i, l in v1]
    out["whatif_B_at_bAccel_and_lift_at_travelAccel_rt1.5ms"] = run(v2, 1.5)

    # (3) the bench rotation per dot instead of the provisional calibration (tools/disp.py fed 38 deg
    #     with a 10 deg suck-back, i.e. 28 deg net per dot; the engine's net rotation is per dot)
    def with_rotation(pairs, new_rot):
        out_p, b_old, b_new = [], 0.0, 0.0
        for i, l in pairs:
            c = strip(l).upper()
            if c.startswith("G92") and re.search(r"\bB-?\d", c):
                b_old = b_new = float(re.search(r"\bB(-?\d+(?:\.\d*)?)", c).group(1))
                out_p.append((i, l))
                continue
            m = re.search(r"\bB(-?\d+(?:\.\d*)?)", c) if MOTION.match(c) else None
            if not m:
                out_p.append((i, l))
                continue
            b = float(m.group(1))
            d = b - b_old
            board_feed = d > 0 and any(abs(d - (r + cfg["retractDeg"])) < 1e-3 for r in rot) and i >= begin
            b_new += (new_rot + cfg["retractDeg"]) if board_feed else d
            b_old = b
            out_p.append((i, re.sub(r"\bB-?\d+(?:\.\d*)?", "B%.4f" % b_new, l, count=1)))
        return out_p
    v3 = with_rotation(base, bench_rotation_deg)
    out["whatif_rotation_%gdeg_rt1.5ms" % bench_rotation_deg] = run(v3, 1.5)
    v4 = with_rotation(v2, bench_rotation_deg)
    out["whatif_rotation_%gdeg_B_at_bAccel_lift_at_travelAccel_rt1.5ms" % bench_rotation_deg] = run(v4, 1.5)
    return out


def bench_validation():
    runs = ["/home/lumen/instancelabs/runs/board_b1", "/home/lumen/instancelabs/runs/board_b2",
            "/home/lumen/instancelabs/runs/board_b3"]
    out = {}
    for rt in (0.0, 1.5):
        rows = []
        for run in runs:
            if not os.path.exists(os.path.join(run, "dots.csv")):
                continue
            pre, lines, tags = dph_estimate.bench_lines(run)
            meas = json.load(open(os.path.join(run, "timing.json")))
            r = dph_estimate.simulate(pre + lines, rt_ms=rt)
            rows.append({"run": os.path.basename(run), "dots": meas["n"], "measured_s": round(meas["seconds"], 3),
                         "predicted_s": round(r["seconds"], 3),
                         "error_pct": round(100 * (r["seconds"] - meas["seconds"]) / meas["seconds"], 2),
                         "measured_dph": round(meas["dph"]), "predicted_dph": round(3600 * meas["n"] / r["seconds"])})
        out["rt%.1fms" % rt] = rows
    return out


# --------------------------------------------------------------------------- main

def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("what", choices=["all", "load", "stop", "board", "bench", "dph"])
    ap.add_argument("--stream", help="dph: a saved board-run-streamed.gcode")
    for k, v in DEF.items():
        ap.add_argument("--" + k.replace("_", "-"), dest=k, default=v)
    ap.add_argument("--prefix", default="il-acc-", help="uinav session name prefix")
    a = ap.parse_args(argv)
    os.makedirs(a.out, exist_ok=True)
    live_before = {f: os.stat(os.path.join(a.live, f)).st_mtime for f in ("machine.xml", "packages.xml", "parts.xml", "boards.xml")}
    results = {"jar": a.jar, "patched": a.patched, "dispense_config": a.dispense_config, "board": a.board,
               "sim_only_changes": ["head visual-homing-method=None (uinav --xml-set; not in the load scenario)",
                                    "waste location %s (web API)" % WASTE,
                                    "needle Z frame %s and its offset confirmed (web API; I4)" % NEEDLE_FRAME,
                                    "board surface: runs/zmap.json + 4 corners on its plane (web API; I4)",
                                    "testDotOpticalCheck=false (web API)",
                                    "chip-0603 status characterized (web API; §5.7 rule)"],
               "started": time.strftime("%Y-%m-%d %H:%M:%S")}
    rpath = os.path.join(a.out, "results.json")

    def save():
        with open(rpath, "w") as f:
            json.dump(results, f, indent=1)

    if a.what == "dph":
        cfg = json.load(open(a.dispense_config))
        print(json.dumps({"dph": estimate_dph(a.patched, open(a.stream).read().splitlines(), cfg),
                          "dph_model_bench_validation": bench_validation()}, indent=1))
        return 0
    if a.what in ("all", "load"):
        results["load"] = scenario_load(a)
        log("load", "PASS" if results["load"]["pass"] else "FAIL")
        save()
    if a.what in ("all", "stop"):
        results["stop"] = []
        scenario_stop(a, results["stop"], save)
    if a.what in ("all", "board"):
        results["board"] = scenario_board(a)
        log("board", "PASS" if results["board"]["pass"] else "FAIL")
        save()
    if a.what in ("all", "board", "bench"):
        results["dph_model_bench_validation"] = bench_validation()
        save()
    live_after = {f: os.stat(os.path.join(a.live, f)).st_mtime for f in live_before}
    results["live_config_untouched"] = live_before == live_after
    results["finished"] = time.strftime("%Y-%m-%d %H:%M:%S")
    save()
    print(rpath)


if __name__ == "__main__":
    sys.exit(main())
