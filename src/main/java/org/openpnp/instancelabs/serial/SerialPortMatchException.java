package org.openpnp.instancelabs.serial;

import java.io.IOException;

/**
 * Thrown when the configured USB identity cannot be resolved to exactly one port and there is no
 * configured port name to fall back to, or when the identity is ambiguous.
 */
public class SerialPortMatchException extends IOException {
    private static final long serialVersionUID = 1L;

    public SerialPortMatchException(String message) {
        super(message);
    }
}
