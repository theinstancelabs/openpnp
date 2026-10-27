package org.openpnp.instancelabs.vision;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openpnp.instancelabs.vision.Synth.Background;
import org.openpnp.instancelabs.vision.Synth.Shot;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class InspectToolTest {
    @TempDir
    Path tmp;

    @Test
    public void printsJsonForPngPair() throws Exception {
        double mm = 0.035;
        Shot b = DotInspectorTest.base(Background.FR4, mm);
        Shot a = b.copy();
        a.noiseSeed = 8;
        a.deposits.add(Synth.disk(Synth.PAD_X0, Synth.PAD_Y0, 0.6));
        a.deposits.add(Synth.disk(-Synth.PAD_X0, -Synth.PAD_Y0, 0.4));
        File bf = tmp.resolve("before.png").toFile();
        File af = tmp.resolve("after.png").toFile();
        File of = tmp.resolve("overlay.png").toFile();
        ImageIO.write(Synth.render(b), "png", bf);
        ImageIO.write(Synth.render(a), "png", af);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = InspectTool.run(new String[] {bf.getPath(), af.getPath(), "0.035", "--pad",
                Synth.PAD_X0 + "," + Synth.PAD_Y0 + "," + Synth.PAD_W + "," + Synth.PAD_H + ",0",
                "--overlay", of.getPath()}, new PrintStream(out, true, "UTF-8"),
                new PrintStream(err, true, "UTF-8"));
        String json = out.toString("UTF-8");
        System.out.println("[dot-vision] InspectTool output:\n" + json);
        assertEquals(0, code, err.toString("UTF-8"));
        JsonObject o = new JsonParser().parse(json).getAsJsonObject();
        assertEquals(2, o.get("dotCount").getAsInt());
        JsonArray dots = o.getAsJsonArray("dots");
        double d0 = dots.get(0).getAsJsonObject().get("diameterMm").getAsDouble();
        double d1 = dots.get(1).getAsJsonObject().get("diameterMm").getAsDouble();
        assertEquals(0.4, Math.min(d0, d1), 0.03);
        assertEquals(0.6, Math.max(d0, d1), 0.03);
        double cov = o.getAsJsonArray("pads").get(0).getAsJsonObject().get("coverage")
                .getAsDouble();
        assertEquals(Math.PI * 0.09 / (Synth.PAD_W * Synth.PAD_H), cov, 0.05);
        assertTrue(of.isFile() && ImageIO.read(of) != null);
    }

    @Test
    public void usageErrors() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream o = new PrintStream(out, true, "UTF-8");
        PrintStream e = new PrintStream(err, true, "UTF-8");
        assertEquals(2, InspectTool.run(new String[] {"a.png"}, o, e));
        assertEquals(2, InspectTool.run(new String[] {"a.png", "b.png", "0.03", "--bogus"}, o, e));
        assertEquals(1, InspectTool.run(new String[] {
                tmp.resolve("missing1.png").toString(), tmp.resolve("missing2.png").toString(),
                "0.03"}, o, e));
        assertEquals(0, InspectTool.run(new String[] {"--help"}, o, e));
        assertTrue(new String(err.toByteArray(), StandardCharsets.UTF_8).contains("usage"));
    }
}
