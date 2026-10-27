package org.openpnp.instancelabs.serial;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.fazecast.jSerialComm.SerialPort;

/**
 * Source of the current serial port list. Injected so that the matching logic can be unit-tested
 * with a fixed port list, without touching real hardware.
 */
@FunctionalInterface
public interface SerialPortEnumerator {
    /**
     * @return the ports currently present. Must not open any port.
     */
    List<SerialPortIdentity> enumerate();

    /**
     * The production enumerator: {@link SerialPort#getCommPorts()}, which lists ports and reads
     * their USB descriptors from the OS without opening them.
     */
    static SerialPortEnumerator jSerialComm() {
        return () -> {
            SerialPort[] ports = SerialPort.getCommPorts();
            List<SerialPortIdentity> list = new ArrayList<>(ports.length);
            for (SerialPort port : ports) {
                list.add(SerialPortIdentity.of(port));
            }
            return Collections.unmodifiableList(list);
        };
    }
}
