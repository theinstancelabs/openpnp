package org.openpnp.instancelabs.dispense;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Static safety check of a generated program before it is streamed. Deterministic rules:
 * <ul>
 * <li>Every line is a command the dispenser sends ({@link ControllerCommands#check}): M106/M107 only
 * as the four fixed pump/valve commands (no PWM, no other port), no M42/M43 or other output
 * commands, the watchdog only as M990 / M990 S0 / M990 S&lt;window&gt;.</li>
 * <li>Valve ON only while the pump is ON; valve OFF before pump OFF (plan §4.5).</li>
 * <li>I2: no B move before G92 B0 unless B was already zeroed since home/reconnect.</li>
 * <li>B moves only inside a pressure session (pump and valve ON).</li>
 * <li>No M400 between a dot's first and last line (no per-dot synchronisation).</li>
 * <li>The program ends with valve and pump OFF and the watchdog disarmed.</li>
 * </ul>
 */
public final class GcodeStreamValidator {
    private GcodeStreamValidator() {
    }

    public static List<String> validate(GcodeProgram prog, DispenseConfig cfg, String letterB) {
        List<String> p = new ArrayList<>();
        boolean pump = false, valve = false, zeroed = prog.bZeroedAtStart, inDot = false, armed = false;
        Pattern bWord = Pattern.compile("(^|\\s)" + Pattern.quote(letterB) + "[-+]?[0-9.]");
        int n = 0;
        for (GcodeProgram.Line line : prog.lines) {
            n++;
            if (line.isComment()) {
                continue;
            }
            String s = line.text.trim();
            String where = "line " + n + " '" + s + "': ";
            String refused = ControllerCommands.check(s);
            if (refused != null) {
                p.add(where + refused);
                continue;
            }
            String norm = ControllerCommands.normalize(s);
            if (norm.equals(ControllerCommands.PUMP_ON)) {
                pump = true;
            }
            else if (norm.equals(ControllerCommands.VALVE_ON)) {
                if (!pump) {
                    p.add(where + "valve ON before pump ON");
                }
                valve = true;
            }
            else if (norm.equals(ControllerCommands.VALVE_OFF)) {
                valve = false;
            }
            else if (norm.equals(ControllerCommands.PUMP_OFF)) {
                if (valve) {
                    p.add(where + "pump OFF while valve still ON (valve must close first)");
                }
                pump = false;
            }
            else if (s.matches("G92\\s.*") && bWord.matcher(s.substring(3)).find()) {
                zeroed = true;
            }
            else if (s.matches("G[01](\\s.*)?") && bWord.matcher(s.substring(2)).find()) {
                if (!zeroed) {
                    p.add(where + "I2: B move before G92 " + letterB + "0");
                }
                if (!(pump && valve)) {
                    p.add(where + "B move outside a pressure session");
                }
            }
            else if (s.equals("M400") && inDot) {
                p.add(where + "M400 inside a dot (breaks streaming)");
            }
            else if (norm.startsWith("M990 S") && !norm.equals(ControllerCommands.KEEPALIVE_DISARM)) {
                armed = true;
            }
            else if (norm.equals(ControllerCommands.KEEPALIVE_DISARM)) {
                armed = false;
            }
            if (line.has(GcodeProgram.Marker.DOT_BEGIN)) {
                inDot = true;
            }
            if (line.has(GcodeProgram.Marker.DOT_END)) {
                inDot = false;
            }
        }
        if (pump || valve) {
            p.add("program ends with pneumatics ON");
        }
        if (armed) {
            p.add("program ends with the host watchdog armed");
        }
        return p;
    }
}
