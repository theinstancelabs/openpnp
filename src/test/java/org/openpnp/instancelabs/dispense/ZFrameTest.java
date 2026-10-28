package org.openpnp.instancelabs.dispense;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The I4 Z frame (motion review findings): needle frame anchored by an N1 and a needle contact,
 * places and surfaces checked against it, per-dot Z from the probed board plane, a hard needle
 * floor, travel / hop bounds on the see-saw. Numbers from the bench: N1 contacts on the ftp board
 * 5.425..5.70 (runs/zmap.json), the needle dispenses at raw z1 ~52.45 (N2 Z ~10.55,
 * runs/board_plan_r2.json), so the N1-to-needle offset is ~4.9 mm. ToyMapper: raw Z = 63 - N2 Z.
 */
public class ZFrameTest {
    /** zmap.json points (machine x, y, N1 contact z). */
    static final double[][] ZMAP = {{309.702, 123.219, 5.55}, {309.873, 152.064, 5.6}, {310.050, 181.903, 5.7},
        {338.714, 118.976, 5.475}, {338.909, 151.800, 5.5}, {347.083, 179.577, 5.6}, {368.774, 122.682, 5.425},
        {368.945, 151.527, 5.475}, {369.122, 181.366, 5.575}};

    static List<DispenseConfig.SurfacePoint> points(double[][] p, double dz) {
        List<DispenseConfig.SurfacePoint> l = new ArrayList<>();
        for (double[] v : p) {
            l.add(new DispenseConfig.SurfacePoint(v[0], v[1], v[2] + dz));
        }
        return l;
    }

    static DispenseConfig cfg() {
        DispenseConfig c = new DispenseConfig();
        c.minPadTypeStatus = "provisional";
        c.testDotOpticalCheck = false;
        c.needleAnchor = new DispenseConfig.NeedleAnchor(5.5, 10.4, 338.9, 151.8, 1000L);
        c.needleZOffsetMm = 4.9;
        c.needleZOffsetSetAtMs = 2000L;
        c.locations.put("waste", new DispenseConfig.NamedLocation(290, 125, 5.6));
        DispenseConfig.SurfaceMap m = new DispenseConfig.SurfaceMap();
        m.points = points(ZMAP, 0);
        c.surfaces.put("board", m);
        return c;
    }

    /** Three 0603 pads inside the probed area, at the given board location Z. */
    static DispensePlan plan(DispenseConfig c, double boardZ) {
        PadType t = DispenseDb.seedPadTypes().get(1); // chip-0603
        t.status = PadTypeStatus.PROVISIONAL;
        t.version = 1;
        List<PadGeometry> pads = new ArrayList<>();
        pads.add(new PadGeometry("R1.1", 320.0, 130.0, 0, 0.8, 0.95, boardZ, t));
        pads.add(new PadGeometry("R2.1", 350.0, 160.0, 0, 0.8, 0.95, boardZ, t));
        pads.add(new PadGeometry("R3.1", 365.0, 178.0, 0, 0.8, 0.95, boardZ, t));
        Calibration cal = new Calibration();
        cal.id = 1;
        cal.qMm3PerRev = 0.47;
        return new DispensePlanner(c, new PadTypeResolver(Collections.singletonList(t), null), cal, null)
                .withSurface(DispenseService.planeOf(c, c.boardSurfaceName)).plan("ftp", pads, 290, 125);
    }

    static InterlockReport check(DispenseConfig c, DispensePlan p) {
        Interlocks.Inputs in = new Interlocks.Inputs();
        in.cfg = c;
        in.machineEnabled = true;
        in.machineHomed = true;
        in.nozzleName = "N2";
        in.compatibleNozzleTips = Collections.emptyList();
        in.plan = p;
        in.estimatedPressurisedSeconds = 10;
        in.scope = DispenseService.boardScope(c, p);
        return Interlocks.check(in);
    }

    static List<String> i4(InterlockReport r) {
        List<String> l = new ArrayList<>();
        for (InterlockReport.Violation v : r.violations) {
            if (v.code.equals("I4")) {
                l.add(v.message);
            }
        }
        return l;
    }

    @Test
    public void dotsTakeTheirZFromTheProbedPlaneAndLandAtTheBenchHeight() throws Exception {
        DispenseConfig c = cfg();
        DispensePlan p = plan(c, 6.2); // the job's board Z (6.2) is not used for the needle
        assertTrue(check(c, p).ok(), check(c, p).toString());
        SurfacePlane pl = DispenseService.planeOf(c, "board");
        for (DispensePlan.PlannedDot d : p.dots) {
            assertEquals(pl.z(d.xMm, d.yMm), d.surfaceZMm, 1e-9);
            assertEquals(d.surfaceZMm + 4.9 + 0.15, d.dispenseZMm, 1e-9);
            double raw = 63 - d.dispenseZMm;
            assertTrue(raw > 52.2 && raw < 52.5, "raw dispense Z " + raw + " (bench 52.24..52.46)");
        }
        ToyMapper m = new ToyMapper(290, 125, 31.5, 0);
        GcodeProgram prog = GcodeStreamBuilder.forPlan(c, m, p, false, false, GcodeStreamBuilder.Part.FULL);
        assertEquals(Collections.emptyList(), GcodeStreamValidator.validate(prog, c, "B",
                GcodeStreamValidator.zWindow(c, m)));
    }

    @Test
    public void placesOrSurfacesTakenInTheNeedleFrameAreRefused() {
        // review scenario: waste captured with 'Use N2 Z (tip touching)' while the offset was 0 (needle frame ~10.4)
        DispenseConfig c = cfg();
        c.locations.put("waste", new DispenseConfig.NamedLocation(290, 125, 10.4));
        List<String> v = i4(check(c, plan(c, 5.5)));
        assertTrue(v.stream().anyMatch(x -> x.contains("place 'waste'")), v.toString());
        // board points taken with the needle
        c = cfg();
        c.surfaces.get("board").points = points(ZMAP, 4.9);
        v = i4(check(c, plan(c, 5.5)));
        assertTrue(v.stream().anyMatch(x -> x.contains("outside the needle-frame surface")), v.toString());
    }

    @Test
    public void noNeedleFrameOrAStaleOneIsRefused() {
        DispenseConfig c = cfg();
        c.needleAnchor = null;
        assertTrue(i4(check(c, plan(c, 5.5))).get(0).contains("needle Z frame is not set"));
        c = cfg();
        c.needleChangedAtMs = 1500L; // after the anchor (1000), before the offset (2000)
        assertTrue(i4(check(c, plan(c, 5.5))).get(0).contains("changed after the needle Z frame"));
        // the review's offset-0 case: a touch-off cannot reach the ~4.9 mm frame offset
        c = cfg();
        c.needleZOffsetMm = 0.0;
        List<String> v = i4(check(c, plan(c, 5.5)));
        assertTrue(v.stream().anyMatch(x -> x.contains("away from the needle frame")), v.toString());
        assertTrue(c.isNeedleAnchored());
        c.needleZOffsetSetAtMs = 500L; // offset older than the anchor
        assertFalse(c.isNeedleTouchedOff());
    }

    @Test
    public void aBoardMustBeProbedFlatAroundEveryDotAndPlannedOnTheCurrentMap() {
        DispenseConfig c = cfg();
        c.surfaces.clear();
        DispensePlan p = plan(c, 5.5);
        assertNull(p.surfaceFingerprint);
        assertTrue(p.warnings.stream().anyMatch(w -> w.contains("no probed board surface")), p.warnings.toString());
        assertTrue(i4(check(c, p)).get(0).contains("no 'board' surface"));

        c = cfg();
        p = plan(c, 5.5);
        assertNotNull(p.surfaceFingerprint);
        // the surface was re-probed after planning
        c.surfaces.get("board").points.get(0).z += 0.01;
        assertTrue(i4(check(c, p)).stream().anyMatch(x -> x.contains("plan the board again")));

        // a dot outside the probed area (pads far from the points)
        c = cfg();
        c.surfaces.get("board").points = points(new double[][] {{309, 123, 5.5}, {369, 123, 5.5}, {339, 150, 5.5}}, 0);
        v(i4(check(c, plan(c, 5.5))), "outside the probed 'board' surface");

        // not flat enough for a plane: one point 0.2 mm high (gap budget 0.075)
        c = cfg();
        c.surfaces.get("board").points.get(4).z += 0.2;
        v(i4(check(c, plan(c, 5.5))), "not flat enough");

        // degenerate: three points on a line
        List<DispenseConfig.SurfacePoint> line = points(new double[][] {{300, 120, 5.5}, {330, 150, 5.5},
            {360, 180, 5.5}}, 0);
        assertThrows(IllegalArgumentException.class, () -> SurfacePlane.fit(line));
    }

    static void v(List<String> violations, String text) {
        assertTrue(violations.stream().anyMatch(x -> x.contains(text)), violations.toString());
    }

    @Test
    public void surfacePlaneFitHullAndResiduals() {
        SurfacePlane pl = SurfacePlane.fit(points(ZMAP, 0));
        assertEquals(9, pl.pointCount);
        assertTrue(pl.maxAbsResidualMm < 0.05, "zmap.json residual " + pl.maxAbsResidualMm);
        assertEquals(0, pl.distanceOutside(340, 150), 0);
        assertEquals(10, pl.distanceOutside(379.122 + 0.0, 181.366 - 30), 1.5);
        assertTrue(pl.distanceOutside(250, 150) > 50);
    }

    /** Review finding: travel / hop values on the see-saw could drive a nozzle into the work. */
    @Test
    public void travelAndHopStayInsideTheSafeZone() throws Exception {
        DispenseConfig c = cfg();
        DispensePlan p = plan(c, 5.5);
        ToyMapper m = new ToyMapper(290, 125, 31.5, 0);
        // travelSafeZMm = 5 (a typo): raw 58, the needle 5 mm into the waste pad
        c.travelSafeZMm = 5;
        Exception e = assertThrows(IllegalStateException.class,
                () -> GcodeStreamBuilder.forPlan(c, m, p, false, false, GcodeStreamBuilder.Part.FULL));
        assertTrue(e.getMessage().contains("safe zone"), e.getMessage());
        // travelSafeZMm = 62 ("safer"): raw 1, N1 4.5 mm below the board
        c.travelSafeZMm = 62;
        assertThrows(IllegalStateException.class,
                () -> GcodeStreamBuilder.forPlan(c, m, p, false, false, GcodeStreamBuilder.Part.FULL));
        // hopHeightMm 50: the settings refuse it, and a hop above travel Z is refused by the builder
        DispenseConfig h = cfg();
        h.hopHeightMm = 50;
        assertFalse(h.validate().isEmpty());
        GcodeStreamBuilder b = new GcodeStreamBuilder(cfg(), m, false, false);
        b.sessionStart();
        b.pressurize();
        e = assertThrows(IllegalStateException.class,
                () -> b.dot(new GcodeStreamBuilder.DotSpec(320, 130, 10.6, 40.0, 30, 0, "x")));
        assertTrue(e.getMessage().contains("above the travel Z"), e.getMessage());
        DispenseConfig g = cfg();
        g.needleGapMm = 2;
        assertFalse(g.validate().isEmpty());
    }

    /** Review finding: no hard Z floor. The builder and, independently, the validator enforce one. */
    @Test
    public void needleFloorInTheBuilderAndOnTheRawLines() throws Exception {
        DispenseConfig c = cfg();
        ToyMapper m = new ToyMapper(290, 125, 31.5, 0);
        // floor = 5.5 - 1.0 + 4.9 - 0.1 = 9.3 (N2 Z) = raw 53.7
        assertEquals(9.3, c.needleFloorZMm(), 1e-9);
        GcodeStreamBuilder b = new GcodeStreamBuilder(c, m, false, false);
        b.sessionStart();
        b.pressurize();
        Exception e = assertThrows(IllegalStateException.class,
                () -> b.dot(new GcodeStreamBuilder.DotSpec(320, 130, 5.0, 6.0, 30, 0, "x")));
        assertTrue(e.getMessage().contains("needle floor"), e.getMessage());
        // a raw line past the floor (the review's G1 Z58.0000 / G1 Z62.8500) is refused by the validator
        GcodeStreamValidator.ZWindow w = GcodeStreamValidator.zWindow(c, m);
        for (String bad : new String[] {"G1 Z58.0000 F1500", "G1 Z62.8500 F3000", "G0 X10 Z1.0000 F1500"}) {
            GcodeProgram prog = new GcodeProgram();
            prog.lines.add(new GcodeProgram.Line(bad, GcodeProgram.Marker.NONE, -9, null, 0, null));
            List<String> v = GcodeStreamValidator.validate(prog, c, "B", w);
            assertEquals(1, v.size(), bad + " " + v);
            assertTrue(v.get(0).contains("I4: raw Z"), v.toString());
        }
        GcodeProgram ok = new GcodeProgram();
        ok.lines.add(new GcodeProgram.Line("G1 Z52.4500 F3000", GcodeProgram.Marker.NONE, -9, null, 0, null));
        assertTrue(GcodeStreamValidator.validate(ok, c, "B", w).isEmpty());
    }
}
