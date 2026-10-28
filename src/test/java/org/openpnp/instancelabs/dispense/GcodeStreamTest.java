package org.openpnp.instancelabs.dispense;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/** G-code stream generation: golden output for a 3-pad toy board, plus validator rules. */
public class GcodeStreamTest {
    static DispenseConfig toyConfig() {
        DispenseConfig c = new DispenseConfig();
        c.locations.put("waste", new DispenseConfig.NamedLocation(60, 30, 1.0));
        c.needleZOffsetMm = 0.05;
        c.minPadTypeStatus = "provisional";
        c.testDotOpticalCheck = false;
        return c;
    }

    static DispensePlan toyPlan(DispenseConfig c) {
        PadType t = DispenseDb.seedPadTypes().get(1); // chip-0603
        t.status = PadTypeStatus.PROVISIONAL;
        t.version = 1;
        List<PadGeometry> pads = new ArrayList<>();
        pads.add(new PadGeometry("R2.1", 110.0, 85.0, 90, 0.8, 0.95, 1.6, t));
        pads.add(new PadGeometry("R1.2", 101.65, 80.0, 0, 0.8, 0.95, 1.6, t));
        pads.add(new PadGeometry("R1.1", 100.0, 80.0, 0, 0.8, 0.95, 1.6, t));
        Calibration cal = new Calibration();
        cal.id = 1;
        cal.qMm3PerRev = 0.47;
        DispensePlan plan = new DispensePlanner(c, new PadTypeResolver(java.util.Collections.singletonList(t), null),
                cal, null).plan("toy", pads, 60, 30);
        plan.planId = "golden";
        return plan;
    }

    static void assertGolden(String name, String actual) throws Exception {
        String golden;
        File out = new File("target/" + name + ".actual");
        try (InputStream in = GcodeStreamTest.class.getResourceAsStream("/dispense/" + name)) {
            if (in == null) {
                Files.write(out.toPath(), actual.getBytes(StandardCharsets.UTF_8));
                throw new AssertionError("golden file missing; actual written to " + out.getAbsolutePath());
            }
            golden = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        if (!golden.equals(actual)) {
            Files.write(out.toPath(), actual.getBytes(StandardCharsets.UTF_8));
        }
        assertEquals(golden, actual);
    }

    @Test
    public void golden3PadBoardWithLiveBacklash() throws Exception {
        DispenseConfig c = toyConfig();
        ToyMapper m = new ToyMapper(150, 100, 31.5, 0);
        // live X/Y: OneSidedPositioning, 0.1 mm, speed factor 0.5; z1 DirectionalCompensation 0 mm
        m.backlash = new AxisBacklash[] {
            new AxisBacklash(org.openpnp.machine.reference.axis.ReferenceControllerAxis.BacklashCompensationMethod.OneSidedPositioning, 0.1, 0.5, 0.226, 1e-4),
            new AxisBacklash(org.openpnp.machine.reference.axis.ReferenceControllerAxis.BacklashCompensationMethod.OneSidedPositioning, 0.1, 0.5, 0.16, 1e-4),
            new AxisBacklash(org.openpnp.machine.reference.axis.ReferenceControllerAxis.BacklashCompensationMethod.DirectionalCompensation, 0.0, 0.1, 0, 1e-4)};
        GcodeProgram p = GcodeStreamBuilder.forPlan(c, m, toyPlan(c), false, false, GcodeStreamBuilder.Part.FULL);
        assertGolden("golden-3pad-backlash.gcode", p.asString());
        assertTrue(GcodeStreamValidator.validate(p, c, "B").isEmpty());
    }

    @Test
    public void golden3PadBoard() throws Exception {
        DispenseConfig c = toyConfig();
        DispensePlan plan = toyPlan(c);
        GcodeProgram p = GcodeStreamBuilder.forPlan(c, new ToyMapper(150, 100, 31.5, 0), plan, false, false,
                GcodeStreamBuilder.Part.FULL);
        assertGolden("golden-3pad.gcode", p.asString());
        assertTrue(GcodeStreamValidator.validate(p, c, "B").isEmpty(), GcodeStreamValidator.validate(p, c, "B").toString());
        assertEquals(3, p.dotCount);
        assertEquals(0.0, p.finalB, 0);
        // final state: N2 at travel Z above the last dot
        assertEquals(31.5, p.finalNozzle[2], 1e-12);
        assertEquals(63 - 31.5, p.finalPlanned[2], 1e-12);
    }

    @Test
    public void bRotationPerDotIsNetOfSuckBack() throws Exception {
        DispenseConfig c = toyConfig();
        DispensePlan plan = toyPlan(c);
        GcodeProgram p = GcodeStreamBuilder.forPlan(c, new ToyMapper(150, 100, 31.5, 0), plan, false, false,
                GcodeStreamBuilder.Part.FULL);
        // B feed-end coordinates advance by exactly prime, test dot, then each dot's rotation
        List<Double> b = new ArrayList<>();
        for (String l : p.text()) {
            if (l.startsWith("G1 B")) {
                b.add(Double.parseDouble(l.substring(4, l.indexOf(' ', 4))));
            }
        }
        // feed, suck-back pairs
        assertEquals(10, b.size());
        List<Double> feedEnds = new ArrayList<>();
        for (int i = 0; i < b.size(); i += 2) {
            feedEnds.add(b.get(i));
            assertEquals(b.get(i) - c.retractDeg, b.get(i + 1), 1e-4);
        }
        double r = plan.dots.get(0).rotationDeg;
        assertEquals(5, feedEnds.size());
        assertEquals(360, feedEnds.get(0), 1e-4);
        assertEquals(360 + r, feedEnds.get(1), 1e-4);
        assertEquals(360 + 2 * r, feedEnds.get(2), 1e-4);
        assertEquals(360 + 4 * r, feedEnds.get(4), 1e-4);
    }

    @Test
    public void negativeExtrudeSignAndZeroDwell() throws Exception {
        DispenseConfig c = toyConfig();
        c.bExtrudeSign = -1;
        c.dwellMs = 0;
        GcodeProgram p = GcodeStreamBuilder.forPlan(c, new ToyMapper(150, 100, 31.5, 0), toyPlan(c), true, false,
                GcodeStreamBuilder.Part.FULL);
        String s = p.asString();
        assertTrue(s.contains("G1 B-360.0000"));
        assertFalse(s.contains("G92 B0\nM201"), "B already zeroed: no G92 at session start");
        int dotG4 = 0;
        boolean inDot = false;
        for (GcodeProgram.Line l : p.lines) {
            inDot |= l.has(GcodeProgram.Marker.DOT_BEGIN) && l.dotIndex >= 0;
            if (inDot && l.text.startsWith("G4")) {
                dotG4++;
            }
            if (l.has(GcodeProgram.Marker.DOT_END)) {
                inDot = false;
            }
        }
        assertEquals(0, dotG4, "dwell 0 -> no per-dot G4 (no planner synchronisation)");
        assertTrue(GcodeStreamValidator.validate(p, c, "B").isEmpty());
    }

    @Test
    public void keepaliveSplitsLongDwells() throws Exception {
        DispenseConfig c = toyConfig();
        c.pumpToValveSettleMs = 2500;
        GcodeProgram p = GcodeStreamBuilder.forPlan(c, new ToyMapper(150, 100, 31.5, 0), toyPlan(c), false, true,
                GcodeStreamBuilder.Part.FULL);
        String s = p.asString();
        assertTrue(s.contains("M990 S3000"));
        assertTrue(s.contains("M106 P2 S255\nG4 P1000\nG4 P1000\nG4 P500\nM106 P3 S255"));
        assertTrue(s.contains("M990 S0"));
        assertTrue(GcodeStreamValidator.validate(p, c, "B").isEmpty());
    }

    @Test
    public void primeAndTestOnlyThenDots() throws Exception {
        DispenseConfig c = toyConfig();
        DispensePlan plan = toyPlan(c);
        GcodeProgram a = GcodeStreamBuilder.forPlan(c, new ToyMapper(150, 100, 31.5, 0), plan, false, false,
                GcodeStreamBuilder.Part.PRIME_AND_TEST);
        assertEquals(0, a.dotCount);
        assertTrue(a.asString().contains("; dot -2 test-dot"));
        GcodeProgram b = GcodeStreamBuilder.forPlan(c, new ToyMapper(60, 30, 31.5, 0), plan, true, false,
                GcodeStreamBuilder.Part.DOTS);
        assertEquals(3, b.dotCount);
        assertTrue(b.asString().contains("; dot -1 prime rot 90.000"));
        assertFalse(b.asString().contains("test-dot"));
    }

    @Test
    public void missingWasteLocationRefusesPrime() {
        DispenseConfig c = toyConfig();
        c.locations.clear();
        assertThrows(Exception.class, () -> GcodeStreamBuilder.forPlan(c, new ToyMapper(150, 100, 31.5, 0),
                toyPlan(toyConfig()), false, false, GcodeStreamBuilder.Part.FULL));
    }

    static GcodeProgram raw(String... lines) {
        GcodeProgram p = new GcodeProgram();
        for (String l : lines) {
            p.lines.add(new GcodeProgram.Line(l, GcodeProgram.Marker.NONE, -9, null, 0, null));
        }
        return p;
    }

    @Test
    public void validatorCatchesUnsafePrograms() {
        DispenseConfig c = toyConfig();
        // I2: B before G92 B0
        assertFalse(GcodeStreamValidator.validate(raw("M106 P2 S255", "M106 P3 S255", "G1 B10 F100", "M107 P3",
                "M107 P2"), c, "B").isEmpty());
        // valve before pump
        assertFalse(GcodeStreamValidator.validate(raw("M106 P3 S255", "M106 P2 S255", "M107 P3", "M107 P2"), c, "B")
                .isEmpty());
        // PWM on the valve (S180 is the live value that the plan forbids)
        assertFalse(GcodeStreamValidator.validate(raw("M106 P2 S255", "M106 P3 S180", "M107 P3", "M107 P2"), c, "B")
                .isEmpty());
        // pump off while valve on
        assertFalse(GcodeStreamValidator.validate(raw("M106 P2 S255", "M106 P3 S255", "M107 P2", "M107 P3"), c, "B")
                .isEmpty());
        // ends pressurised
        assertFalse(GcodeStreamValidator.validate(raw("M106 P2 S255", "M106 P3 S255"), c, "B").isEmpty());
        // B outside a pressure session
        assertFalse(GcodeStreamValidator.validate(raw("G92 B0", "G1 B10 F100"), c, "B").isEmpty());
        // fine
        assertTrue(GcodeStreamValidator.validate(raw("G92 B0", "M106 P2 S255", "M106 P3 S255", "G1 B10 F100",
                "M107 P3", "M107 P2"), c, "B").isEmpty());
    }

    @Test
    public void pneumaticAndWatchdogCommandsAreFixed() {
        // review finding: PWM on the pump (buck) or valve (S180), swapped ports, other outputs
        for (String bad : new String[] {"M106 P2 S128", "M106 P3 S180", "m0106 p2 s128", "M106 P3 S255 X1",
                "M106 S255", "M106 P1 S255", "M107 P1", "M107", "M42 P10 S255", "M43 T P10", "M3 S1000", "M990 S50",
                "M990 S1", "M990 P1", "M115 S1", "T0", "N5 M106 P2 S255", "$X"}) {
            assertTrue(ControllerCommands.check(bad) != null, bad);
        }
        for (String good : new String[] {"M106 P2 S255", "m106  p3  s255", "M107 P2", "M107 P3", "M115", "M990",
                "M990 S0", "M990 S3000", "G1 Z31.5000 F1500", "G92 B0", "M400", "M410", "M18 B", "G4 P100",
                "M204 S200", "; comment"}) {
            assertTrue(ControllerCommands.check(good) == null, good);
        }
        DispenseConfig c = GcodeStreamTest.toyConfig();
        List<String> p = GcodeStreamValidator.validate(raw("M106 P2 S255", "M106 P3 S180", "M42 P10 S255",
                "M107 P1", "M107 P3", "M107 P2"), c, "B");
        assertEquals(3, p.size(), p.toString());
        // the settings no longer carry G-code strings: nothing to edit, old files still load
        assertFalse(new DispenseConfig().toJson().contains("Gcode"));
        DispenseConfig old = DispenseConfig.fromJson("{\"schemaVersion\":1,\"valveOffGcode\":\"M107 P1\","
                + "\"keepalivePingGcode\":\"M106 P3 S180\",\"needleGapMm\":0.12}");
        assertEquals(0.12, old.needleGapMm, 0);
        assertTrue(old.validate().isEmpty());
        assertTrue(new DispenseConfig().validate().isEmpty());
        DispenseConfig w = new DispenseConfig();
        w.keepaliveWindowMs = 1500; // less than 2 x the 1000 ms ping interval
        assertEquals(1, w.validate().size());
    }
}
