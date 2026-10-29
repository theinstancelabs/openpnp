package org.openpnp.instancelabs.sim;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.ConvolveOp;
import java.awt.image.DataBufferByte;
import java.awt.image.Kernel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.openpnp.instancelabs.dispense.BoardPadExtractor;
import org.openpnp.instancelabs.dispense.DispenseService;
import org.openpnp.instancelabs.dispense.PadGeometry;
import org.openpnp.instancelabs.dispense.PadTypeResolver;
import org.openpnp.model.Board;
import org.openpnp.model.BoardLocation;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.pmw.tinylog.Logger;

/**
 * Draws what the top camera sees of a {@link SimScene}: the board at its true seat (mask, pads,
 * fiducials, paste), the blank and the bed, in OpenPnP's image orientation (+x right, +y up).
 * Pixel (u, v), counted from pixel centres as the vision code does, shows machine
 * X = camX + (u − w/2)·uppX·(1 + uppError[0]), Y = camY − (v − h/2)·uppY·(1 + uppError[1]).
 *
 * <p>Fiducials come from the scene (il_sim_scene.py writes them from the board file's placements) or,
 * when the scene has none, from the board itself; pads from the board's placements and footprints /
 * pad types (BoardPadExtractor on the board placed at the origin). Nothing is specific to one board.</p>
 */
public final class SimBoardRenderer {
    /** Colours, BGR measured on runs/board3_FID1_2.jpg (PadCleanCheck), as java.awt.Color(r, g, b). */
    static final Color MASK = bgr(142, 59, 0);
    static final Color FIDUCIAL_OPENING = bgr(128, 47, 0);
    static final Color FIDUCIAL_COPPER = bgr(241, 240, 199);
    static final Color PAD_COPPER = bgr(246, 244, 222);
    static final Color PASTE = bgr(160, 63, 13);
    static final Color BLANK = bgr(142, 59, 0);
    static final Color OUTSIDE = bgr(40, 40, 40);
    static final Color UNCONFIRMED = bgr(128, 128, 128);
    static final double PASTE_DOT_MM = 1.0;
    static final String STAMP = "SIM";

    private SimBoardRenderer() {
    }

    static Color bgr(int b, int g, int r) {
        return new Color(r, g, b);
    }

    /** Pads from the dispense service's pad types when it runs, otherwise footprint pads only. */
    public static BufferedImage render(SimScene sc, Board board, double camX, double camY, int w, int h,
            double uppX, double uppY) {
        return render(sc, board, board == null ? Collections.emptyList() : boardPads(board, resolver()), camX,
                camY, w, h, uppX, uppY);
    }

    /**
     * A board size the scene does not give is filled from the board ({@link SimScene#withBoardSize}).
     *
     * @param boardPads pads in board mm ({@link #boardPads})
     * @return a TYPE_3BYTE_BGR frame
     */
    public static BufferedImage render(SimScene sc, Board board, List<PadGeometry> boardPads, double camX,
            double camY, int w, int h, double uppX, double uppY) {
        sc.withBoardSize(board);
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g.setColor(OUTSIDE);
            g.fillRect(0, 0, w, h);
            AffineTransform machine = machineToPixel(sc, camX, camY, w, h, uppX, uppY);
            AffineTransform boardTx = new AffineTransform(machine);
            boardTx.concatenate(sc.seat);
            if (sc.blankRect != null) {
                double[] r = sc.blankRect;
                fill(g, machine, new Rectangle2D.Double(r[0], r[1], r[2] - r[0], r[3] - r[1]), BLANK);
                for (double[] d : sc.blankDots) {
                    fill(g, machine, disk(d[0], d[1], PASTE_DOT_MM), PASTE);
                }
            }
            if (sc.hasBoardSize()) {
                fill(g, boardTx, new Rectangle2D.Double(0, 0, sc.boardWidthMm, sc.boardHeightMm), MASK);
            }
            for (PadGeometry p : boardPads) {
                if (!(p.wMm > 0 && p.hMm > 0)) {
                    continue; // no geometry known: nothing to draw
                }
                AffineTransform t = new AffineTransform(boardTx);
                t.translate(p.xMm, p.yMm);
                t.rotate(Math.toRadians(p.rotationDeg));
                double arc = Math.max(0, Math.min(100, p.roundness)) / 100 * Math.min(p.wMm, p.hMm);
                fill(g, t, new RoundRectangle2D.Double(-p.wMm / 2, -p.hMm / 2, p.wMm, p.hMm, arc, arc), PAD_COPPER);
                if (sc.dirtyPads.contains(p.padId) || sc.dirtyPads.contains(p.placementId)) {
                    fill(g, t, disk(0, 0, Math.min(p.wMm, p.hMm)), PASTE);
                }
            }
            for (SimScene.Fiducial f : sc.visibleFiducials(board)) {
                fill(g, boardTx, disk(f.bx, f.by, f.mask), FIDUCIAL_OPENING);
                fill(g, boardTx, disk(f.bx, f.by, f.d), FIDUCIAL_COPPER);
            }
            for (double[] d : sc.dirtySpots) {
                fill(g, boardTx, disk(d[0], d[1], PASTE_DOT_MM), PASTE);
            }
        }
        finally {
            g.dispose();
        }
        if (sc.camera.blurPx > 0) {
            img = blur(img, sc.camera.blurPx);
        }
        addNoise(img, sc.camera.noise, sc.vacuum.seed);
        stamp(img, STAMP, new Color(200, 200, 200));
        return img;
    }

    /** A uniform grey frame stamped {@code text}: no marks the vision code could take for anything. */
    public static BufferedImage blank(int w, int h, String text) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D g = img.createGraphics();
        try {
            g.setColor(UNCONFIRMED);
            g.fillRect(0, 0, w, h);
        }
        finally {
            g.dispose();
        }
        stamp(img, text, new Color(180, 180, 180));
        return img;
    }

    /** The board's pads in board mm: BoardPadExtractor on the board placed at the origin. */
    public static List<PadGeometry> boardPads(Board board, PadTypeResolver resolver) {
        try {
            BoardLocation bl = new BoardLocation(board);
            bl.setLocation(new Location(LengthUnit.Millimeters, 0, 0, 0, 0));
            return new BoardPadExtractor(resolver, true).extract(bl).pads;
        }
        catch (Exception e) {
            Logger.warn("sim camera: no pads for board {}: {}", board.getName(), e.getMessage());
            return new ArrayList<>();
        }
    }

    static PadTypeResolver resolver() {
        DispenseService svc = DispenseService.get();
        if (svc != null && svc.getDb() != null) {
            try {
                return new PadTypeResolver(svc.getDb().currentPadTypes(), svc.getDb().packageMappings());
            }
            catch (Exception e) {
                Logger.warn("sim camera: pad types not read, footprint pads only: {}", e.getMessage());
            }
        }
        return new PadTypeResolver(Collections.emptyList(), null);
    }

    /** Machine mm → Java2D pixel coordinates (pixel centres at +0.5). */
    static AffineTransform machineToPixel(SimScene sc, double camX, double camY, int w, int h, double uppX,
            double uppY) {
        double ux = uppX * (1 + sc.camera.uppError[0]);
        double uy = uppY * (1 + sc.camera.uppError[1]);
        AffineTransform t = AffineTransform.getTranslateInstance(w / 2.0 + 0.5, h / 2.0 + 0.5);
        t.scale(1 / ux, -1 / uy);
        t.translate(-camX, -camY);
        return t;
    }

    private static Shape disk(double x, double y, double d) {
        return new Ellipse2D.Double(x - d / 2, y - d / 2, d, d);
    }

    private static void fill(Graphics2D g, AffineTransform t, Shape s, Color c) {
        g.setColor(c);
        g.fill(t.createTransformedShape(s));
    }

    private static BufferedImage blur(BufferedImage img, int r) {
        int n = 2 * r + 1;
        float[] k = new float[n * n];
        Arrays.fill(k, 1f / (n * n));
        BufferedImage out = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_3BYTE_BGR);
        return new ConvolveOp(new Kernel(n, n, k), ConvolveOp.EDGE_NO_OP, null).filter(img, out);
    }

    /** ±noise grey levels per pixel, the same on the three channels; seeded (xorshift), so a frame repeats. */
    private static void addNoise(BufferedImage img, int noise, long seed) {
        if (noise <= 0) {
            return;
        }
        byte[] px = ((DataBufferByte) img.getRaster().getDataBuffer()).getData();
        long s = seed * 0x9E3779B97F4A7C15L | 1;
        int span = 2 * noise + 1;
        for (int i = 0; i + 2 < px.length; i += 3) {
            s ^= s << 13;
            s ^= s >>> 7;
            s ^= s << 17;
            int d = (int) Long.remainderUnsigned(s, span) - noise;
            for (int c = 0; c < 3; c++) {
                px[i + c] = (byte) Math.max(0, Math.min(255, (px[i + c] & 0xFF) + d));
            }
        }
    }

    /** Small text in the top-left corner (8 px glyphs), far from the frame centre and every probe look ring. */
    private static void stamp(BufferedImage img, String text, Color c) {
        Graphics2D g = img.createGraphics();
        try {
            g.setColor(c);
            g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 10));
            g.drawString(text, 4, 12);
        }
        finally {
            g.dispose();
        }
    }
}
