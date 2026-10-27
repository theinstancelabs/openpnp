package org.openpnp.instancelabs.api;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.openpnp.instancelabs.dispense.DispenseConfig;
import org.openpnp.instancelabs.dispense.DispenseListener;
import org.openpnp.instancelabs.dispense.DispenseService;
import org.openpnp.instancelabs.dispense.DriverGcodeChannel;
import org.openpnp.instancelabs.dispense.GcodeChannel;
import org.openpnp.instancelabs.dispense.OpenPnpRawMapper;
import org.openpnp.instancelabs.dispense.RunResult;
import org.openpnp.machine.reference.ReferenceMachine;
import org.openpnp.spi.Machine;
import org.openpnp.spi.MachineListener;
import org.openpnp.spi.MotionPlanner.CompletionType;
import org.pmw.tinylog.Logger;

/**
 * Manual pressure hold for the Dispense tab: switch the barrel pressure on and leave it on (e.g. to
 * read the regulator gauge), then off. Order per plan §4.5: on = pump, wait, valve, settle; off =
 * valve (barrel vents), then pump. The G-code is the engine's configured pump/valve commands
 * ({@link DispenseConfig}; full-on {@code S255} only, enforced by the engine's CONFIG interlock).
 *
 * <ul>
 * <li>I7: switched off automatically after {@code maxContinuousOnTimeS}.</li>
 * <li>I1: vented when OpenPnP stops/disables the machine, and by the web STOP.</li>
 * <li>Host watchdog: armed and pinged (on the machine thread) when the firmware reports the
 * capability, as the engine does. If the machine thread is busy for longer than the watchdog
 * window, the firmware vents on its own (fail-safe).</li>
 * </ul>
 *
 * <p>Prime, test dot, board runs and calibrations are engine runs that pressurise and vent on their
 * own; the API refuses them while a hold is on, so on-time accounting (I7) stays with one owner.</p>
 */
final class PressureHold implements DispenseListener {
    enum State {
        OFF, STARTING, ON, STOPPING
    }

    private final ApiContext ctx;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "il-api-pressure-timer");
        t.setDaemon(true);
        return t;
    });
    /** Serialises G-code I/O of this class (never held while waiting for the machine thread). */
    private final Object io = new Object();
    private final AtomicBoolean pingQueued = new AtomicBoolean();

    private volatile State state = State.OFF;
    private volatile long onSinceMs;
    private volatile long autoOffAtMs;
    private volatile boolean keepaliveArmed;
    private volatile long lastPingMs;
    private volatile int pings;
    private volatile String lastEvent = "pressure off";
    private volatile long lastEventAtMs = System.currentTimeMillis();
    private volatile String lastError;
    private DispenseConfig cfg;
    private GcodeChannel channel;
    private ScheduledFuture<?> autoOff;
    private ScheduledFuture<?> pinger;
    private Machine listenedMachine;

    private final MachineListener machineListener = new MachineListener.Adapter() {
        @Override
        public void machineAboutToBeDisabled(Machine machine, String reason) {
            // Still connected here: vent from this (stopping) thread, like OpenPnP's DISABLE_COMMAND.
            if (state != State.OFF) {
                ventNow("OpenPnP stop/disable: " + reason);
            }
        }

        @Override
        public void machineDisabled(Machine machine, String reason) {
            if (state != State.OFF) {
                markOff("machine disabled (" + reason + "); the controller link is closed");
            }
        }
    };

    PressureHold(ApiContext ctx) {
        this.ctx = ctx;
    }

    boolean isActive() {
        return state != State.OFF;
    }

    State getState() {
        return state;
    }

    /**
     * Switches the pressure on (pump, wait, valve, settle) on the machine thread. Waits for the
     * sequence to be acknowledged; returns early with state STARTING if the machine thread is busy.
     */
    Map<String, Object> start(DispenseService svc) throws Exception {
        Machine m = ctx.machine();
        synchronized (this) {
            if (state != State.OFF) {
                throw ApiException.refused("pressure is already " + state.name().toLowerCase());
            }
            state = State.STARTING;
            lastError = null;
        }
        attach(m);
        DispenseConfig c = svc.getConfig();
        Future<Object> f;
        try {
            f = m.submit(() -> {
                doStart(m, svc, c);
                return null;
            });
        }
        catch (RuntimeException e) {
            markOff("could not queue the pressure start: " + e.getMessage());
            throw e;
        }
        long timeout = c.pumpToValveSettleMs + c.valveSettleMs + 4L * c.commandTimeoutMs + 5000;
        try {
            f.get(timeout, TimeUnit.MILLISECONDS);
        }
        catch (TimeoutException e) {
            Map<String, Object> s = snapshot();
            s.put("note", "queued behind other machine work; the pressure switches on when OpenPnP is free");
            return s;
        }
        catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw ApiException.refused("could not switch the pressure on: " + cause.getMessage())
                    .with("pressure", snapshot());
        }
        return snapshot();
    }

    /** Machine thread. */
    private void doStart(Machine m, DispenseService svc, DispenseConfig c) throws Exception {
        if (state != State.STARTING) {
            String why = lastEvent;
            markOff("pressure start cancelled before any G-code was sent (" + why + ")");
            throw new Exception("cancelled before it started (" + why + ")");
        }
        try {
            m.getMotionPlanner().waitForCompletion(null, CompletionType.WaitForStillstand);
            OpenPnpRawMapper mapper = new OpenPnpRawMapper((ReferenceMachine) m, svc.getDispenserNozzle(),
                    c.bAxisName);
            GcodeChannel ch = new DriverGcodeChannel(mapper.getDriver());
            boolean ka = c.keepaliveEnabled && hasWatchdog(ch, c);
            synchronized (io) {
                synchronized (this) {
                    cfg = c;
                    channel = ch;
                }
                if (ka) {
                    ch.send(c.keepaliveArmGcode, c.commandTimeoutMs);
                    keepaliveArmed = true;
                    lastPingMs = System.currentTimeMillis();
                }
                ch.send(c.pumpOnGcode, c.commandTimeoutMs);
                onSinceMs = System.currentTimeMillis(); // I7 counts from the pump
                ch.send("G4 P" + c.pumpToValveSettleMs, c.commandTimeoutMs + c.pumpToValveSettleMs);
                ch.send(c.valveOnGcode, c.commandTimeoutMs);
                ch.send("G4 P" + c.valveSettleMs, c.commandTimeoutMs + c.valveSettleMs);
            }
        }
        catch (Exception e) {
            lastError = e.getMessage();
            ventNow("pressure start failed: " + e.getMessage());
            throw e;
        }
        boolean cancelled;
        synchronized (this) {
            cancelled = state != State.STARTING;
            if (!cancelled) {
                state = State.ON;
                long limitMs = Math.round(c.maxContinuousOnTimeS * 1000);
                autoOffAtMs = onSinceMs + limitMs;
                autoOff = timer.schedule(() -> stopQuietly("I7: on-time limit of " + c.maxContinuousOnTimeS
                        + " s reached"), Math.max(0, autoOffAtMs - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
                if (keepaliveArmed) {
                    pinger = timer.scheduleAtFixedRate(this::ping, c.keepaliveIntervalMs, c.keepaliveIntervalMs,
                            TimeUnit.MILLISECONDS);
                }
            }
        }
        if (cancelled) {
            ventNow("stop requested while the pressure was switching on");
            throw new Exception("stopped while switching on");
        }
        event("pressure on: " + c.pumpOnGcode + ", wait " + c.pumpToValveSettleMs + " ms, " + c.valveOnGcode
                + ", settle " + c.valveSettleMs + " ms" + (keepaliveArmed ? "; host watchdog armed" : ""));
    }

    private static boolean hasWatchdog(GcodeChannel ch, DispenseConfig c) {
        try {
            for (String line : ch.sendAndCollect(c.capabilityQueryGcode, c.commandTimeoutMs)) {
                if (line.contains(c.keepaliveCapabilityToken)) {
                    return true;
                }
            }
        }
        catch (Exception e) {
            Logger.warn(e, "pressure hold: capability query failed");
        }
        return false;
    }

    /** Keepalive ping on the machine thread (never two queued at once). */
    private void ping() {
        Machine m = listenedMachine;
        if (state != State.ON || !keepaliveArmed || m == null || !pingQueued.compareAndSet(false, true)) {
            return;
        }
        try {
            m.submit(() -> {
                try {
                    synchronized (io) {
                        GcodeChannel ch;
                        DispenseConfig c;
                        synchronized (this) {
                            ch = channel;
                            c = cfg;
                        }
                        if (state == State.ON && keepaliveArmed && ch != null) {
                            ch.send(c.keepalivePingGcode, c.commandTimeoutMs);
                            lastPingMs = System.currentTimeMillis();
                            pings++;
                        }
                    }
                }
                finally {
                    pingQueued.set(false);
                }
                return null;
            }, null, true);
        }
        catch (RuntimeException e) {
            pingQueued.set(false);
        }
    }

    /** Switches the pressure off (valve, then pump). Safe to call when already off. */
    Map<String, Object> stop(String reason) {
        synchronized (this) {
            if (state == State.OFF) {
                Map<String, Object> s = snapshot();
                s.put("note", "pressure was already off");
                return s;
            }
            if (state == State.STARTING) {
                // doStart() sees this and vents as soon as its sequence is through.
                state = State.STOPPING;
                event("stop requested while switching on: " + reason);
                return snapshot();
            }
            state = State.STOPPING;
            cancelTimers();
        }
        vent(reason);
        return snapshot();
    }

    private void stopQuietly(String reason) {
        try {
            stop(reason);
        }
        catch (Exception e) {
            Logger.error(e, "pressure hold: automatic stop failed");
        }
    }

    /**
     * Emergency path for the web STOP: vents now from the calling thread when the machine thread
     * is busy, otherwise through it.
     */
    void emergencyStop(String reason) {
        State s = state;
        if (s == State.OFF) {
            return;
        }
        if (s == State.STARTING) {
            state = State.STOPPING;
            event("STOP while switching on: " + reason);
            return;
        }
        synchronized (this) {
            state = State.STOPPING;
            cancelTimers();
        }
        vent(reason);
    }

    private void vent(String reason) {
        Machine m = listenedMachine;
        if (m != null && !m.isTask(Thread.currentThread()) && !m.isBusy()) {
            try {
                m.submit(() -> {
                    ventNow(reason);
                    return null;
                }, null, true).get(30, TimeUnit.SECONDS);
                return;
            }
            catch (Exception e) {
                Logger.warn(e, "pressure hold: vent on the machine thread failed; sending directly");
            }
        }
        // Machine thread busy (or this is it): send directly, as OpenPnP's emergency Stop does.
        ventNow(reason);
    }

    /** Valve OFF, pump OFF, watchdog disarm; every line attempted. Any thread. */
    private void ventNow(String reason) {
        List<String> errors = new ArrayList<>();
        synchronized (io) {
            GcodeChannel ch;
            DispenseConfig c;
            boolean ka;
            synchronized (this) {
                ch = channel;
                c = cfg;
                ka = keepaliveArmed;
            }
            if (ch != null && c != null) {
                List<String> lines = new ArrayList<>(Arrays.asList(c.valveOffGcode, c.pumpOffGcode));
                if (ka) {
                    lines.add(c.keepaliveDisarmGcode);
                }
                for (String line : lines) {
                    try {
                        ch.send(line, c.commandTimeoutMs);
                    }
                    catch (Exception e) {
                        errors.add(line + ": " + e.getMessage());
                        Logger.error(e, "pressure hold: '{}' failed", line);
                    }
                }
            }
        }
        if (!errors.isEmpty()) {
            lastError = String.join("; ", errors);
        }
        markOff("pressure off (valve, then pump): " + reason
                + (errors.isEmpty() ? "" : " - FAILED lines: " + errors));
    }

    private void markOff(String why) {
        synchronized (this) {
            state = State.OFF;
            cancelTimers();
            channel = null;
            keepaliveArmed = false;
            onSinceMs = 0;
            autoOffAtMs = 0;
        }
        event(why);
    }

    private void cancelTimers() {
        if (autoOff != null) {
            autoOff.cancel(false);
            autoOff = null;
        }
        if (pinger != null) {
            pinger.cancel(false);
            pinger = null;
        }
    }

    private void event(String text) {
        lastEvent = text;
        lastEventAtMs = System.currentTimeMillis();
        Logger.info("Instance Labs pressure hold: {}", text);
        DispenseService svc = DispenseService.get();
        if (svc != null) {
            svc.getDb().event(null, "INFO", "PRESSURE_HOLD", text);
        }
    }

    private synchronized void attach(Machine m) {
        if (listenedMachine != m) {
            if (listenedMachine != null) {
                listenedMachine.removeListener(machineListener);
            }
            m.addListener(machineListener);
            listenedMachine = m;
        }
    }

    // --- DispenseListener: an engine run always ends with valve + pump off -------------------

    @Override
    public void onFinished(RunResult run) {
        if (state == State.ON) {
            markOff("the " + run.kind + " run switched the pneumatics off at its end");
        }
    }

    Map<String, Object> snapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        long now = System.currentTimeMillis();
        State s = state;
        m.put("state", s.name().toLowerCase());
        m.put("on", s == State.ON);
        m.put("onSinceMs", onSinceMs == 0 ? null : onSinceMs);
        m.put("autoOffAtMs", autoOffAtMs == 0 ? null : autoOffAtMs);
        m.put("remainingS", autoOffAtMs == 0 ? null : Math.max(0, (autoOffAtMs - now) / 1000.0));
        m.put("keepaliveArmed", keepaliveArmed);
        m.put("lastPingAgeMs", keepaliveArmed ? now - lastPingMs : null);
        m.put("pings", pings);
        m.put("lastEvent", lastEvent);
        m.put("lastEventAtMs", lastEventAtMs);
        m.put("lastError", lastError);
        return m;
    }

    void shutdown() {
        if (state != State.OFF) {
            emergencyStop("web API shutting down");
        }
        Machine m = listenedMachine;
        if (m != null) {
            m.removeListener(machineListener);
        }
        timer.shutdownNow();
    }
}
