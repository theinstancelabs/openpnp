package org.openpnp.instancelabs.dispense;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The fixed controller commands of the dispenser, and the guard every line the engine or the
 * pressure hold sends must pass. These are constants, not settings (review finding: a settable
 * pump/valve/watchdog string could PWM the pump through its buck, PWM the valve at S180, swap pump
 * and valve, or never vent).
 *
 * <ul>
 * <li>Right pump: VAC2's first line, {@code M106 P2 S255} / {@code M107 P2} (plan §4.2a: ON/OFF only,
 * never PWM through the 12 V buck).</li>
 * <li>Right valve: {@code M106 P3 S255} / {@code M107 P3} (plan §10 item 8: the EV-3-24 needs >= 90 %
 * of 24 V; never S180).</li>
 * <li>Host watchdog (il/safety firmware): {@code M115} capability query, {@code M990 S<ms>} arm,
 * {@code M990} ping, {@code M990 S0} disarm.</li>
 * </ul>
 *
 * <p>{@link #check(String)} allows only the command words the engine needs. Any line whose command is
 * M106 or M107 must be one of the four pneumatic constants exactly; output-driving commands such as
 * M42/M43/M3-M9 are not on the list, so they are refused.</p>
 */
public final class ControllerCommands {
    private ControllerCommands() {
    }

    public static final String PUMP_ON = "M106 P2 S255";
    public static final String PUMP_OFF = "M107 P2";
    public static final String VALVE_ON = "M106 P3 S255";
    public static final String VALVE_OFF = "M107 P3";
    public static final String CAPABILITY_QUERY = "M115";
    public static final String WATCHDOG_CAPABILITY = "Cap:HOST_WATCHDOG:1";
    public static final String KEEPALIVE_PING = "M990";
    public static final String KEEPALIVE_DISARM = "M990 S0";
    /** il/safety firmware: M990 S must be 0 or 100..3600000 ms. */
    public static final int KEEPALIVE_MIN_MS = 100;
    public static final int KEEPALIVE_MAX_MS = 3600000;

    private static final Set<String> PNEUMATIC = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(PUMP_ON, PUMP_OFF, VALVE_ON, VALVE_OFF)));
    /** Command words (letter + number, leading zeros removed) the engine may send. */
    private static final Set<String> ALLOWED_WORDS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "G0", "G1", "G4", "G92", "M18", "M106", "M107", "M114", "M115", "M201", "M203", "M204", "M400",
            "M410", "M990")));
    private static final Pattern WORD = Pattern.compile("^([GM])0*(\\d+)(?![0-9.])");
    private static final Pattern ARM = Pattern.compile("^M990 S(\\d{1,9})$");

    /** The host-watchdog arm command for a window of {@code ms}. */
    public static String keepaliveArm(int ms) {
        return "M990 S" + ms;
    }

    /** Upper-case, comment removed, whitespace collapsed. */
    static String normalize(String line) {
        String s = line == null ? "" : line;
        int c = s.indexOf(';');
        if (c >= 0) {
            s = s.substring(0, c);
        }
        return s.trim().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
    }

    /** The command word of a line ("M106" for "m0106 p2"), or null if it does not start with G or M. */
    static String word(String normalized) {
        Matcher m = WORD.matcher(normalized);
        return m.find() ? m.group(1) + Integer.parseInt(m.group(2)) : null;
    }

    /** True for M106/M107 lines (any form). */
    public static boolean isOutputCommand(String line) {
        String w = word(normalize(line));
        return "M106".equals(w) || "M107".equals(w);
    }

    /**
     * Why the line may not be sent, or null if it may. Comments and blank lines are allowed (the
     * driver strips them).
     */
    public static String check(String line) {
        String s = normalize(line);
        if (s.isEmpty()) {
            return null;
        }
        String w = word(s);
        if (w == null || !ALLOWED_WORDS.contains(w)) {
            return "'" + line + "' is not a command the dispenser sends";
        }
        switch (w) {
            case "M106":
            case "M107":
                if (!PNEUMATIC.contains(s)) {
                    return "'" + line + "': only " + PUMP_ON + ", " + PUMP_OFF + ", " + VALVE_ON + " and " + VALVE_OFF
                            + " may switch an output (no PWM, no other port)";
                }
                return null;
            case "M115":
                return s.equals(CAPABILITY_QUERY) ? null : "'" + line + "': the capability query is exactly M115";
            case "M990":
                if (s.equals(KEEPALIVE_PING) || s.equals(KEEPALIVE_DISARM)) {
                    return null;
                }
                Matcher m = ARM.matcher(s);
                if (m.matches()) {
                    long ms = Long.parseLong(m.group(1));
                    if (ms >= KEEPALIVE_MIN_MS && ms <= KEEPALIVE_MAX_MS) {
                        return null;
                    }
                }
                return "'" + line + "': M990 must be M990, M990 S0 or M990 S<" + KEEPALIVE_MIN_MS + ".."
                        + KEEPALIVE_MAX_MS + ">";
            default:
                return null;
        }
    }

    /** Throws if {@link #check(String)} refuses the line. */
    public static void requireAllowed(String line) {
        String why = check(line);
        if (why != null) {
            throw new IllegalStateException("refused to send G-code: " + why);
        }
    }
}
