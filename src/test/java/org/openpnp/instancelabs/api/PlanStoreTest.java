package org.openpnp.instancelabs.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;
import org.openpnp.instancelabs.dispense.DispensePlan;

/** The run subsets of a plan (POST /api/run with maxDots or padIds). */
public class PlanStoreTest {
    /** A plan in path order, one entry per pad: {padId, dots, net steps per dot}. */
    static DispensePlan plan(Object[]... pads) {
        DispensePlan p = new DispensePlan();
        p.planId = "p";
        for (Object[] x : pads) {
            DispensePlan.PlannedPad pad = new DispensePlan.PlannedPad();
            pad.padId = (String) x[0];
            pad.dotCount = (Integer) x[1];
            pad.dotSteps = (Integer) x[2];
            p.pads.add(pad);
            for (int k = 0; k < pad.dotCount; k++) {
                DispensePlan.PlannedDot d = new DispensePlan.PlannedDot();
                d.index = p.dots.size();
                d.padId = pad.padId;
                d.padIndex = p.pads.size() - 1;
                d.netSteps = pad.dotSteps;
                d.xMm = 100 + d.index;
                d.yMm = 80;
                p.dots.add(d);
            }
        }
        return p;
    }

    /**
     * Review 1: J1's two pads named 6 share the pad id J1.6. A subset keeps every dot's own pad (by the
     * plan's pad index) and its dose, so the stream does not take the two for one pad split in two.
     */
    @Test
    public void aSubsetKeepsEachDotsPadAndDose() {
        DispensePlan p = plan(new Object[] {"J1.6", 1, 800}, new Object[] {"J1.1", 1, 1600},
                new Object[] {"J1.6", 1, 800}, new Object[] {"R1.1", 1, 800});
        DispensePlan s = PlanStore.subset(p, null, 3);
        assertEquals(3, s.dots.size());
        assertEquals(3, s.pads.size());
        for (int i = 0; i < 3; i++) {
            assertSame(p.pads.get(i), s.pads.get(i));
            assertEquals(i, s.dots.get(i).padIndex, "dot " + i);
            assertEquals(p.dots.get(i).netSteps, s.dots.get(i).netSteps, "dot " + i);
        }
        // the first two dots: the second J1.6 pad has none, so it is not in the subset
        DispensePlan two = PlanStore.subset(p, null, 2);
        assertEquals(2, two.pads.size());
        assertSame(p.pads.get(0), two.pads.get(0));
        assertSame(p.pads.get(1), two.pads.get(1));
    }
}
