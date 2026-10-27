package org.openpnp.instancelabs.dispense;

import java.util.ArrayList;
import java.util.List;

import org.openpnp.machine.reference.driver.GcodeDriver;
import org.openpnp.machine.reference.driver.GcodeDriver.CommandType;

/**
 * {@link GcodeChannel} over an OpenPnP GcodeDriver: {@code sendCommand()} applies the driver's own
 * flow control (GcodeDriver: wait for the confirmation of each line; GcodeAsyncDriver: its queue),
 * its error regex and its logging. Must be used from the machine thread.
 */
public class DriverGcodeChannel implements GcodeChannel {
    private final GcodeDriver driver;

    public DriverGcodeChannel(GcodeDriver driver) {
        this.driver = driver;
    }

    @Override
    public void send(String line, long timeoutMs) throws Exception {
        driver.sendCommand(line, timeoutMs);
    }

    @Override
    public List<String> sendAndCollect(String line, long timeoutMs) throws Exception {
        driver.receiveResponses(); // drop stale lines
        driver.sendCommand(line, timeoutMs);
        String confirm = driver.getCommand(null, CommandType.COMMAND_CONFIRM_REGEX);
        List<String> out = new ArrayList<>();
        for (GcodeDriver.Line l : driver.receiveResponses(confirm == null ? "^ok.*" : confirm, timeoutMs,
                (r) -> r)) {
            out.add(l.getLine());
        }
        return out;
    }
}
