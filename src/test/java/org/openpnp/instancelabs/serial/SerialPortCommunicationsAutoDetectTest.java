package org.openpnp.instancelabs.serial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringWriter;
import java.util.Arrays;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.openpnp.machine.reference.driver.SerialPortCommunications;
import org.openpnp.model.Configuration;
import org.simpleframework.xml.Serializer;

/**
 * SerialPortCommunications integration: persistence compatibility and resolution through an
 * injected (fake) matcher. Never connects, never enumerates real ports.
 */
public class SerialPortCommunicationsAutoDetectTest {
    /** The serial element as found in the live/legacy machine.xml (no new attributes). */
    static final String LEGACY_XML = "<serial line-ending-type=\"LF\" port-name=\"ttyS4\" "
            + "baud=\"115200\" flow-control=\"RtsCts\" data-bits=\"Eight\" stop-bits=\"One\" "
            + "parity=\"None\" set-dtr=\"false\" set-rts=\"false\" name=\"SerialPortCommunications\"/>";

    static SerialPortCommunications withFakePorts(SerialPortCommunications serial,
            SerialPortIdentity... ports) {
        serial.setPortMatcher(SerialPortMatcherTest.matcher(Arrays.asList(ports),
                Map.of(SerialPortMatcherTest.LUMEN_BY_ID, "/dev/ttyACM0")));
        return serial;
    }

    @Test
    public void legacyXmlLoadsWithoutIdentity() throws Exception {
        Serializer serializer = Configuration.createSerializer();
        SerialPortCommunications serial =
                serializer.read(SerialPortCommunications.class, LEGACY_XML);
        assertEquals("ttyS4", serial.getPortName());
        assertNull(serial.getUsbSerialNumber());
        assertNull(serial.getUsbDescription());
        assertEquals(SerialPortCommunications.FlowControl.RtsCts, serial.getFlowControl());
    }

    @Test
    public void unusedIdentityIsNotWritten() throws Exception {
        // Keeps machine.xml readable by stock OpenPnP (its Persister is strict).
        Serializer serializer = Configuration.createSerializer();
        SerialPortCommunications serial =
                serializer.read(SerialPortCommunications.class, LEGACY_XML);
        serial.setUsbSerialNumber("   "); // as written by an empty text field
        serial.setUsbDescription("");
        StringWriter out = new StringWriter();
        serializer.write(serial, out);
        assertFalse(out.toString().contains("usb-"), out.toString());
        assertTrue(out.toString().contains("port-name=\"ttyS4\""), out.toString());
    }

    @Test
    public void identityRoundTrips() throws Exception {
        Serializer serializer = Configuration.createSerializer();
        SerialPortCommunications serial = new SerialPortCommunications();
        serial.setPortName(SerialPortMatcherTest.LUMEN_BY_ID);
        serial.setUsbSerialNumber(" 387932683335 ");
        serial.setUsbDescription("MARLIN_OPULO_LUMEN_REV5");
        StringWriter out = new StringWriter();
        serializer.write(serial, out);
        String xml = out.toString();
        assertTrue(xml.contains("usb-serial-number=\"387932683335\""), xml);
        assertTrue(xml.contains("usb-description=\"MARLIN_OPULO_LUMEN_REV5\""), xml);

        SerialPortCommunications back = serializer.read(SerialPortCommunications.class, xml);
        assertEquals("387932683335", back.getUsbSerialNumber());
        assertEquals("MARLIN_OPULO_LUMEN_REV5", back.getUsbDescription());
        assertEquals(SerialPortMatcherTest.LUMEN_BY_ID, back.getPortName());
    }

    @Test
    public void resolvePortUsesIdentityAndRecordsLastMatch() throws Exception {
        Serializer serializer = Configuration.createSerializer();
        SerialPortCommunications serial = withFakePorts(
                serializer.read(SerialPortCommunications.class, LEGACY_XML),
                SerialPortMatcherTest.TTY_S4, SerialPortMatcherTest.lumen("ttyACM1", "387932683335"));
        serial.setUsbSerialNumber("387932683335");
        assertNull(serial.getLastMatch());

        SerialPortMatch m = serial.resolvePort();
        assertEquals(SerialPortMatch.Method.SerialNumber, m.getMethod());
        assertEquals("ttyACM1", m.getPortDescriptor());
        assertSame(m, serial.getLastMatch());
    }

    @Test
    public void resolvePortLegacyBehaviourUnchanged() throws Exception {
        SerialPortCommunications serial = withFakePorts(new SerialPortCommunications(),
                SerialPortMatcherTest.LUMEN_ACM0);
        serial.setPortName("ttyACM0");
        SerialPortMatch m = serial.resolvePort();
        assertEquals(SerialPortMatch.Method.PortName, m.getMethod());
        assertEquals("ttyACM0", m.getPortDescriptor());
        assertSame(SerialPortMatcherTest.LUMEN_ACM0, m.getIdentity());
    }

    @Test
    public void resolvePortFailsWhenNothingConfigured() {
        SerialPortCommunications serial = withFakePorts(new SerialPortCommunications());
        assertThrows(SerialPortMatchException.class, serial::resolvePort);
    }
}
