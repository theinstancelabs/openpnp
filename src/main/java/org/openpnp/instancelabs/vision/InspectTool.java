package org.openpnp.instancelabs.vision;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Offline bench tool: runs {@link DotInspector} on a before/after image pair and prints JSON.
 * Touches no camera or machine.
 *
 * <pre>
 * java -cp openpnp-gui.jar:lib/* org.openpnp.instancelabs.vision.InspectTool \
 *     before.png after.png MM_PER_PX_X [MM_PER_PX_Y] [options]
 * </pre>
 */
public final class InspectTool {
    static final String USAGE = String.join("\n",
            "usage: InspectTool BEFORE.png AFTER.png MM_PER_PX_X [MM_PER_PX_Y] [options]",
            "  --pad CX,CY,W,H[,ROT]   pad rectangle in mm from image centre (+y up), deg CCW;",
            "                          repeatable, reports coverage 0..1",
            "  --min-diameter MM       (default 0.15)",
            "  --max-diameter MM       (default 2.0)",
            "  --threshold GREY        diff threshold 0-255 (default 25)",
            "  --blur PX               blur kernel px (default 3)",
            "  --min-circularity C     (default 0.5)",
            "  --no-register           do not estimate/remove a global shift",
            "  --max-shift PX          largest shift applied (default 8)",
            "  --overlay OUT.png       write AFTER with dots (green), rejects (red), pads (blue)");

    private InspectTool() {}

    public static void main(String[] args) {
        int code = run(args, System.out, System.err);
        if (code != 0) {
            System.exit(code);
        }
    }

    /** Runs the tool; returns the process exit code (0 ok, 1 runtime error, 2 usage error). */
    static int run(String[] args, PrintStream out, PrintStream err) {
        List<String> positional = new ArrayList<>();
        List<double[]> pads = new ArrayList<>();
        DotInspectorSettings s = new DotInspectorSettings();
        String overlay = null;
        try {
            for (int i = 0; i < args.length; i++) {
                String a = args[i];
                switch (a) {
                    case "-h":
                    case "--help":
                        out.println(USAGE);
                        return 0;
                    case "--pad":
                        pads.add(parsePad(value(args, ++i, a)));
                        break;
                    case "--min-diameter":
                        s.minDiameterMm = Double.parseDouble(value(args, ++i, a));
                        break;
                    case "--max-diameter":
                        s.maxDiameterMm = Double.parseDouble(value(args, ++i, a));
                        break;
                    case "--threshold":
                        s.diffThreshold = Double.parseDouble(value(args, ++i, a));
                        break;
                    case "--blur":
                        s.blurPx = Double.parseDouble(value(args, ++i, a));
                        break;
                    case "--min-circularity":
                        s.minCircularity = Double.parseDouble(value(args, ++i, a));
                        break;
                    case "--no-register":
                        s.registerShift = false;
                        break;
                    case "--max-shift":
                        s.maxShiftPx = Double.parseDouble(value(args, ++i, a));
                        break;
                    case "--overlay":
                        overlay = value(args, ++i, a);
                        break;
                    default:
                        if (a.startsWith("--")) {
                            throw new IllegalArgumentException("unknown option " + a);
                        }
                        positional.add(a);
                }
            }
            if (positional.size() < 3 || positional.size() > 4) {
                throw new IllegalArgumentException("expected BEFORE AFTER MM_PER_PX_X [MM_PER_PX_Y]");
            }
        }
        catch (IllegalArgumentException e) {
            err.println("error: " + e.getMessage());
            err.println(USAGE);
            return 2;
        }

        try {
            BufferedImage before = read(positional.get(0));
            BufferedImage after = read(positional.get(1));
            double mmX = Double.parseDouble(positional.get(2));
            double mmY = positional.size() > 3 ? Double.parseDouble(positional.get(3)) : mmX;
            if (!(mmX > 0) || !(mmY > 0)) {
                throw new IllegalArgumentException("mm per pixel must be positive");
            }
            DotInspector inspector = new DotInspector(s);
            JsonObject root = new JsonObject();
            root.addProperty("before", positional.get(0));
            root.addProperty("after", positional.get(1));
            root.addProperty("width", before.getWidth());
            root.addProperty("height", before.getHeight());
            root.addProperty("mmPerPxX", mmX);
            root.addProperty("mmPerPxY", mmY);
            root.add("settings", new GsonBuilder().create().toJsonTree(s));

            List<Dot> accepted = new ArrayList<>();
            List<Dot> rejected = new ArrayList<>();
            JsonArray dots = new JsonArray();
            JsonArray rejects = new JsonArray();
            JsonArray padResults = new JsonArray();
            try (DotInspector.Analysis an = inspector.analyze(before, after)) {
                JsonObject reg = new JsonObject();
                reg.addProperty("shiftXPx", an.shiftXPx);
                reg.addProperty("shiftYPx", an.shiftYPx);
                reg.addProperty("eccCorrelation", an.eccCorrelation);
                reg.addProperty("applied", an.shiftApplied);
                root.add("registration", reg);
                JsonArray fit = new JsonArray();
                for (double[] p : an.brightnessFit) {
                    JsonObject f = new JsonObject();
                    f.addProperty("gain", p[0]);
                    f.addProperty("offset", p[1]);
                    f.addProperty("gradX", p[2]);
                    f.addProperty("gradY", p[3]);
                    fit.add(f);
                }
                root.add("brightnessFit", fit);
                for (DotInspector.Blob b : an.blobs) {
                    Dot d = an.toDot(b, mmX, mmY);
                    String reason = inspector.rejectReason(d);
                    JsonObject o = dotJson(d, b);
                    if (reason == null) {
                        accepted.add(d);
                        dots.add(o);
                    }
                    else {
                        o.addProperty("reason", reason);
                        rejected.add(d);
                        rejects.add(o);
                    }
                }
                for (double[] p : pads) {
                    JsonObject o = new JsonObject();
                    o.addProperty("cxMm", p[0]);
                    o.addProperty("cyMm", p[1]);
                    o.addProperty("wMm", p[2]);
                    o.addProperty("hMm", p[3]);
                    o.addProperty("rotDeg", p[4]);
                    try {
                        o.addProperty("coverage",
                                inspector.coverage(an, mmX, mmY, p[0], p[1], p[2], p[3], p[4]));
                    }
                    catch (IllegalArgumentException e) {
                        o.addProperty("error", e.getMessage());
                    }
                    padResults.add(o);
                }
            }
            root.addProperty("dotCount", accepted.size());
            root.add("dots", dots);
            root.add("rejected", rejects);
            root.add("pads", padResults);
            if (overlay != null) {
                writeOverlay(after, mmX, mmY, accepted, rejected, pads, new File(overlay));
                root.addProperty("overlay", overlay);
            }
            out.println(new GsonBuilder().setPrettyPrinting()
                    .serializeSpecialFloatingPointValues().create().toJson(root));
            return 0;
        }
        catch (Exception e) {
            err.println("error: " + e);
            return 1;
        }
    }

    private static JsonObject dotJson(Dot d, DotInspector.Blob b) {
        JsonObject o = new JsonObject();
        o.addProperty("xMm", d.xMm);
        o.addProperty("yMm", d.yMm);
        o.addProperty("diameterMm", d.diameterMm);
        o.addProperty("areaMm2", d.areaMm2);
        o.addProperty("circularity", d.circularity);
        o.addProperty("xPx", b.cxPx);
        o.addProperty("yPx", b.cyPx);
        o.addProperty("areaPx", b.areaPx);
        o.addProperty("contrast", b.contrast);
        o.addProperty("background", b.background);
        o.addProperty("level", b.level);
        return o;
    }

    private static String value(String[] args, int i, String option) {
        if (i >= args.length) {
            throw new IllegalArgumentException(option + " needs a value");
        }
        return args[i];
    }

    private static double[] parsePad(String v) {
        String[] f = v.split(",");
        if (f.length < 4 || f.length > 5) {
            throw new IllegalArgumentException("--pad expects CX,CY,W,H[,ROT]");
        }
        double[] p = new double[5];
        for (int i = 0; i < f.length; i++) {
            p[i] = Double.parseDouble(f[i].trim());
        }
        return p;
    }

    private static BufferedImage read(String path) throws Exception {
        BufferedImage img = ImageIO.read(new File(path));
        if (img == null) {
            throw new IllegalArgumentException("cannot read image " + path);
        }
        return img;
    }

    private static void writeOverlay(BufferedImage after, double mmX, double mmY, List<Dot> dots,
            List<Dot> rejected, List<double[]> pads, File file) throws Exception {
        int w = after.getWidth();
        int h = after.getHeight();
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.drawImage(after, 0, 0, null);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setStroke(new BasicStroke(1f));
            g.setColor(Color.BLUE);
            for (double[] p : pads) {
                double rad = Math.toRadians(p[4]);
                Path2D path = new Path2D.Double();
                double[][] c = {{-1, -1}, {1, -1}, {1, 1}, {-1, 1}};
                for (int i = 0; i < 4; i++) {
                    double u = c[i][0] * p[2] / 2;
                    double v = c[i][1] * p[3] / 2;
                    double x = p[0] + u * Math.cos(rad) - v * Math.sin(rad);
                    double y = p[1] + u * Math.sin(rad) + v * Math.cos(rad);
                    // +0.5: Java2D pixel centres are at half-integers.
                    double px = w / 2.0 + x / mmX + 0.5;
                    double py = h / 2.0 - y / mmY + 0.5;
                    if (i == 0) {
                        path.moveTo(px, py);
                    }
                    else {
                        path.lineTo(px, py);
                    }
                }
                path.closePath();
                g.draw(path);
            }
            drawDots(g, dots, Color.GREEN, w, h, mmX, mmY);
            drawDots(g, rejected, Color.RED, w, h, mmX, mmY);
        }
        finally {
            g.dispose();
        }
        ImageIO.write(img, "png", file);
    }

    private static void drawDots(Graphics2D g, List<Dot> dots, Color color, int w, int h,
            double mmX, double mmY) {
        g.setColor(color);
        for (Dot d : dots) {
            double px = w / 2.0 + d.xMm / mmX + 0.5;
            double py = h / 2.0 - d.yMm / mmY + 0.5;
            double rx = d.diameterMm / 2 / mmX;
            double ry = d.diameterMm / 2 / mmY;
            g.draw(new Ellipse2D.Double(px - rx, py - ry, 2 * rx, 2 * ry));
        }
    }
}
