package org.openpnp.instancelabs.serial;

import java.util.Locale;
import java.util.Objects;

import com.fazecast.jSerialComm.SerialPort;

/**
 * Immutable snapshot of what jSerialComm reports about one serial port during enumeration.
 *
 * OpenPnP's stock port list keeps only {@link SerialPort#getSystemPortName()} and discards the
 * rest. This class keeps the USB identity (serial number, description, VID:PID) so a port can be
 * found again after it is renumbered (e.g. ttyACM0 becoming ttyACM1 after a board reset).
 *
 * Creating an identity from a {@link SerialPort} only reads fields that jSerialComm filled in
 * during enumeration; it never opens the port.
 */
public final class SerialPortIdentity {
    /** jSerialComm reports -1 when the VID/PID is unknown (non-USB ports). */
    public static final int UNKNOWN_ID = -1;

    private final String systemPortName;
    private final String systemPortPath;
    private final String portDescription;
    private final String descriptivePortName;
    private final String serialNumber;
    private final int vendorId;
    private final int productId;
    private final String portLocation;

    public SerialPortIdentity(String systemPortName, String systemPortPath, String portDescription,
            String descriptivePortName, String serialNumber, int vendorId, int productId,
            String portLocation) {
        this.systemPortName = clean(systemPortName);
        this.systemPortPath = clean(systemPortPath);
        this.portDescription = clean(portDescription);
        this.descriptivePortName = clean(descriptivePortName);
        this.serialNumber = clean(serialNumber);
        this.vendorId = vendorId;
        this.productId = productId;
        this.portLocation = clean(portLocation);
    }

    /**
     * Snapshot the enumeration data of a jSerialComm port. Does not open the port.
     */
    public static SerialPortIdentity of(SerialPort port) {
        return new SerialPortIdentity(port.getSystemPortName(), port.getSystemPortPath(),
                port.getPortDescription(), port.getDescriptivePortName(), port.getSerialNumber(),
                port.getVendorID(), port.getProductID(), port.getPortLocation());
    }

    private static String clean(String s) {
        return s == null ? "" : s.trim();
    }

    public String getSystemPortName() {
        return systemPortName;
    }

    public String getSystemPortPath() {
        return systemPortPath;
    }

    public String getPortDescription() {
        return portDescription;
    }

    public String getDescriptivePortName() {
        return descriptivePortName;
    }

    /**
     * @return the USB serial number, or "" if the OS did not report one.
     */
    public String getSerialNumber() {
        return serialNumber;
    }

    public int getVendorId() {
        return vendorId;
    }

    public int getProductId() {
        return productId;
    }

    public String getPortLocation() {
        return portLocation;
    }

    public boolean hasUsbIds() {
        return vendorId != UNKNOWN_ID && productId != UNKNOWN_ID;
    }

    /**
     * jSerialComm reports "Unknown" as serial number for some ports where none is available.
     */
    public boolean hasSerialNumber() {
        return !serialNumber.isEmpty() && !serialNumber.equalsIgnoreCase("Unknown");
    }

    /**
     * The string to pass to {@link SerialPort#getCommPort(String)} to open this port. The system
     * port name is what stock OpenPnP stores and opens, so it is the most portable choice.
     */
    public String getOpenDescriptor() {
        return !systemPortName.isEmpty() ? systemPortName : systemPortPath;
    }

    /**
     * Case-insensitive substring match against both the port description and the descriptive
     * name (different OSes put the USB product string in different fields).
     */
    public boolean descriptionContains(String needle) {
        if (needle == null || needle.trim().isEmpty()) {
            return false;
        }
        String n = needle.trim().toLowerCase(Locale.ROOT);
        return portDescription.toLowerCase(Locale.ROOT).contains(n)
                || descriptivePortName.toLowerCase(Locale.ROOT).contains(n);
    }

    /**
     * The best human-readable description: the USB product string if present, else the
     * descriptive name.
     */
    public String getBestDescription() {
        return !portDescription.isEmpty() ? portDescription : descriptivePortName;
    }

    /**
     * One-line summary for logs and the settings UI, e.g.
     * {@code ttyACM0 (/dev/ttyACM0) "MARLIN_OPULO_LUMEN_REV5 CDC in FS Mode" S/N 387932683335 USB 0483:5740}
     */
    public String toDisplayString() {
        StringBuilder sb = new StringBuilder(systemPortName.isEmpty() ? "?" : systemPortName);
        if (!systemPortPath.isEmpty() && !systemPortPath.equals(systemPortName)) {
            sb.append(" (").append(systemPortPath).append(")");
        }
        String description = getBestDescription();
        if (!description.isEmpty()) {
            sb.append(" \"").append(description).append("\"");
        }
        if (hasSerialNumber()) {
            sb.append(" S/N ").append(serialNumber);
        }
        if (hasUsbIds()) {
            sb.append(String.format(Locale.ROOT, " USB %04x:%04x", vendorId, productId));
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return toDisplayString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SerialPortIdentity)) {
            return false;
        }
        SerialPortIdentity other = (SerialPortIdentity) o;
        return vendorId == other.vendorId && productId == other.productId
                && systemPortName.equals(other.systemPortName)
                && systemPortPath.equals(other.systemPortPath)
                && portDescription.equals(other.portDescription)
                && descriptivePortName.equals(other.descriptivePortName)
                && serialNumber.equals(other.serialNumber)
                && portLocation.equals(other.portLocation);
    }

    @Override
    public int hashCode() {
        return Objects.hash(systemPortName, systemPortPath, portDescription, descriptivePortName,
                serialNumber, vendorId, productId, portLocation);
    }
}
