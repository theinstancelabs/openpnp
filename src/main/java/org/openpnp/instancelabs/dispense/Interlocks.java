package org.openpnp.instancelabs.dispense;

import java.util.List;
import java.util.Locale;

/**
 * Pre-flight interlocks (plan §5.5). Runtime interlocks are enforced elsewhere: I1 (outputs off on
 * error/abort/Stop) and I7 (on-time, at runtime) in {@link DispenseExecutor}; I2 (no B move before
 * G92 B0) structurally in {@link GcodeStreamBuilder} and checked by {@link GcodeStreamValidator}.
 */
public final class Interlocks {
    private Interlocks() {
    }

    /** Facts about the machine gathered by the caller (keeps this class free of OpenPnP state). */
    public static class Inputs {
        public DispenseConfig cfg;
        public boolean machineEnabled;
        public boolean machineHomed;
        public String nozzleName;
        /** Names of nozzle tips N2 is configured as compatible with (I3). */
        public List<String> compatibleNozzleTips;
        /** Name of the nozzle tip currently loaded on N2, or null (I3). */
        public String loadedNozzleTip;
        public BarrelPressureSensor pressureSensor;
        public DispensePlan plan;
        /** Program estimate of pressurised time (I7 pre-check). */
        public double estimatedPressurisedSeconds;
        /** I9 optical check possible (top camera + vision present). */
        public boolean opticalCheckAvailable;
        /** What the run touches (I4 Z frame); the default is a generic needle run. */
        public Scope scope = new Scope();
    }

    /** What a run uses, for the I4 Z-frame checks. */
    public static class Scope {
        /** False for runs that never move the needle (the pressure hold): no I4. */
        public boolean needleMotion = true;
        /** The named I4 waiver: the needle touch-off run itself (it sets the touched-off state). */
        public boolean touchOffWaiver;
        /** Places whose Z must agree with the needle frame (waste, blank). */
        public final List<String> referencePlaces = new java.util.ArrayList<>();
        /** Surface map this run takes its dot Z from (board or blank), or null. */
        public String surfaceName;
        /** Machine X/Y of the dots that take their Z from that surface. */
        public final List<double[]> surfaceTargets = new java.util.ArrayList<>();
        /** Plan runs: the plan must have been made on the current surface map. */
        public boolean checkPlanSurface;

        public static Scope noNeedle() {
            Scope s = new Scope();
            s.needleMotion = false;
            return s;
        }
    }

    /**
     * I4 (plan §5.5): needle touched off since the last needle change, and the target inside the
     * probed surface. Review finding: nothing tied place / board Z to the frame the needle offset
     * was measured in. The frame is now anchored by one surface touched by N1 and by the needle;
     * places, surface points and the offset must agree with it, every dot must lie inside its
     * probed surface, and the surface must be flat to within the gap budget.
     */
    static void checkI4(Inputs in, InterlockReport r) {
        DispenseConfig cfg = in.cfg;
        Scope sc = in.scope == null ? new Scope() : in.scope;
        if (!sc.needleMotion) {
            return;
        }
        DispenseConfig.NeedleAnchor a = cfg.needleAnchor;
        if (a == null) {
            r.add("I4", "the needle Z frame is not set: touch the same surface with N1 and with the needle tip and "
                    + "record both heights (Needle card)");
            return;
        }
        if (!cfg.isNeedleAnchored()) {
            r.add("I4", "the needle was changed after the needle Z frame was recorded: record the N1 and needle "
                    + "contact heights again (Needle card)");
            return;
        }
        if (sc.touchOffWaiver) {
            r.notes.add("I4: touch-off run (the touched-off state is what it sets)");
        }
        else if (!cfg.isNeedleTouchedOff()) {
            r.add("I4", "needle Z offset has not been set since the last needle change / needle frame (run a touch-off)");
        }
        double tol = cfg.zFrameToleranceMm;
        double drift = cfg.needleZOffsetMm - a.offsetMm();
        if (Math.abs(drift) > cfg.needleOffsetMaxDriftMm) {
            r.add("I4", String.format(Locale.ROOT, "needle Z offset %.3f mm is %.3f mm away from the needle frame's "
                    + "%.3f mm (limit %.2f): record the needle frame again", cfg.needleZOffsetMm, drift, a.offsetMm(),
                    cfg.needleOffsetMaxDriftMm));
        }
        for (String name : sc.referencePlaces) {
            DispenseConfig.NamedLocation loc = cfg.location(name);
            if (loc == null) {
                continue; // a missing place is refused by the run itself
            }
            double d = loc.z - a.surfaceZMm;
            if (Math.abs(d) > tol) {
                r.add("I4", String.format(Locale.ROOT, "place '%s' Z %.3f is %.3f mm from the needle-frame surface "
                        + "(Z %.3f, tolerance %.2f). Place Z is the N1 contact height (OpenPnP frame); a height "
                        + "taken with the needle differs by about the needle offset (%.2f mm)", name, loc.z, d,
                        a.surfaceZMm, tol, a.offsetMm()));
            }
        }
        if (sc.surfaceName == null) {
            return;
        }
        DispenseConfig.SurfaceMap m = cfg.surface(sc.surfaceName);
        if (m == null || m.points == null || m.points.size() < 3) {
            r.add("I4", "no '" + sc.surfaceName + "' surface: probe at least 3 points around it with N1 (Surfaces card)");
            return;
        }
        SurfacePlane pl;
        try {
            pl = SurfacePlane.fit(m.points);
        }
        catch (IllegalArgumentException e) {
            r.add("I4", "'" + sc.surfaceName + "' surface: " + e.getMessage());
            return;
        }
        if (pl.maxAbsResidualMm > cfg.surfaceMaxResidualMm) {
            r.add("I4", String.format(Locale.ROOT, "'%s' surface is not flat enough for a plane: largest residual "
                    + "%.3f mm, limit %.3f mm (gap budget)", sc.surfaceName, pl.maxAbsResidualMm, cfg.surfaceMaxResidualMm));
        }
        if (pl.minZ < a.surfaceZMm - tol || pl.maxZ > a.surfaceZMm + tol) {
            r.add("I4", String.format(Locale.ROOT, "'%s' surface Z %.3f..%.3f is outside the needle-frame surface "
                    + "%.3f +/- %.2f: surface points are N1 contact heights (OpenPnP frame)", sc.surfaceName, pl.minZ,
                    pl.maxZ, a.surfaceZMm, tol));
        }
        int outside = 0;
        double worst = 0;
        for (double[] xy : sc.surfaceTargets) {
            double d = pl.distanceOutside(xy[0], xy[1]);
            if (d > cfg.surfaceMarginMm) {
                outside++;
                worst = Math.max(worst, d);
            }
        }
        if (outside > 0) {
            r.add("I4", String.format(Locale.ROOT, "%d dot(s) lie outside the probed '%s' surface (up to %.1f mm "
                    + "beyond its points, margin %.1f mm): probe points around them", outside, sc.surfaceName, worst,
                    cfg.surfaceMarginMm));
        }
        if (sc.checkPlanSurface && in.plan != null && !pl.fingerprint().equals(in.plan.surfaceFingerprint)) {
            r.add("I4", "the plan was made " + (in.plan.surfaceFingerprint == null ? "without the probed '"
                    : "on another '") + sc.surfaceName + "' surface: plan the board again");
        }
    }

    public static InterlockReport check(Inputs in) {
        InterlockReport r = new InterlockReport();
        DispenseConfig cfg = in.cfg;
        for (String problem : cfg.validate()) {
            r.add("CONFIG", problem);
        }
        if (!in.machineEnabled) {
            r.add("MACHINE", "machine is not enabled");
        }
        if (!in.machineHomed) {
            r.add("MACHINE", "machine is not homed");
        }
        // I3: the paste tool is not a vacuum nozzle; N2 must not be usable for pick/discard.
        if (in.compatibleNozzleTips != null && !in.compatibleNozzleTips.isEmpty()) {
            r.add("I3", in.nozzleName + " is configured as a pick nozzle with compatible nozzle tips "
                    + in.compatibleNozzleTips + "; remove them so pick/discard can never fire the paste pneumatics");
        }
        if (in.loadedNozzleTip != null) {
            r.add("I3", in.nozzleName + " has nozzle tip '" + in.loadedNozzleTip + "' loaded");
        }
        checkI4(in, r);
        // I5: barrel pressure window (disabled by default: no sensor fitted).
        if (cfg.pressureInterlockEnabled) {
            Double psi = null;
            try {
                psi = in.pressureSensor == null ? null : in.pressureSensor.readPsi();
            }
            catch (Exception e) {
                r.add("I5", "barrel pressure read failed: " + e.getMessage());
            }
            if (psi == null) {
                if (!r.has("I5")) {
                    r.add("I5", "pressure interlock enabled but no barrel pressure reading is available");
                }
            }
            else if (psi < cfg.pressureMinPsi || psi > cfg.pressureMaxPsi) {
                r.add("I5", String.format(java.util.Locale.ROOT, "barrel pressure %.2f psi outside [%.2f, %.2f]",
                        psi, cfg.pressureMinPsi, cfg.pressureMaxPsi));
            }
        }
        else {
            r.notes.add("I5: barrel pressure window disabled (no sensor)");
        }
        // I7: estimated continuous on-time.
        if (in.estimatedPressurisedSeconds > cfg.maxContinuousOnTimeS) {
            r.add("I7", String.format(java.util.Locale.ROOT,
                    "estimated pressurised time %.0f s exceeds the %.0f s on-time limit",
                    in.estimatedPressurisedSeconds, cfg.maxContinuousOnTimeS));
        }
        // I9: prime + test dot.
        if (cfg.primeAndTestDot) {
            if (cfg.location(cfg.wasteLocationName) == null) {
                r.add("I9", "waste location '" + cfg.wasteLocationName + "' is not set");
            }
            if (cfg.testDotOpticalCheck && !in.opticalCheckAvailable) {
                r.add("I9", "optical test-dot check enabled but no top camera / dot vision is available");
            }
        }
        else {
            r.notes.add("I9: prime + test dot disabled in the configuration");
        }
        // Plan rules (§5.6/§5.7).
        if (in.plan != null) {
            if (in.plan.dots.isEmpty()) {
                r.add("PLAN", "plan has no dots");
            }
            List<DispensePlan.PlannedPad> blocked = in.plan.blockedPads();
            if (!blocked.isEmpty() && cfg.refuseFlaggedPads) {
                StringBuilder sb = new StringBuilder();
                int shown = 0;
                for (DispensePlan.PlannedPad p : blocked) {
                    if (shown++ < 8) {
                        sb.append(p.padId).append(" (").append(p.typeKey == null ? p.packageId : p.typeKey)
                                .append(": ").append(p.flagNames()).append(") ");
                    }
                }
                r.add("PLAN", blocked.size() + " pad(s) not dispensable: " + sb.toString().trim()
                        + (blocked.size() > 8 ? " ..." : ""));
            }
            else if (!blocked.isEmpty()) {
                r.notes.add(blocked.size() + " flagged pad(s) will be skipped");
            }
        }
        return r;
    }
}
