package org.openpnp.instancelabs.api;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.openpnp.instancelabs.dispense.DispensePlan;

/**
 * Plans the UI has previewed, so "Run" dispenses exactly the plan that was shown (by planId).
 * Keeps the most recent {@value #MAX} plans in memory; every plan is also stored by the engine
 * (pad_plan table).
 */
final class PlanStore {
    static final int MAX = 20;

    static final class Entry {
        final DispensePlan plan;
        /** How the board was chosen (job board or file + location), echoed back to the UI. */
        final Map<String, Object> source;
        /** Kinematic lower-bound estimate of the whole program (no acceleration), s; null if unknown. */
        Double estimatedSeconds;
        Double estimatedPressurisedSeconds;
        String estimateError;

        Entry(DispensePlan plan, Map<String, Object> source) {
            this.plan = plan;
            this.source = source;
        }
    }

    private final LinkedHashMap<String, Entry> plans = new LinkedHashMap<String, Entry>(16, 0.75f, true) {
        private static final long serialVersionUID = 1L;

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Entry> eldest) {
            return size() > MAX;
        }
    };

    synchronized void put(Entry e) {
        plans.put(e.plan.planId, e);
    }

    synchronized Entry get(String planId) {
        return plans.get(planId);
    }

    /**
     * A plan restricted to some pads and/or the first {@code maxDots} dots in path order. The path
     * order of the full plan is kept and dots are re-indexed from 0. Returns the plan itself if
     * nothing is excluded.
     */
    static DispensePlan subset(DispensePlan p, List<String> padIds, Integer maxDots) {
        Set<String> wanted = padIds == null || padIds.isEmpty() ? null : new HashSet<>(padIds);
        if (wanted != null) {
            Set<String> known = new HashSet<>();
            for (DispensePlan.PlannedPad pad : p.pads) {
                known.add(pad.padId);
            }
            List<String> unknown = new ArrayList<>();
            for (String id : wanted) {
                if (!known.contains(id)) {
                    unknown.add(id);
                }
            }
            if (!unknown.isEmpty()) {
                throw new IllegalArgumentException("pads not in plan " + p.planId + ": " + unknown);
            }
        }
        if (maxDots != null && maxDots < 1) {
            throw new IllegalArgumentException("maxDots must be at least 1");
        }
        List<DispensePlan.PlannedDot> dots = new ArrayList<>();
        for (DispensePlan.PlannedDot d : p.dots) {
            if (wanted != null && !wanted.contains(d.padId)) {
                continue;
            }
            if (maxDots != null && dots.size() >= maxDots) {
                break;
            }
            dots.add(d);
        }
        if (dots.size() == p.dots.size()) {
            return p;
        }
        Set<String> padsWithDots = new HashSet<>();
        for (DispensePlan.PlannedDot d : dots) {
            padsWithDots.add(d.padId);
        }
        DispensePlan s = new DispensePlan();
        s.planId = p.planId + "-subset-" + UUID.randomUUID().toString().substring(0, 8);
        s.boardName = p.boardName;
        s.boardLocationId = p.boardLocationId;
        s.createdAtMs = System.currentTimeMillis();
        s.calibrationId = p.calibrationId;
        s.qMm3PerRev = p.qMm3PerRev;
        s.calibrationStatus = p.calibrationStatus;
        s.needleGapMm = p.needleGapMm;
        s.needleZOffsetMm = p.needleZOffsetMm;
        s.hopHeightMm = p.hopHeightMm;
        s.surfaceFingerprint = p.surfaceFingerprint;
        s.boardFingerprint = p.boardFingerprint;
        double path = 0;
        for (int i = 1; i < dots.size(); i++) {
            path += Math.hypot(dots.get(i).xMm - dots.get(i - 1).xMm, dots.get(i).yMm - dots.get(i - 1).yMm);
        }
        // Dot-to-dot length of the kept dots (the parent's start leg is not included).
        s.pathLengthMm = path;
        s.pathLengthNearestNeighbourMm = Double.NaN;
        for (DispensePlan.PlannedPad pad : p.pads) {
            if (padsWithDots.contains(pad.padId)) {
                s.pads.add(pad);
            }
        }
        for (int i = 0; i < dots.size(); i++) {
            DispensePlan.PlannedDot o = dots.get(i);
            DispensePlan.PlannedDot d = new DispensePlan.PlannedDot();
            d.index = i;
            d.padId = o.padId;
            d.xMm = o.xMm;
            d.yMm = o.yMm;
            d.surfaceZMm = o.surfaceZMm;
            d.dispenseZMm = o.dispenseZMm;
            d.hopZMm = o.hopZMm;
            d.rotationDeg = o.rotationDeg;
            d.expectedDiameterMm = o.expectedDiameterMm;
            s.dots.add(d);
        }
        s.warnings.addAll(p.warnings);
        s.warnings.add("subset of plan " + p.planId + ": " + dots.size() + " of " + p.dots.size() + " dots on "
                + s.pads.size() + " of " + p.pads.size() + " pads (path order kept)");
        return s;
    }
}
