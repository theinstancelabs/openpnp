package org.openpnp.instancelabs.dispense;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

/** Streaming, I1 shutdown, I7 on-time, keepalive pings and DPH. */
public class DispenseExecutorTest {
    /** Fake channel with a fake clock: every line takes `msPerLine`, G4 P<n> takes n ms. */
    static class FakeChannel implements GcodeChannel {
        final List<String> sent = new ArrayList<>();
        long now = 1_000_000;
        long msPerLine = 2;
        int failAt = -1;

        @Override
        public void send(String line, long timeoutMs) throws Exception {
            if (failAt >= 0 && sent.size() == failAt) {
                sent.add(line);
                throw new Exception("simulated driver error at " + line);
            }
            sent.add(line);
            now += msPerLine;
            if (line.startsWith("G4 P")) {
                now += Long.parseLong(line.substring(4).trim());
            }
        }

        @Override
        public List<String> sendAndCollect(String line, long timeoutMs) throws Exception {
            send(line, timeoutMs);
            return Arrays.asList("ok");
        }
    }

    static GcodeProgram program(DispenseConfig c, boolean keepalive) throws Exception {
        return GcodeStreamBuilder.forPlan(c, new ToyMapper(150, 100, 31.5, 0), GcodeStreamTest.toyPlan(c), false,
                keepalive, GcodeStreamBuilder.Part.FULL);
    }

    static RunResult run(int dots) {
        RunResult r = new RunResult();
        r.runId = "t";
        r.kind = "board";
        r.dotsPlanned = dots;
        r.startedAtMs = 1_000_000;
        return r;
    }

    @Test
    public void streamsEveryNonCommentLineInOrderWithoutPerDotM400() throws Exception {
        DispenseConfig c = GcodeStreamTest.toyConfig();
        GcodeProgram p = program(c, false);
        FakeChannel ch = new FakeChannel();
        DispenseExecutor ex = new DispenseExecutor(c, ch, "B", () -> ch.now);
        RunResult r = run(3);
        List<Integer> progress = new ArrayList<>();
        ex.stream(p, r, new DispenseListener() {
            @Override
            public void onDot(RunResult run, RunResult.DotTiming t, int done, int total) {
                progress.add(done);
            }
        });
        List<String> expected = new ArrayList<>();
        for (String l : p.text()) {
            if (!l.startsWith(";")) {
                expected.add(l);
            }
        }
        assertEquals(expected, ch.sent);
        assertEquals(Arrays.asList(1, 2, 3), progress);
        assertEquals(3, r.dotsDone);
        assertEquals(2, java.util.Collections.frequency(ch.sent, "M400"), "only the two session-end M400s");
        // timings: per dot start < end, monotonic
        long prev = 0;
        for (RunResult.DotTiming t : r.dotTimings) {
            assertTrue(t.startMs < t.endMs);
            assertTrue(t.startMs >= prev);
            prev = t.endMs;
        }
        assertEquals(r.dotTimings.get(0).startMs, r.firstDotStartMs);
        // last dot end = completion of the M400 after it (true end), not its last ack
        assertTrue(r.lastDotEndMs > r.dotTimings.get(2).endMs);
        double expectedDph = 3 / ((r.lastDotEndMs - r.firstDotStartMs) / 1000.0) * 3600;
        assertEquals(expectedDph, r.dphMotion(), 1e-9);
        r.endedAtMs = ch.now;
        assertTrue(r.dphOverall() < r.dphMotion());
    }

    @Test
    public void driverErrorRunsShutdownInPlanOrder() throws Exception {
        DispenseConfig c = GcodeStreamTest.toyConfig();
        GcodeProgram p = program(c, true);
        FakeChannel ch = new FakeChannel();
        ch.failAt = 20; // somewhere inside the session, pneumatics on
        DispenseExecutor ex = new DispenseExecutor(c, ch, "B", () -> ch.now);
        RunResult r = run(3);
        assertThrows(Exception.class, () -> ex.stream(p, r, null));
        List<String> tail = ch.sent.subList(ch.sent.size() - 5, ch.sent.size());
        assertEquals(Arrays.asList("M410", "M107 P3", "M107 P2", "M18 B", "M990 S0"), tail);
    }

    @Test
    public void abortRequestStopsBeforeNextLine() throws Exception {
        DispenseConfig c = GcodeStreamTest.toyConfig();
        GcodeProgram p = program(c, false);
        FakeChannel ch = new FakeChannel();
        DispenseExecutor ex = new DispenseExecutor(c, ch, "B", () -> ch.now);
        RunResult r = run(3);
        Exception e = assertThrows(DispenseExecutor.AbortedException.class, () -> ex.stream(p, r,
                new DispenseListener() {
                    @Override
                    public void onDot(RunResult run, RunResult.DotTiming t, int done, int total) {
                        ex.requestAbort("operator stop");
                    }
                }));
        assertEquals("operator stop", e.getMessage());
        assertEquals(1, r.dotsDone);
        List<String> tail = ch.sent.subList(ch.sent.size() - 4, ch.sent.size());
        assertEquals(Arrays.asList("M410", "M107 P3", "M107 P2", "M18 B"), tail);
        assertFalse(ch.sent.subList(0, ch.sent.size() - 4).contains("M410"));
    }

    @Test
    public void i7OnTimeLimitAborts() throws Exception {
        DispenseConfig c = GcodeStreamTest.toyConfig();
        c.maxContinuousOnTimeS = 0.5;
        GcodeProgram p = program(c, false);
        FakeChannel ch = new FakeChannel();
        ch.msPerLine = 40;
        DispenseExecutor ex = new DispenseExecutor(c, ch, "B", () -> ch.now);
        RunResult r = run(3);
        Exception e = assertThrows(DispenseExecutor.AbortedException.class, () -> ex.stream(p, r, null));
        assertTrue(e.getMessage().startsWith("I7"), e.getMessage());
        assertEquals("M107 P2", ch.sent.get(ch.sent.size() - 2));
    }

    @Test
    public void keepalivePingsAtInterval() throws Exception {
        DispenseConfig c = GcodeStreamTest.toyConfig();
        c.pumpToValveSettleMs = 3000;
        GcodeProgram p = program(c, true);
        FakeChannel ch = new FakeChannel();
        DispenseExecutor ex = new DispenseExecutor(c, ch, "B", () -> ch.now);
        RunResult r = run(3);
        ex.stream(p, r, null);
        assertTrue(r.keepalivePings >= 3, "pings: " + r.keepalivePings);
        // no ping before arming or after disarming
        int arm = ch.sent.indexOf("M990 S3000");
        int disarm = ch.sent.indexOf("M990 S0");
        for (int i = 0; i < ch.sent.size(); i++) {
            if (ch.sent.get(i).equals("M990")) {
                assertTrue(i > arm && i < disarm);
            }
        }
        // the gap between consecutive pings while armed never exceeds interval + one G4 chunk
        long last = -1;
        long t = 1_000_000;
        long maxGap = 0;
        for (String l : ch.sent) {
            t += ch.msPerLine + (l.startsWith("G4 P") ? Long.parseLong(l.substring(4)) : 0);
            if (l.equals("M990") || l.equals("M990 S3000")) {
                if (last > 0) {
                    maxGap = Math.max(maxGap, t - last);
                }
                last = t;
            }
        }
        assertTrue(maxGap <= c.keepaliveIntervalMs * 2 + 50, "max gap " + maxGap);
    }
}
