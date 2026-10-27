package org.openpnp.instancelabs.serial;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Resolves a configured serial identity to the port to open.
 *
 * Order (first hit wins):
 * <ol>
 * <li><b>USB serial number</b> (exact, case-insensitive). Unique per board.</li>
 * <li><b>USB description</b> (case-insensitive substring of the port description or
 * descriptive name, e.g. {@code MARLIN_OPULO_LUMEN_REV5}). Shared by every board of the same
 * model, so more than one hit is an error unless the configured port name picks one.</li>
 * <li><b>Configured port name / path</b>, exactly as stock OpenPnP does. A
 * {@code /dev/serial/by-id/...} path is passed through unchanged (jSerialComm resolves the
 * symlink itself) and is also used to look up the identity for display.</li>
 * </ol>
 * Several candidates for one rule are narrowed by the later rules (description, then port name);
 * if still ambiguous a {@link SerialPortMatchException} is thrown rather than guessing, because
 * opening the wrong device is worse than not connecting.
 *
 * This class is pure logic over the injected {@link SerialPortEnumerator} and path resolver; it
 * never opens a port.
 */
public class SerialPortMatcher {
    private final SerialPortEnumerator enumerator;
    private final Function<String, String> pathResolver;

    /**
     * @param enumerator source of the port list.
     * @param pathResolver maps a configured port name to its canonical path (resolving symlinks
     *        such as /dev/serial/by-id/...); must not open the device. Return the input when it
     *        cannot be resolved.
     */
    public SerialPortMatcher(SerialPortEnumerator enumerator, Function<String, String> pathResolver) {
        this.enumerator = enumerator;
        this.pathResolver = pathResolver;
    }

    /**
     * The production matcher: jSerialComm enumeration and filesystem symlink resolution.
     */
    public static SerialPortMatcher createDefault() {
        return new SerialPortMatcher(SerialPortEnumerator.jSerialComm(),
                SerialPortMatcher::canonicalPath);
    }

    /**
     * Resolve a path such as /dev/serial/by-id/... to its target (/dev/ttyACM0). Only reads the
     * symlink; does not open the device. Names without a path separator (ttyACM0, COM3) are
     * returned unchanged.
     */
    public static String canonicalPath(String portName) {
        if (portName == null || portName.indexOf('/') < 0) {
            return portName;
        }
        try {
            return new File(portName).getCanonicalPath();
        }
        catch (IOException | SecurityException e) {
            return portName;
        }
    }

    /**
     * @return the current port list from the enumerator (for UI listing). Never opens a port.
     */
    public List<SerialPortIdentity> enumerate() {
        List<SerialPortIdentity> ports = enumerator.enumerate();
        return ports == null ? Collections.emptyList() : ports;
    }

    public SerialPortMatch resolve(String usbSerialNumber, String usbDescription, String portName)
            throws SerialPortMatchException {
        String serial = blankToNull(usbSerialNumber);
        String description = blankToNull(usbDescription);
        String name = blankToNull(portName);
        List<String> warnings = new ArrayList<>();

        List<SerialPortIdentity> ports;
        if (serial != null || description != null || name != null) {
            ports = enumerateSafely(warnings);
        }
        else {
            ports = Collections.emptyList();
        }

        if (serial != null) {
            List<SerialPortIdentity> hits = ports.stream()
                    .filter(p -> p.hasSerialNumber() && p.getSerialNumber().equalsIgnoreCase(serial))
                    .collect(Collectors.toList());
            if (!hits.isEmpty()) {
                SerialPortIdentity chosen = narrow(hits, description, name,
                        "USB serial number \"" + serial + "\"", warnings);
                return new SerialPortMatch(SerialPortMatch.Method.SerialNumber,
                        chosen.getOpenDescriptor(), chosen, warnings);
            }
            warnings.add("No serial port with USB serial number \"" + serial + "\" found"
                    + listForMessage(ports) + ".");
        }

        if (description != null) {
            List<SerialPortIdentity> hits = ports.stream()
                    .filter(p -> p.descriptionContains(description))
                    .collect(Collectors.toList());
            if (!hits.isEmpty()) {
                SerialPortIdentity chosen = narrow(hits, null, name,
                        "USB description \"" + description + "\"", warnings);
                if (serial != null) {
                    warnings.add("Connected by USB description although USB serial number \""
                            + serial + "\" is configured; this may be a different board (found "
                            + chosen.toDisplayString() + ").");
                }
                return new SerialPortMatch(SerialPortMatch.Method.Description,
                        chosen.getOpenDescriptor(), chosen, warnings);
            }
            warnings.add("No serial port whose USB description contains \"" + description
                    + "\" found" + listForMessage(ports) + ".");
        }

        if (name != null) {
            SerialPortIdentity identity = ports.stream()
                    .filter(p -> matchesPortName(p, name))
                    .findFirst()
                    .orElse(null);
            if ((serial != null || description != null)) {
                warnings.add("Falling back to the configured port name \"" + name + "\".");
            }
            return new SerialPortMatch(SerialPortMatch.Method.PortName, name, identity, warnings);
        }

        if (serial == null && description == null) {
            throw new SerialPortMatchException("No serial port configured.");
        }
        throw new SerialPortMatchException(String.join(" ", warnings)
                + " No port name is configured to fall back to.");
    }

    /**
     * Reduce several candidates to one using the description and then the port name. Throws if
     * still ambiguous.
     */
    private SerialPortIdentity narrow(List<SerialPortIdentity> hits, String description,
            String name, String what, List<String> warnings) throws SerialPortMatchException {
        if (hits.size() == 1) {
            return hits.get(0);
        }
        List<SerialPortIdentity> candidates = hits;
        if (description != null) {
            List<SerialPortIdentity> byDescription = candidates.stream()
                    .filter(p -> p.descriptionContains(description))
                    .collect(Collectors.toList());
            if (byDescription.size() == 1) {
                warnings.add(hits.size() + " ports match " + what
                        + "; chose the one matching the USB description.");
                return byDescription.get(0);
            }
            if (!byDescription.isEmpty()) {
                candidates = byDescription;
            }
        }
        if (name != null) {
            List<SerialPortIdentity> byName = candidates.stream()
                    .filter(p -> matchesPortName(p, name))
                    .collect(Collectors.toList());
            if (byName.size() == 1) {
                warnings.add(hits.size() + " ports match " + what
                        + "; chose the configured port name \"" + name + "\".");
                return byName.get(0);
            }
        }
        throw new SerialPortMatchException(candidates.size() + " serial ports match " + what
                + ": " + candidates.stream().map(SerialPortIdentity::toDisplayString)
                        .collect(Collectors.joining("; "))
                + ". Set the USB serial number (or a /dev/serial/by-id/... port name) to choose one.");
    }

    /**
     * True if the configured port name refers to this port: same system name, same path, or a
     * symlink (e.g. /dev/serial/by-id/...) resolving to it.
     */
    public boolean matchesPortName(SerialPortIdentity port, String configured) {
        if (configured == null) {
            return false;
        }
        String c = configured.trim();
        if (c.isEmpty()) {
            return false;
        }
        String sysName = port.getSystemPortName();
        String sysPath = port.getSystemPortPath();
        if (c.equals(sysName) || c.equals(sysPath)) {
            return true;
        }
        String canonical = pathResolver.apply(c);
        if (canonical == null) {
            return false;
        }
        return canonical.equals(sysPath)
                || (!sysName.isEmpty() && canonical.equals("/dev/" + sysName));
    }

    private List<SerialPortIdentity> enumerateSafely(List<String> warnings) {
        try {
            List<SerialPortIdentity> ports = enumerator.enumerate();
            return ports == null ? Collections.emptyList() : ports;
        }
        catch (RuntimeException | LinkageError e) {
            // LinkageError: jSerialComm's native library could not be loaded.
            warnings.add("Serial port enumeration failed: " + e);
            return Collections.emptyList();
        }
    }

    private static String listForMessage(List<SerialPortIdentity> ports) {
        if (ports.isEmpty()) {
            return " (no serial ports enumerated)";
        }
        return " among " + ports.stream().map(SerialPortIdentity::toDisplayString)
                .collect(Collectors.joining("; "));
    }

    private static String blankToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
