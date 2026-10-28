package org.openpnp.instancelabs.dispense;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Builds the raw G-code block for one pressure session (plan §4.5): session start (G92 B0 unless B
 * is already zeroed since home/reconnect, M201/M203 for B, optional host-watchdog arm), pump ON ->
 * wait -> valve ON -> settle, prime/test dot on the waste location, then per dot: XY at the
 * current (hop) height, Z down to the gap, B feed, dwell, B suck-back, Z up with the dispense-lift
 * acceleration. Session end: M400, valve OFF, pump OFF, re-zero B (aborts: M410 first, see
 * {@link #abortLines}).
 *
 * <p>All XYZ coordinates go through {@link RawMapper} (OpenPnP's transform chain) and
 * {@link BacklashEmulator}. B is absolute from the session's G92 B0; the B coordinate at the end
 * of each feed advances by exactly the dot's rotation, so the suck-back of one dot is taken up by
 * the next feed and the net extrusion per dot equals its planned rotation.</p>
 */
public class GcodeStreamBuilder {
    /** One dot to dispense, in N2 machine coordinates. */
    public static final class DotSpec {
        public final double x, y, dispenseZ, hopZ, rotationDeg;
        public final int index;
        public final String label;

        public DotSpec(double x, double y, double dispenseZ, double hopZ, double rotationDeg, int index,
                String label) {
            this.x = x;
            this.y = y;
            this.dispenseZ = dispenseZ;
            this.hopZ = hopZ;
            this.rotationDeg = rotationDeg;
            this.index = index;
            this.label = label;
        }

        public static DotSpec of(DispensePlan.PlannedDot d) {
            return new DotSpec(d.xMm, d.yMm, d.dispenseZMm, d.hopZMm, d.rotationDeg, d.index, d.padId);
        }
    }

    /** Which part of a board run a program covers. */
    public enum Part {
        /** Prime + test dot + all dots in one pressure session. */
        FULL,
        /** Prime + test dot only (when the test dot is optically checked before the board). */
        PRIME_AND_TEST,
        /** Re-prime + all dots (after a passed optical test-dot check). */
        DOTS
    }

    public static final int PRIME_INDEX = -1;
    public static final int TEST_DOT_INDEX = -2;

    private final DispenseConfig cfg;
    private final RawMapper mapper;
    private final BacklashEmulator emu;
    private final GcodeProgram prog = new GcodeProgram();
    private final String[] letters;
    private final String lb;
    private double[] lastDriver;
    private double[] nozzle;
    private boolean bZeroed;
    /** Current B position in extrude-positive units (raw B = bExtrudeSign * bPos). */
    private double bPos;
    /** B position at the end of the last feed (the next feed ends rotation further). */
    private double bFeedEnd;
    private boolean pumpOn, valveOn, armed;
    private final boolean keepalive;
    private double t;
    private double pumpOnT = -1;
    private double accel = Double.NaN;
    private GcodeProgram.Marker pending;
    private int pendingIndex;
    private String pendingPad;
    private double pendingRot;

    public GcodeStreamBuilder(DispenseConfig cfg, RawMapper mapper, boolean bZeroedAtStart, boolean keepalive) {
        this.cfg = cfg;
        this.mapper = mapper;
        this.keepalive = keepalive;
        double[] planned = mapper.currentPlanned();
        double[] driver = mapper.currentDriver();
        double[] off = new double[3];
        for (int i = 0; i < 3; i++) {
            off[i] = driver[i] - planned[i];
        }
        this.emu = new BacklashEmulator(mapper.backlash(), planned, off, cfg.honorBacklash);
        this.lastDriver = driver.clone();
        this.nozzle = mapper.currentNozzleLocation().clone();
        this.letters = new String[] {mapper.letterX(), mapper.letterY(), mapper.letterZ()};
        this.lb = mapper.letterB();
        this.bZeroed = bZeroedAtStart;
        this.bPos = bZeroedAtStart ? cfg.bExtrudeSign * mapper.currentB() : Double.NaN;
        this.bFeedEnd = bPos;
        prog.bZeroedAtStart = bZeroedAtStart;
        prog.keepalive = keepalive;
    }

    // ------------------------------------------------------------------------------------------
    // Composite programs
    // ------------------------------------------------------------------------------------------

    /** Program for (part of) a board plan. */
    public static GcodeProgram forPlan(DispenseConfig cfg, RawMapper mapper, DispensePlan plan,
            boolean bZeroed, boolean keepalive, Part part) throws Exception {
        List<DotSpec> dots = new ArrayList<>();
        for (DispensePlan.PlannedDot d : plan.dots) {
            dots.add(DotSpec.of(d));
        }
        double testRot = plan.dots.isEmpty() ? 0 : plan.dots.get(0).rotationDeg;
        GcodeStreamBuilder b = new GcodeStreamBuilder(cfg, mapper, bZeroed, keepalive);
        b.comment("; instancelabs dispense plan " + plan.planId + " board " + plan.boardName + " part " + part
                + " calibration #" + plan.calibrationId);
        b.sessionStart();
        DispenseConfig.NamedLocation waste = cfg.location(cfg.wasteLocationName);
        boolean prime = cfg.primeAndTestDot || part != Part.FULL;
        if (prime) {
            if (waste == null) {
                throw new Exception("I9: waste location '" + cfg.wasteLocationName + "' is not set");
            }
            b.travelTo(waste.x, waste.y);
        }
        else if (!dots.isEmpty()) {
            b.travelTo(dots.get(0).x, dots.get(0).y);
        }
        b.pressurize();
        if (prime) {
            double primeRot = part == Part.DOTS ? cfg.rePrimeRotationDeg : cfg.primeRotationDeg;
            b.dot(b.atLocation(waste, 0, 0, primeRot, PRIME_INDEX, "prime"));
            if (part != Part.DOTS && testRot > 0) {
                b.dot(b.atLocation(waste, cfg.testDotOffsetXMm, cfg.testDotOffsetYMm, testRot, TEST_DOT_INDEX,
                        "test-dot"));
            }
        }
        if (part != Part.PRIME_AND_TEST) {
            for (int i = 0; i < dots.size(); i++) {
                if (i == 0) {
                    b.travelTo(dots.get(0).x, dots.get(0).y);
                }
                b.dot(dots.get(i));
            }
        }
        b.endSession();
        return b.build();
    }

    /**
     * Program for calibration dots (dot ladder, touch-off, shots): optional prime at the waste
     * location, then the dots in the given order.
     */
    public static GcodeProgram forDots(DispenseConfig cfg, RawMapper mapper, List<DotSpec> dots,
            boolean bZeroed, boolean keepalive, boolean primeAtWaste, String title) throws Exception {
        GcodeStreamBuilder b = new GcodeStreamBuilder(cfg, mapper, bZeroed, keepalive);
        b.comment("; instancelabs dispense " + title);
        b.sessionStart();
        DispenseConfig.NamedLocation waste = cfg.location(cfg.wasteLocationName);
        if (primeAtWaste) {
            if (waste == null) {
                throw new Exception("waste location '" + cfg.wasteLocationName + "' is not set");
            }
            b.travelTo(waste.x, waste.y);
        }
        else if (!dots.isEmpty()) {
            b.travelTo(dots.get(0).x, dots.get(0).y);
        }
        b.pressurize();
        if (primeAtWaste) {
            b.dot(b.atLocation(waste, 0, 0, cfg.primeRotationDeg, PRIME_INDEX, "prime"));
        }
        for (int i = 0; i < dots.size(); i++) {
            if (i == 0) {
                b.travelTo(dots.get(0).x, dots.get(0).y);
            }
            b.dot(dots.get(i));
        }
        b.endSession();
        return b.build();
    }

    /** The fixed abort / error sequence (plan §4.4 order): quick-stop, valve OFF, pump OFF. */
    public static List<String> abortLines(DispenseConfig cfg, boolean keepaliveArmed, String letterB) {
        List<String> l = new ArrayList<>(Arrays.asList("M410", ControllerCommands.VALVE_OFF,
                ControllerCommands.PUMP_OFF));
        if (cfg.abortDisablesB) {
            l.add("M18 " + letterB);
        }
        if (keepaliveArmed) {
            l.add(ControllerCommands.KEEPALIVE_DISARM);
        }
        return l;
    }

    /** Dot at a named location (+ offset) with the configured gap; lifts to travel Z after. */
    public DotSpec atLocation(DispenseConfig.NamedLocation loc, double dx, double dy, double rot, int index,
            String label) {
        double z = loc.z + cfg.needleZOffsetMm + cfg.needleGapMm;
        return new DotSpec(loc.x + dx, loc.y + dy, z, cfg.travelSafeZMm, rot, index, label);
    }

    // ------------------------------------------------------------------------------------------
    // Building blocks
    // ------------------------------------------------------------------------------------------

    public void comment(String c) {
        add(c.startsWith(";") ? c : "; " + c, GcodeProgram.Marker.NONE);
    }

    public void sessionStart() {
        comment("; session start");
        if (!bZeroed) {
            add("G92 " + lb + "0", GcodeProgram.Marker.B_ZERO);
            bZeroed = true;
            bPos = 0;
            bFeedEnd = 0;
        }
        add("M201 " + lb + f0(cfg.bAccelDegS2), GcodeProgram.Marker.NONE);
        add("M203 " + lb + f0(cfg.bMaxFeedDegS), GcodeProgram.Marker.NONE);
        setAccel(cfg.travelAccelMmS2);
        if (keepalive) {
            add(ControllerCommands.keepaliveArm(cfg.keepaliveWindowMs), GcodeProgram.Marker.KEEPALIVE_ARM);
            armed = true;
        }
    }

    public void pressurize() {
        comment("; pressurize: pump ON -> wait -> valve ON -> settle");
        add(ControllerCommands.PUMP_ON, GcodeProgram.Marker.PUMP_ON);
        pumpOn = true;
        pumpOnT = t;
        dwell(cfg.pumpToValveSettleMs);
        add(ControllerCommands.VALVE_ON, GcodeProgram.Marker.VALVE_ON);
        valveOn = true;
        dwell(cfg.valveSettleMs);
    }

    /** Lift to travel safe Z (if not there), then XY. */
    public void travelTo(double x, double y) throws Exception {
        if (Math.abs(nozzle[2] - cfg.travelSafeZMm) > 1e-6) {
            moveNozzle(nozzle[0], nozzle[1], cfg.travelSafeZMm, cfg.zLiftFeedMmS, "G1");
        }
        moveNozzle(x, y, cfg.travelSafeZMm, cfg.travelFeedMmS, "G0");
    }

    public void dot(DotSpec d) throws Exception {
        if (!bZeroed) {
            throw new IllegalStateException("I2: no B move before B is zeroed");
        }
        if (!(pumpOn && valveOn)) {
            throw new IllegalStateException("dot outside a pressure session");
        }
        comment(String.format(Locale.ROOT, "; dot %d %s rot %.3f", d.index, d.label, d.rotationDeg));
        pending = GcodeProgram.Marker.DOT_BEGIN;
        pendingIndex = d.index;
        pendingPad = d.label;
        pendingRot = d.rotationDeg;
        moveNozzle(d.x, d.y, nozzle[2], cfg.travelFeedMmS, "G0");
        moveNozzle(d.x, d.y, d.dispenseZ, cfg.zDownFeedMmS, "G1");
        bFeedEnd += d.rotationDeg;
        moveB(bFeedEnd, cfg.bFeedDegS);
        dwell(cfg.dwellMs);
        if (cfg.retractDeg > 0) {
            moveB(bFeedEnd - cfg.retractDeg, cfg.retractFeedDegS);
        }
        setAccel(cfg.zLiftAccelMmS2);
        moveNozzle(d.x, d.y, d.hopZ, cfg.zLiftFeedMmS, "G1");
        setAccel(cfg.travelAccelMmS2);
        markLast(GcodeProgram.Marker.DOT_END, d.index, d.label, d.rotationDeg);
        prog.dotCount += d.index >= 0 ? 1 : 0;
    }

    public void endSession() throws Exception {
        // No M410 here: after M400 nothing is left to stop, and Marlin's quick-stop holds its "ok"
        // for about 1 s (planner cleaning_buffer_counter), which kept the valve open 1 s after the
        // last dot (review finding). The valve vents right after the last dot's lift has finished.
        comment("; session end: wait, valve OFF (vent), pump OFF");
        add("M400", GcodeProgram.Marker.SYNC);
        add(ControllerCommands.VALVE_OFF, GcodeProgram.Marker.VALVE_OFF);
        valveOn = false;
        add(ControllerCommands.PUMP_OFF, GcodeProgram.Marker.PUMP_OFF);
        pumpOn = false;
        if (pumpOnT >= 0) {
            prog.estimatedPressurisedSeconds += t - pumpOnT;
            pumpOnT = -1;
        }
        if (armed) {
            add(ControllerCommands.KEEPALIVE_DISARM, GcodeProgram.Marker.KEEPALIVE_DISARM);
            armed = false;
        }
        add("G92 " + lb + "0", GcodeProgram.Marker.B_ZERO);
        bPos = 0;
        bFeedEnd = 0;
        if (Math.abs(nozzle[2] - cfg.travelSafeZMm) > 1e-6) {
            moveNozzle(nozzle[0], nozzle[1], cfg.travelSafeZMm, cfg.zLiftFeedMmS, "G1");
        }
        add("M400", GcodeProgram.Marker.SYNC);
    }

    public void dwell(int ms) {
        if (ms <= 0) {
            return;
        }
        int chunk = keepalive ? Math.max(100, cfg.keepaliveIntervalMs) : ms;
        int remaining = ms;
        while (remaining > 0) {
            int c = Math.min(chunk, remaining);
            add("G4 P" + c, GcodeProgram.Marker.NONE);
            t += c / 1000.0;
            remaining -= c;
        }
    }

    public GcodeProgram build() {
        if (pumpOn || valveOn) {
            throw new IllegalStateException("program would leave the pneumatics on");
        }
        prog.finalPlanned = emu.planned();
        prog.finalDriver = emu.driver();
        prog.finalB = cfg.bExtrudeSign * bPos;
        prog.finalNozzle = nozzle.clone();
        prog.estimatedSeconds = t;
        return prog;
    }

    // ------------------------------------------------------------------------------------------

    private void moveNozzle(double x, double y, double z, double feedMmS, String word) throws Exception {
        RawPoint r = mapper.toRaw(x, y, z);
        for (BacklashEmulator.Move m : emu.moveTo(new double[] {r.x, r.y, r.z})) {
            StringBuilder sb = new StringBuilder(word);
            boolean any = false;
            for (int i = 0; i < 3; i++) {
                if (!f4(m.driver[i]).equals(f4(lastDriver[i]))) {
                    sb.append(' ').append(letters[i]).append(f4(m.driver[i]));
                    any = true;
                }
            }
            if (!any) {
                continue;
            }
            double f = feedMmS * m.speedFactor;
            sb.append(" F").append(f0(f * 60));
            double dx = m.driver[0] - lastDriver[0], dy = m.driver[1] - lastDriver[1], dz = m.driver[2] - lastDriver[2];
            t += Math.sqrt(dx * dx + dy * dy + dz * dz) / f;
            lastDriver = m.driver.clone();
            add(sb.toString(), GcodeProgram.Marker.NONE);
        }
        nozzle = new double[] {x, y, z};
    }

    private void moveB(double ext, double feedDegS) {
        add("G1 " + lb + f4(cfg.bExtrudeSign * ext) + " F" + f0(feedDegS * 60), GcodeProgram.Marker.NONE);
        t += Math.abs(ext - bPos) / feedDegS;
        bPos = ext;
    }

    private void setAccel(double a) {
        if (Double.isNaN(accel) || Math.abs(accel - a) > 1e-9) {
            add("M204 S" + f0(a), GcodeProgram.Marker.NONE);
            accel = a;
        }
    }

    private void add(String text, GcodeProgram.Marker m) {
        GcodeProgram.Marker marker = m;
        int idx = -9;
        String pad = null;
        double rot = 0;
        List<GcodeProgram.Marker> extra = null;
        if (pending != null && !text.startsWith(";")) {
            if (marker != GcodeProgram.Marker.NONE) {
                extra = new ArrayList<>();
                extra.add(marker);
            }
            marker = pending;
            idx = pendingIndex;
            pad = pendingPad;
            rot = pendingRot;
            pending = null;
        }
        prog.lines.add(new GcodeProgram.Line(text, marker, idx, pad, rot, extra));
    }

    private void markLast(GcodeProgram.Marker m, int index, String pad, double rot) {
        for (int i = prog.lines.size() - 1; i >= 0; i--) {
            GcodeProgram.Line l = prog.lines.get(i);
            if (l.isComment()) {
                continue;
            }
            List<GcodeProgram.Marker> extra = new ArrayList<>(l.extra);
            GcodeProgram.Marker primary = l.marker;
            if (primary == GcodeProgram.Marker.NONE) {
                primary = m;
            }
            else {
                extra.add(m);
            }
            prog.lines.set(i, new GcodeProgram.Line(l.text, primary, index, pad, rot, extra));
            return;
        }
    }

    static String f4(double v) {
        String s = String.format(Locale.ROOT, "%.4f", v);
        return s.equals("-0.0000") ? "0.0000" : s;
    }

    static String f0(double v) {
        return String.format(Locale.ROOT, "%.0f", v);
    }
}
