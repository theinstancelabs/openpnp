package org.openpnp.instancelabs.dispense;

import java.util.List;

/** Line-oriented, flow-controlled link to the controller. */
public interface GcodeChannel {
    /** Sends one line and returns when the driver's flow control accepted it. */
    void send(String line, long timeoutMs) throws Exception;

    /** Sends one line and returns every response line up to and including the confirmation. */
    List<String> sendAndCollect(String line, long timeoutMs) throws Exception;
}
