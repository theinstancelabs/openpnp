package org.openpnp.instancelabs.dispense;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

import org.openpnp.machine.reference.ReferenceMachine;
import org.openpnp.machine.reference.driver.GcodeDriver;
import org.openpnp.machine.reference.driver.TcpCommunications;
import org.openpnp.model.Configuration;
import org.openpnp.spi.Nozzle;


/** Loads the DispenseSimMachine test configuration into a temp config dir and connects it to a RecordingGcodeServer. */
public final class SimMachine implements AutoCloseable {
    public final RecordingGcodeServer server;
    public final File configDir;
    public final ReferenceMachine machine;
    public final GcodeDriver driver;
    public final Nozzle n1, n2;

    public SimMachine() throws Exception {
        server = new RecordingGcodeServer();
        configDir = java.nio.file.Files.createTempDirectory("dispense-sim").toFile();
        try (InputStream in = SimMachine.class.getResourceAsStream("/config/DispenseSimMachine/machine.xml")) {
            Files.copy(in, new File(configDir, "machine.xml").toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        Configuration.initialize(configDir);
        Configuration.get().load();
        machine = (ReferenceMachine) Configuration.get().getMachine();
        driver = (GcodeDriver) machine.getDrivers().get(0);
        ((TcpCommunications) driver.getCommunications()).setIpAddress("127.0.0.1");
        ((TcpCommunications) driver.getCommunications()).setPort(server.getPort());
        n1 = machine.getDefaultHead().getNozzleByName("N1");
        n2 = machine.getDefaultHead().getNozzleByName("N2");
        machine.setEnabled(true);
        machine.home();
    }

    @Override
    public void close() throws Exception {
        try {
            machine.setEnabled(false);
        }
        finally {
            server.close();
        }
    }
}
