package org.openpnp.instancelabs.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openpnp.instancelabs.dispense.DispenseService;
import org.openpnp.instancelabs.dispense.SimMachine;
import org.openpnp.model.Configuration;
import org.openpnp.model.Package;
import org.openpnp.model.Part;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The HTTP API end to end on a simulated machine: OpenPnP's GcodeDriver over TCP to the engine's
 * RecordingGcodeServer (localhost; no device is opened), the real DispenseService, the real
 * ApiServer on an ephemeral 127.0.0.1 port, a JDK HTTP client.
 */
public class ApiServerSimTest {
    SimMachine sim;
    DispenseService svc;
    ApiServer api;
    HttpClient http;

    @BeforeEach
    public void before() throws Exception {
        sim = new SimMachine();
        svc = DispenseService.start(Configuration.get());
        assertTrue(svc.isAvailable(), String.valueOf(svc.getUnavailableReason()));
        ApiSettings s = new ApiSettings();
        s.port = 0;
        api = ApiServer.start(Configuration.get(), s);
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @AfterEach
    public void after() throws Exception {
        api.stop();
        svc.shutdown();
        sim.close();
    }

    // ------------------------------------------------------------------------------------------
    // HTTP helpers
    // ------------------------------------------------------------------------------------------

    static final class Res {
        final int status;
        final String body;
        final String contentType;

        Res(HttpResponse<String> r) {
            status = r.statusCode();
            body = r.body();
            contentType = r.headers().firstValue("Content-Type").orElse("");
        }

        JsonObject json() {
            return new JsonParser().parse(body).getAsJsonObject();
        }

        JsonArray array() {
            return new JsonParser().parse(body).getAsJsonArray();
        }

        @Override
        public String toString() {
            return status + " " + body;
        }
    }

    Res call(String method, String path, String json, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + api.getPort() + path))
                .timeout(Duration.ofSeconds(120));
        for (int i = 0; i + 1 < headers.length; i += 2) {
            b.header(headers[i], headers[i + 1]);
        }
        if (json != null) {
            b.header("Content-Type", "application/json");
            b.method(method, HttpRequest.BodyPublishers.ofString(json));
        }
        else {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        }
        return new Res(http.send(b.build(), HttpResponse.BodyHandlers.ofString()));
    }

    Res get(String path) throws Exception {
        return call("GET", path, null);
    }

    Res post(String path, String json) throws Exception {
        return call("POST", path, json);
    }

    JsonObject ok(Res r) {
        assertTrue(r.status >= 200 && r.status < 300, r.toString());
        return r.json();
    }

    static List<String> commands(List<String> lines) {
        List<String> l = new ArrayList<>();
        for (String s : lines) {
            if (!s.startsWith(";")) {
                l.add(s);
            }
        }
        return l;
    }

    void setPlaces() throws Exception {
        ok(call("PUT", "/api/locations/waste", "{\"x\":80,\"y\":40,\"z\":1.0}"));
        ok(call("PUT", "/api/locations/blank", "{\"x\":90,\"y\":40,\"z\":1.0}"));
        ok(post("/api/needle/offset", "{\"offsetMm\":0.05}"));
    }

    // ------------------------------------------------------------------------------------------
    // Tests
    // ------------------------------------------------------------------------------------------

    @Test
    public void servesTheUiAndStatus() throws Exception {
        Res index = get("/");
        assertEquals(200, index.status);
        assertTrue(index.contentType.startsWith("text/html"));
        assertTrue(index.body.contains("id=\"btn-stop\""));
        for (String f : new String[] {"/static/app.js", "/static/app.css", "/static/favicon.svg"}) {
            assertEquals(200, get(f).status, f);
        }
        assertEquals(404, get("/static/../machine.xml").status);
        assertEquals(404, get("/api/nope").status);
        assertEquals(405, call("DELETE", "/api/status", null).status);

        JsonObject s = ok(get("/api/status"));
        JsonObject m = s.getAsJsonObject("machine");
        assertTrue(m.get("enabled").getAsBoolean());
        assertTrue(m.get("homed").getAsBoolean());
        JsonObject d = s.getAsJsonObject("dispense");
        assertTrue(d.get("available").getAsBoolean());
        assertEquals("off", s.getAsJsonObject("pressure").get("state").getAsString());
        List<String> ids = new ArrayList<>();
        for (JsonElement e : s.getAsJsonArray("interlocks")) {
            ids.add(e.getAsJsonObject().get("id").getAsString());
        }
        for (String id : new String[] {"MACHINE", "CONFIG", "I1", "I2", "I3", "I4", "I5", "I6", "I7", "I8", "I9"}) {
            assertTrue(ids.contains(id), id + " in " + ids);
        }
        // test-dot default = one 0603 pad at the provisional q (plan §5.6: about 58 deg)
        double rot = d.getAsJsonObject("testDot").get("defaultRotationDeg").getAsDouble();
        assertEquals(58.2, rot, 0.5);
    }

    @Test
    public void refusesCrossSiteRequestsAndForeignHosts() throws Exception {
        Res r = call("POST", "/api/needle/changed", "{}", "Origin", "http://evil.example");
        assertEquals(403, r.status, r.toString());
        r = call("POST", "/api/needle/changed", "{}", "Sec-Fetch-Site", "cross-site");
        assertEquals(403, r.status, r.toString());
        // same-origin browser request is fine
        r = call("POST", "/api/needle/offset", "{\"offsetMm\":0.01}", "Origin", "http://127.0.0.1:" + api.getPort(),
                "Sec-Fetch-Site", "same-origin");
        assertEquals(200, r.status, r.toString());
        // DNS-rebinding guard: a foreign Host header is refused even for GET
        try (Socket sock = new Socket(InetAddress.getLoopbackAddress(), api.getPort())) {
            OutputStream out = sock.getOutputStream();
            out.write(("GET /api/status HTTP/1.1\r\nHost: evil.example:" + api.getPort()
                    + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            String first = new BufferedReader(new InputStreamReader(sock.getInputStream(), StandardCharsets.US_ASCII))
                    .readLine();
            assertTrue(first.contains(" 403"), first);
        }
        assertTrue(ApiServer.isLoopbackHost("localhost:8765"));
        assertTrue(ApiServer.isLoopbackHost("[::1]:8765"));
        assertFalse(ApiServer.isLoopbackHost("127.0.0.1.evil.example"));
    }

    @Test
    public void padTypesAreGroupedAddableVersionedAndRetirable() throws Exception {
        JsonObject list = ok(get("/api/pad-types"));
        JsonArray fams = list.getAsJsonArray("families");
        assertEquals("Chip (2-terminal SMD)", fams.get(0).getAsJsonObject().get("family").getAsString());
        boolean has0603 = false;
        for (JsonElement t : fams.get(0).getAsJsonObject().getAsJsonArray("types")) {
            if (t.getAsJsonObject().get("typeKey").getAsString().equals("chip-0603")) {
                has0603 = true;
                assertEquals("not-started", t.getAsJsonObject().get("status").getAsString());
                assertEquals(58.2, t.getAsJsonObject().get("singleDotRotationDeg").getAsDouble(), 0.5);
            }
        }
        assertTrue(has0603);

        Res created = post("/api/pad-types",
                "{\"family\":\"Custom\",\"name\":\"Test pad 1\",\"padWMm\":1.2,\"padHMm\":0.8,\"k\":0.9}");
        assertEquals(201, created.status, created.toString());
        JsonObject pt = created.json().getAsJsonObject("padType");
        assertEquals("custom-test-pad-1", pt.get("typeKey").getAsString());
        assertEquals(1, pt.get("version").getAsInt());
        assertEquals("single", pt.get("layout").getAsString());

        JsonObject upd = ok(call("PUT", "/api/pad-types/custom-test-pad-1", "{\"status\":\"provisional\",\"tMm\":0.12}"))
                .getAsJsonObject("padType");
        assertEquals(2, upd.get("version").getAsInt());
        assertEquals("provisional", upd.get("status").getAsString());
        assertEquals(0.9, upd.get("k").getAsDouble(), 1e-9, "unchanged fields are kept");

        assertEquals(400, call("PUT", "/api/pad-types/custom-test-pad-1", "{\"packagePattern\":\"([\"}").status);
        assertEquals(400, call("PUT", "/api/pad-types/custom-test-pad-1", "{\"status\":\"great\"}").status);
        assertEquals(409, post("/api/pad-types", "{\"typeKey\":\"chip-0603\",\"name\":\"x\",\"padWMm\":1,\"padHMm\":1}").status);
        assertEquals(400, post("/api/pad-types", "{\"name\":\"no size\"}").status);

        JsonObject ret = ok(call("DELETE", "/api/pad-types/custom-test-pad-1", null)).getAsJsonObject("padType");
        assertEquals("Retired", ret.get("family").getAsString());
        assertEquals("not-started", ret.get("status").getAsString());
        assertTrue(ret.get("packagePattern").isJsonNull());
        JsonObject hist = ok(get("/api/pad-types/custom-test-pad-1"));
        assertEquals(3, hist.getAsJsonArray("history").size(), "append-only: three versions");

        JsonObject rule = ok(call("PUT", "/api/pad-types/chip-0603/rule", "{\"maxDots\":2}"));
        assertEquals(2, rule.get("maxDots").getAsInt());
    }

    @Test
    public void pressureHoldSwitchesPumpThenValveAndVentsValveFirst() throws Exception {
        setPlaces();
        sim.server.mark();
        JsonObject on = ok(post("/api/pressure/start", null));
        assertEquals("on", on.get("state").getAsString(), on.toString());
        List<String> l = commands(sim.server.sinceMark());
        int pump = l.indexOf("M106 P2 S255"), valve = l.indexOf("M106 P3 S255");
        assertTrue(pump >= 0 && valve > pump, l.toString());
        assertEquals("G4 P500", l.get(pump + 1), "pump -> wait -> valve (plan §4.5)");
        assertTrue(l.contains("M990 S3000"), "host watchdog armed (server reports the capability)");

        // engine runs are refused while the hold is on, with the reason
        Res prime = post("/api/prime", "{}");
        assertEquals(409, prime.status);
        assertTrue(prime.json().get("reason").getAsString().contains("Switch it off first"), prime.toString());
        assertEquals(409, post("/api/pressure/start", null).status);

        Thread.sleep(1500); // at least one keepalive ping
        sim.server.mark();
        JsonObject off = ok(post("/api/pressure/stop", null));
        assertEquals("off", off.get("state").getAsString());
        l = commands(sim.server.sinceMark());
        int vOff = l.indexOf("M107 P3"), pOff = l.indexOf("M107 P2");
        assertTrue(vOff >= 0 && pOff > vOff, "valve off, then pump off: " + l);
        assertTrue(l.contains("M990 S0"), l.toString());
        assertTrue(ok(get("/api/status")).getAsJsonObject("pressure").get("pings").getAsInt() >= 1);
    }

    @Test
    public void openPnpsOwnStopVentsTheHold() throws Exception {
        setPlaces();
        ok(post("/api/pressure/start", null));
        sim.server.mark();
        sim.machine.setEnabled(false); // what OpenPnP's Stop button does (no web STOP involved)
        List<String> l = commands(sim.server.sinceMark());
        int vOff = l.indexOf("M107 P3"), pOff = l.indexOf("M107 P2");
        assertTrue(vOff >= 0 && pOff > vOff, l.toString());
        assertEquals("off", ok(get("/api/status")).getAsJsonObject("pressure").get("state").getAsString());
    }

    @Test
    public void holdSwitchesOffAtTheOnTimeLimit() throws Exception {
        setPlaces();
        ok(call("PUT", "/api/config", "{\"maxContinuousOnTimeS\":1}"));
        JsonObject on = ok(post("/api/pressure/start", null));
        assertEquals("on", on.get("state").getAsString());
        sim.server.mark();
        long t0 = System.currentTimeMillis();
        while (ok(get("/api/status")).getAsJsonObject("pressure").get("state").getAsString().equals("on")
                && System.currentTimeMillis() - t0 < 5000) {
            Thread.sleep(50);
        }
        JsonObject p = ok(get("/api/status")).getAsJsonObject("pressure");
        assertEquals("off", p.get("state").getAsString());
        assertTrue(p.get("lastEvent").getAsString().contains("I7"), p.toString());
        List<String> l = commands(sim.server.sinceMark());
        assertTrue(l.indexOf("M107 P3") >= 0 && l.indexOf("M107 P2") > l.indexOf("M107 P3"), l.toString());
    }

    @Test
    public void stopVentsTheHoldAndStopsTheMachine() throws Exception {
        setPlaces();
        ok(post("/api/pressure/start", null));
        sim.server.mark();
        JsonObject r = ok(post("/api/stop", null));
        assertTrue(r.get("stopped").getAsBoolean());
        assertFalse(r.get("machineEnabled").getAsBoolean(), r.toString());
        List<String> l = commands(sim.server.sinceMark());
        int vOff = l.indexOf("M107 P3"), pOff = l.indexOf("M107 P2");
        assertTrue(vOff >= 0 && pOff > vOff, l.toString());
        JsonObject s = ok(get("/api/status"));
        assertFalse(s.getAsJsonObject("machine").get("enabled").getAsBoolean());
        assertEquals("off", s.getAsJsonObject("pressure").get("state").getAsString());
        // STOP is always allowed, also when everything is already stopped
        assertEquals(200, post("/api/stop", null).status);
    }

    @Test
    public void primeAndTestDotRunThroughTheEngine() throws Exception {
        setPlaces();
        sim.server.mark();
        JsonObject prime = ok(post("/api/prime", "{\"rotationDeg\":360,\"wait\":true}"));
        JsonObject res = prime.getAsJsonObject("result");
        assertEquals("OK", res.get("status").getAsString(), prime.toString());
        List<String> l = commands(sim.server.sinceMark());
        assertTrue(l.indexOf("G92 B0") >= 0 && l.indexOf("G92 B0") < l.indexOf("M106 P2 S255"), "I2: " + l);
        assertTrue(l.indexOf("M107 P3") < l.lastIndexOf("M107 P2"), l.toString());

        JsonObject td = ok(post("/api/test-dot", "{\"wait\":true}"));
        assertEquals("OK", td.getAsJsonObject("result").get("status").getAsString(), td.toString());
        JsonObject loc = ok(get("/api/locations")).getAsJsonObject(DispenseEndpoints.TEST_DOT_LOCATION);
        assertEquals(82.0, loc.get("x").getAsDouble(), 1e-9, "waste + 2 mm test-dot offset");
        assertEquals(40.0, loc.get("y").getAsDouble(), 1e-9);
        JsonObject run = ok(get("/api/status")).getAsJsonObject("run");
        assertEquals("test dot", run.get("label").getAsString());
        assertFalse(run.get("running").getAsBoolean());
        assertEquals(400, post("/api/test-dot", "{\"rotationDeg\":-5}").status);
    }

    @Test
    public void refusalsCarryTheInterlockReason() throws Exception {
        Res noWaste = post("/api/prime", "{}");
        assertEquals(409, noWaste.status);
        assertTrue(noWaste.json().get("reason").getAsString().contains("waste"), noWaste.toString());
        setPlaces();
        sim.machine.setHomed(false);
        Res r = post("/api/prime", "{}");
        assertEquals(409, r.status);
        JsonObject b = r.json();
        assertTrue(b.get("reason").getAsString().contains("not homed"), r.toString());
        assertEquals("MACHINE", b.getAsJsonObject("interlocks").getAsJsonArray("violations").get(0).getAsJsonObject()
                .get("code").getAsString());
        assertEquals(409, post("/api/pressure/start", null).status);
        // the dot ladder requires a touch-off (I4)
        sim.machine.setHomed(true);
        ok(post("/api/needle/changed", null));
        Res dl = post("/api/calibrations/dot-ladder", "{}");
        assertEquals(409, dl.status);
        assertTrue(dl.body.contains("I4"), dl.toString());
    }

    File writeBoard() throws Exception {
        return writeBoard(new double[][] {{2, 2, 0}, {8, 2, 90}, {14, 6, 0}});
    }

    File writeBoard(double[][] pos) throws Exception {
        Package pkg = new Package("R_0603_1608Metric");
        Configuration.get().addPackage(pkg);
        Part part = new Part("R0603-API-TEST");
        part.setPackage(pkg);
        Configuration.get().addPart(part);
        StringBuilder sb = new StringBuilder("<openpnp-board version=\"1.1\" name=\"api-test.board.xml\">\n"
                + "<dimensions units=\"Millimeters\" x=\"20.0\" y=\"10.0\" z=\"0.0\" rotation=\"0.0\"/>\n<placements>\n");
        for (int i = 0; i < pos.length; i++) {
            sb.append("<placement version=\"1.4\" side=\"Top\" id=\"R").append(i + 1)
                    .append("\" part-id=\"R0603-API-TEST\" type=\"Placement\" enabled=\"true\" rank=\"0\">\n")
                    .append("<location units=\"Millimeters\" x=\"").append(pos[i][0]).append("\" y=\"")
                    .append(pos[i][1]).append("\" z=\"0.0\" rotation=\"").append(pos[i][2]).append("\"/>\n")
                    .append("<error-handling>Default</error-handling>\n</placement>\n");
        }
        sb.append("</placements>\n<fiducials/>\n</openpnp-board>\n");
        File f = new File(sim.configDir, "api-test.board.xml");
        Files.write(f.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
        return f;
    }

    String planBody(File board) {
        return "{\"source\":\"file\",\"file\":\"" + board.getAbsolutePath().replace("\\", "\\\\")
                + "\",\"x\":100,\"y\":80,\"z\":1.0,\"rotation\":0}";
    }

    @Test
    public void plansAndRunsABoardWithDph() throws Exception {
        setPlaces();
        File board = writeBoard();
        // §5.7 rule: 0603 is not-started -> the plan is refused, and says why
        JsonObject refused = ok(post("/api/plan", planBody(board)));
        assertFalse(refused.getAsJsonObject("interlocks").get("ok").getAsBoolean());
        assertTrue(refused.toString().contains("STATUS_BELOW_MIN"), refused.toString());
        Res runRefused = post("/api/run", "{\"planId\":\"" + refused.get("planId").getAsString() + "\"}");
        assertEquals(409, runRefused.status);
        assertTrue(runRefused.body.contains("PLAN"), runRefused.toString());

        ok(call("PUT", "/api/pad-types/chip-0603", "{\"status\":\"characterized\"}"));
        // no dot vision on this branch (contract stub): no optical test-dot check
        ok(call("PUT", "/api/config", "{\"testDotOpticalCheck\":false}"));
        JsonObject plan = ok(post("/api/plan", planBody(board)));
        JsonObject sum = plan.getAsJsonObject("summary");
        assertEquals(6, sum.get("pads").getAsInt());
        assertEquals(6, sum.get("dots").getAsInt());
        assertEquals(0, sum.get("blockedPads").getAsInt());
        assertTrue(plan.getAsJsonObject("interlocks").get("ok").getAsBoolean(), plan.toString());
        assertTrue(sum.get("estimatedSeconds").getAsDouble() > 0, sum.toString());
        assertTrue(sum.get("estimatedDphUpperBound").getAsDouble() > 0);
        assertEquals(6, plan.getAsJsonArray("dots").size());
        String planId = plan.get("planId").getAsString();
        assertEquals(planId, ok(get("/api/plan/" + planId)).get("planId").getAsString());

        sim.server.mark();
        JsonObject run = ok(post("/api/run", "{\"planId\":\"" + planId + "\",\"wait\":true}"));
        JsonObject res = run.getAsJsonObject("result");
        assertEquals("OK", res.get("status").getAsString(), run.toString());
        assertEquals(6, res.get("dotsDone").getAsInt());
        assertTrue(res.get("dphMotion").getAsDouble() > 0);
        assertTrue(res.get("dphOverall").getAsDouble() > 0);
        List<String> l = commands(sim.server.sinceMark());
        assertTrue(l.indexOf("M106 P2 S255") < l.indexOf("M106 P3 S255"));

        JsonObject s = ok(get("/api/status"));
        assertEquals(6, s.getAsJsonObject("lastRun").get("dotsDone").getAsInt());
        JsonObject progress = s.getAsJsonObject("run");
        assertEquals(6, progress.getAsJsonArray("doneDots").size());
        assertTrue(get("/api/runs").array().size() >= 1);
        JsonObject last = ok(get("/api/runs/last")).getAsJsonObject("run");
        assertEquals(6, last.getAsJsonArray("dotTimings").size());

        // a subset: only the first 2 dots, logged as such
        JsonObject sub = ok(post("/api/run", "{\"planId\":\"" + planId + "\",\"maxDots\":2,\"wait\":true}"));
        assertEquals(2, sub.get("dots").getAsInt());
        assertEquals(2, sub.getAsJsonObject("result").get("dotsDone").getAsInt());
        assertEquals(2, sub.getAsJsonObject("result").get("dotsPlanned").getAsInt());
        assertEquals(planId, sub.get("parentPlanId").getAsString());

        // a stale plan (needle offset changed) is refused
        ok(post("/api/needle/offset", "{\"offsetMm\":0.07}"));
        Res stale = post("/api/run", "{\"planId\":\"" + planId + "\"}");
        assertEquals(409, stale.status);
        assertTrue(stale.body.contains("plan the board again"), stale.toString());
        assertEquals(404, post("/api/run", "{\"planId\":\"nope\"}").status);
    }

    /** TCP proxy that delays every "ok" from the controller, so a run takes long enough to STOP it. */
    static final class SlowProxy implements AutoCloseable {
        final java.net.ServerSocket ss;
        final List<Socket> sockets = new java.util.concurrent.CopyOnWriteArrayList<>();

        SlowProxy(int targetPort, long okDelayMs) throws Exception {
            ss = new java.net.ServerSocket(0, 5, InetAddress.getLoopbackAddress());
            Thread t = new Thread(() -> {
                while (!ss.isClosed()) {
                    try {
                        Socket client = ss.accept();
                        Socket up = new Socket(InetAddress.getLoopbackAddress(), targetPort);
                        sockets.add(client);
                        sockets.add(up);
                        pump(client, up, 0);
                        pump(up, client, okDelayMs);
                    }
                    catch (Exception e) {
                        // closed
                    }
                }
            }, "SlowProxy");
            t.setDaemon(true);
            t.start();
        }

        static void pump(Socket from, Socket to, long okDelayMs) {
            Thread t = new Thread(() -> {
                try (BufferedReader in = new BufferedReader(new InputStreamReader(from.getInputStream(),
                        StandardCharsets.UTF_8))) {
                    OutputStream out = to.getOutputStream();
                    String line;
                    while ((line = in.readLine()) != null) {
                        if (okDelayMs > 0 && line.startsWith("ok")) {
                            Thread.sleep(okDelayMs);
                        }
                        out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                        out.flush();
                    }
                }
                catch (Exception e) {
                    // closed
                }
            }, "SlowProxy-pump");
            t.setDaemon(true);
            t.start();
        }

        int port() {
            return ss.getLocalPort();
        }

        @Override
        public void close() throws Exception {
            ss.close();
            for (Socket s : sockets) {
                s.close();
            }
        }
    }

    @Test
    public void stopDuringARunAbortsTheEngineAndVentsBeforeOpenPnpStops() throws Exception {
        setPlaces();
        double[][] pos = new double[30][];
        for (int i = 0; i < pos.length; i++) {
            pos[i] = new double[] {2 + 2.5 * (i % 10), 2 + 3 * (i / 10), 0};
        }
        File board = writeBoard(pos);
        ok(call("PUT", "/api/pad-types/chip-0603", "{\"status\":\"characterized\"}"));
        ok(call("PUT", "/api/config", "{\"testDotOpticalCheck\":false}"));
        // reconnect OpenPnP through the slow proxy (still only localhost sockets)
        try (SlowProxy proxy = new SlowProxy(sim.server.getPort(), 10)) {
            sim.machine.setEnabled(false);
            sim.driver.disconnect(); // connection-keep-alive would otherwise keep the direct link
            ((org.openpnp.machine.reference.driver.TcpCommunications) sim.driver.getCommunications())
                    .setPort(proxy.port());
            sim.machine.setEnabled(true);
            sim.machine.home();
            JsonObject plan = ok(post("/api/plan", planBody(board)));
            assertEquals(60, plan.getAsJsonObject("summary").get("dots").getAsInt(), plan.toString());
            assertTrue(plan.getAsJsonObject("interlocks").get("ok").getAsBoolean(), plan.toString());
            assertEquals(202, post("/api/run", "{\"planId\":\"" + plan.get("planId").getAsString() + "\"}").status);
            long t0 = System.currentTimeMillis();
            JsonObject s;
            do {
                Thread.sleep(20);
                s = ok(get("/api/status"));
            } while ((s.get("run").isJsonNull() || s.getAsJsonObject("run").get("dotsDone").getAsInt() < 2)
                    && System.currentTimeMillis() - t0 < 30000);
            sim.server.mark();
            JsonObject stop = ok(post("/api/stop", null));
            t0 = System.currentTimeMillis();
            do {
                Thread.sleep(20);
                s = ok(get("/api/status"));
            } while (s.getAsJsonObject("run").get("running").getAsBoolean() && System.currentTimeMillis() - t0 < 30000);
            JsonObject run = s.getAsJsonObject("run");
            assertEquals("ABORTED", run.get("status").getAsString(), run.toString());
            assertTrue(run.get("dotsDone").getAsInt() < 60, run.toString());
            assertFalse(s.getAsJsonObject("machine").get("enabled").getAsBoolean(), stop.toString());
            List<String> l = commands(sim.server.sinceMark());
            int q = l.indexOf("M410"), v = l.indexOf("M107 P3"), p = l.indexOf("M107 P2"), b = l.indexOf("M18 B");
            assertTrue(q >= 0 && v > q && p > v && b > p, "engine I1 after STOP: " + l);
            // this config has no DISABLE_COMMAND: STOP waited for the engine before OpenPnP's Stop
            assertTrue(stop.toString().contains("engine"), stop.toString());
        }
    }

    @Test
    public void scaleEntriesCreateRecords() throws Exception {
        JsonObject c = ok(post("/api/calibrations/scale-shots",
                "{\"rotationDegPerShot\":360,\"perShotMassMg\":\"1.80, 1.82, 1.79\",\"tip\":\"22G\"}"))
                        .getAsJsonObject("calibration");
        // q = mass / density per turn = 1.8033 / 3.7
        assertEquals(1.8033 / 3.7, c.get("qMm3PerRev").getAsDouble(), 1e-3);
        assertEquals(3, c.get("qN").getAsInt());
        assertEquals("provisional", c.get("status").getAsString());
        long id = c.get("id").getAsLong();
        JsonObject row = ok(post("/api/calibrations/scale-dots",
                "{\"calibrationId\":" + id + ",\"rotationDeg\":58,\"count\":300,\"totalMassMg\":84,\"scaleSdMg\":0.5}"))
                        .getAsJsonObject("row");
        assertEquals(0.28, row.get("mgPerDotMean").getAsDouble(), 1e-9);
        JsonObject all = ok(get("/api/calibrations"));
        assertTrue(all.getAsJsonArray("calibrations").size() >= 2);
        assertEquals(1, get("/api/calibrations/" + id + "/ladder").array().size());
        assertEquals(400, post("/api/calibrations/scale-shots", "{\"rotationDegPerShot\":360}").status);
    }

    @Test
    public void settingsAreValidatedAndNeverPwm() throws Exception {
        Res pwm = call("PUT", "/api/config", "{\"pumpOnGcode\":\"M106 P2 S180\"}");
        assertEquals(400, pwm.status);
        assertTrue(pwm.body.contains("S255"), pwm.toString());
        assertEquals(400, call("PUT", "/api/config", "{\"noSuchSetting\":1}").status);
        // I4 state cannot be forged through the settings; an unchanged round-trip is accepted
        Res forge = call("PUT", "/api/config", "{\"needleZOffsetSetAtMs\":" + System.currentTimeMillis() + "}");
        assertEquals(400, forge.status, forge.toString());
        JsonObject all = ok(get("/api/config"));
        assertEquals(200, call("PUT", "/api/config", all.toString()).status);
        assertEquals(404, get("/static/..").status);
        JsonObject c = ok(call("PUT", "/api/config", "{\"needleGapMm\":0.2}"));
        assertEquals(0.2, c.get("needleGapMm").getAsDouble(), 1e-9);
        assertEquals("M106 P2 S255", c.get("pumpOnGcode").getAsString());
    }

    @Test
    public void cameraAndBoardsWithoutGui() throws Exception {
        // the sim machine has no camera
        Res cam = get("/api/camera/top.jpg");
        assertEquals(404, cam.status, cam.toString());
        JsonObject b = ok(get("/api/boards"));
        assertFalse(b.get("jobAvailable").getAsBoolean(), "headless: no OpenPnP job window");
        assertTrue(get("/api/packages").array().size() > 0);
        assertTrue(ok(get("/api")).getAsJsonArray("routes").size() > 20);
    }

    @Test
    public void homeAndStartGoThroughOpenPnp() throws Exception {
        ok(post("/api/stop", null));
        assertEquals(409, post("/api/machine/home", "{}").status, "not started");
        JsonObject en = ok(post("/api/machine/enable", null));
        assertTrue(en.get("enabled").getAsBoolean());
        JsonObject h = ok(post("/api/machine/home", "{\"wait\":true}"));
        assertTrue(h.get("homed").getAsBoolean());
    }
}
