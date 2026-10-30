package org.openpnp.instancelabs.dispense;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.openpnp.instancelabs.padprofile.Tip;

/**
 * Tip setups (§22.2, §22.8): a setup's dot table is the newest passing optical record per dose of the setup at its
 * recipe and paste and the simulator flag, any age; failed records, other setups, another recipe or paste, a job
 * flagged wrong-tip and the other simulator flag never enter it; the mass per step is the newest confirmed weighing.
 */
public class DotTableTest {
    static final String S = "25g-straight";
    long nextId = 1;

    DoseRecord optical(DispenseConfig c, int steps, double dMean, double d95, boolean pass, long at) {
        DoseRecord r = DoseRecordTest.record(DoseRecord.OPTICAL, steps, c, at);
        r.id = nextId++;
        r.tipKey = S;
        r.diameterMeanMm = dMean;
        r.diameterP95Mm = d95;
        r.pass = pass;
        r.jobId = "job-a";
        return r;
    }

    DoseRecord coupon(DispenseConfig c, int steps, long at) {
        DoseRecord r = DoseRecordTest.record(DoseRecord.COUPON, steps, c, at);
        r.id = nextId++;
        r.tipKey = S;
        r.jobId = "job-a";
        return r;
    }

    DoseRecord mass(DoseRecord coupon, double totalMg, Long confirmedBy) {
        DoseRecord m = DoseRecordTest.mass(coupon, totalMg);
        m.id = nextId++;
        m.createdAtMs = coupon.createdAtMs + 600_000;
        m.confirmedBy = confirmedBy;
        m.jobId = coupon.jobId;
        return m;
    }

    @Test
    public void theNewestPassingOpticalRecordPerDoseAnyAge() {
        DispenseConfig c = new DispenseConfig();
        List<DoseRecord> l = new ArrayList<>();
        l.add(optical(c, 400, 0.60, 0.66, true, 1_000L)); // years old: still in the table
        l.add(optical(c, 400, 0.62, 0.69, true, 5_000L));
        l.add(optical(c, 400, 0.70, 0.95, false, 6_000L)); // failed: never
        l.add(optical(c, 800, 0.81, 0.88, true, 7_000L));
        DoseRecord other = optical(c, 1600, 1.0, 1.1, true, 8_000L);
        other.tipKey = "ad22tta-b"; // another setup's
        l.add(other);
        DoseRecord recipe = optical(c, 1600, 1.0, 1.1, true, 9_000L);
        recipe.doseFp = "another-recipe";
        l.add(recipe);
        DoseRecord paste = optical(c, 1600, 1.0, 1.1, true, 9_100L);
        paste.pasteKey = "another-paste";
        l.add(paste);
        DoseRecord sim = optical(c, 1600, 1.0, 1.1, true, 9_200L);
        sim.simulated = true;
        l.add(sim);
        DoseRecord flagged = optical(c, 1600, 1.0, 1.1, true, 9_300L);
        flagged.jobId = "job-wrong";
        l.add(flagged);
        DoseRecords.DotTable t = DoseRecords.dotTable(l, S, c.profile.doseFingerprint(), c.paste.key, false,
                Collections.singleton("job-wrong"));
        assertEquals(2, t.doses.size(), t.doses.keySet().toString());
        Tip.Measured q = t.doses.get(400);
        assertEquals(0.62, q.dMeanMm, 0);
        assertEquals(0.69, q.d95Mm, 0);
        assertEquals(2, q.recordId);
        assertEquals(5_000L, q.atMs);
        assertEquals(0.88, t.doses.get(800).d95Mm, 0);
        assertNull(t.mgPerStep, "never weighed");
        // the simulator's table has the simulator's record only
        DoseRecords.DotTable s = DoseRecords.dotTable(l, S, c.profile.doseFingerprint(), c.paste.key, true,
                Collections.<String>emptySet());
        assertEquals(Collections.singleton(1600), s.doses.keySet());
    }

    @Test
    public void theMassPerStepIsTheNewestConfirmedWeighing() {
        DispenseConfig c = new DispenseConfig();
        List<DoseRecord> l = new ArrayList<>();
        DoseRecord k1 = coupon(c, 800, 10_000L);
        DoseRecord k2 = coupon(c, 800, 20_000L);
        DoseRecord k3 = coupon(c, 800, 30_000L);
        l.add(k1);
        l.add(k2);
        l.add(k3);
        DoseRecord m1 = mass(k1, 68 * 0.8, null);
        l.add(m1);
        DoseRecord m2 = mass(k2, 68 * 0.82, m1.id); // agrees with m1: confirmed
        l.add(m2);
        DoseRecord m3 = mass(k3, 68 * 1.2, null); // newer, but weighed once
        l.add(m3);
        DoseRecords.DotTable t = DoseRecords.dotTable(l, S, c.profile.doseFingerprint(), c.paste.key, false,
                Collections.<String>emptySet());
        assertTrue(t.mgPerStepConfirmed);
        assertEquals(0.82 / 800, t.mgPerStep, 1e-9);
        assertEquals(m2.createdAtMs, (long) t.mgPerStepAtMs);
        // without the confirmed pair: the newest single weighing
        l.remove(m2);
        DoseRecords.DotTable once = DoseRecords.dotTable(l, S, c.profile.doseFingerprint(), c.paste.key, false,
                Collections.<String>emptySet());
        assertFalse(once.mgPerStepConfirmed);
        assertEquals(1.2 / 800, once.mgPerStep, 1e-9);
        // a skipped weighing is no reading; the job flagged wrong-tip leaves the table
        Set<String> flagged = Collections.singleton("job-a");
        DoseRecords.DotTable none = DoseRecords.dotTable(l, S, c.profile.doseFingerprint(), c.paste.key, false, flagged);
        assertNull(none.mgPerStep);
        assertTrue(none.doses.isEmpty());
    }

    @Test
    public void flaggedJobsLeaveTheEvidence() {
        DispenseConfig c = DoseRecordTest.setup();
        long now = DoseRecordTest.NOW;
        DoseRecord a = DoseRecordTest.record(DoseRecord.OPTICAL, 800, c, now - 60_000);
        a.jobId = "job-ok";
        DoseRecord b = DoseRecordTest.record(DoseRecord.OPTICAL, 800, c, now - 30_000);
        b.jobId = "job-wrong";
        List<DoseRecord> l = new ArrayList<>();
        l.add(a);
        l.add(b);
        DoseRecord.Epochs ep = DoseRecordTest.MACHINE;
        assertEquals(2, DoseRecords.fresh(l, c, ep, now, Collections.<String>emptySet()).size());
        List<DoseRecord> fresh = DoseRecords.fresh(l, c, ep, now, Collections.singleton("job-wrong"));
        assertEquals(1, fresh.size());
        assertEquals("job-ok", fresh.get(0).jobId);
    }
}
