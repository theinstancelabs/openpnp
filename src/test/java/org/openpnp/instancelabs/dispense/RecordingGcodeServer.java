package org.openpnp.instancelabs.dispense;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal Marlin-like TCP server on localhost for tests: records every received line, tracks
 * X/Y/Z/A/B from G0/G1/G92, answers M114 with the tracked position and M115 with an optional
 * Cap:HOST_WATCHDOG:1, and "ok" to everything. Never touches any device.
 */
public class RecordingGcodeServer implements AutoCloseable {
    private final ServerSocket server;
    private final List<String> lines = new ArrayList<>();
    private final List<Long> times = new ArrayList<>();
    /** Lines (exact text) that get no reply at all, e.g. to make a send time out. */
    public final java.util.Set<String> mute = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** If true, "G4 P<ms>" waits <ms> before its "ok", as Marlin does. */
    public volatile boolean realDwell = false;
    private final Map<Character, Double> pos = new LinkedHashMap<>();
    public volatile boolean hostWatchdogCapability = true;
    /** If >= 0, reply "Error: simulated" to the n-th received line (0-based) after mark(). */
    public volatile int failAtLine = -1;
    private volatile int markIndex = 0;
    private final Thread thread;
    private static final Pattern WORD = Pattern.compile("([XYZAB])(-?\\d+(?:\\.\\d+)?)");

    public RecordingGcodeServer() throws Exception {
        server = new ServerSocket(0, 5, InetAddress.getLoopbackAddress());
        for (char c : "XYZAB".toCharArray()) {
            pos.put(c, 0.0);
        }
        thread = new Thread(this::run, "RecordingGcodeServer");
        thread.setDaemon(true);
        thread.start();
    }

    public int getPort() {
        return server.getLocalPort();
    }

    private void run() {
        while (!server.isClosed()) {
            try (Socket s = server.accept()) {
                BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                OutputStream out = s.getOutputStream();
                String line;
                while ((line = in.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) {
                        continue;
                    }
                    int idx;
                    synchronized (this) {
                        lines.add(line);
                        times.add(System.nanoTime());
                        idx = lines.size() - 1 - markIndex;
                        track(line);
                    }
                    StringBuilder reply = new StringBuilder();
                    if (failAtLine >= 0 && idx == failAtLine) {
                        reply.append("Error: simulated failure\n");
                    }
                    if (line.startsWith("M115")) {
                        reply.append("FIRMWARE_NAME:Marlin (RecordingGcodeServer)\n");
                        if (hostWatchdogCapability) {
                            reply.append("Cap:HOST_WATCHDOG:1\n");
                        }
                    }
                    else if (line.startsWith("M114")) {
                        synchronized (this) {
                            reply.append(String.format(Locale.ROOT, "X:%.4f Y:%.4f Z:%.4f A:%.4f B:%.4f Count X:0 Y:0 Z:0\n",
                                    pos.get('X'), pos.get('Y'), pos.get('Z'), pos.get('A'), pos.get('B')));
                        }
                    }
                    if (mute.contains(line)) {
                        continue;
                    }
                    if (realDwell && line.startsWith("G4 P")) {
                        Thread.sleep(Long.parseLong(line.substring(4).trim()));
                    }
                    reply.append("ok\n");
                    out.write(reply.toString().getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
            }
            catch (Exception e) {
                // socket closed
            }
        }
    }

    private void track(String line) {
        String code = line.split("[\\s;]")[0];
        if (code.equals("G0") || code.equals("G1") || code.equals("G92")) {
            String body = line.contains(";") ? line.substring(0, line.indexOf(';')) : line;
            Matcher m = WORD.matcher(body.substring(code.length()));
            while (m.find()) {
                pos.put(m.group(1).charAt(0), Double.parseDouble(m.group(2)));
            }
        }
        else if (code.equals("G28")) {
            // like the LumenPnP: homes X/Y/Z only (Z home 31.5 in the test config is set by OpenPnP)
            pos.put('X', 0.0);
            pos.put('Y', 0.0);
            pos.put('Z', 31.5);
        }
    }

    public synchronized void mark() {
        markIndex = lines.size();
    }

    /** Lines received since the last mark(). */
    public synchronized List<String> sinceMark() {
        return new ArrayList<>(lines.subList(markIndex, lines.size()));
    }

    /** Receive time (System.nanoTime()) of the i-th line since the last mark(). */
    public synchronized long timeSinceMark(int i) {
        return times.get(markIndex + i);
    }

    public synchronized List<String> all() {
        return new ArrayList<>(lines);
    }

    public synchronized double position(char axis) {
        return pos.get(axis);
    }

    @Override
    public void close() throws Exception {
        server.close();
    }
}
