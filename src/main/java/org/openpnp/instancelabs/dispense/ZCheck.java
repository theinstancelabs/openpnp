package org.openpnp.instancelabs.dispense;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Z after a home (I4). A home can move the Z zero: it moved about 0.7 mm across an OpenPnP home on
 * 2026-10-28, board 2 then probed 0.85 mm low and the needle moved about twice the shift (the see-saw
 * maps z2 = 63 - z1, so the needle relation moves by 2 x the zero error). So Z data measured before
 * the last home (the needle frame, the touch-off, the surfaces a task uses) are accepted only after
 * one N1 touch at a stored point agrees within {@link #TOLERANCE_MM}. Pure; no OpenPnP state.
 */
public final class ZCheck {
    private ZCheck() {
    }

    /**
     * Largest |touch - stored| that passes: just over one 0.025 mm fine step (the bench re-probe of
     * board 2 at 3 stored spots agreed within 0.025 mm); the needle then moves at most ~0.06 mm.
     */
    public static final double TOLERANCE_MM = 0.03;
    /** Name of the needle anchor's spot as a reference. */
    public static final String NEEDLE_FRAME = "needle frame";

    /** A stored N1 contact that can be touched again. */
    public static final class Reference {
        /** Surface name ("board", "blank") or {@link #NEEDLE_FRAME}. */
        public String name;
        /** Index of the point in its surface (0-based), -1 for the needle frame. */
        public int point;
        /** N1 tip machine X/Y and its stored contact Z. */
        public double x, y, z;
        /** When it was measured. */
        public Long atMs;
    }

    /** The latest Z check (memory only). */
    public static final class Result {
        public long atMs;
        /** The home this check is for. */
        public Long homeAtMs;
        public String refName;
        public double x, y, refZ, z, deltaMm;
        public boolean pass;
        /** When the reference was measured. */
        public Long refAtMs;
    }

    /** A Z datum of a task's scope, with the time it was measured. */
    public static final class Stale {
        public String what;
        public Long atMs;

        public Stale(String what, Long atMs) {
            this.what = what;
            this.atMs = atMs;
        }
    }

    /**
     * Stored N1 contacts measured before {@code lastHomeAtMs} (all of them when null), in this order:
     * the points of the board surface when it is tied to the plan's registration (its spots were
     * chosen bare), the points of the blank surface, the needle anchor's X/Y when they are set. A
     * surface probed after the home is in the new frame already and is no reference.
     */
    public static List<Reference> references(DispenseConfig cfg, String planBoardRegistration, Long lastHomeAtMs) {
        List<Reference> l = new ArrayList<>();
        DispenseConfig.SurfaceMap board = cfg.surface(cfg.boardSurfaceName);
        if (board != null && board.boardFingerprint != null && board.boardFingerprint.equals(planBoardRegistration)) {
            addPoints(l, cfg.boardSurfaceName, board, lastHomeAtMs);
        }
        if (!Objects.equals(cfg.blankLocationName, cfg.boardSurfaceName)) {
            addPoints(l, cfg.blankLocationName, cfg.surface(cfg.blankLocationName), lastHomeAtMs);
        }
        DispenseConfig.NeedleAnchor a = cfg.needleAnchor;
        if (a != null && a.xMm != null && a.yMm != null && before(a.setAtMs, lastHomeAtMs)) {
            Reference r = new Reference();
            r.name = NEEDLE_FRAME;
            r.point = -1;
            r.x = a.xMm;
            r.y = a.yMm;
            r.z = a.surfaceZMm;
            r.atMs = a.setAtMs;
            l.add(r);
        }
        return l;
    }

    private static void addPoints(List<Reference> l, String name, DispenseConfig.SurfaceMap m, Long home) {
        if (m == null || m.points == null || !before(m.setAtMs, home)) {
            return;
        }
        for (int i = 0; i < m.points.size(); i++) {
            DispenseConfig.SurfacePoint p = m.points.get(i);
            Reference r = new Reference();
            r.name = name;
            r.point = i;
            r.x = p.x;
            r.y = p.y;
            r.z = p.z;
            r.atMs = m.setAtMs;
            l.add(r);
        }
    }

    /** Measured before the home (a datum without a time counts as before). */
    private static boolean before(Long atMs, Long homeAtMs) {
        return homeAtMs == null || atMs == null || atMs < homeAtMs;
    }

    /** Pass iff |z - ref.z| <= {@link #TOLERANCE_MM}. */
    public static boolean verdict(Reference ref, double z) {
        return Math.abs(z - ref.z) <= TOLERANCE_MM + 1e-9;
    }

    /** The check result of touching {@code ref} at Z {@code z}. */
    public static Result result(Reference ref, double z, long atMs, Long homeAtMs) {
        Result r = new Result();
        r.atMs = atMs;
        r.homeAtMs = homeAtMs;
        r.refName = ref.name;
        r.x = ref.x;
        r.y = ref.y;
        r.refZ = ref.z;
        r.z = z;
        r.deltaMm = z - ref.z;
        r.pass = verdict(ref, z);
        r.refAtMs = ref.atMs;
        return r;
    }

    /** True when {@code check} is a passing check for this home. */
    public static boolean accepts(Result check, Long lastHomeAtMs) {
        return check != null && check.pass && lastHomeAtMs != null && lastHomeAtMs.equals(check.homeAtMs);
    }

    /** True when {@code check} is a failed check for this home. */
    public static boolean failed(Result check, Long lastHomeAtMs) {
        return check != null && !check.pass && lastHomeAtMs != null && lastHomeAtMs.equals(check.homeAtMs);
    }

    /**
     * The Z data of a scope measured before the home: the needle frame, the touch-off, and every
     * surface the scope uses ({@code surfaceName} and {@code moreSurfaces}). Places carry no time;
     * the same check covers them. Empty without a home.
     */
    public static List<Stale> stale(DispenseConfig cfg, Interlocks.Scope sc, Long lastHomeAtMs) {
        List<Stale> l = new ArrayList<>();
        if (lastHomeAtMs == null) {
            return l;
        }
        if (cfg.needleAnchor != null && cfg.needleAnchor.setAtMs < lastHomeAtMs) {
            l.add(new Stale("the needle frame", cfg.needleAnchor.setAtMs));
        }
        if (cfg.needleZOffsetSetAtMs != null && cfg.needleZOffsetSetAtMs < lastHomeAtMs) {
            l.add(new Stale("the touch-off", cfg.needleZOffsetSetAtMs));
        }
        if (sc != null) {
            List<String> names = new ArrayList<>();
            if (sc.surfaceName != null) {
                names.add(sc.surfaceName);
            }
            for (Map.Entry<String, List<double[]>> e : sc.moreSurfaces.entrySet()) {
                if (!names.contains(e.getKey())) {
                    names.add(e.getKey());
                }
            }
            for (String n : names) {
                DispenseConfig.SurfaceMap m = cfg.surface(n);
                if (m != null && (m.setAtMs == null || m.setAtMs < lastHomeAtMs)) {
                    l.add(new Stale("the '" + n + "' surface", m.setAtMs));
                }
            }
        }
        return l;
    }

    /** I4 note when the task checks Z itself (the code "I4" included). */
    public static String checksFirstNote(Long lastHomeAtMs, boolean homesFirst) {
        if (homesFirst || lastHomeAtMs == null) {
            return "I4: homes first, then checks Z (one N1 touch)";
        }
        return "I4: Z data predate the home at " + clock(lastHomeAtMs) + ": checks Z first (one N1 touch)";
    }

    /** I4 violation (without the code): stale Z data and no passing check since the home. */
    public static String notCheckedMessage(List<Stale> stale, long lastHomeAtMs) {
        List<String> parts = new ArrayList<>();
        for (Stale s : stale) {
            parts.add(s.what + (s.atMs == null ? "" : " (" + clock(s.atMs) + ")"));
        }
        return join(parts) + (parts.size() == 1 ? " was" : " were") + " measured before the home at "
                + clock(lastHomeAtMs) + ", and Z has not been checked since: Check Z (one N1 touch, ≈ 20 s); "
                + "Run does it by itself";
    }

    /** I4 violation and Z-check refusal (without the code): the check after this home failed. */
    public static String movedMessage(Result r) {
        return String.format(Locale.ROOT, "Z moved %.2f mm at the home of %s: N1 touched %s at X %.2f, Y %.2f at Z "
                + "%.3f, where it touched %.3f%s. The needle frame, the touch-off and the surfaces are off by this "
                + "(the needle by about twice). Record the needle frame again (Setup › Needle Z frame), touch "
                + "off, probe the blank and register the board again.", r.deltaMm,
                r.homeAtMs == null ? "?" : clock(r.homeAtMs), describe(r.refName), r.x, r.y, r.z, r.refZ,
                r.refAtMs == null ? "" : " at " + clock(r.refAtMs));
    }

    /** Refusal (without the code): Z must be checked but nothing measured before the home is left. */
    public static String noReferenceMessage(Long lastHomeAtMs) {
        return "Z must be checked after the home at " + (lastHomeAtMs == null ? "?" : clock(lastHomeAtMs))
                + ", but no point measured before it is left to touch (no blank or board surface, no needle-frame "
                + "X/Y): record the needle frame again";
    }

    /** "the blank", "the board", "the needle-frame spot". */
    public static String describe(String refName) {
        return NEEDLE_FRAME.equals(refName) ? "the needle-frame spot" : "the " + refName;
    }

    private static String join(List<String> parts) {
        if (parts.size() <= 1) {
            return parts.isEmpty() ? "" : parts.get(0);
        }
        return String.join(", ", parts.subList(0, parts.size() - 1)) + " and " + parts.get(parts.size() - 1);
    }

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);
    private static final DateTimeFormatter DAY_CLOCK = DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.ROOT);

    /** Local wall-clock time of an epoch-ms instant, "14:19" (the texts' times). */
    public static String clock(long epochMs) {
        return CLOCK.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()));
    }

    /** "10-28 13:02". */
    public static String dayClock(long epochMs) {
        return DAY_CLOCK.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()));
    }
}
