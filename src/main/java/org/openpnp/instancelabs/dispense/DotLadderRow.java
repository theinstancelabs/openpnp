package org.openpnp.instancelabs.dispense;

/** One measured rung of the dot ladder (plan §5.6 table {@code dot_ladder}). Append-only. */
public class DotLadderRow {
    public long id;
    public long calibrationId;
    public long createdAtMs;
    /** Auger rotation per dot, degrees of B. */
    public double rotationDeg;
    public Double zGapMm;
    public int n;
    public Double diameterMeanMm;
    public Double diameterCv;
    public Double areaMeanMm2;
    public Double areaCv;
    public Double mgPerDotMean;
    public Double mgPerDotSe;
    /** Spherical-cap height computed from mass and diameter (plan §6.1). */
    public Double heightMm;
    public String method;
    public String notes;

    public DotLadderRow() {
    }

    public DotLadderRow(long calibrationId, double rotationDeg, int n, Double diameterMeanMm,
            Double diameterCv, Double areaMeanMm2, Double areaCv) {
        this.calibrationId = calibrationId;
        this.rotationDeg = rotationDeg;
        this.n = n;
        this.diameterMeanMm = diameterMeanMm;
        this.diameterCv = diameterCv;
        this.areaMeanMm2 = areaMeanMm2;
        this.areaCv = areaCv;
    }
}
