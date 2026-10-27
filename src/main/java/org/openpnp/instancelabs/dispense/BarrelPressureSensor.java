package org.openpnp.instancelabs.dispense;

/** I5 hook: barrel (post-valve) pressure. No sensor is fitted yet (plan §4.5, M18). */
public interface BarrelPressureSensor {
    /** Current barrel pressure in psi, or null if unavailable. */
    Double readPsi() throws Exception;
}
