package org.openpnp.instancelabs.dispense;

import java.util.List;

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
        // I4: needle Z offset set since the last needle change. (Z-map containment: no Z-map yet.)
        if (!cfg.isNeedleTouchedOff()) {
            r.add("I4", "needle Z offset has not been set since the last needle change (run a touch-off)");
        }
        r.notes.add("I4: board Z-map containment not checked (no Z-map exists yet; board Z from the board location)");
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
