package org.openpnp.instancelabs.dispense;

import java.util.List;
import java.util.function.LongSupplier;

import org.pmw.tinylog.Logger;

/**
 * Streams a {@link GcodeProgram} line by line through a {@link GcodeChannel}, i.e. through the
 * driver's flow control, without any per-dot M400. Interleaves host-watchdog pings, enforces I7
 * (maximum continuous pneumatic on-time) and runs the I1 shutdown sequence (M410, valve OFF, pump
 * OFF, M18 B) on any error, abort request or Stop.
 */
public class DispenseExecutor {
    public static class AbortedException extends Exception {
        private static final long serialVersionUID = 1L;

        public AbortedException(String message) {
            super(message);
        }
    }

    private final DispenseConfig cfg;
    private final GcodeChannel channel;
    private final LongSupplier clock;
    private final String letterB;
    private volatile String abortReason;
    private boolean armed;
    private long lastPingMs;
    private long pressureOnMs = -1;

    public DispenseExecutor(DispenseConfig cfg, GcodeChannel channel, String letterB, LongSupplier clock) {
        this.cfg = cfg;
        this.channel = channel;
        this.letterB = letterB;
        this.clock = clock == null ? System::currentTimeMillis : clock;
    }

    /** Thread-safe; the streaming loop stops before its next line and shuts the pneumatics off. */
    public void requestAbort(String reason) {
        abortReason = reason == null ? "abort requested" : reason;
    }

    public boolean isAbortRequested() {
        return abortReason != null;
    }

    /**
     * Streams the program. On any exception the shutdown sequence is sent (best effort) and the
     * exception is rethrown; the result carries what was achieved.
     */
    public void stream(GcodeProgram prog, RunResult result, DispenseListener listener) throws Exception {
        long dotStart = 0;
        boolean dotEndedSinceSync = false;
        int total = result.dotsPlanned;
        try {
            for (GcodeProgram.Line line : prog.lines) {
                if (line.isComment()) {
                    continue;
                }
                long now = clock.getAsLong();
                if (abortReason != null) {
                    throw new AbortedException(abortReason);
                }
                if (pressureOnMs >= 0 && now - pressureOnMs > cfg.maxContinuousOnTimeS * 1000.0) {
                    throw new AbortedException(String.format(java.util.Locale.ROOT,
                            "I7: pneumatics on for %.1f s, limit %.0f s", (now - pressureOnMs) / 1000.0,
                            cfg.maxContinuousOnTimeS));
                }
                if (armed && now - lastPingMs >= cfg.keepaliveIntervalMs) {
                    channel.send(cfg.keepalivePingGcode, cfg.commandTimeoutMs);
                    result.keepalivePings++;
                    result.linesSent++;
                    lastPingMs = clock.getAsLong();
                }
                if (line.has(GcodeProgram.Marker.DOT_BEGIN)) {
                    dotStart = clock.getAsLong();
                    if (line.dotIndex >= 0 && result.firstDotStartMs == 0) {
                        result.firstDotStartMs = dotStart;
                    }
                }
                long timeout = line.has(GcodeProgram.Marker.SYNC) ? -1 : cfg.commandTimeoutMs;
                channel.send(line.text, timeout);
                result.linesSent++;
                result.streamedLines.add(line.text);
                now = clock.getAsLong();
                if (line.has(GcodeProgram.Marker.PUMP_ON)) {
                    pressureOnMs = now;
                }
                if (line.has(GcodeProgram.Marker.PUMP_OFF)) {
                    pressureOnMs = -1;
                }
                if (line.has(GcodeProgram.Marker.KEEPALIVE_ARM)) {
                    armed = true;
                    lastPingMs = now;
                }
                if (line.has(GcodeProgram.Marker.KEEPALIVE_DISARM)) {
                    armed = false;
                }
                if (line.has(GcodeProgram.Marker.SYNC) && dotEndedSinceSync) {
                    // The controller has now physically finished the last streamed dot.
                    result.lastDotEndMs = now;
                    dotEndedSinceSync = false;
                }
                if (line.has(GcodeProgram.Marker.DOT_END) && line.dotIndex >= 0) {
                    RunResult.DotTiming t = new RunResult.DotTiming(line.dotIndex, line.padId,
                            line.rotationDeg, dotStart, now);
                    result.dotTimings.add(t);
                    result.dotsDone++;
                    result.lastDotEndMs = now;
                    dotEndedSinceSync = true;
                    if (listener != null) {
                        try {
                            listener.onDot(result, t, result.dotsDone, total);
                        }
                        catch (Exception e) {
                            Logger.warn(e, "dispense listener failed");
                        }
                    }
                }
            }
        }
        catch (Exception e) {
            shutdown(result);
            throw e;
        }
    }

    /**
     * I1: quick-stop and pneumatics off, in the plan §4.4 order. Every line is attempted even if
     * an earlier one fails.
     */
    public void shutdown(RunResult result) {
        List<String> lines = GcodeStreamBuilder.abortLines(cfg, armed, letterB);
        for (String l : lines) {
            try {
                channel.send(l, cfg.commandTimeoutMs);
                result.linesSent++;
                result.streamedLines.add(l);
            }
            catch (Exception e) {
                result.warnings.add("shutdown line '" + l + "' failed: " + e.getMessage());
                Logger.error(e, "dispense shutdown line {} failed", l);
            }
        }
        armed = false;
        pressureOnMs = -1;
    }
}
