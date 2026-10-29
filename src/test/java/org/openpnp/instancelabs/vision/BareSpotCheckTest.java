package org.openpnp.instancelabs.vision;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.openpnp.instancelabs.vision.BareSpotCheck.Result;
import org.openpnp.instancelabs.vision.BareSpotCheck.Settings;

/**
 * The camera look on bench frames (raw 1920 x 1080 at 0.01074 x 0.01106 mm/px) and on Java2D
 * frames. Each bench spot is cut out of its frame, centred on the spot, and printed with the
 * prefix "[bare-spot]" together with its margin to the defaults.
 */
public class BareSpotCheckTest {
    static final double SX = FidScene.BENCH_X, SY = FidScene.BENCH_Y;
    static final String BARE = "/org/openpnp/instancelabs/vision/bare/";
    static final String FID = "/org/openpnp/instancelabs/vision/fid/";

    /** The look at raw pixel (x, y) of a bench frame: a 560 x 544 px cut centred there. */
    static Result look(String label, String path, int x, int y) throws Exception {
        BufferedImage img = FidScene.resource(path);
        Result r = BareSpotCheck.check(img.getSubimage(x - 280, y - 272, 560, 544), SX, SY, null);
        Settings s = new Settings();
        System.out.println(String.format(Locale.ROOT,
                "[bare-spot] %-46s share %5.1f %% (limit %.1f %%, x%.2f)  spread %5.1f (limit"
                        + " %.0f, x%.2f)  %s",
                label, 100 * r.foreignShare, 100 * s.maxForeignShare,
                r.foreignShare / s.maxForeignShare, r.ringSpread, s.maxRingSpread,
                r.ringSpread / s.maxRingSpread, r.bare ? "bare" : "NOT BARE"));
        return r;
    }

    /**
     * Bare surface. Looks centred on the spot, as the engine takes them, keep at least 2x margin
     * on the share; the board3 spots sit 2.9-3.0 mm from FID1 off the frame centre, in the ring
     * light's reflections, and only stay under the limit. Every spread keeps 1.3x.
     */
    @Test
    public void bareSpots() throws Exception {
        Object[][] spots = {
                {"bare1 centre (board mask)", BARE + "bare1.jpg", 960, 540},
                {"bare2 centre (silkscreen tip 0.7 mm away)", BARE + "bare2.jpg", 960, 540},
                {"board3_FID1_2 mask 3.0 mm above FID1", BARE + "board3_FID1_2.jpg", 960, 272},
                {"board3_FID1_2 mask 2.9 mm up-right of FID1", BARE + "board3_FID1_2.jpg", 1150, 350},
                {"board3_FID1_2 mask 2.9 mm up-left of FID1", BARE + "board3_FID1_2.jpg", 770, 350},
                {"reflow1_FID2_0 centre (3.2 mm from FID2)", BARE + "reflow1_FID2_0.jpg", 960, 540},
                {"reflow1_FID3_0 centre (board edge in ring)", FID + "reflow1_FID3_0.jpg", 960, 540},
                {"blk7_365 blank between dot rows", BARE + "blk7_365.jpg", 1060, 627}};
        Settings s = new Settings();
        for (Object[] p : spots) {
            Result r = look((String) p[0], (String) p[1], (Integer) p[2], (Integer) p[3]);
            assertTrue(r.bare, p[0] + ": " + r);
            assertNull(r.reason);
            boolean offCentre = ((String) p[0]).startsWith("board3");
            assertTrue(r.foreignShare <= s.maxForeignShare / (offCentre ? 1 : 2), p[0] + ": " + r);
            assertTrue(r.ringSpread <= s.maxRingSpread / 1.3, p[0] + ": " + r);
        }
    }

    /** Paste, silkscreen, a board edge: each with at least 4x the share limit. */
    @Test
    public void foreignSpots() throws Exception {
        Object[][] spots = {
                {"blk7_365 on a purge dot", BARE + "blk7_365.jpg", 950, 767},
                {"blk7_365 on another purge dot", BARE + "blk7_365.jpg", 1172, 488},
                {"blk7_365 purge dot 1.2 mm beside", BARE + "blk7_365.jpg", 1062, 767},
                {"blk_y243_x340 paste on a round pad", BARE + "blk_y243_x340.jpg", 875, 453},
                {"reflow1_FID1_0 centre (FID1 silkscreen)", FID + "reflow1_FID1_0.jpg", 960, 540},
                {"board2_FID1_0 centre (board edge across)", FID + "board2_FID1_0.jpg", 960, 540}};
        Settings s = new Settings();
        for (Object[] p : spots) {
            Result r = look((String) p[0], (String) p[1], (Integer) p[2], (Integer) p[3]);
            assertFalse(r.bare, p[0] + ": " + r);
            assertTrue(r.foreignShare >= 4 * s.maxForeignShare, p[0] + ": " + r);
            assertTrue(r.reason.contains("paste, a part or print"), r.reason);
        }
    }

    /** At the blank's edge the ring itself is not one surface. */
    @Test
    public void blankEdge() throws Exception {
        Result r = look("blank_edge at the edge", BARE + "blank_edge.jpg", 1500, 540);
        assertFalse(r.bare);
        assertTrue(r.ringSpread >= 2 * new Settings().maxRingSpread, r.toString());
        assertTrue(r.reason.startsWith("the surface around the spot is not uniform"), r.reason);
    }

    static BufferedImage synth(java.util.function.Consumer<java.awt.Graphics2D> draw) {
        return FidScene.render(640, 600, SX, SY, draw);
    }

    @Test
    public void syntheticLooks() {
        Result plain = BareSpotCheck.check(FidScene.noise(synth(g -> { }), 3, 4), SX, SY, null);
        assertTrue(plain.bare, plain.toString());
        assertEquals(0, plain.foreignShare, 0);
        assertEquals(142, plain.refB, 1);
        assertEquals(59, plain.refG, 1);
        assertEquals(0, plain.refR, 1);
        // The SIM's paste on the SIM's mask (22.6 apart): a 1.0 mm dot on the spot, with noise.
        Result dirty = BareSpotCheck.check(FidScene.noise(synth(g -> FidScene.disk(g, 0.1, -0.2,
                1.0, FidScene.PASTE)), 5, 4), SX, SY, null);
        assertFalse(dirty.bare, dirty.toString());
        assertTrue(dirty.foreignShare > 0.2, dirty.toString());
        // A 0.5 mm paste dot (6 % of the 1 mm disc) at 0.5 mm from the centre.
        Result speck = BareSpotCheck.check(synth(g -> FidScene.disk(g, 0.5, 0, 0.5,
                FidScene.PASTE)), SX, SY, null);
        assertFalse(speck.bare, speck.toString());
        assertEquals(0.5 * 0.5 / 4, speck.foreignShare, 0.01);
        // The same dot 1.3 mm away is outside the disc and too small to move the ring median.
        Result beside = BareSpotCheck.check(synth(g -> FidScene.disk(g, 1.3, 0, 0.5,
                FidScene.PASTE)), SX, SY, null);
        assertTrue(beside.bare, beside.toString());
        // The dark bed beyond an edge 2 mm from the spot covers 15 % of the ring: the median is
        // still the surface, and the spot itself is clear.
        Result edge = BareSpotCheck.check(synth(g -> {
            g.setColor(FidScene.bgr(40, 40, 40));
            g.fill(new Rectangle2D.Double(2.0, -10, 20, 20));
        }), SX, SY, null);
        assertTrue(edge.bare, edge.toString());
        // A ring of two surfaces (0.25 mm stripes of mask and print around a clean disc) has no
        // one reference colour.
        Result striped = BareSpotCheck.check(synth(g -> {
            g.setColor(FidScene.bgr(220, 200, 150));
            for (double x = -4; x < 4; x += 0.5) {
                g.fill(new Rectangle2D.Double(x, -4, 0.25, 8));
            }
            FidScene.disk(g, 0, 0, 2.4, FidScene.MASK);
        }), SX, SY, null);
        assertFalse(striped.bare, striped.toString());
        assertTrue(striped.reason.startsWith("the surface around the spot is not uniform"),
                striped.reason);
        // Grey frames read the same as their colour.
        BufferedImage colour = synth(g -> FidScene.disk(g, 0, 0, 0.8, FidScene.PAD));
        BufferedImage grey = new BufferedImage(640, 600, BufferedImage.TYPE_BYTE_GRAY);
        grey.getGraphics().drawImage(colour, 0, 0, null);
        Result gr = BareSpotCheck.check(grey, SX, SY, null);
        assertFalse(gr.bare);
        assertEquals(gr.refB, gr.refR, 0);
        assertEquals(0.16, gr.foreignShare, 0.01);
    }

    @Test
    public void refusals() {
        BufferedImage small = synth(g -> { }).getSubimage(0, 0, 500, 500);
        assertThrows(IllegalArgumentException.class,
                () -> BareSpotCheck.check(small, SX, SY, null), "the 3 mm ring needs 560 px");
        BufferedImage ok = synth(g -> { });
        assertThrows(IllegalArgumentException.class, () -> BareSpotCheck.check(ok, 0, SY, null));
        assertThrows(IllegalArgumentException.class, () -> BareSpotCheck.check(null, SX, SY, null));
        Settings bad = new Settings();
        bad.ringInnerMm = 0.5;
        assertThrows(IllegalArgumentException.class, () -> BareSpotCheck.check(ok, SX, SY, bad));
        assertThrows(IllegalArgumentException.class, () -> BareSpotCheck.check(ok, 4, 4, null),
                "4 mm per pixel is too coarse");
    }
}
