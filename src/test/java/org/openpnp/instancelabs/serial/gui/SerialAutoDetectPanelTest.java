package org.openpnp.instancelabs.serial.gui;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;

import org.junit.jupiter.api.Test;
import org.openpnp.instancelabs.serial.SerialPortIdentity;
import org.openpnp.instancelabs.serial.SerialPortMatch;

/**
 * Pure-logic parts of the settings panel (no Swing components created, so runs headless).
 */
public class SerialAutoDetectPanelTest {
    static final SerialPortIdentity LUMEN = new SerialPortIdentity("ttyACM0", "/dev/ttyACM0",
            "MARLIN_OPULO_LUMEN_REV5 CDC in FS Mode", "MARLIN_OPULO_LUMEN_REV5 CDC in FS Mode",
            "387932683335", 0x0483, 0x5740, "");
    static final SerialPortIdentity TTY_S4 = new SerialPortIdentity("ttyS4", "/dev/ttyS4",
            "Physical Port S4", "Physical Port S4", "", -1, -1, "");
    static final SerialPortIdentity OTHER_STM32 = new SerialPortIdentity("ttyACM1",
            "/dev/ttyACM1", "STM32 Virtual ComPort", "", "205F37A84E43", 0x0483, 0x5740, "");

    @Test
    public void detectPrefersLumenThenAnyUsbPort() {
        assertSame(LUMEN, SerialAutoDetectPanel.preferred(Arrays.asList(TTY_S4, OTHER_STM32, LUMEN)));
        assertSame(OTHER_STM32, SerialAutoDetectPanel.preferred(Arrays.asList(TTY_S4, OTHER_STM32)));
        assertNull(SerialAutoDetectPanel.preferred(Arrays.asList(TTY_S4)));
    }

    @Test
    public void describeLastMatch() {
        assertTrue(SerialAutoDetectPanel.describeLastMatch(null).startsWith("Not connected"));
        SerialPortMatch m = new SerialPortMatch(SerialPortMatch.Method.SerialNumber, "ttyACM0",
                LUMEN, Collections.singletonList("something odd"));
        String text = SerialAutoDetectPanel.describeLastMatch(m);
        assertTrue(text.startsWith("Last connect: ttyACM0 by USB serial number: ttyACM0"), text);
        assertTrue(text.contains("S/N 387932683335 USB 0483:5740"), text);
        assertTrue(text.contains("\nWarning: something odd"), text);
    }
}
