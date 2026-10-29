#!/usr/bin/env python3
"""Instance Labs SIM scene: where the simulated board truly sits, for the SIM vacuum sensor and top camera.

Writes <configDir>/instancelabs/sim-scene.json (read by org.openpnp.instancelabs.sim.SimScene; format in
docs/instancelabs/AUTO_REGISTER_DESIGN.md §9.2). Only into a config COPY (a uinav SIM session's stage), never
into the live ~/.openpnp2. Fiducials, pads and the board size are read from the board file (placements, its
<dimensions>) and, when a config dir is given, the footprints of its parts.xml / packages.xml: nothing here is
specific to one board, so an imported board works the same way.

Affines are [[a, b, tx], [c, d, ty]], board mm -> machine mm (SimScene's "affine"). The pivot is the board
origin: the board origin lands at (tx, ty).

Python API (tests compute every expected value from the transforms):
  seat(x, y, rot_deg, sx=1, sy=1, shear_deg=0)   T(x, y)·R(rot)·Shear·Scale (Utils2D.affineInfo's order)
  reseat(base, dx, dy, drot_deg)                 a re-seat in the machine frame about base(board origin)
  apply(affine, bx, by), compose(a, b), invert(a)
  fiducials(board_file, config_dir=None)         [{name, bx, by, d, mask}] from type="Fiducial" placements
  pads(board_file, config_dir=None)              {pad id: (bx, by)}: footprint pads, else the part centre
  min_pad_shift(board_file, a, b), moved_mm(board_file, a, b)
  scene(...) -> dict, write(config_dir, scene), board_contact_z(scene, bx, by), blank_contact_z(scene, x, y)
  CAMERA_XML_SETS, FAST_TIMINGS, FAST_VACUUM

CLI:
  il_sim_scene.py write --config DIR --board FILE --x X --y Y --rot DEG --z Z
                        [--dx --dy --drot --sx --sy --shear] [--slope-x --slope-y] [--leaky bx,by[,r]]...
                        [--hide FID]... [--dirty-spot bx,by]... [--blank x0,y0,x1,y1 --blank-z Z
                        --blank-slope-x --blank-slope-y] [--tip normal|missing|blocked|part]
                        [--upp-error ex,ey] [--fast]
  il_sim_scene.py selftest [--board FILE] [--config DIR]
Python 3 standard library only.
"""
import argparse
import json
import math
import os
import re
import sys
import tempfile
import xml.etree.ElementTree as ET

SCENE_REL = os.path.join("instancelabs", "sim-scene.json")
LIVE_CONFIG = os.path.join(os.path.expanduser("~"), ".openpnp2")
# Installs the SIM top camera in a uinav SIM session (uinav first turns Top into an ImageCamera).
CAMERA_XML_SETS = [".//camera[@name='Top']@class=org.openpnp.instancelabs.sim.SimBoardCamera",
                   ".//camera[@name='Top']@width=1920",
                   ".//camera[@name='Top']@height=1080"]
# Engine settings (PUT /api/config) for fast simulated probes: refused on a real machine (spec §4.4).
FAST_TIMINGS = {"vacuumProbe": {"vacuumSettleMs": 300, "readDelayMs": 0}}
# Sensor time constants that go with FAST_TIMINGS: a baseline 300 ms after the valve opens, and reads right
# after M400, would otherwise be read before the simulated vacuum settles (it follows the wall clock).
FAST_VACUUM = {"tauS": 0.03, "sealTauS": 0.0}
FID_DIAMETER = re.compile(r"(?i)fiducial[_-]?(\d+(?:\.\d+)?)mm")
FID_MASK = re.compile(r"(?i)mask(\d+(?:\.\d+)?)mm")
# Bench defaults (SimScene): board plane from BOARD_PROBES_R7, blank from BLANK_PROBES (z0 at the rect corner).
DEFAULT_BLANK = {"rect": [280.0, 225.0, 365.0, 250.0], "surface": {"z0": 5.62, "slopeX": -0.0012, "slopeY": 0.0}}


# --------------------------------------------------------------------------- affines

def seat(x, y, rot_deg, sx=1.0, sy=1.0, shear_deg=0.0):
    """T(x, y)·R(rot)·Shear·Scale: board origin at (x, y); shear is an x-shear (tan(shear) in column 2)."""
    c, s = math.cos(math.radians(rot_deg)), math.sin(math.radians(rot_deg))
    k = math.tan(math.radians(shear_deg))
    return [[c * sx, (c * k - s) * sy, float(x)],
            [s * sx, (s * k + c) * sy, float(y)]]


def compose(a, b):
    """a ∘ b (b first)."""
    return [[a[0][0] * b[0][0] + a[0][1] * b[1][0], a[0][0] * b[0][1] + a[0][1] * b[1][1],
             a[0][0] * b[0][2] + a[0][1] * b[1][2] + a[0][2]],
            [a[1][0] * b[0][0] + a[1][1] * b[1][0], a[1][0] * b[0][1] + a[1][1] * b[1][1],
             a[1][0] * b[0][2] + a[1][1] * b[1][2] + a[1][2]]]


def invert(a):
    det = a[0][0] * a[1][1] - a[0][1] * a[1][0]
    i00, i01, i10, i11 = a[1][1] / det, -a[0][1] / det, -a[1][0] / det, a[0][0] / det
    return [[i00, i01, -(i00 * a[0][2] + i01 * a[1][2])],
            [i10, i11, -(i10 * a[0][2] + i11 * a[1][2])]]


def apply(affine, bx, by):
    return (affine[0][0] * bx + affine[0][1] * by + affine[0][2],
            affine[1][0] * bx + affine[1][1] * by + affine[1][2])


def translation(dx, dy):
    return [[1.0, 0.0, float(dx)], [0.0, 1.0, float(dy)]]


def reseat(base, dx, dy, drot_deg):
    """The board moved in the machine frame: T(base(0) + (dx, dy)) ∘ R(drot) ∘ T(−base(0)) ∘ base."""
    ox, oy = apply(base, 0.0, 0.0)
    return compose(translation(ox + dx, oy + dy),
                   compose(seat(0, 0, drot_deg), compose(translation(-ox, -oy), base)))


# --------------------------------------------------------------------------- board file

def _mm(value, units):
    return float(value) * (25.4 if (units or "Millimeters") == "Inches" else 1.0)


def _location(placement):
    loc = placement.find("location")
    u = loc.get("units")
    return _mm(loc.get("x"), u), _mm(loc.get("y"), u), float(loc.get("rotation") or 0.0)


def _footprints(config_dir):
    """part id -> (package id, [footprint pads {x, y, w, h, roundness} in mm]) from the config's parts/packages."""
    if not config_dir:
        return {}
    parts_xml, packages_xml = os.path.join(config_dir, "parts.xml"), os.path.join(config_dir, "packages.xml")
    if not (os.path.exists(parts_xml) and os.path.exists(packages_xml)):
        return {}
    packages = {}
    for pkg in ET.parse(packages_xml).getroot().iter("package"):
        fp = pkg.find("footprint")
        pads = []
        if fp is not None:
            u = fp.get("units")
            for p in fp.iter("pad"):
                pads.append({"x": _mm(p.get("x", 0), u), "y": _mm(p.get("y", 0), u),
                             "w": _mm(p.get("width", 0), u), "h": _mm(p.get("height", 0), u),
                             "roundness": float(p.get("roundness", 0))})
        packages[pkg.get("id")] = pads
    out = {}
    for part in ET.parse(parts_xml).getroot().iter("part"):
        pid = part.get("package-id")
        out[part.get("id")] = (pid, packages.get(pid, []))
    return out


def board_size(board_file):
    d = ET.parse(board_file).getroot().find("dimensions")
    if d is None:
        return 0.0, 0.0
    return _mm(d.get("x"), d.get("units")), _mm(d.get("y"), d.get("units"))


def fiducials(board_file, config_dir=None):
    """Enabled top-side type="Fiducial" placements: d from the first round footprint pad, else parsed from the
    package (or part) id as Fiducial_1mm_Mask3mm, else 1.0; mask parsed, else 3.0 (SimScene.fiducialsOf)."""
    fps = _footprints(config_dir)
    out = []
    for p in ET.parse(board_file).getroot().iter("placement"):
        if p.get("type") != "Fiducial" or p.get("enabled", "true") == "false" or p.get("side", "Top") != "Top":
            continue
        bx, by, _ = _location(p)
        pkg_id, pads = fps.get(p.get("part-id"), (None, []))
        name = pkg_id or p.get("part-id") or ""
        d = next((q["w"] for q in pads if q["roundness"] >= 100 and abs(q["w"] - q["h"]) < 1e-9 and q["w"] > 0),
                 None)
        if d is None:
            m = FID_DIAMETER.search(name)
            d = float(m.group(1)) if m else 1.0
        m = FID_MASK.search(name)
        out.append({"name": p.get("id"), "bx": bx, "by": by, "d": d, "mask": float(m.group(1)) if m else 3.0})
    return out


def pads(board_file, config_dir=None):
    """{pad id: (bx, by)} for every top-side type="Placement" placement: its footprint pads (config_dir's
    packages.xml), or the part centre when the footprint has none (pad-type packages)."""
    fps = _footprints(config_dir)
    out = {}
    for p in ET.parse(board_file).getroot().iter("placement"):
        if p.get("type") != "Placement" or p.get("side", "Top") != "Top":
            continue
        px, py, rot = _location(p)
        _, fpads = fps.get(p.get("part-id"), (None, []))
        if not fpads:
            out[p.get("id")] = (px, py)
            continue
        c, s = math.cos(math.radians(rot)), math.sin(math.radians(rot))
        for i, q in enumerate(fpads):
            out["%s.%d" % (p.get("id"), i + 1)] = (px + q["x"] * c - q["y"] * s, py + q["x"] * s + q["y"] * c)
    return out


def _shifts(points, a, b):
    out = []
    for bx, by in points:
        ax, ay = apply(a, bx, by)
        cx, cy = apply(b, bx, by)
        out.append(math.hypot(ax - cx, ay - cy))
    return out


def min_pad_shift(board_file, a, b, config_dir=None):
    """Smallest distance any pad moves between seats a and b (how sensitive a test is to the correction)."""
    return min(_shifts(pads(board_file, config_dir).values(), a, b))


def moved_mm(board_file, a, b, config_dir=None):
    """Largest fiducial move between seats a and b (the registration's movedMm)."""
    return max(_shifts([(f["bx"], f["by"]) for f in fiducials(board_file, config_dir)], a, b))


# --------------------------------------------------------------------------- scene

def plane(z0, slope_x=0.0, slope_y=0.0):
    return {"z0": float(z0), "slopeX": float(slope_x), "slopeY": float(slope_y)}


def scene(board_file, seat_affine, board_plane, blank=None, leaky=(), hide=(), dirty=(), dirty_spots=(),
          vacuum=None, camera=None, config_dir=None):
    """The scene dict. board_plane: plane(z0, sx, sy), N1 contact Z in board mm from the board origin.
    blank: {"rect": [x0, y0, x1, y1], "surface": plane(...) from (x0, y0), "dots": [{x, y}]} (machine mm),
    None for the bench default, False for none. leaky: [(bx, by[, r])]; dirty: pad or placement ids."""
    w, h = board_size(board_file)
    sc = {"version": 1,
          "board": {"file": os.path.basename(board_file), "size": [w, h],
                    "seat": {"affine": [list(map(float, r)) for r in seat_affine]},
                    "surface": dict(board_plane),
                    "fiducials": fiducials(board_file, config_dir),
                    "hideFiducials": list(hide), "dirtyPads": list(dirty),
                    "dirtySpots": [{"bx": float(x), "by": float(y)} for x, y in dirty_spots],
                    "leakySpots": [{"bx": float(l[0]), "by": float(l[1]), "r": float(l[2]) if len(l) > 2 else 0.6}
                                   for l in leaky]},
          "vacuum": dict(vacuum or {}),
          "camera": dict(camera or {})}
    if blank is False:
        sc["blank"] = None
    else:
        sc["blank"] = json.loads(json.dumps(blank if blank is not None else DEFAULT_BLANK))
    return sc


def board_contact_z(sc, bx, by):
    s = sc["board"]["surface"]
    return s["z0"] + s.get("slopeX", 0.0) * bx + s.get("slopeY", 0.0) * by


def blank_contact_z(sc, x, y):
    b = sc.get("blank") or DEFAULT_BLANK
    s = b.get("surface", DEFAULT_BLANK["surface"])
    x0, y0 = b.get("rect", DEFAULT_BLANK["rect"])[:2]
    return s["z0"] + s.get("slopeX", 0.0) * (x - x0) + s.get("slopeY", 0.0) * (y - y0)


def scene_path(config_dir):
    return os.path.join(config_dir, SCENE_REL)


def write(config_dir, sc):
    """Atomic (tmp + rename): a running session reloads it on change. Never into the live config."""
    real = os.path.realpath(config_dir)
    live = os.path.realpath(LIVE_CONFIG)
    if real == live or real.startswith(live + os.sep):
        raise SystemExit("il_sim_scene: refusing to write a sim scene into the live config %s" % LIVE_CONFIG)
    path = scene_path(config_dir)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    fd, tmp = tempfile.mkstemp(prefix=".sim-scene.", suffix=".tmp", dir=os.path.dirname(path))
    try:
        with os.fdopen(fd, "w") as f:
            json.dump(sc, f, indent=1)
            f.write("\n")
        os.replace(tmp, path)
    except BaseException:
        if os.path.exists(tmp):
            os.unlink(tmp)
        raise
    return path


# --------------------------------------------------------------------------- CLI

def _floats(text, n_min, n_max=None):
    v = [float(t) for t in text.split(",")]
    if len(v) < n_min or len(v) > (n_max or n_min):
        raise argparse.ArgumentTypeError("expected %d numbers: %s" % (n_min, text))
    return v


def cmd_write(a):
    nominal = seat(a.x, a.y, a.rot, a.sx, a.sy, a.shear)
    true_seat = reseat(nominal, a.dx, a.dy, a.drot)
    blank = None
    if a.blank or a.blank_z is not None:
        rect = _floats(a.blank, 4) if a.blank else DEFAULT_BLANK["rect"]
        z0 = a.blank_z if a.blank_z is not None else DEFAULT_BLANK["surface"]["z0"]
        blank = {"rect": rect, "surface": plane(z0, a.blank_slope_x, a.blank_slope_y)}
    vacuum = dict(FAST_VACUUM) if a.fast else {}
    if a.tip:
        vacuum["tip"] = a.tip
    camera = {"uppError": _floats(a.upp_error, 2)} if a.upp_error else {}
    sc = scene(a.board, true_seat, plane(a.z, a.slope_x, a.slope_y), blank=blank,
               leaky=[_floats(l, 2, 3) for l in a.leaky], hide=a.hide,
               dirty_spots=[_floats(d, 2) for d in a.dirty_spot], vacuum=vacuum, camera=camera, config_dir=a.config)
    path = write(a.config, sc)
    print(json.dumps({"scene": path, "seat": true_seat, "fiducials": len(sc["board"]["fiducials"]),
                      "movedFromNominalMm": round(moved_mm(a.board, seat(a.x, a.y, a.rot), true_seat, a.config), 4)}))


def _close(a, b, tol=1e-9):
    return all(abs(x - y) <= tol for x, y in zip(a, b))


def cmd_selftest(a):
    checks = []

    def check(name, ok):
        checks.append((name, bool(ok)))

    s = seat(10, 20, 90)
    check("seat: origin at (x, y)", _close(apply(s, 0, 0), (10, 20)))
    check("seat: +90 deg turns board +x to machine +y", _close(apply(s, 1, 0), (10, 21)))
    check("seat: x-shear moves +y toward +x", _close(apply(seat(0, 0, 0, 1, 1, 45), 0, 1), (1, 1)))
    check("seat: scale", _close(apply(seat(0, 0, 0, 2, 3), 1, 1), (2, 3)))
    b = seat(296.0, 114.5, -1.6, 1.002, 0.997, 0.1)
    check("invert", _close(apply(compose(invert(b), b), 3.3, 4.4), (3.3, 4.4)))
    r = reseat(b, 0.6, -0.45, 0.0)
    check("reseat: pure shift moves every point by (dx, dy)",
          _close(apply(r, 50, 60), tuple(p + d for p, d in zip(apply(b, 50, 60), (0.6, -0.45)))))
    r = reseat(b, 0.0, 0.0, 0.2)
    check("reseat: turns about the board origin", _close(apply(r, 0, 0), apply(b, 0, 0)))
    bx, by = 70.0, 0.0
    p0, p1 = apply(b, bx, by), apply(r, bx, by)
    o = apply(b, 0, 0)
    ang = math.degrees(math.atan2(p1[1] - o[1], p1[0] - o[0]) - math.atan2(p0[1] - o[1], p0[0] - o[0]))
    check("reseat: by drot", abs(ang - 0.2) < 1e-9)
    check("fiducial name parse", FID_DIAMETER.search("Fiducial_1mm_Mask3mm-Fiducial").group(1) == "1"
          and FID_MASK.search("Fiducial_1mm_Mask3mm").group(1) == "3")
    if a.board:
        f = fiducials(a.board, a.config)
        check("board fiducials found (%d)" % len(f), len(f) >= 2)
        sc = scene(a.board, b, plane(5.43), config_dir=a.config)
        check("scene is JSON", json.loads(json.dumps(sc))["board"]["file"] == os.path.basename(a.board))
        print(json.dumps({"fiducials": f, "size": board_size(a.board), "pads": len(pads(a.board, a.config))}))
    for name, ok in checks:
        print(("PASS " if ok else "FAIL ") + name)
    return 0 if all(ok for _, ok in checks) else 1


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    sub = ap.add_subparsers(dest="cmd")
    w = sub.add_parser("write", help="write the scene into a config copy")
    w.add_argument("--config", required=True, help="the session's config COPY")
    w.add_argument("--board", required=True, help="board file (fiducials, pads and size are read from it)")
    for k in ("x", "y", "rot", "z"):
        w.add_argument("--" + k, type=float, required=True, help="nominal board location (z: N1 contact Z)")
    for k, d in (("dx", 0.0), ("dy", 0.0), ("drot", 0.0), ("sx", 1.0), ("sy", 1.0), ("shear", 0.0),
                 ("slope-x", 0.0), ("slope-y", 0.0), ("blank-slope-x", -0.0012), ("blank-slope-y", 0.0)):
        w.add_argument("--" + k, type=float, default=d)
    w.add_argument("--blank", help="x0,y0,x1,y1 (machine mm)")
    w.add_argument("--blank-z", type=float, help="blank N1 contact Z at (x0, y0)")
    w.add_argument("--leaky", action="append", default=[], help="bx,by[,r] (board mm)")
    w.add_argument("--hide", action="append", default=[], help="fiducial name not drawn")
    w.add_argument("--dirty-spot", action="append", default=[], help="bx,by: a paste dot (board mm)")
    w.add_argument("--tip", choices=["normal", "missing", "blocked", "part"])
    w.add_argument("--upp-error", help="ex,ey: true mm/px = configured x (1 + e)")
    w.add_argument("--fast", action="store_true", help="sensor time constants for FAST_TIMINGS")
    t = sub.add_parser("selftest", help="check the transforms (and a board file)")
    t.add_argument("--board")
    t.add_argument("--config")
    a = ap.parse_args(argv)
    if a.cmd == "write":
        cmd_write(a)
        return 0
    if a.cmd == "selftest":
        return cmd_selftest(a)
    ap.print_help()
    return 2


if __name__ == "__main__":
    sys.exit(main())
