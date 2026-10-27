package org.openpnp.instancelabs.dispense;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Measured rotation -> dot diameter relation of one calibration (plan §5.6 "calibrated dot
 * ladder"). Interpolates piecewise-linearly, which is monotone whenever the data is, and ONLY
 * inside the measured rotation range: outside it every query returns null (never extrapolates).
 */
public final class DotLadder {
    public static final class Rung {
        public final double rotationDeg;
        public final double diameterMm;
        public final DotLadderRow row;

        Rung(double rotationDeg, double diameterMm, DotLadderRow row) {
            this.rotationDeg = rotationDeg;
            this.diameterMm = diameterMm;
            this.row = row;
        }
    }

    private final List<Rung> rungs;
    private final boolean monotonic;

    private DotLadder(List<Rung> rungs) {
        this.rungs = Collections.unmodifiableList(rungs);
        boolean m = true;
        for (int i = 1; i < rungs.size(); i++) {
            if (rungs.get(i).diameterMm < rungs.get(i - 1).diameterMm) {
                m = false;
            }
        }
        this.monotonic = m;
    }

    /**
     * Builds the ladder from append-only rows: for each rotation the newest row with a diameter wins.
     */
    public static DotLadder of(List<DotLadderRow> rows) {
        Map<Double, DotLadderRow> latest = new LinkedHashMap<>();
        if (rows != null) {
            for (DotLadderRow r : rows) {
                if (r.diameterMeanMm == null || r.diameterMeanMm <= 0 || r.rotationDeg <= 0) {
                    continue;
                }
                DotLadderRow prev = latest.get(r.rotationDeg);
                if (prev == null || r.id >= prev.id) {
                    latest.put(r.rotationDeg, r);
                }
            }
        }
        List<Rung> l = new ArrayList<>();
        for (DotLadderRow r : latest.values()) {
            l.add(new Rung(r.rotationDeg, r.diameterMeanMm, r));
        }
        l.sort((a, b) -> Double.compare(a.rotationDeg, b.rotationDeg));
        return new DotLadder(l);
    }

    public static DotLadder empty() {
        return new DotLadder(new ArrayList<>());
    }

    public boolean isEmpty() {
        return rungs.isEmpty();
    }

    public boolean isMonotonic() {
        return monotonic;
    }

    public List<Rung> rungs() {
        return rungs;
    }

    public double minRotationDeg() {
        return rungs.isEmpty() ? Double.NaN : rungs.get(0).rotationDeg;
    }

    public double maxRotationDeg() {
        return rungs.isEmpty() ? Double.NaN : rungs.get(rungs.size() - 1).rotationDeg;
    }

    public boolean inRange(double rotationDeg) {
        return !rungs.isEmpty() && rotationDeg >= minRotationDeg() - 1e-9
                && rotationDeg <= maxRotationDeg() + 1e-9;
    }

    /** Diameter at the rotation, or null outside the measured range or if not monotonic. */
    public Double diameterAt(double rotationDeg) {
        if (!monotonic || !inRange(rotationDeg)) {
            return null;
        }
        if (rungs.size() == 1) {
            return rungs.get(0).diameterMm;
        }
        for (int i = 1; i < rungs.size(); i++) {
            Rung a = rungs.get(i - 1);
            Rung b = rungs.get(i);
            if (rotationDeg <= b.rotationDeg + 1e-9) {
                double f = (rotationDeg - a.rotationDeg) / (b.rotationDeg - a.rotationDeg);
                f = Math.max(0, Math.min(1, f));
                return a.diameterMm + f * (b.diameterMm - a.diameterMm);
            }
        }
        return rungs.get(rungs.size() - 1).diameterMm;
    }
}
