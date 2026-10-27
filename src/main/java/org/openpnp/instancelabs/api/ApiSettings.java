package org.openpnp.instancelabs.api;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Settings of the local HTTP API (plan §3.3). Read from
 * {@code <configDir>/instancelabs/web-api.json} if present; the API never writes this file, so LAN
 * access can only be turned on by editing it by hand (explicit opt-in).
 *
 * <p>Overrides, highest first: system properties {@code instancelabs.api.port} /
 * {@code instancelabs.api.lan} / {@code instancelabs.api.enabled}, then environment variables
 * {@code OPENPNP_IL_API_PORT} / {@code OPENPNP_IL_API_LAN} / {@code OPENPNP_IL_API_ENABLED}
 * (used by tests and the simulator launcher), then the file, then the defaults.</p>
 */
public class ApiSettings {
    public static final String FILE_NAME = "web-api.json";

    /** TCP port. 0 = any free port (tests). */
    public int port = 8765;
    /**
     * Listen on every network interface so other devices on the LAN (e.g. a tablet next to the
     * machine) can open the UI. Off by default: 127.0.0.1 only.
     */
    public boolean lanAccess = false;
    /** Set false to not start the API at all. */
    public boolean enabled = true;
    /** Worker threads for HTTP requests. */
    public int threads = 8;

    public static File fileFor(File configDir) {
        return new File(new File(configDir, "instancelabs"), FILE_NAME);
    }

    /** Defaults, then the file (if any), then environment, then system properties. */
    public static ApiSettings load(File configDir) {
        ApiSettings s = new ApiSettings();
        if (configDir != null) {
            File f = fileFor(configDir);
            if (f.isFile()) {
                try {
                    ApiSettings fromFile = Json.GSON.fromJson(
                            new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8), ApiSettings.class);
                    if (fromFile != null) {
                        s = fromFile;
                    }
                }
                catch (IOException | RuntimeException e) {
                    org.pmw.tinylog.Logger.warn(e, "Instance Labs API: ignoring unreadable {}", f);
                }
            }
        }
        s.port = intOverride("instancelabs.api.port", "OPENPNP_IL_API_PORT", s.port);
        s.lanAccess = boolOverride("instancelabs.api.lan", "OPENPNP_IL_API_LAN", s.lanAccess);
        s.enabled = boolOverride("instancelabs.api.enabled", "OPENPNP_IL_API_ENABLED", s.enabled);
        if (s.threads < 2) {
            s.threads = 2;
        }
        return s;
    }

    private static String override(String prop, String env) {
        String v = System.getProperty(prop);
        if (v == null || v.trim().isEmpty()) {
            v = System.getenv(env);
        }
        return v == null || v.trim().isEmpty() ? null : v.trim();
    }

    private static int intOverride(String prop, String env, int def) {
        String v = override(prop, env);
        if (v == null) {
            return def;
        }
        try {
            return Integer.parseInt(v);
        }
        catch (NumberFormatException e) {
            org.pmw.tinylog.Logger.warn("Instance Labs API: ignoring non-numeric {}={}", prop, v);
            return def;
        }
    }

    private static boolean boolOverride(String prop, String env, boolean def) {
        String v = override(prop, env);
        return v == null ? def : Boolean.parseBoolean(v) || "1".equals(v);
    }
}
