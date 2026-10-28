package org.openpnp.instancelabs.dispense;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openpnp.instancelabs.vision.Dot;
import org.openpnp.model.Configuration;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.spi.MotionPlanner.CompletionType;

/**
 * End to end on a simulated machine: DispenseService plans, interlocks, streams through OpenPnP's
 * GcodeDriver (TCP to RecordingGcodeServer), re-syncs OpenPnP, stores the run.
 */
public class DispenseServiceSimTest {
    SimMachine sim;
    DispenseService svc;
    final List<String> phases = new ArrayList<>();

    /** Fake top camera: always sees one 0.70 mm dot at the frame centre. */
    static class FakeImager implements DotImager {
        int captures;
        double diameter = 0.70;

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public Frame capture(double x, double y) {
            captures++;
            return new Frame(null, 0.01, 0.01, x, y);
        }

        @Override
        public List<Dot> inspect(Frame before, Frame after) {
            return Arrays.asList(new Dot(0.02, -0.01, diameter, Math.PI * diameter * diameter / 4, 0.9));
        }
    }

    FakeImager imager = new FakeImager();

    @BeforeEach
    public void before() throws Exception {
        sim = new SimMachine();
        svc = DispenseService.start(Configuration.get());
        assertTrue(svc.isAvailable(), String.valueOf(svc.getUnavailableReason()));
        svc.setDotImager(imager);
        svc.addListener(new DispenseListener() {
            @Override
            public void onPhase(RunResult run, String phase) {
                phases.add(phase);
            }
        });
        DispenseConfig c = svc.getConfig();
        c.locations.put("waste", new DispenseConfig.NamedLocation(80, 40, 1.0));
        c.locations.put("blank", new DispenseConfig.NamedLocation(90, 40, 1.0));
        c.minPadTypeStatus = "provisional";
        svc.updateConfig(c);
        // I4 needle frame: N1 at 1.3 and the needle at 1.35 on the same surface (offset 0.05)
        svc.setNeedleAnchor(1.3, 1.35, null, null);
        svc.setNeedleZOffset(0.05, "manual", "test");
        // probed surfaces: the board (around the toy pads, Z 1.6) and the blank (Z 1.0)
        svc.setSurface("board", pts(new double[][] {{95, 75, 1.6}, {115, 75, 1.6}, {95, 90, 1.6}, {115, 90, 1.6}}),
                "test");
        svc.setSurface("blank", pts(new double[][] {{88, 38, 1.0}, {102, 38, 1.0}, {88, 50, 1.0}, {102, 50, 1.0}}),
                "test");
        PadType t = svc.getDb().currentPadType("chip-0603");
        t.status = PadTypeStatus.PROVISIONAL;
        svc.savePadType(t);
    }

    static List<DispenseConfig.SurfacePoint> pts(double[][] p) {
        List<DispenseConfig.SurfacePoint> l = new ArrayList<>();
        for (double[] v : p) {
            l.add(new DispenseConfig.SurfacePoint(v[0], v[1], v[2]));
        }
        return l;
    }

    /** Fake squash: touch-off dots whose commanded gap <= 0.05 come out 1.0 mm wide. */
    static DotImager squashImager() {
        final double[] gapsSeen = NeedleTouchOff.gaps(new NeedleTouchOff.Request());
        return new DotImager() {
            int i = 0;
            final List<Frame> afterFrames = new ArrayList<>();

            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public Frame capture(double x, double y) {
                return new Frame(null, 0.01, 0.01, x, y);
            }

            @Override
            public List<Dot> inspect(Frame before, Frame after) {
                double g = gapsSeen[i++];
                double dia = g <= 0.05 + 1e-9 ? 1.0 : 0.62;
                return Arrays.asList(new Dot(0, 0, dia, 0.3, 0.9));
            }
        };
    }

    @AfterEach
    public void after() throws Exception {
        svc.shutdown();
        sim.close();
    }

    DispensePlan plan() throws Exception {
        PadType t = svc.getDb().currentPadType("chip-0603");
        List<PadGeometry> pads = new ArrayList<>();
        pads.add(new PadGeometry("R1.1", 100.0, 80.0, 0, 0.8, 0.95, 1.6, t));
        pads.add(new PadGeometry("R1.2", 101.65, 80.0, 0, 0.8, 0.95, 1.6, t));
        pads.add(new PadGeometry("R2.1", 110.0, 85.0, 90, 0.8, 0.95, 1.6, t));
        return svc.newPlanner().plan("sim-toy", pads, 80, 40);
    }

    static int count(List<String> l, String s) {
        return java.util.Collections.frequency(l, s);
    }

    @Test
    public void runsBoardAndResyncsOpenPnp() throws Exception {
        DispensePlan plan = plan();
        assertEquals(3, plan.dots.size());
        sim.server.mark();
        RunResult r = svc.runPlan(plan).get(60, TimeUnit.SECONDS);
        assertEquals(RunResult.Status.OK, r.status, r.toString() + " " + r.interlocks);
        assertEquals(3, r.dotsDone);
        assertTrue(r.keepaliveActive, "M115 reported Cap:HOST_WATCHDOG:1");
        assertNotNull(r.dphMotion());
        assertNotNull(r.dphOverall());
        assertTrue(r.dphOverall() <= r.dphMotion());
        assertEquals(2, imager.captures, "I9 before/after images of the test dot");

        List<String> sent = sim.server.sinceMark();
        java.nio.file.Files.write(java.nio.file.Paths.get("target/sim-board-run.gcode"), sent);
        // optical I9 => two pressure sessions: prime+test dot, then re-prime + dots
        assertEquals(2, count(sent, "M106 P2 S255"));
        assertEquals(2, count(sent, "M106 P3 S255"));
        assertEquals(2, count(sent, "M107 P3"));
        assertEquals(2, count(sent, "M990 S3000"));
        assertEquals(2, count(sent, "M990 S0"));
        assertTrue(sent.indexOf("M115") < sent.indexOf("M106 P2 S255"));
        assertTrue(sent.indexOf("G92 B0") < sent.indexOf("M106 P2 S255"), "I2: B zeroed before the first B move");
        assertFalse(sent.contains("M18 B"));
        for (String l : sent) {
            assertFalse(l.startsWith("M106") && !l.endsWith("S255"), "no PWM: " + l);
        }
        // re-sync: OpenPnP agrees with the controller about X/Y/Z and B
        OpenPnpRawMapper m = sim.machine.execute(() -> new OpenPnpRawMapper(sim.machine, sim.n2, "b"));
        double[] d = m.currentDriver();
        assertEquals(sim.server.position('X'), d[0], 1e-4);
        assertEquals(sim.server.position('Y'), d[1], 1e-4);
        assertEquals(sim.server.position('Z'), d[2], 1e-4);
        assertEquals(0.0, sim.server.position('B'), 0);
        assertEquals(0.0, m.currentB(), 0);
        Location n2 = sim.n2.getLocation().convertToUnits(LengthUnit.Millimeters);
        DispensePlan.PlannedDot last = plan.dots.get(plan.dots.size() - 1);
        assertEquals(last.xMm, n2.getX(), 1e-6);
        assertEquals(last.yMm, n2.getY(), 1e-6);
        assertEquals(31.5, n2.getZ(), 1e-6);
        assertTrue(svc.isBZeroed());

        // OpenPnP's next own move starts from the truth: a pure Z move must not move X/Y
        sim.server.mark();
        sim.machine.execute(() -> {
            sim.n2.moveTo(n2.derive(null, null, 25.0, null));
            sim.n2.waitForCompletion(CompletionType.WaitForStillstand);
            return null;
        });
        for (String l : sim.server.sinceMark()) {
            if (l.startsWith("G1")) {
                assertFalse(l.contains(" X") || l.contains(" Y"), "unexpected XY in " + l);
            }
        }
        assertEquals(63 - 25.0, sim.server.position('Z'), 1e-4);

        // stored
        assertEquals(1, svc.getDb().recentRuns(10).size());
        assertEquals(3, svc.getDb().query("SELECT * FROM dot_log").size());
        assertTrue(phases.contains("dispensing"));
    }

    @Test
    public void controllerErrorTriggersI1ShutdownAndUnhomes() throws Exception {
        DispensePlan plan = plan();
        DispenseConfig c = svc.getConfig();
        c.testDotOpticalCheck = false;
        svc.updateConfig(c);
        sim.server.mark();
        sim.server.failAtLine = 30; // inside the pressure session
        RunResult r = svc.runPlan(plan).get(60, TimeUnit.SECONDS);
        assertEquals(RunResult.Status.ERROR, r.status, r.toString());
        List<String> sent = sim.server.sinceMark();
        java.nio.file.Files.write(java.nio.file.Paths.get("target/sim-board-error.gcode"), sent);
        int m410 = sent.lastIndexOf("M410");
        assertTrue(m410 > 30, sent.toString());
        assertEquals(Arrays.asList("M410", "M107 P3", "M107 P2", "M18 B", "M990 S0"), sent.subList(m410, m410 + 5));
        assertTrue(sent.subList(m410, sent.size()).contains("M114 ; get position"), "position read back");
        assertFalse(sim.machine.isHomed(), "M410 may lose steps: machine must be re-homed");
        assertFalse(svc.isBZeroed());
    }

    @Test
    public void interlockRefusalSendsNothing() throws Exception {
        svc.markNeedleChanged(); // I4
        DispensePlan plan = plan();
        sim.server.mark();
        RunResult r = svc.runPlan(plan).get(60, TimeUnit.SECONDS);
        assertEquals(RunResult.Status.REFUSED, r.status);
        assertTrue(r.interlocks.has("I4"), r.interlocks.toString());
        for (String l : sim.server.sinceMark()) {
            assertFalse(l.startsWith("M106") || l.startsWith("G1 B") || l.startsWith("G92"), l);
        }
    }

    @Test
    public void noWatchdogCapabilityMeansNoKeepalive() throws Exception {
        sim.server.hostWatchdogCapability = false;
        DispenseConfig c = svc.getConfig();
        c.testDotOpticalCheck = false;
        svc.updateConfig(c);
        sim.server.mark();
        RunResult r = svc.runPlan(plan()).get(60, TimeUnit.SECONDS);
        assertEquals(RunResult.Status.OK, r.status, r.toString());
        assertFalse(r.keepaliveActive);
        for (String l : sim.server.sinceMark()) {
            assertFalse(l.startsWith("M990"), l);
        }
        assertEquals(1, count(sim.server.sinceMark(), "M106 P2 S255"), "single session without optical I9");
    }

    @Test
    public void testDotTooSmallStopsBeforeBoard() throws Exception {
        imager.diameter = 0.1;
        DispensePlan plan = plan();
        // plan has no ladder -> no expected diameter; minimum is DotInspectorSettings.minDiameterMm 0.15
        sim.server.mark();
        RunResult r = svc.runPlan(plan).get(60, TimeUnit.SECONDS);
        assertEquals(RunResult.Status.ERROR, r.status);
        assertTrue(r.message.startsWith("I9"), r.message);
        assertEquals(0, r.dotsDone);
        assertEquals(1, count(sim.server.sinceMark(), "M106 P2 S255"), "board session never started");
    }

    @Test
    public void touchOffAndLadderCalibrations() throws Exception {
        svc.setDotImager(squashImager());
        double before = svc.getConfig().needleZOffsetMm;
        DispenseService.TouchOffResult t = svc.runNeedleTouchOff(new NeedleTouchOff.Request(), true)
                .get(60, TimeUnit.SECONDS);
        assertEquals(RunResult.Status.OK, t.run.status, t.run.toString());
        assertTrue(t.analysis.found(), t.analysis.reason);
        assertEquals(0.0625, t.analysis.contactGapMm, 1e-12);
        assertEquals(before + 0.0625, svc.getConfig().needleZOffsetMm, 1e-12);
        assertTrue(t.applied);

        svc.setDotImager(imager);
        DotLadderCalibration.Request req = new DotLadderCalibration.Request();
        req.rotationsDeg = new double[] {30, 58};
        req.repeats = 2;
        DotLadderCalibration.Result lr = svc.runDotLadder(req).get(60, TimeUnit.SECONDS);
        assertEquals(RunResult.Status.OK, lr.run.status, lr.run.toString());
        assertEquals(2, lr.rows.size());
        assertEquals(2, svc.getDotLadder(lr.calibrationId).size());

        RunResult shots = svc.runShots("waste", 360, 3, true).get(60, TimeUnit.SECONDS);
        assertEquals(RunResult.Status.OK, shots.status, shots.toString());
        assertEquals(3, shots.dotsDone);
    }

    static double primeRawZ(List<String> sent) {
        // the first Z down after the first valve ON is the prime at the waste pad
        int v = sent.indexOf("M106 P3 S255");
        for (String l : sent.subList(v, sent.size())) {
            if (l.startsWith("G1 Z")) {
                return Double.parseDouble(l.split("\\s+")[1].substring(1));
            }
        }
        return Double.NaN;
    }

    /**
     * Review finding: after "I changed the needle", prime, test dot and shots still dispensed with
     * the stale offset (I4 was waived for them). Now they are refused; only the touch-off itself
     * runs (named waiver), needs a fresh needle frame, and primes higher while not touched off.
     */
    @Test
    public void needleChangeBlocksEverythingButTheTouchOff() throws Exception {
        svc.markNeedleChanged();
        sim.server.mark();
        RunResult prime = svc.runShots("waste", 90, 1, true).get(60, TimeUnit.SECONDS);
        assertEquals(RunResult.Status.REFUSED, prime.status, prime.toString());
        assertTrue(prime.interlocks.has("I4"), prime.interlocks.toString());
        // touch-off without a new needle frame: refused too (the old frame belongs to the old needle)
        RunResult to = svc.runNeedleTouchOff(new NeedleTouchOff.Request(), false).get(60, TimeUnit.SECONDS).run;
        assertEquals(RunResult.Status.REFUSED, to.status, to.toString());
        for (String l : sim.server.sinceMark()) {
            assertFalse(l.startsWith("M106") || l.startsWith("G1"), "nothing pressurised or moved: " + l);
        }
        // new frame (the replacement needle is 0.3 mm longer): prime, ladder still refused
        svc.setNeedleAnchor(1.3, 1.05, null, null);
        assertEquals(RunResult.Status.REFUSED, svc.runShots("waste", 90, 1, true).get(60, TimeUnit.SECONDS).status);
        DotLadderCalibration.Request lr = new DotLadderCalibration.Request();
        lr.rotationsDeg = new double[] {30};
        lr.repeats = 1;
        assertEquals(RunResult.Status.REFUSED, svc.runDotLadder(lr).get(60, TimeUnit.SECONDS).run.status);
        // the touch-off runs under its waiver; its prime is 0.5 mm higher than a normal prime
        svc.setDotImager(squashImager());
        sim.server.mark();
        DispenseService.TouchOffResult t = svc.runNeedleTouchOff(new NeedleTouchOff.Request(), true)
                .get(60, TimeUnit.SECONDS);
        assertEquals(RunResult.Status.OK, t.run.status, t.run.toString());
        double offset = -0.25; // anchor offset
        double expected = 63 - (1.0 + offset + 0.15 + 0.5);
        assertEquals(expected, primeRawZ(sim.server.sinceMark()), 1e-4);
        assertTrue(t.run.interlocks.notes.stream().anyMatch(n -> n.contains("touch-off run")),
                t.run.interlocks.toString());
        assertTrue(svc.getConfig().isNeedleTouchedOff());
        // now a prime runs, at the normal height
        sim.server.mark();
        RunResult ok = svc.runShots("waste", 90, 1, true).get(60, TimeUnit.SECONDS);
        assertEquals(RunResult.Status.OK, ok.status, ok.toString());
        assertEquals(63 - (1.0 + svc.getConfig().needleZOffsetMm + 0.15), primeRawZ(sim.server.sinceMark()), 1e-4);
    }
}
