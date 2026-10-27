package org.openpnp.instancelabs.serial;

import java.util.Collections;
import java.util.List;

/**
 * The outcome of resolving a configured serial identity to a concrete port.
 */
public final class SerialPortMatch {
    public enum Method {
        /** Found by USB serial number. */
        SerialNumber("USB serial number"),
        /** Found by USB description substring. */
        Description("USB description"),
        /** No identity matched (or none configured); using the configured port name/path. */
        PortName("configured port name");

        private final String label;

        Method(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }
    }

    private final Method method;
    private final String portDescriptor;
    private final SerialPortIdentity identity;
    private final List<String> warnings;

    public SerialPortMatch(Method method, String portDescriptor, SerialPortIdentity identity,
            List<String> warnings) {
        this.method = method;
        this.portDescriptor = portDescriptor;
        this.identity = identity;
        this.warnings = Collections.unmodifiableList(warnings);
    }

    public Method getMethod() {
        return method;
    }

    /**
     * @return what to pass to {@code SerialPort.getCommPort()}.
     */
    public String getPortDescriptor() {
        return portDescriptor;
    }

    /**
     * @return the enumerated identity of the chosen port, or null if the port was not seen in
     *         the enumeration (possible for {@link Method#PortName}).
     */
    public SerialPortIdentity getIdentity() {
        return identity;
    }

    public List<String> getWarnings() {
        return warnings;
    }

    /**
     * One-line summary for logs and the settings UI.
     */
    public String getSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append(portDescriptor).append(" by ").append(method.getLabel());
        if (identity != null) {
            sb.append(": ").append(identity.toDisplayString());
        }
        else {
            sb.append(": (port not seen in enumeration; identity unknown)");
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return getSummary();
    }
}
