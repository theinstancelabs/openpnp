package org.openpnp.instancelabs.dispense;

import java.sql.SQLException;
import java.util.Arrays;

/**
 * Manual scale entry (plan §5.2): the operator types masses read from the balance. Shots give q
 * (mm^3/rev, with CV and n when per-shot masses are entered); a dot batch gives mg/dot (+/- SE) for
 * one ladder rung. Both append rows; nothing is overwritten.
 */
public final class ScaleCalibration {
    private ScaleCalibration() {
    }

    public static class ShotsEntry {
        public double rotationDegPerShot;
        public int count;
        /** Either totalMassMg or perShotMassMg must be given. */
        public Double totalMassMg;
        public double[] perShotMassMg;
        public double densityMgMm3 = 3.7;
        public String tip, cartridge, pasteLot, operator, notes;
        public Double setPressurePsi, bFeedDegS, barrelTempC, barrelPressurePsi, pasteHoursRt;
        public Long validUntilMs;
        /** Mark the resulting calibration 'characterized' (your pass criteria, plan §5.2). */
        public boolean markCharacterized;
    }

    public static class DotsEntry {
        public long calibrationId;
        public double rotationDeg;
        public int count;
        /** Net mass of the dots (gross - tare), mg. */
        public double totalMassMg;
        /** Scale repeatability s_scale (mg) from M9, for the standard error. */
        public Double scaleSdMg;
        public double densityMgMm3 = 3.7;
        /** Mean diameter measured by the camera on the same dots, if available. */
        public Double diameterMeanMm;
        public Double diameterCv;
        public String operator, notes;
    }

    public static Calibration recordShots(DispenseDb db, ShotsEntry e) throws SQLException {
        if (e.rotationDegPerShot <= 0 || e.densityMgMm3 <= 0) {
            throw new IllegalArgumentException("rotation and density must be > 0");
        }
        Calibration c = new Calibration();
        double total;
        if (e.perShotMassMg != null && e.perShotMassMg.length > 0) {
            double[] q = new double[e.perShotMassMg.length];
            for (int i = 0; i < q.length; i++) {
                q[i] = DotMath.qFromMass(e.perShotMassMg[i], 1, e.rotationDegPerShot, e.densityMgMm3);
            }
            c.qMm3PerRev = DotMath.mean(q);
            double cv = DotMath.cv(q);
            c.qCv = Double.isNaN(cv) ? null : cv;
            c.qN = q.length;
            total = Arrays.stream(e.perShotMassMg).sum();
        }
        else if (e.totalMassMg != null && e.count > 0) {
            c.qMm3PerRev = DotMath.qFromMass(e.totalMassMg, e.count, e.rotationDegPerShot, e.densityMgMm3);
            c.qN = e.count;
            total = e.totalMassMg;
        }
        else {
            throw new IllegalArgumentException("enter per-shot masses or a total mass and count");
        }
        c.method = "scale-shots";
        c.status = e.markCharacterized ? "characterized" : "provisional";
        c.densityMgMm3 = e.densityMgMm3;
        c.tip = e.tip;
        c.cartridge = e.cartridge;
        c.pasteLot = e.pasteLot;
        c.operator = e.operator;
        c.setPressurePsi = e.setPressurePsi;
        c.bFeedDegS = e.bFeedDegS;
        c.barrelTempC = e.barrelTempC;
        c.barrelPressurePsi = e.barrelPressurePsi;
        c.pasteHoursRt = e.pasteHoursRt;
        c.validUntilMs = e.validUntilMs;
        c.notes = e.notes;
        db.insertCalibration(c);
        db.insertScaleEntry("shots", e.rotationDegPerShot, c.qN, total,
                e.perShotMassMg == null ? null : Arrays.toString(e.perShotMassMg), e.densityMgMm3, c.id, e.notes,
                e.operator);
        return c;
    }

    public static DotLadderRow recordDots(DispenseDb db, DotsEntry e) throws SQLException {
        if (e.count <= 0 || e.rotationDeg <= 0 || e.totalMassMg <= 0 || e.densityMgMm3 <= 0) {
            throw new IllegalArgumentException("count, rotation, mass and density must be > 0");
        }
        if (db.calibration(e.calibrationId) == null) {
            throw new IllegalArgumentException("unknown calibration " + e.calibrationId);
        }
        DotLadderRow r = new DotLadderRow();
        r.calibrationId = e.calibrationId;
        r.rotationDeg = e.rotationDeg;
        r.n = e.count;
        r.mgPerDotMean = e.totalMassMg / e.count;
        // sqrt(2) because tare and gross are two readings (plan §5.2).
        r.mgPerDotSe = e.scaleSdMg == null ? null : Math.sqrt(2) * e.scaleSdMg / e.count;
        // Diameter: measured on these dots if given; otherwise the ladder's interpolated value is used
        // only to compute the height and is NOT stored as a measurement.
        Double d = e.diameterMeanMm;
        String dSource = "measured";
        if (d == null) {
            d = DotLadder.of(db.dotLadder(e.calibrationId)).diameterAt(e.rotationDeg);
            dSource = "ladder-interpolated";
        }
        r.diameterMeanMm = e.diameterMeanMm;
        r.diameterCv = e.diameterCv;
        if (e.diameterMeanMm != null) {
            r.areaMeanMm2 = Math.PI * d * d / 4;
        }
        if (d != null) {
            r.heightMm = DotMath.sphericalCapHeightMm(r.mgPerDotMean / e.densityMgMm3, d);
        }
        String hNote = d == null ? "height not computed (no diameter)" : "height from " + dSource + " diameter";
        e.notes = e.notes == null ? hNote : e.notes + "; " + hNote;
        r.method = "scale-dots";
        r.notes = e.notes;
        db.insertDotLadderRow(r);
        db.insertScaleEntry("dots", e.rotationDeg, e.count, e.totalMassMg, null, e.densityMgMm3,
                e.calibrationId, e.notes, e.operator);
        return r;
    }
}
