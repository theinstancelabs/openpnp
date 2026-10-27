package org.openpnp.instancelabs.dispense;

/** Progress callbacks. Called on the machine thread; implementations must return quickly. */
public interface DispenseListener {
    default void onPhase(RunResult run, String phase) {
    }

    default void onDot(RunResult run, RunResult.DotTiming timing, int done, int total) {
    }

    default void onFinished(RunResult run) {
    }
}
