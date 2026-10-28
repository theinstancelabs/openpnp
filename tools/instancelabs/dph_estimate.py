#!/usr/bin/env python3
"""Predict real-machine dispense time from a G-code stream with Marlin-like kinematics.

Model (Marlin 2.1 as built for the LumenPnP rev05 board, il/lumen-rev05 7776c73):
  * each G0/G1 is one planner block; length = |XYZ| if any of X/Y/Z moves, else |B| (degrees),
    as get_move_distance() (Marlin/src/module/motion.cpp:1078);
  * block acceleration = M204 S (travel acceleration) limited per axis by the max acceleration
    (M201, firmware default), as Planner::_populate_block (planner.cpp:2477-2557). So a B-only move
    accelerates at the current M204 S value unless M201 B is lower;
  * nominal speed = F limited per axis by the max feedrate (M203 / firmware default);
  * junction speeds by junction deviation (JUNCTION_DEVIATION_MM 0.013) with look-ahead
    forward/backward passes; G4 and M400 drain the planner (G4.cpp:36 planner.synchronize());
  * trapezoid (constant acceleration, no S-curve: S_CURVE_ACCELERATION is off);
  * host latency: every line costs `rt_ms` of host round trip. Lines overlap motion while the
    planner has work; after a drain (G4 / M400) the controller idles until the next moves arrive:
    motion restarts when 3 moves are queued or the next sync line arrives (planner.cpp:745-750,
    BLOCK_DELAY_FOR_1ST_MOVE).
  * a block starts and ends at >= MINIMAL_STEP_RATE (120 steps/s) on its fastest axis
    (planner.cpp:727, :795-796), e.g. 27 deg/s for B at 4.44 steps/deg;
Not modelled: SLOWDOWN (only stretches segments shorter than 20 ms when 2..7 moves are queued),
step rounding, USB jitter.

Usage:
  dph_estimate.py gcode FILE [--limits machine-limits.json] [--rt-ms 1.5] [--json]
  dph_estimate.py bench RUN_DIR... [--rt-ms 1.5]     # replay tools/disp.py runs and compare with timing.json
"""
import argparse
import csv
import json
import math
import os
import re
import sys

AXES = ("X", "Y", "Z", "B")
JD_MM = 0.013
MIN_PLANNER_SPEED = 0.05

# Firmware defaults of il/lumen-rev05 (Marlin/Configuration.h:1228,1241,1258-1260)
FW_MAX_FEED = {"X": 1500.0, "Y": 1500.0, "Z": 500.0, "B": 50000.0}
FW_MAX_ACCEL = {"X": 10000.0, "Y": 10000.0, "Z": 10000.0, "B": 10000.0}
FW_TRAVEL_ACCEL = 2500.0
# Steps per unit (Configuration.h:1221) and MINIMAL_STEP_RATE (planner.cpp:727, :795-796): a block
# never starts or ends below 120 steps/s on its fastest-stepping axis.
FW_STEPS = {"X": 320.0, "Y": 320.0, "Z": 40.0, "B": 4.44}
MINIMAL_STEP_RATE = 120.0

WORD = re.compile(r"([A-Z])\s*(-?\d+(?:\.\d*)?)")


def parse(line):
    code = line.split(";", 1)[0].strip().upper()
    if not code:
        return None, {}
    words = WORD.findall(code)
    if not words:
        return None, {}
    cmd = words[0][0] + str(int(float(words[0][1])))
    return cmd, {k: float(v) for k, v in words[1:]}


def trapezoid_time(L, v0, v1, vmax, a):
    """Time for a block of length L from entry v0 to exit v1, cruise <= vmax, accel a."""
    if L <= 0:
        return 0.0
    d1 = max(0.0, (vmax * vmax - v0 * v0) / (2 * a))
    d2 = max(0.0, (vmax * vmax - v1 * v1) / (2 * a))
    if d1 + d2 <= L:
        return (vmax - v0) / a + (vmax - v1) / a + (L - d1 - d2) / vmax
    vp = math.sqrt(max(v0 * v0, v1 * v1, (2 * a * L + v0 * v0 + v1 * v1) / 2))
    return (vp - v0) / a + (vp - v1) / a


class Block:
    __slots__ = ("L", "v", "a", "u", "cat", "line", "tag", "entry", "exit", "t", "vmin")

    def __init__(self, L, v, a, u, cat, line, tag, vmin=0.0):
        self.L, self.v, self.a, self.u, self.cat, self.line, self.tag = L, v, a, u, cat, line, tag
        self.vmin = min(vmin, v)
        self.entry = self.exit = 0.0
        self.t = 0.0


def junction_vmax_sq(prev, cur):
    cos_theta = -sum(p * c for p, c in zip(prev.u, cur.u))
    if cos_theta > 0.999999:
        return MIN_PLANNER_SPEED ** 2
    cos_theta = max(cos_theta, -0.999999)
    sin_d2 = math.sqrt(0.5 * (1.0 - cos_theta))
    a = min(prev.a, cur.a)
    vj2 = a * JD_MM * sin_d2 / (1.0 - sin_d2)
    return min(vj2, prev.v ** 2, cur.v ** 2)


def plan_segment(blocks):
    """Look-ahead over blocks between two drains: entry 0, exit 0, junction-deviation limits."""
    n = len(blocks)
    if not n:
        return
    vj = [0.0] * (n + 1)  # vj[i] = max speed at the start of block i (vj[n] = 0 end)
    for i in range(1, n):
        vj[i] = math.sqrt(junction_vmax_sq(blocks[i - 1], blocks[i]))
    # backward pass
    for i in range(n - 1, -1, -1):
        b = blocks[i]
        vj[i] = min(vj[i], math.sqrt(vj[i + 1] ** 2 + 2 * b.a * b.L))
    # forward pass
    for i in range(n):
        b = blocks[i]
        vj[i + 1] = min(vj[i + 1], math.sqrt(vj[i] ** 2 + 2 * b.a * b.L))
    for i, b in enumerate(blocks):
        b.entry, b.exit = max(vj[i], b.vmin), max(vj[i + 1], b.vmin)
        b.t = trapezoid_time(b.L, b.entry, b.exit, b.v, b.a)


def simulate(lines, limits=None, rt_ms=1.5, tag_fn=None):
    """Return dict with total seconds, per-category seconds and per-tag seconds.

    limits: {"maxFeed": {axis: unit/s}, "maxAccel": {axis: unit/s^2}, "travelAccel": float}
    tag_fn(index, line) -> tag or None (e.g. the dot a line belongs to); tags inherit forward.
    """
    max_feed = dict(FW_MAX_FEED)
    max_accel = dict(FW_MAX_ACCEL)
    steps = dict(FW_STEPS)
    accel = FW_TRAVEL_ACCEL
    if limits:
        max_feed.update(limits.get("maxFeed", {}))
        max_accel.update(limits.get("maxAccel", {}))
        steps.update(limits.get("stepsPerUnit", {}))
        accel = limits.get("travelAccel", accel)
    rt = rt_ms / 1000.0
    pos = {a: 0.0 for a in AXES}
    known = {a: False for a in AXES}
    feed = 1000.0  # mm/s, until an F word
    absolute = True
    cats = {"travel_xy": 0.0, "z": 0.0, "auger_b": 0.0, "mixed": 0.0, "dwell": 0.0, "host_idle": 0.0}
    tags = {}
    tag = None
    segment = []
    seg_lines = []  # (count of lines since the last drain when each block arrived)
    t_total = 0.0
    lines_since_drain = 0
    moves_since_drain = 0
    restart_lines = None  # lines needed after a drain before motion starts

    def add_tag(tg, cat, dt):
        if tg is None:
            return
        d = tags.setdefault(tg, {"total": 0.0})
        d[cat] = d.get(cat, 0.0) + dt
        d["total"] += dt

    def drain(extra_dwell=0.0, sync_tag=None):
        nonlocal segment, t_total, lines_since_drain, moves_since_drain, restart_lines
        plan_segment(segment)
        # host idle before motion restarts after the previous drain
        if segment and restart_lines is not None:
            idle = restart_lines * rt
            cats["host_idle"] += idle
            add_tag(segment[0].tag, "host_idle", idle)
            t_total += idle
        for b in segment:
            cats[b.cat] += b.t
            add_tag(b.tag, b.cat, b.t)
            t_total += b.t
        # the sync line's own round trip (its ok) + the dwell
        cats["dwell"] += extra_dwell
        add_tag(sync_tag, "dwell", extra_dwell)
        t_total += extra_dwell + rt
        cats["host_idle"] += rt
        add_tag(sync_tag, "host_idle", rt)
        segment = []
        lines_since_drain = 0
        moves_since_drain = 0
        restart_lines = None

    for i, raw in enumerate(lines):
        if tag_fn is not None:
            tg = tag_fn(i, raw)
            if tg is not None:
                tag = tg
        if raw.strip() == "; @t0":
            # clock start (e.g. the host timer of a measured run): settle what came before, zero
            if segment:
                drain(0.0, tag)
            t_total = 0.0
            for k in cats:
                cats[k] = 0.0
            tags.clear()
            lines_since_drain = 0
            moves_since_drain = 0
            restart_lines = None
            continue
        cmd, w = parse(raw)
        if cmd is None:
            continue
        lines_since_drain += 1
        if cmd in ("G0", "G1"):
            if "F" in w:
                feed = w["F"] / 60.0
            target = dict(pos)
            for a in AXES:
                if a in w:
                    target[a] = w[a] if absolute else pos[a] + w[a]
            d = {a: target[a] - pos[a] if known[a] else 0.0 for a in AXES}
            for a in AXES:
                if a in w:
                    known[a] = True
            pos = target
            lin = math.sqrt(d["X"] ** 2 + d["Y"] ** 2 + d["Z"] ** 2)
            L = lin if lin > 1e-9 else abs(d["B"])
            if L < 1e-9:
                continue
            u = tuple(d[a] / L for a in AXES)
            v = feed
            acc = accel
            for k, a in enumerate(AXES):
                ua = abs(u[k])
                if ua > 0:
                    v = min(v, max_feed[a] / ua)
                    acc = min(acc, max_accel[a] / ua)
            moved = [a for a in AXES if abs(d[a]) > 1e-9]
            if set(moved) <= {"X", "Y"}:
                cat = "travel_xy"
            elif moved == ["Z"]:
                cat = "z"
            elif moved == ["B"]:
                cat = "auger_b"
            else:
                cat = "mixed"
            vmin = MINIMAL_STEP_RATE / max(steps[a] * abs(u[k]) for k, a in enumerate(AXES))
            segment.append(Block(L, v, acc, u, cat, raw, tag, vmin))
            moves_since_drain += 1
            if restart_lines is None and moves_since_drain >= 3:
                restart_lines = lines_since_drain
        elif cmd == "G4":
            dwell = w.get("P", 0.0) / 1000.0 + w.get("S", 0.0)
            if restart_lines is None:
                restart_lines = lines_since_drain
            drain(dwell, tag)
        elif cmd in ("M400", "M410"):
            if restart_lines is None:
                restart_lines = lines_since_drain
            drain(0.0, tag)
        elif cmd == "M204":
            if "S" in w:
                accel = w["S"]
            if "T" in w:
                accel = w["T"]
        elif cmd == "M201":
            for a in AXES:
                if a in w:
                    max_accel[a] = w[a]
        elif cmd == "M203":
            for a in AXES:
                if a in w:
                    max_feed[a] = w[a]
        elif cmd == "G92":
            for a in AXES:
                if a in w:
                    pos[a] = w[a]
                    known[a] = True
        elif cmd == "G90":
            absolute = True
        elif cmd == "G91":
            absolute = False
    if segment:
        if restart_lines is None:
            restart_lines = lines_since_drain
        drain(0.0, tag)
    return {"seconds": t_total, "categories": cats, "tags": tags}


# --------------------------------------------------------------------------- bench replay

def bench_lines(run_dir):
    """Rebuild the exact stream tools/disp.py sent (2026-10-27 version) from its dots.csv."""
    OFF = (24.351, -76.141)
    RETRACT, DWELL, DZ = 10.0, 100, 1.0
    rows = list(csv.DictReader(open(os.path.join(run_dir, "dots.csv"))))
    first = rows[0]
    pre = ["M204 S2000", "M201 B10000",
           "G92 X%.3f Y%.3f Z%.3f B0" % (float(first["x"]) - OFF[0], float(first["y"]) - OFF[1],
                                       float(first["z"]) - DZ),
           "; @t0"]
    lines = []
    tags = []
    for r in rows:
        x, y, z = float(r["x"]), float(r["y"]), float(r["z"])
        th, b = float(r["theta"]), float(r["b_start"])
        dot = ["G1 X%.3f Y%.3f F12000" % (x - OFF[0], y - OFF[1]),
               "G1 Z%.3f F3000" % z,
               "G1 B%.2f F43200" % (b + th),
               "G4 P%d" % DWELL,
               "G1 B%.2f F43200" % (b + th - RETRACT),
               "G1 Z%.3f F3000" % (z - DZ)]
        lines += dot
        tags += [int(r["i"])] * len(dot)
    lines.append("M400")
    tags.append(tags[-1])
    return pre, lines, tags


def cmd_bench(a):
    out = []
    for run in a.runs:
        pre, lines, tags = bench_lines(run)
        meas = json.load(open(os.path.join(run, "timing.json")))
        res = simulate(pre + lines, rt_ms=a.rt_ms, tag_fn=lambda i, l, n=len(pre): tags[i - n] if i >= n else None)
        n = meas["n"]
        out.append({"run": os.path.basename(run.rstrip("/")), "dots": n, "measured_s": round(meas["seconds"], 3),
                    "predicted_s": round(res["seconds"], 3),
                    "error_pct": round(100 * (res["seconds"] - meas["seconds"]) / meas["seconds"], 2),
                    "measured_dph": round(meas["dph"]), "predicted_dph": round(3600 * n / res["seconds"]),
                    "categories_s": {k: round(v, 3) for k, v in res["categories"].items()}})
    print(json.dumps({"rt_ms": a.rt_ms, "runs": out}, indent=2))


def cmd_gcode(a):
    lines = open(a.file).read().splitlines()
    limits = json.load(open(a.limits)) if a.limits else None
    res = simulate(lines, limits, rt_ms=a.rt_ms)
    if a.json:
        print(json.dumps(res, indent=2))
    else:
        print("%.3f s" % res["seconds"])
        for k, v in res["categories"].items():
            print("  %-10s %8.3f s" % (k, v))


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    p = sub.add_parser("gcode")
    p.add_argument("file")
    p.add_argument("--limits")
    p.add_argument("--rt-ms", type=float, default=1.5)
    p.add_argument("--json", action="store_true")
    p = sub.add_parser("bench")
    p.add_argument("runs", nargs="+")
    p.add_argument("--rt-ms", type=float, default=1.5)
    a = ap.parse_args(argv)
    {"gcode": cmd_gcode, "bench": cmd_bench}[a.cmd](a)


if __name__ == "__main__":
    sys.exit(main())
