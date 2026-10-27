package org.openpnp.instancelabs.serial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

/**
 * All tests use a fixed, in-memory port list and an in-memory symlink map. No real port is
 * enumerated or opened.
 */
public class SerialPortMatcherTest {
    static final String LUMEN_SERIAL = "387932683335";
    static final String LUMEN_DESCRIPTION = "MARLIN_OPULO_LUMEN_REV5";
    static final String LUMEN_BY_ID =
            "/dev/serial/by-id/usb-STMicroelectronics_MARLIN_OPULO_LUMEN_REV5_CDC_in_FS_Mode_387932683335-if00";

    static SerialPortIdentity lumen(String tty, String serial) {
        return new SerialPortIdentity(tty, "/dev/" + tty, "MARLIN_OPULO_LUMEN_REV5 CDC in FS Mode",
                "MARLIN_OPULO_LUMEN_REV5 CDC in FS Mode", serial, 0x0483, 0x5740, "1-3:1.0");
    }

    /** The motherboard UART the live config pointed at (plan §2 row 1). */
    static final SerialPortIdentity TTY_S4 = new SerialPortIdentity("ttyS4", "/dev/ttyS4",
            "Physical Port S4", "Physical Port S4", "", -1, -1, "0-0");

    /** Some other STM32 USB-serial gadget with ST's generic 0483:5740 (plan §2 row 1). */
    static final SerialPortIdentity OTHER_STM32 = new SerialPortIdentity("ttyACM1", "/dev/ttyACM1",
            "STM32 Virtual ComPort", "STM32 Virtual ComPort", "205F37A84E43", 0x0483, 0x5740,
            "1-4:1.0");

    static final SerialPortIdentity LUMEN_ACM0 = lumen("ttyACM0", LUMEN_SERIAL);

    static SerialPortMatcher matcher(SerialPortIdentity... ports) {
        return matcher(Arrays.asList(ports), Map.of(LUMEN_BY_ID, "/dev/ttyACM0"));
    }

    static SerialPortMatcher matcher(List<SerialPortIdentity> ports, Map<String, String> links) {
        Function<String, String> resolver = p -> links.getOrDefault(p, p);
        return new SerialPortMatcher(() -> new ArrayList<>(ports), resolver);
    }

    @Test
    public void serialNumberWinsOverWrongPortName() throws Exception {
        // Board came back as ttyACM2 after a reset; config still says ttyS4.
        SerialPortIdentity renumbered = lumen("ttyACM2", LUMEN_SERIAL);
        SerialPortMatch m = matcher(TTY_S4, OTHER_STM32, renumbered)
                .resolve(LUMEN_SERIAL, LUMEN_DESCRIPTION, "ttyS4");
        assertEquals(SerialPortMatch.Method.SerialNumber, m.getMethod());
        assertEquals("ttyACM2", m.getPortDescriptor());
        assertSame(renumbered, m.getIdentity());
        assertTrue(m.getWarnings().isEmpty(), m.getWarnings().toString());
    }

    @Test
    public void serialNumberIsTrimmedAndCaseInsensitive() throws Exception {
        SerialPortIdentity hex = new SerialPortIdentity("ttyACM0", "/dev/ttyACM0", "X", "X",
                "205F37A84E43", 0x0483, 0x5740, "");
        SerialPortMatch m = matcher(hex).resolve("  205f37a84e43 ", null, null);
        assertEquals(SerialPortMatch.Method.SerialNumber, m.getMethod());
        assertEquals("ttyACM0", m.getPortDescriptor());
    }

    @Test
    public void genericStm32IsNotMistakenForLumen() throws Exception {
        // Same VID:PID as the Lumen, but neither serial nor description match.
        SerialPortMatcher m = matcher(OTHER_STM32, TTY_S4);
        SerialPortMatchException e = assertThrows(SerialPortMatchException.class,
                () -> m.resolve(LUMEN_SERIAL, LUMEN_DESCRIPTION, null));
        assertTrue(e.getMessage().contains(LUMEN_SERIAL), e.getMessage());
        assertTrue(e.getMessage().contains("No port name"), e.getMessage());
    }

    @Test
    public void descriptionMatchWhenSerialUnknown() throws Exception {
        SerialPortMatch m = matcher(TTY_S4, OTHER_STM32, LUMEN_ACM0)
                .resolve(null, LUMEN_DESCRIPTION, "ttyS4");
        assertEquals(SerialPortMatch.Method.Description, m.getMethod());
        assertEquals("ttyACM0", m.getPortDescriptor());
        assertTrue(m.getWarnings().isEmpty(), m.getWarnings().toString());
    }

    @Test
    public void descriptionIsCaseInsensitiveSubstring() throws Exception {
        SerialPortMatch m = matcher(LUMEN_ACM0).resolve(null, "opulo_lumen", null);
        assertEquals(SerialPortMatch.Method.Description, m.getMethod());
    }

    @Test
    public void descriptionFallbackWarnsWhenSerialConfiguredButAbsent() throws Exception {
        // Replacement motherboard: new serial, same description.
        SerialPortIdentity replacement = lumen("ttyACM0", "999999999999");
        SerialPortMatch m = matcher(TTY_S4, replacement)
                .resolve(LUMEN_SERIAL, LUMEN_DESCRIPTION, "ttyS4");
        assertEquals(SerialPortMatch.Method.Description, m.getMethod());
        assertEquals("ttyACM0", m.getPortDescriptor());
        assertEquals(2, m.getWarnings().size(), m.getWarnings().toString());
        assertTrue(m.getWarnings().get(0).contains("No serial port with USB serial number"));
        assertTrue(m.getWarnings().get(1).contains("different board"));
    }

    @Test
    public void ambiguousDescriptionThrowsRatherThanGuessing() {
        SerialPortMatcher m = matcher(lumen("ttyACM0", "111"), lumen("ttyACM1", "222"));
        SerialPortMatchException e = assertThrows(SerialPortMatchException.class,
                () -> m.resolve(null, LUMEN_DESCRIPTION, null));
        assertTrue(e.getMessage().startsWith("2 serial ports match"), e.getMessage());
        // And also when the configured port name is neither of them.
        assertThrows(SerialPortMatchException.class,
                () -> m.resolve(null, LUMEN_DESCRIPTION, "ttyS4"));
    }

    @Test
    public void ambiguousDescriptionNarrowedByByIdPortName() throws Exception {
        SerialPortIdentity a = lumen("ttyACM0", LUMEN_SERIAL);
        SerialPortIdentity b = lumen("ttyACM1", "222");
        SerialPortMatch m = matcher(Arrays.asList(a, b), Map.of(LUMEN_BY_ID, "/dev/ttyACM0"))
                .resolve(null, LUMEN_DESCRIPTION, LUMEN_BY_ID);
        assertEquals(SerialPortMatch.Method.Description, m.getMethod());
        assertSame(a, m.getIdentity());
        assertEquals("ttyACM0", m.getPortDescriptor());
        assertEquals(1, m.getWarnings().size());
    }

    @Test
    public void duplicateSerialNarrowedByDescriptionThenPortName() throws Exception {
        // A composite device exposing two interfaces with the same serial number.
        SerialPortIdentity if00 = new SerialPortIdentity("ttyACM0", "/dev/ttyACM0",
                "MARLIN_OPULO_LUMEN_REV5 CDC in FS Mode", "", "S1", 1, 2, "");
        SerialPortIdentity if02 = new SerialPortIdentity("ttyACM1", "/dev/ttyACM1",
                "Debug console", "", "S1", 1, 2, "");
        SerialPortMatch m = matcher(if00, if02).resolve("S1", LUMEN_DESCRIPTION, null);
        assertSame(if00, m.getIdentity());

        SerialPortMatcher noDescription = matcher(if00, if02);
        assertThrows(SerialPortMatchException.class,
                () -> noDescription.resolve("S1", null, null));
        SerialPortMatch byName = noDescription.resolve("S1", null, "/dev/ttyACM1");
        assertSame(if02, byName.getIdentity());
    }

    @Test
    public void fallsBackToByIdPathAndReportsItsIdentity() throws Exception {
        // Serial/description not configured: legacy behaviour, but the identity is now known.
        SerialPortMatch m = matcher(TTY_S4, LUMEN_ACM0).resolve(null, null, LUMEN_BY_ID);
        assertEquals(SerialPortMatch.Method.PortName, m.getMethod());
        // The by-id path is passed through; jSerialComm resolves the symlink itself.
        assertEquals(LUMEN_BY_ID, m.getPortDescriptor());
        assertSame(LUMEN_ACM0, m.getIdentity());
        assertTrue(m.getWarnings().isEmpty());
        assertTrue(m.getSummary().contains("S/N 387932683335"), m.getSummary());
        assertTrue(m.getSummary().contains("USB 0483:5740"), m.getSummary());
    }

    @Test
    public void fallsBackToPortNameWithWarningWhenIdentityAbsent() throws Exception {
        SerialPortMatch m = matcher(TTY_S4).resolve(LUMEN_SERIAL, LUMEN_DESCRIPTION, LUMEN_BY_ID);
        assertEquals(SerialPortMatch.Method.PortName, m.getMethod());
        assertEquals(LUMEN_BY_ID, m.getPortDescriptor());
        assertNull(m.getIdentity());
        assertEquals(3, m.getWarnings().size(), m.getWarnings().toString());
        assertTrue(m.getWarnings().get(2).startsWith("Falling back"));
    }

    @Test
    public void legacyConfigWithPlainPortName() throws Exception {
        SerialPortMatch m = matcher(TTY_S4, LUMEN_ACM0).resolve(null, null, "ttyS4");
        assertEquals(SerialPortMatch.Method.PortName, m.getMethod());
        assertEquals("ttyS4", m.getPortDescriptor());
        assertSame(TTY_S4, m.getIdentity());
        assertTrue(m.getWarnings().isEmpty());

        SerialPortMatch full = matcher(TTY_S4, LUMEN_ACM0).resolve("", "  ", "/dev/ttyACM0");
        assertSame(LUMEN_ACM0, full.getIdentity());
    }

    @Test
    public void portNameNotEnumeratedStillUsed() throws Exception {
        SerialPortMatch m = matcher(LUMEN_ACM0).resolve(null, null, "COM7");
        assertEquals("COM7", m.getPortDescriptor());
        assertNull(m.getIdentity());
        assertTrue(m.getSummary().contains("identity unknown"));
    }

    @Test
    public void nothingConfiguredThrowsAndDoesNotEnumerate() {
        AtomicInteger calls = new AtomicInteger();
        SerialPortMatcher m = new SerialPortMatcher(() -> {
            calls.incrementAndGet();
            return new ArrayList<>();
        }, Function.identity());
        SerialPortMatchException e =
                assertThrows(SerialPortMatchException.class, () -> m.resolve(null, "", " "));
        assertEquals("No serial port configured.", e.getMessage());
        assertEquals(0, calls.get());
    }

    @Test
    public void enumerationFailureFallsBackToPortName() throws Exception {
        SerialPortMatcher m = new SerialPortMatcher(() -> {
            throw new UnsatisfiedLinkError("no jSerialComm native library");
        }, Function.identity());
        SerialPortMatch match = m.resolve(LUMEN_SERIAL, null, "ttyACM0");
        assertEquals(SerialPortMatch.Method.PortName, match.getMethod());
        assertEquals("ttyACM0", match.getPortDescriptor());
        assertTrue(match.getWarnings().get(0).contains("enumeration failed"));
    }

    @Test
    public void unknownSerialIsNeverMatched() throws Exception {
        SerialPortIdentity unknown = new SerialPortIdentity("ttyUSB0", "/dev/ttyUSB0", "FT232R",
                "FT232R", "Unknown", 0x0403, 0x6001, "");
        SerialPortMatcher m = matcher(unknown);
        assertThrows(SerialPortMatchException.class, () -> m.resolve("Unknown", null, null));
    }

    @Test
    public void matchesPortNameVariants() {
        Map<String, String> links = new HashMap<>();
        links.put(LUMEN_BY_ID, "/dev/ttyACM0");
        SerialPortMatcher m = matcher(new ArrayList<>(), links);
        assertTrue(m.matchesPortName(LUMEN_ACM0, "ttyACM0"));
        assertTrue(m.matchesPortName(LUMEN_ACM0, "/dev/ttyACM0"));
        assertTrue(m.matchesPortName(LUMEN_ACM0, LUMEN_BY_ID));
        assertTrue(m.matchesPortName(LUMEN_ACM0, " ttyACM0 "));
        assertTrue(!m.matchesPortName(LUMEN_ACM0, "ttyACM1"));
        assertTrue(!m.matchesPortName(LUMEN_ACM0, ""));
        assertTrue(!m.matchesPortName(LUMEN_ACM0, null));
        // Path empty (as on some platforms): the /dev/ + name form still matches.
        SerialPortIdentity noPath = new SerialPortIdentity("ttyACM0", "", "", "", "", -1, -1, "");
        assertTrue(m.matchesPortName(noPath, LUMEN_BY_ID));
    }

    @Test
    public void canonicalPathLeavesPlainNamesAlone() {
        assertEquals("COM3", SerialPortMatcher.canonicalPath("COM3"));
        assertEquals("ttyACM0", SerialPortMatcher.canonicalPath("ttyACM0"));
        assertNull(SerialPortMatcher.canonicalPath(null));
    }

    @Test
    public void identityDisplayString() {
        assertEquals("ttyACM0 (/dev/ttyACM0) \"MARLIN_OPULO_LUMEN_REV5 CDC in FS Mode\" "
                + "S/N 387932683335 USB 0483:5740", LUMEN_ACM0.toDisplayString());
        assertEquals("ttyS4 (/dev/ttyS4) \"Physical Port S4\"", TTY_S4.toDisplayString());
    }
}
