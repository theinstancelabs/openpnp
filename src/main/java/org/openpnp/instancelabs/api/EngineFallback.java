package org.openpnp.instancelabs.api;

import java.util.function.Supplier;

/**
 * Temporary (tip setups design §22.9, ts-api built before ts-engine merges): the contract commit left every new engine
 * method throwing UnsupportedOperationException("§22 ts-engine: not built yet"). Where a route that the earlier tests use
 * now calls such a method, the API calls it and, while it is still that stub, does what it did before §22, so those
 * routes keep working. Removed when il/ts-engine is merged: every caller then calls the engine directly (each call site
 * names the new call first, the old behaviour second).
 */
final class EngineFallback {
    private EngineFallback() {
    }

    /** A call that may throw. */
    @FunctionalInterface
    interface Call<T> {
        T call() throws Exception;
    }

    /** The new engine call, or the behaviour before it while the engine's method is the contract's stub. */
    static <T> T orBefore(Call<T> now, Call<T> before) throws Exception {
        try {
            return now.call();
        }
        catch (UnsupportedOperationException e) {
            if (!notBuilt(e)) {
                throw e;
            }
            return before.call();
        }
    }

    /** As {@link #orBefore} for calls that throw nothing checked (the Tip's sizes and masses). */
    static <T> T quietly(Supplier<T> now, Supplier<T> before) {
        try {
            return now.get();
        }
        catch (UnsupportedOperationException e) {
            if (!notBuilt(e)) {
                throw e;
            }
            return before.get();
        }
    }

    /** The contract's stub said this (and nothing else does). */
    static boolean notBuilt(Throwable e) {
        return e instanceof UnsupportedOperationException && e.getMessage() != null
                && e.getMessage().startsWith("§22 ts-engine: not built yet");
    }
}
