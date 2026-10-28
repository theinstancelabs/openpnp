package org.openpnp.instancelabs.dispense;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.sql.SQLException;
import java.util.List;

import org.junit.jupiter.api.Test;

/** SQLite store: schema, seeds, append-only versioning, scale entry. */
public class DispenseDbTest {
    @Test
    public void seedsFamiliesTypesAndProvisionalCalibration() throws Exception {
        try (DispenseDb db = DispenseDb.inMemory()) {
            List<PadType> t = db.currentPadTypes();
            assertEquals(12, t.size());
            assertEquals("chip-0402", t.get(0).typeKey);
            assertEquals("chip-0603", t.get(1).typeKey);
            assertEquals(0.80, t.get(1).padWMm, 0);
            assertEquals(0.95, t.get(1).padHMm, 0);
            assertEquals(0.10, t.get(1).tMm, 0);
            for (PadType p : t) {
                assertEquals(PadTypeStatus.NOT_STARTED, p.status);
            }
            PadType centre = db.currentPadType("qfn-centre");
            assertTrue(centre.k < 1);
            Calibration c = db.currentCalibration(System.currentTimeMillis());
            assertEquals(0.47, c.qMm3PerRev, 0);
            assertEquals("provisional", c.status);
        }
    }

    @Test
    public void appendOnlyTablesRefuseUpdateAndDelete() throws Exception {
        try (DispenseDb db = DispenseDb.inMemory()) {
            // put at least one row in every table (row triggers only fire on existing rows)
            db.insertPadRule(new PadRule("chip-0603", 2, 1.0, null));
            db.mapPackage("P", "chip-0603");
            DotLadderRow lr = new DotLadderRow(1, 30, 3, 0.5, 0.1, 0.2, 0.1);
            db.insertDotLadderRow(lr);
            DispensePlan plan = GcodeStreamTest.toyPlan(GcodeStreamTest.toyConfig());
            db.insertPadPlan(plan);
            RunResult r = new RunResult();
            r.runId = "x";
            r.kind = "board";
            r.dotTimings.add(new RunResult.DotTiming(0, "p", 1, 1, 2));
            db.insertRun(r);
            db.event("x", "INFO", "c", "m");
            db.insertTouchOff("manual", 0.1, null, null, null);
            db.insertScaleEntry("shots", 360, 1, 1.0, null, 3.7, 1L, null, null);
            for (String t : DispenseDb.TABLES) {
                assertTrue(((Number) db.query("SELECT COUNT(*) AS n FROM " + t).get(0).get("n")).intValue() > 0, t);
                assertThrows(SQLException.class, () -> db.executeRaw("DELETE FROM " + t), t);
            }
            assertThrows(SQLException.class, () -> db.executeRaw("UPDATE pad_types SET k = 2"));
            assertThrows(SQLException.class, () -> db.executeRaw("UPDATE calibration SET q_mm3_per_rev = 1"));
            assertThrows(SQLException.class, () -> db.executeRaw("UPDATE runs SET status = 'OK'"));
            db.insertPadRule(new PadRule("chip-0603", 3, null, null));
            assertEquals(3, (int) db.currentPadRules().get("chip-0603").maxDots);
        }
    }

    @Test
    public void editsAppendVersions() throws Exception {
        try (DispenseDb db = DispenseDb.inMemory()) {
            PadType p = db.currentPadType("chip-0603");
            p.status = PadTypeStatus.PROVISIONAL;
            p.k = 0.9;
            PadType v2 = db.insertPadTypeVersion(p);
            assertEquals(2, v2.version);
            assertEquals(2, db.padTypeHistory("chip-0603").size());
            assertEquals(0.9, db.currentPadType("chip-0603").k, 0);
            assertEquals(1.0, db.padTypeHistory("chip-0603").get(0).k, 0);
            assertEquals(12, db.currentPadTypes().size());
            // user-added type
            PadType mine = new PadType("my-led", "Custom", "Big LED", 1.2, 1.2, PadType.Layout.SINGLE, null, 1.0,
                    "MYLED", null);
            db.insertPadTypeVersion(mine);
            assertEquals(13, db.currentPadTypes().size());
            assertThrows(IllegalArgumentException.class, () -> db.insertPadTypeVersion(
                    new PadType("bad", "x", "x", 1, 1, PadType.Layout.TWO_TERMINAL, null, 1, null, null)));
            // package mapping override
            db.mapPackage("WEIRD", "my-led");
            assertEquals("my-led", db.packageMappings().get("WEIRD"));
            db.mapPackage("WEIRD", null);
            assertTrue(db.packageMappings().isEmpty());
        }
    }

    @Test
    public void characterizedCalibrationWinsUntilExpired() throws Exception {
        try (DispenseDb db = DispenseDb.inMemory()) {
            Calibration c = new Calibration();
            c.method = "scale-shots";
            c.status = "characterized";
            c.qMm3PerRev = 0.52;
            c.validUntilMs = 5000L;
            db.insertCalibration(c);
            Calibration p = new Calibration();
            p.qMm3PerRev = 0.6;
            db.insertCalibration(p);
            assertEquals(0.52, db.currentCalibration(4000).qMm3PerRev, 0);
            assertEquals(0.6, db.currentCalibration(6000).qMm3PerRev, 0, "expired -> newest record");
        }
    }

    @Test
    public void scaleEntries() throws Exception {
        try (DispenseDb db = DispenseDb.inMemory()) {
            ScaleCalibration.ShotsEntry e = new ScaleCalibration.ShotsEntry();
            e.rotationDegPerShot = 720;
            e.perShotMassMg = new double[] {3.50, 3.45, 3.55, 3.48, 3.52};
            e.densityMgMm3 = 3.7;
            e.markCharacterized = true;
            Calibration c = ScaleCalibration.recordShots(db, e);
            assertEquals(DotMath.mean(e.perShotMassMg) / 3.7 / 2, c.qMm3PerRev, 1e-12);
            assertEquals(5, (int) c.qN);
            assertNotNull(c.qCv);
            assertEquals(c.id, db.currentCalibration(System.currentTimeMillis()).id);

            ScaleCalibration.DotsEntry d = new ScaleCalibration.DotsEntry();
            d.calibrationId = c.id;
            d.rotationDeg = 58;
            d.count = 300;
            d.totalMassMg = 84.0;
            d.scaleSdMg = 1.0;
            d.diameterMeanMm = 0.7;
            DotLadderRow r = ScaleCalibration.recordDots(db, d);
            assertEquals(0.28, r.mgPerDotMean, 1e-12);
            assertEquals(Math.sqrt(2) / 300, r.mgPerDotSe, 1e-12);
            assertEquals(DotMath.sphericalCapHeightMm(0.28 / 3.7, 0.7), r.heightMm, 1e-12);
            assertEquals(1, db.dotLadder(c.id).size());
            assertEquals(2, db.query("SELECT * FROM scale_entries").size());
        }
    }

    @Test
    public void runsAndEventsPersistToFile() throws Exception {
        File dir = java.nio.file.Files.createTempDirectory("dispense-db").toFile();
        RunResult r = new RunResult();
        r.runId = "run-1";
        r.kind = "board";
        r.status = RunResult.Status.OK;
        r.startedAtMs = 1000;
        r.endedAtMs = 61000;
        r.dotsPlanned = r.dotsDone = 2;
        r.firstDotStartMs = 2000;
        r.lastDotEndMs = 4000;
        r.dotTimings.add(new RunResult.DotTiming(0, "R1.1", 58, 2000, 2900));
        r.dotTimings.add(new RunResult.DotTiming(1, "R1.2", 58, 2900, 3500));
        try (DispenseDb db = DispenseDb.open(dir)) {
            db.insertRun(r);
            db.event("run-1", "INFO", "X", "hello");
        }
        assertTrue(new File(dir, "instancelabs/dispense.db").exists());
        try (DispenseDb db = DispenseDb.open(dir)) {
            List<java.util.Map<String, Object>> runs = db.recentRuns(5);
            assertEquals(1, runs.size());
            assertEquals(3600.0, ((Number) runs.get(0).get("dph_motion")).doubleValue(), 1e-9);
            assertEquals(120.0, ((Number) runs.get(0).get("dph_overall")).doubleValue(), 1e-9);
            assertEquals(2, db.query("SELECT * FROM dot_log WHERE run_uuid = ?", "run-1").size());
            assertEquals(1, db.recentEvents(5).size());
            assertEquals(12, db.currentPadTypes().size(), "seeded once");
        }
    }

    @Test
    public void configRoundTripsAsJson() throws Exception {
        File dir = java.nio.file.Files.createTempDirectory("dispense-cfg").toFile();
        DispenseConfig c = new DispenseConfig();
        c.locations.put("waste", new DispenseConfig.NamedLocation(1, 2, 3));
        c.needleGapMm = 0.12;
        c.save(dir);
        assertTrue(new File(dir, "instancelabs/dispense-config.json").exists());
        DispenseConfig back = DispenseConfig.load(dir);
        assertEquals(0.12, back.needleGapMm, 0);
        assertEquals(3, back.location("waste").z, 0);
        assertFalse(back.toJson().contains("Gcode"), "pump/valve/watchdog G-code are constants, not settings");
        assertEquals(new DispenseConfig().toJson().length() > 100, true);
        assertEquals(0.15, DispenseConfig.load(java.nio.file.Files.createTempDirectory("x").toFile()).needleGapMm, 0);
    }
}
