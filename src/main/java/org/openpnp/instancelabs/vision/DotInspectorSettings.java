package org.openpnp.instancelabs.vision;

/**
 * Tuning for {@link DotInspector}. Plain mutable fields so callers (and the bench CLI) can set
 * them directly. The first five fields are the shared vision/dispense contract; the remaining
 * fields are optional extras with safe defaults.
 */
public final class DotInspectorSettings {
    /** Smallest accepted equivalent-circle diameter, mm. */
    public double minDiameterMm = 0.15;
    /** Largest accepted equivalent-circle diameter, mm. */
    public double maxDiameterMm = 2.0;
    /**
     * Detection threshold on the brightness-normalized difference image, 0-255 grey levels
     * (largest per-channel difference for colour images).
     */
    public double diffThreshold = 25;
    /** Gaussian pre-blur kernel size in pixels (rounded up to odd; values <= 1 disable blur). */
    public double blurPx = 3;
    /** Smallest accepted circularity 4*pi*A/P^2. */
    public double minCircularity = 0.5;

    /**
     * Extra (not in the contract): estimate and remove a small global X/Y shift between before
     * and after (phase correlation) to tolerate machine re-positioning error.
     */
    public boolean registerShift = true;
    /** Extra: shifts larger than this (px) are treated as unreliable and not applied. */
    public double maxShiftPx = 8;

    public DotInspectorSettings copy() {
        DotInspectorSettings c = new DotInspectorSettings();
        c.minDiameterMm = minDiameterMm;
        c.maxDiameterMm = maxDiameterMm;
        c.diffThreshold = diffThreshold;
        c.blurPx = blurPx;
        c.minCircularity = minCircularity;
        c.registerShift = registerShift;
        c.maxShiftPx = maxShiftPx;
        return c;
    }
}
