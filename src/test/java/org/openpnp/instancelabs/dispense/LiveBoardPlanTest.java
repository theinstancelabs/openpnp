package org.openpnp.instancelabs.dispense;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

import org.junit.jupiter.api.Test;
import org.openpnp.model.Board;
import org.openpnp.model.BoardLocation;
import org.openpnp.model.Configuration;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;

/**
 * Plans the real test board (~/Desktop/ftp.board.xml: 40 R_0603 + 40 LED_0603 + 3 fiducials) with
 * COPIES of the live packages.xml / parts.xml in a temp config dir. Skipped where those files do
 * not exist. Never writes to ~/.openpnp2.
 */
public class LiveBoardPlanTest {
    @Test
    public void ftpBoardPlans160Dots() throws Exception {
        File home = new File(System.getProperty("user.home"));
        File boardFile = new File(home, "Desktop/ftp.board.xml");
        File live = new File(home, ".openpnp2");
        assumeTrue(boardFile.exists() && new File(live, "packages.xml").exists() && new File(live, "parts.xml").exists());
        File dir = Files.createTempDirectory("dispense-live-copy").toFile();
        for (String f : new String[] {"packages.xml", "parts.xml"}) {
            Files.copy(new File(live, f).toPath(), new File(dir, f).toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        File boardCopy = new File(dir, "ftp.board.xml");
        Files.copy(boardFile.toPath(), boardCopy.toPath(), StandardCopyOption.REPLACE_EXISTING);
        Configuration.initialize(dir);
        Configuration.get().load();
        Board board = Configuration.get().getBoard(boardCopy);
        BoardLocation bl = new BoardLocation(board);
        bl.setLocation(new Location(LengthUnit.Millimeters, 150, 100, 1.6, 0));
        try (DispenseDb db = DispenseDb.inMemory()) {
            DispenseConfig cfg = new DispenseConfig();
            cfg.minPadTypeStatus = "not-started"; // seeds only: this checks geometry, not readiness
            Calibration cal = db.currentCalibration(System.currentTimeMillis());
            DispensePlan plan = new DispensePlanner(cfg, new PadTypeResolver(db.currentPadTypes(), db.packageMappings()),
                    cal, db.dotLadder(cal.id)).planBoard(bl, 150, 100);
            assertEquals(160, plan.pads.size(), plan.summary());
            assertEquals(160, plan.dots.size(), plan.summary());
            assertEquals(0, plan.blockedPads().size());
            for (DispensePlan.PlannedPad p : plan.pads) {
                assertEquals("chip-0603", p.typeKey);
                assertEquals(PadGeometry.Source.PAD_TYPE, p.source, "live packages have no footprint pads for 0603");
            }
            assertTrue(plan.pathLengthMm < plan.pathLengthNearestNeighbourMm);
            cfg.locations.put("waste", new DispenseConfig.NamedLocation(140, 90, 1.0));
            GcodeProgram prog = GcodeStreamBuilder.forPlan(cfg, new ToyMapper(150, 100, 31.5, 0), plan, false, false,
                    GcodeStreamBuilder.Part.FULL);
            assertTrue(GcodeStreamValidator.validate(prog, cfg, "B").isEmpty());
            System.out.println(String.format(java.util.Locale.ROOT,
                    "LiveBoardPlanTest: kinematic lower-bound estimate (no acceleration) %.1f s for %d dots, %d lines",
                    prog.estimatedSeconds, prog.dotCount, prog.lines.size()));
            System.out.println("LiveBoardPlanTest: " + plan.summary() + String.format(java.util.Locale.ROOT,
                    ", total rotation %.0f deg = %.3f mm3", plan.totalRotationDeg(),
                    DotMath.volumeForRotation(plan.totalRotationDeg(), cal.qMm3PerRev)));
        }
    }
}
