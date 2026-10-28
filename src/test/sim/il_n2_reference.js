/**
 * Instance Labs sim acceptance (plan §8.1): OpenPnP's OWN N2 position for every pad.
 *
 * Copied into the scripts/ folder of a SIM config copy by il_sim_acceptance.py and run from the
 * Scripts menu. Never part of a real machine config.
 *
 * Input  scripts/il_n2_reference.input.json:
 *   {boardFile, x, y, z, rotation, nozzle, dispenseZ, pads: [{padId, bx, by}]}
 *   (bx, by = pad centre in board coordinates, computed by the harness from the board file)
 * For each pad it lets OpenPnP compute the machine location (Utils2D.calculateBoardPlacementLocation,
 * the same board transform the job processor uses), commands nozzle.moveTo() (OpenPnP's motion
 * path: head offsets, the mapped/negated z2 axis, backlash compensation, GcodeDriver templates),
 * waits for still-stand, then sends "M118 P<index+1>" so the harness can read the raw X/Y/Z
 * the simulated controller received for that pad.
 * Output scripts/il_n2_reference.output.json: [{padId, gx, gy, gz, nx, ny, nz}] (+ "done": true)
 */
var imports = new JavaImporter(org.openpnp.model, org.openpnp.util, java.io, java.nio.file);
with (imports) {
    var dir = scripting.getScriptsDirectory().toString();
    var inp = JSON.parse(new java.lang.String(Files.readAllBytes(Paths.get(dir, "il_n2_reference.input.json")), "UTF-8"));
    Packages.org.openpnp.util.UiUtils['submitUiMachineTask(Thrunnable)'](function() {
        var out = {done: false, pads: []};
        var board = config.getBoard(new File(inp.boardFile));
        var bl = new BoardLocation(board);
        bl.setLocation(new Location(LengthUnit.Millimeters, inp.x, inp.y, inp.z, inp.rotation));
        var nozzle = null;
        var heads = machine.getHeads();
        for (var h = 0; h < heads.size(); h++) {
            var nz = heads.get(h).getNozzles();
            for (var k = 0; k < nz.size(); k++) {
                if (nz.get(k).getName() == inp.nozzle) {
                    nozzle = nz.get(k);
                }
            }
        }
        var driver = machine.getDrivers().get(0);
        var CompletionType = Packages.org.openpnp.spi.MotionPlanner.CompletionType;
        for (var i = 0; i < inp.pads.length; i++) {
            var p = inp.pads[i];
            var g = Utils2D.calculateBoardPlacementLocation(bl, new Location(LengthUnit.Millimeters, p.bx, p.by, 0, 0))
                    .convertToUnits(LengthUnit.Millimeters);
            var rot = nozzle.getLocation().getRotation();
            nozzle.moveTo(new Location(LengthUnit.Millimeters, g.getX(), g.getY(), inp.dispenseZ, rot));
            machine.getMotionPlanner().waitForCompletion(null, CompletionType.WaitForStillstand);
            driver.sendCommand("M118 P" + (i + 1)); // marker: pad index + 1 (numeric, GcodeServer parses it)
            var n = nozzle.getLocation().convertToUnits(LengthUnit.Millimeters);
            out.pads.push({padId: p.padId, gx: g.getX(), gy: g.getY(), gz: g.getZ(),
                           nx: n.getX(), ny: n.getY(), nz: n.getZ()});
        }
        out.done = true;
        Files.write(Paths.get(dir, "il_n2_reference.output.json"),
                new java.lang.String(JSON.stringify(out)).getBytes("UTF-8"));
    });
}
