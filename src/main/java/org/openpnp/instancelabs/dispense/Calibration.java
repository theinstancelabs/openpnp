package org.openpnp.instancelabs.dispense;

/**
 * One auger calibration record (plan §5.6 table {@code calibration}). q is the auger displacement in
 * mm^3 per revolution of B. Append-only.
 */
public class Calibration {
    public long id;
    public long createdAtMs;
    public String operator;
    /** provisional | scale-shots | scale-dots | optical-ladder */
    public String method = "provisional";
    /** provisional | characterized */
    public String status = "provisional";
    public String tip;
    public String cartridge;
    public String pasteLot;
    public Double setPressurePsi;
    public Double bFeedDegS;
    public double qMm3PerRev;
    public Double qCv;
    public Integer qN;
    public Double densityMgMm3;
    public Double barrelTempC;
    public Double barrelPressurePsi;
    public Double pasteHoursRt;
    public Long validUntilMs;
    public String notes;

    public boolean isCharacterized() {
        return "characterized".equalsIgnoreCase(status);
    }

    @Override
    public String toString() {
        return String.format("calibration #%d %s q=%.4f mm3/rev (%s)", id, method, qMm3PerRev, status);
    }
}
