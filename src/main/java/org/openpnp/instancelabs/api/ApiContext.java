package org.openpnp.instancelabs.api;

import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

import org.openpnp.instancelabs.dispense.DispensePlan;
import org.openpnp.instancelabs.dispense.DispenseService;
import org.openpnp.instancelabs.dispense.InterlockReport;
import org.openpnp.model.Configuration;
import org.openpnp.spi.Machine;

/** State and helpers shared by the endpoint groups. */
final class ApiContext {
    /** Longest a {@code "wait": true} request blocks for its run. */
    static final long MAX_WAIT_MS = 10 * 60 * 1000L;

    final Configuration configuration;
    final RunTracker tracker = new RunTracker();
    final PlanStore plans = new PlanStore();
    final PressureHold pressure;
    private DispenseService listened;

    ApiContext(Configuration configuration) {
        this.configuration = configuration;
        this.pressure = new PressureHold(this);
    }

    Machine machine() throws ApiException {
        Machine m = configuration.getMachine();
        if (m == null) {
            throw ApiException.unavailable("OpenPnP has no machine configured");
        }
        return m;
    }

    /** The engine, or null before the configuration has loaded. Keeps our listeners attached. */
    DispenseService svcOrNull() {
        DispenseService s = DispenseService.get();
        attach(s);
        return s;
    }

    /** The engine (may be "unavailable", e.g. no N2). */
    DispenseService svc() throws ApiException {
        DispenseService s = svcOrNull();
        if (s == null) {
            throw ApiException.unavailable(
                    "the dispense engine is not running (configuration not loaded, or it failed to start: see the OpenPnP log)");
        }
        return s;
    }

    /** The engine, which must have its dispenser nozzle. */
    DispenseService availableSvc() throws ApiException {
        DispenseService s = svc();
        if (!s.isAvailable()) {
            throw ApiException.unavailable("the dispenser is unavailable: " + s.getUnavailableReason());
        }
        return s;
    }

    private synchronized void attach(DispenseService s) {
        if (s == null || s == listened) {
            return;
        }
        if (listened != null) {
            listened.removeListener(tracker);
            listened.removeListener(pressure);
        }
        s.addListener(tracker);
        s.addListener(pressure);
        listened = s;
    }

    /** Engine runs pressurise on their own: not while the manual pressure hold is on. */
    void requireNoPressureHold() throws ApiException {
        if (pressure.isActive()) {
            throw ApiException.refused("the pressure is being held on from the Dispense tab. Switch it off first: "
                    + "prime, test dot, runs and calibrations switch the pressure on and off themselves")
                    .with("pressure", pressure.snapshot());
        }
    }

    void requireEngineIdle(DispenseService s) throws ApiException {
        if (s.isBusy()) {
            throw ApiException.refused("a dispense task is already running; wait for it or press STOP");
        }
    }

    /**
     * The engine's pre-flight interlocks, as its calibration/shots runs apply them: no I9 (no
     * board) and, unless {@code requireTouchOff}, no I4. The engine re-checks when it runs.
     */
    InterlockReport preflightNoPlan(DispenseService s, boolean requireTouchOff) {
        InterlockReport r = s.checkInterlocks(null, 0);
        r.violations.removeIf(v -> v.code.equals("I9") || (!requireTouchOff && v.code.equals("I4")));
        return r;
    }

    InterlockReport preflightPlan(DispenseService s, DispensePlan plan, double estimatedPressurisedSeconds) {
        return s.checkInterlocks(plan, estimatedPressurisedSeconds);
    }

    static void refuseUnlessOk(InterlockReport r, String what) throws ApiException {
        if (!r.ok()) {
            StringBuilder sb = new StringBuilder(what + " refused by the interlocks: ");
            for (int i = 0; i < r.violations.size(); i++) {
                InterlockReport.Violation v = r.violations.get(i);
                sb.append(i == 0 ? "" : "; ").append(v.code).append(": ").append(v.message);
            }
            throw ApiException.refused(sb.toString()).with("interlocks", Views.interlocks(r));
        }
    }

    /**
     * With {@code wait}, blocks until the engine task finishes (bounded) and returns its result;
     * otherwise returns at once. The UI follows progress through /api/status either way.
     */
    static <T> Object awaitIf(boolean wait, Future<T> f, Function<T, Object> view, Map<String, Object> accepted)
            throws Exception {
        if (!wait) {
            accepted.put("started", true);
            return ApiServer.Response.json(202, accepted);
        }
        try {
            accepted.put("result", view.apply(f.get(MAX_WAIT_MS, TimeUnit.MILLISECONDS)));
            accepted.put("finished", true);
            return accepted;
        }
        catch (TimeoutException e) {
            accepted.put("finished", false);
            accepted.put("note", "still running after " + MAX_WAIT_MS / 1000 + " s; follow /api/status");
            return ApiServer.Response.json(202, accepted);
        }
    }

    void shutdown() {
        pressure.shutdown();
        synchronized (this) {
            if (listened != null) {
                listened.removeListener(tracker);
                listened.removeListener(pressure);
                listened = null;
            }
        }
    }
}
