package org.openpnp.instancelabs.padprofile;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.openpnp.instancelabs.dispense.DispenseDb;
import org.openpnp.instancelabs.dispense.PadType;
import org.openpnp.instancelabs.dispense.PadTypeResolver;
import org.openpnp.instancelabs.dispense.PadTypeStatus;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Tip setups (§22.8): the suggester per tip setup. The bench setup (the seeded 22G bent taper, §1's model) gives
 * exactly the results of a4bb6d20d: a golden file of every §1.9 fixture check, written by that code.
 */
public class SuggesterSetupTest {
    static final String GOLDEN = "/tips/suggester-bench-a4bb6d20d.json";

    /** The checks of the golden file: every package alone, every board side, the boards with the seed types. */
    static Map<String, CheckInputs> goldenInputs() {
        Map<String, CheckInputs> m = new LinkedHashMap<>();
        for (String board : new String[] {"ftp", "mobo", "nano"}) {
            PadFixtures.Fixture f = PadFixtures.load(board);
            for (Map.Entry<String, List<PadInstance>> e : PadFixtures.packageLevel(f).entrySet()) {
                CheckInputs in = benchInputs(e.getValue());
                in.pastePartialPackages.add(e.getValue().get(0).packageId);
                m.put(board + "/package/" + e.getKey(), in);
            }
            for (String side : new String[] {"top", "bottom"}) {
                m.put(board + "/" + side, benchInputs(PadFixtures.side(f, side)));
                m.put(board + "/" + side + "/seed-types", withSeedTypes(PadFixtures.side(f, side)));
            }
        }
        return m;
    }

    /** The bench tip as a4bb6d20d named it (its texts say "22G tip"). */
    static Tip goldenTip() {
        Tip t = Tip.g22();
        t.name = "22G tip";
        return t;
    }

    static CheckInputs benchInputs(List<PadInstance> pads) {
        CheckInputs in = PadFixtures.inputs(pads);
        in.tip = goldenTip();
        return in;
    }

    /**
     * The seed pad types resolved onto the pads as the planner would (D/PadTypeResolver), chip-0603 characterized
     * with its own half-turn plan and chip-0805 validated with a weighed mass (an anchor), the rest not started.
     */
    static CheckInputs withSeedTypes(List<PadInstance> pads) {
        List<PadType> types = DispenseDb.seedPadTypes();
        for (PadType t : types) {
            t.version = 1;
            if ("chip-0603".equals(t.typeKey)) {
                t.status = PadTypeStatus.CHARACTERIZED;
                t.dotSteps = 800;
                t.dotsPerPad = 1;
                t.tipKey = Tip.SEED_KEY;
            }
            else if ("chip-0805".equals(t.typeKey)) {
                t.status = PadTypeStatus.VALIDATED;
                t.dotSteps = 1600;
                t.dotsPerPad = 1;
                t.massPerPadMg = 2.0;
                t.tipKey = Tip.SEED_KEY;
            }
        }
        PadTypeResolver resolver = new PadTypeResolver(types, null);
        CheckInputs in = benchInputs(new ArrayList<>());
        for (PadType t : types) {
            TypeInfo i = new TypeInfo();
            i.key = t.typeKey;
            i.name = t.name;
            i.family = t.family;
            i.version = t.version;
            i.status = t.status.dbName;
            i.padWMm = t.padWMm;
            i.padHMm = t.padHMm;
            i.padRoundness = t.padRoundness;
            i.k = t.k;
            i.dotSteps = t.dotSteps;
            i.dotsPerPad = t.dotsPerPad;
            i.tipKey = t.tipKey;
            i.massPerPadMg = t.massPerPadMg;
            i.origin = t.origin;
            in.types.put(t.typeKey, i);
        }
        for (PadInstance p : pads) {
            PadType t = resolver.resolve(p.packageId, p.padName, Double.NaN, Double.NaN, p.areaMm2());
            if (t != null) {
                p.typeKey = t.typeKey;
                p.typeVersion = t.version;
                p.typeStatus = t.status.dbName;
            }
            in.pads.add(p);
        }
        return in;
    }

    /** The result as the golden file keeps it: the tip, the anchors and the groups (without the per-pad map). */
    static JsonObject golden(ProfileCheck.Result r) {
        Gson g = new GsonBuilder().serializeSpecialFloatingPointValues().create();
        JsonObject o = new JsonParser().parse(g.toJson(r)).getAsJsonObject();
        o.remove("pads");
        return o;
    }

    @Test
    public void writesTheGoldenFileWhenAsked() throws Exception {
        String out = System.getProperty("golden.write");
        if (out == null) {
            return;
        }
        JsonObject all = new JsonObject();
        for (Map.Entry<String, CheckInputs> e : goldenInputs().entrySet()) {
            all.add(e.getKey(), golden(ProfileCheck.of(e.getValue())));
        }
        Files.write(new File(out).toPath(), new GsonBuilder().setPrettyPrinting().serializeSpecialFloatingPointValues()
                .create().toJson(all).getBytes(StandardCharsets.UTF_8));
        assertTrue(new File(out).length() > 0);
    }

    static JsonObject readGolden() throws Exception {
        try (InputStream in = SuggesterSetupTest.class.getResourceAsStream(GOLDEN)) {
            return new JsonParser().parse(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    /** Every key of the golden value is in the new one with the same value (new keys are allowed). */
    static void sameAsGolden(String path, JsonElement golden, JsonElement now, List<String> diffs) {
        if (golden.isJsonObject() && now != null && now.isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : golden.getAsJsonObject().entrySet()) {
                sameAsGolden(path + "." + e.getKey(), e.getValue(), now.getAsJsonObject().get(e.getKey()), diffs);
            }
            return;
        }
        if (golden.isJsonArray() && now != null && now.isJsonArray()
                && golden.getAsJsonArray().size() == now.getAsJsonArray().size()) {
            for (int i = 0; i < golden.getAsJsonArray().size(); i++) {
                sameAsGolden(path + "[" + i + "]", golden.getAsJsonArray().get(i), now.getAsJsonArray().get(i), diffs);
            }
            return;
        }
        if (!golden.equals(now) && diffs.size() < 20) {
            diffs.add(path + ": " + golden + " -> " + now);
        }
    }
}
