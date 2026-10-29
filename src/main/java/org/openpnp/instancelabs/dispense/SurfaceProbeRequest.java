package org.openpnp.instancelabs.dispense;

import java.util.List;

/** Probe the blank (or another needle-frame surface) automatically with N1. */
public final class SurfaceProbeRequest {
    public String surfaceName;
    /** The nominal N1 contact Z of the surface (the place's z, or the request's). */
    public double nominalZ;
    /** Explicit N1 tip machine X/Y (3..20); null = {@link ProbeTargets#forRow}. */
    public List<double[]> targets;
}
