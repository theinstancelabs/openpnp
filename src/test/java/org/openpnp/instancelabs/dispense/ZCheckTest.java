package org.openpnp.instancelabs.dispense;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Z after a home: references (board tied, blank, anchor X/Y) measured before the home, the 0.03 mm
 * verdict, and the I4 rule that refuses needle work on Z data older than the last home until a Z check
 * for that home passed.
 */
public class ZCheckTest {
    static final long ANCHOR_AT = 1_793_200_000_000L;
    static final long TOUCH_OFF_AT = ANCHOR_AT + 3 * 60_000;
    static final long BOARD_AT = ANCHOR_AT + 38 * 60_000;
    static final long BLANK_AT = ANCHOR_AT + 30 * 60_000;
    static final long HOME_AT = ANCHOR_AT + 77 * 60_000;

    static DispenseConfig cfg() {
        DispenseConfig c = ZFrameTest.cfg();
        c.needleAnchor.setAtMs = ANCHOR_AT;
        c.needleZOffsetSetAtMs = TOUCH_OFF_AT;
        c.surfaces.get("board").setAtMs = BOARD_AT;
        DispenseConfig.SurfaceMap blank = new DispenseConfig.SurfaceMap();
        blank.points = ZFrameTest.points(new double[][] {{322.0, 225.5, 5.62}, {360.0, 225.5, 5.60},
            {340.0, 240.0, 5.61}}, 0);
        blank.setAtMs = BLANK_AT;
        c.surfaces.put("blank", blank);
        // the purge row on the blank: a board run's scope uses the blank surface too
        c.purgeRow.x0Mm = 330.0;
        c.purgeRow.y0Mm = 230.0;
        c.purgeRow.spots = 10;
        return c;
    }

    static InterlockReport check(DispenseConfig c, Long home, ZCheck.Result z, boolean zCheckFirst) {
        DispensePlan p = ZFrameTest.plan(c, 5.5);
        Interlocks.Inputs in = new Interlocks.Inputs();
        in.cfg = c;
        in.machineEnabled = true;
        in.machineHomed = true;
        in.nozzleName = "N2";
        in.compatibleNozzleTips = Collections.emptyList();
        in.plan = p;
        in.estimatedPressurisedSeconds = 10;
        in.scope = DispenseService.boardScope(c, p);
        in.lastHomeAtMs = home;
        in.zCheck = z;
        in.zCheckFirst = zCheckFirst;
        return Interlocks.check(in);
    }

    @Test
    public void referencesInOrderAndOnlyBeforeTheHome() {
        DispenseConfig c = cfg();
        List<ZCheck.Reference> l = ZCheck.references(c, ZFrameTest.SEAT, HOME_AT);
        assertEquals(5 + 3 + 1, l.size());
        assertEquals("board", l.get(0).name);
        assertEquals(303.848, l.get(0).x, 1e-9);
        assertEquals(5.425, l.get(0).z, 1e-9);
        assertEquals(Long.valueOf(BOARD_AT), l.get(0).atMs);
        assertEquals("blank", l.get(5).name);
        assertEquals(322.0, l.get(5).x, 1e-9);
        assertEquals(ZCheck.NEEDLE_FRAME, l.get(8).name);
        assertEquals(333.1, l.get(8).x, 1e-9);
        assertEquals(5.5, l.get(8).z, 1e-9);
        // a board surface tied to another registration is no reference
        l = ZCheck.references(c, "another-seat", HOME_AT);
        assertEquals("blank", l.get(0).name);
        assertEquals(4, l.size());
        // a surface probed after the home is in the new frame already
        c.surfaces.get("blank").setAtMs = HOME_AT + 1;
        l = ZCheck.references(c, ZFrameTest.SEAT, HOME_AT);
        assertEquals(6, l.size());
        assertEquals(ZCheck.NEEDLE_FRAME, l.get(5).name);
        // the anchor without X/Y has no spot to touch
        c.needleAnchor.xMm = null;
        c.surfaces.get("board").setAtMs = HOME_AT + 1;
        assertTrue(ZCheck.references(c, ZFrameTest.SEAT, HOME_AT).isEmpty());
        assertEquals("Z must be checked after the home at " + ZCheck.clock(HOME_AT) + ", but no point measured before "
                + "it is left to touch (no blank or board surface, no needle-frame X/Y): record the needle frame again",
                ZCheck.noReferenceMessage(HOME_AT));
    }

    @Test
    public void verdictAtThreeHundredthsOfAMillimetre() {
        ZCheck.Reference r = new ZCheck.Reference();
        r.z = 5.620;
        assertTrue(ZCheck.verdict(r, 5.620 + 0.029));
        assertTrue(ZCheck.verdict(r, 5.620 - 0.029));
        assertFalse(ZCheck.verdict(r, 5.620 + 0.031));
        assertFalse(ZCheck.verdict(r, 5.620 - 0.031));
        ZCheck.Result res = ZCheck.result(r, 5.651, HOME_AT + 10, HOME_AT);
        assertFalse(res.pass);
        assertEquals(0.031, res.deltaMm, 1e-9);
    }

    @Test
    public void noRuleWithoutAKnownHome() {
        assertTrue(check(cfg(), null, null, false).ok(), check(cfg(), null, null, false).toString());
        assertTrue(ZCheck.stale(cfg(), new Interlocks.Scope(), null).isEmpty());
    }

    @Test
    public void staleDataAreNamedWithTheirTimes() {
        InterlockReport r = check(cfg(), HOME_AT, null, false);
        List<String> i4 = ZFrameTest.i4(r);
        assertEquals(1, i4.size(), i4.toString());
        String t = ZCheck.clock(HOME_AT);
        assertEquals("the needle frame (" + ZCheck.clock(ANCHOR_AT) + "), the touch-off (" + ZCheck.clock(TOUCH_OFF_AT)
                + "), the 'board' surface (" + ZCheck.clock(BOARD_AT) + ") and the 'blank' surface ("
                + ZCheck.clock(BLANK_AT) + ") were measured before the home at " + t + ", and Z has not been checked "
                + "since: Check Z (one N1 touch, \u2248 20 s); Run does it by itself", i4.get(0));
        // data measured after the home are not stale
        DispenseConfig c = cfg();
        c.needleAnchor.setAtMs = HOME_AT + 1;
        c.needleZOffsetSetAtMs = HOME_AT + 2;
        c.surfaces.get("board").setAtMs = HOME_AT + 3;
        c.surfaces.get("blank").setAtMs = HOME_AT + 3;
        assertTrue(check(c, HOME_AT, null, false).ok());
        c.surfaces.get("blank").setAtMs = BLANK_AT;
        assertTrue(ZFrameTest.i4(check(c, HOME_AT, null, false)).get(0)
                .startsWith("the 'blank' surface (" + ZCheck.clock(BLANK_AT) + ") was measured before the home"));
        assertTrue(ZCheck.clock(HOME_AT).matches("\\d\\d:\\d\\d"));
        assertTrue(ZCheck.dayClock(HOME_AT).matches("\\d\\d-\\d\\d \\d\\d:\\d\\d"));
    }

    @Test
    public void onlyAPassingCheckForThisHomeAccepts() {
        DispenseConfig c = cfg();
        ZCheck.Reference ref = ZCheck.references(c, ZFrameTest.SEAT, HOME_AT).get(5); // the blank's first point
        ZCheck.Result pass = ZCheck.result(ref, 5.63, HOME_AT + 60_000, HOME_AT);
        assertTrue(pass.pass);
        assertTrue(check(c, HOME_AT, pass, false).ok());
        // the same check after a later home does not count
        assertFalse(check(c, HOME_AT + 3_600_000, pass, false).ok());
        // a failed check for this home: "Z moved"
        ZCheck.Result moved = ZCheck.result(ref, 4.92, HOME_AT + 60_000, HOME_AT);
        assertFalse(moved.pass);
        List<String> i4 = ZFrameTest.i4(check(c, HOME_AT, moved, false));
        assertEquals(1, i4.size());
        assertEquals("Z moved -0.70 mm at the home of " + ZCheck.clock(HOME_AT) + ": N1 touched the blank at X 322.00, "
                + "Y 225.50 at Z 4.920, where it touched 5.620 at " + ZCheck.clock(BLANK_AT) + ". The needle frame, the "
                + "touch-off and the surfaces are off by this (the needle by about twice). Record the needle frame "
                + "again (Setup \u203a Needle Z frame), touch off, probe the blank and register the board again.",
                i4.get(0));
        // the failed check stands even when the task would check Z itself
        assertEquals(i4, ZFrameTest.i4(check(c, HOME_AT, moved, true)));
    }

    @Test
    public void aTaskThatChecksZItselfGetsANote() {
        InterlockReport r = check(cfg(), HOME_AT, null, true);
        assertTrue(r.ok(), r.toString());
        assertTrue(r.notes.contains("I4: Z data predate the home at " + ZCheck.clock(HOME_AT)
                + ": checks Z first (one N1 touch)"), r.notes.toString());
        // a run that will home makes every Z datum stale: it checks Z after its home
        Interlocks.Inputs in = new Interlocks.Inputs();
        DispenseConfig c = cfg();
        DispensePlan p = ZFrameTest.plan(c, 5.5);
        in.cfg = c;
        in.machineEnabled = true;
        in.nozzleName = "N2";
        in.compatibleNozzleTips = new ArrayList<>();
        in.plan = p;
        in.scope = DispenseService.boardScope(c, p);
        in.homeFirst = true;
        in.zCheckFirst = true;
        r = Interlocks.check(in);
        assertTrue(r.ok(), r.toString());
        assertTrue(r.notes.contains("I4: homes first, then checks Z (one N1 touch)"), r.notes.toString());
        assertTrue(r.notes.contains("MACHINE: not homed: homes first"), r.notes.toString());
    }

    @Test
    public void scopesWithoutNeedleMotionHaveNoRule() {
        Interlocks.Inputs in = new Interlocks.Inputs();
        in.cfg = cfg();
        in.machineEnabled = true;
        in.machineHomed = true;
        in.compatibleNozzleTips = new ArrayList<>();
        in.scope = Interlocks.Scope.noNeedle();
        in.lastHomeAtMs = HOME_AT;
        assertTrue(Interlocks.check(in).ok());
        // shots at the waste: the needle frame and the touch-off only
        in.scope = DispenseService.shotsScope("waste", true);
        List<String> i4 = ZFrameTest.i4(Interlocks.check(in));
        assertEquals(1, i4.size());
        assertTrue(i4.get(0).startsWith("the needle frame (" + ZCheck.clock(ANCHOR_AT) + ") and the touch-off ("
                + ZCheck.clock(TOUCH_OFF_AT) + ") were measured before the home"), i4.get(0));
    }
}
