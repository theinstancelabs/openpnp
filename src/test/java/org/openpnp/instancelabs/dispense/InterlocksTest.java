package org.openpnp.instancelabs.dispense;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;

import org.junit.jupiter.api.Test;

/** Pre-flight interlocks (plan §5.5). */
public class InterlocksTest {
    static Interlocks.Inputs ok() {
        Interlocks.Inputs in = new Interlocks.Inputs();
        DispenseConfig c = GcodeStreamTest.toyConfig();
        c.needleZOffsetSetAtMs = 2000L;
        c.needleChangedAtMs = 1000L;
        in.cfg = c;
        in.machineEnabled = true;
        in.machineHomed = true;
        in.nozzleName = "N2";
        in.compatibleNozzleTips = Collections.emptyList();
        in.plan = GcodeStreamTest.toyPlan(c);
        in.estimatedPressurisedSeconds = 10;
        return in;
    }

    @Test
    public void allClear() {
        InterlockReport r = Interlocks.check(ok());
        assertTrue(r.ok(), r.toString());
    }

    @Test
    public void i3PickNozzleRefused() {
        Interlocks.Inputs in = ok();
        in.compatibleNozzleTips = Arrays.asList("NT1", "NT2");
        InterlockReport r = Interlocks.check(in);
        assertTrue(r.has("I3"));
        assertTrue(r.toString().contains("NT1"));
        in = ok();
        in.loadedNozzleTip = "NT1";
        assertTrue(Interlocks.check(in).has("I3"));
    }

    @Test
    public void i4NeedleTouchOffAfterChange() {
        Interlocks.Inputs in = ok();
        in.cfg.needleChangedAtMs = 3000L;
        assertTrue(Interlocks.check(in).has("I4"));
        in.cfg.needleZOffsetSetAtMs = null;
        in.cfg.needleChangedAtMs = null;
        assertTrue(Interlocks.check(in).has("I4"));
    }

    @Test
    public void i5PressureWindowHook() {
        Interlocks.Inputs in = ok();
        in.cfg.pressureInterlockEnabled = true;
        assertTrue(Interlocks.check(in).has("I5"), "enabled without sensor refuses");
        in.pressureSensor = () -> 25.0;
        assertTrue(Interlocks.check(in).has("I5"));
        in.pressureSensor = () -> 8.0;
        assertFalse(Interlocks.check(in).has("I5"));
        in.cfg.pressureInterlockEnabled = false;
        in.pressureSensor = () -> 99.0;
        assertFalse(Interlocks.check(in).has("I5"), "disabled by default");
    }

    @Test
    public void i7EstimatedOnTime() {
        Interlocks.Inputs in = ok();
        in.estimatedPressurisedSeconds = in.cfg.maxContinuousOnTimeS + 1;
        assertTrue(Interlocks.check(in).has("I7"));
    }

    @Test
    public void i9PrimeAndOpticalCheck() {
        Interlocks.Inputs in = ok();
        in.cfg.locations.clear();
        assertTrue(Interlocks.check(in).has("I9"));
        in = ok();
        in.cfg.testDotOpticalCheck = true;
        in.opticalCheckAvailable = false;
        assertTrue(Interlocks.check(in).has("I9"));
        in.opticalCheckAvailable = true;
        assertFalse(Interlocks.check(in).has("I9"));
        in.cfg.primeAndTestDot = false;
        in.cfg.locations.clear();
        assertFalse(Interlocks.check(in).has("I9"));
    }

    @Test
    public void flaggedPadsAndMachineState() {
        Interlocks.Inputs in = ok();
        in.plan.pads.get(0).flags.add(PadFlag.STATUS_BELOW_MIN);
        assertTrue(Interlocks.check(in).has("PLAN"));
        in.cfg.refuseFlaggedPads = false;
        assertFalse(Interlocks.check(in).has("PLAN"));
        in.machineHomed = false;
        assertTrue(Interlocks.check(in).has("MACHINE"));
        in = ok();
        in.cfg.valveOnGcode = "M106 P3 S180";
        assertTrue(Interlocks.check(in).has("CONFIG"));
    }
}
