package org.openpnp.instancelabs.sim;

import java.awt.geom.AffineTransform;
import java.awt.geom.NoninvertibleTransformException;
import java.awt.geom.Point2D;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.ConcurrentModificationException;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.openpnp.model.Abstract2DLocatable.Side;
import org.openpnp.model.Board;
import org.openpnp.model.Configuration;
import org.openpnp.model.Footprint;
import org.openpnp.model.Length;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.model.Package;
import org.openpnp.model.Placement;
import org.pmw.tinylog.Logger;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The simulated world of the SIM vacuum sensor and the SIM top camera: where the board truly sits,
 * the heights N1 touches, holes and paste. Read from {@code <configDir>/instancelabs/sim-scene.json},
 * which only the sim scripts write, into a config copy (src/test/sim/il_sim_scene.py). Missing keys
 * take the bench defaults below. Public fields, so tests can build and change a scene in code.
 *
 * <p>Frames: board mm (the board file's placements, origin at the board's bottom-left corner) and
 * machine mm (OpenPnP's frame, the X/Y the head is sent to). {@link #seat} maps the first to the
 * second.</p>
 */
public final class SimScene {
    /** A plane z = z0 + slopeX·u + slopeY·v, with (u, v) measured from the plane's reference point. */
    public static final class Plane {
        public double z0, slopeX, slopeY;

        public Plane(double z0, double slopeX, double slopeY) {
            this.z0 = z0;
            this.slopeX = slopeX;
            this.slopeY = slopeY;
        }

        public double at(double u, double v) {
            return z0 + slopeX * u + slopeY * v;
        }
    }

    /** A circle in board mm (a leaky hole or via). */
    public static final class Spot {
        public double bx, by, r;

        public Spot(double bx, double by, double r) {
            this.bx = bx;
            this.by = by;
            this.r = r;
        }
    }

    /** A fiducial in board mm: copper disk {@code d} inside a mask opening {@code mask}. */
    public static final class Fiducial {
        public String name;
        public double bx, by, d, mask;

        public Fiducial(String name, double bx, double by, double d, double mask) {
            this.name = name;
            this.bx = bx;
            this.by = by;
            this.d = d;
            this.mask = mask;
        }
    }

    /**
     * The N1 vacuum sensor (counts as the controller reports them; more negative = more vacuum). The
     * time constants are on the wall clock: a session with the simulator timings (vacuumSettleMs 300,
     * readDelayMs 0) needs fast ones (il_sim_scene.py FAST_VACUUM), as a real sensor would.
     */
    public static final class Vacuum {
        public double openAir = -1_550_000;
        /** Seal drop deciles (offline parse of runs/serial.log); each spot draws its own drop from them. */
        public double[] sealDrops = {257000, 271000, 290000, 303000, 316000, 329000, 350000, 370000, 404000};
        public double partialBandMm = 0.03;
        public double leakDrop = 15000;
        public double offValue = 0;
        public double tauS = 0.3, sealTauS = 0.03, releaseTauS = 0.4;
        public double noise = 1500;
        public long seed = 7;
        /** normal | missing | blocked | part */
        public String tip = "normal";
    }

    /** The simulated top camera's departures from its configuration. */
    public static final class Camera {
        /** True mm/px = configured × (1 + error), per axis. */
        public double[] uppError = {0, 0};
        /** Uniform grey-level noise ±noise. */
        public int noise = 4;
        public int blurPx = 1;
    }

    public static final String[] TIP_STATES = {"normal", "missing", "blocked", "part"};

    /** Matched against the end of each board path of the configuration. */
    public String boardFile;
    /**
     * Board width/height (mm); 0 when the scene does not say (then taken from the board, see
     * {@link #withBoardSize}). While unknown, nothing lies on the board: no seal, no mask.
     */
    public double boardWidthMm, boardHeightMm;
    /** The true board→machine transform; the board origin lands at its translation. */
    public AffineTransform seat;
    /** N1 contact Z on the board, in board mm from the board origin ({@link #boardContactZ}). */
    public Plane boardSurface = new Plane(5.43, -0.0006, 0.0009);
    /** From the scene, or null: then the renderer takes them from the board's Fiducial placements. */
    public List<Fiducial> fiducials;
    public Set<String> hideFiducials = new HashSet<>();
    /** Pad ids ("R12.1") or placement ids ("R12") drawn with paste. */
    public Set<String> dirtyPads = new HashSet<>();
    /** Paste dots of 1.0 mm, board mm. */
    public List<double[]> dirtySpots = new ArrayList<>();
    public List<Spot> leakySpots = new ArrayList<>();
    /** Blank rectangle, machine mm: x0, y0, x1, y1; null = no blank. */
    public double[] blankRect = {280, 225, 365, 250};
    /**
     * N1 contact Z on the blank, in machine mm from the rectangle's first corner (x0, y0)
     * ({@link #blankContactZ}): the bench defaults (BLANK_PROBES) are fitted there.
     */
    public Plane blankSurface = new Plane(5.62, -0.0012, 0.0);
    /** Paste dots of 1.0 mm, machine mm. */
    public List<double[]> blankDots = new ArrayList<>();
    /** Contact everywhere else: below every probe floor, so off the board there is no contact. */
    public double bedZ = 2.5;
    /** N1 tip = raw head X/Y + this offset (tools/probe.py). */
    public double n1OffsetX = -24.178, n1OffsetY = -63.91;
    public double tipRadiusMm = 0.35;
    public Vacuum vacuum = new Vacuum();
    public Camera camera = new Camera();

    public static SimScene load(File f) throws IOException {
        String text = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        JsonElement e;
        try {
            e = new JsonParser().parse(text);
        }
        catch (RuntimeException ex) {
            throw new IOException("sim scene " + f + " is not valid JSON: " + ex.getMessage(), ex);
        }
        if (!e.isJsonObject()) {
            throw new IOException("sim scene " + f + " is not a JSON object");
        }
        try {
            return of(e.getAsJsonObject());
        }
        catch (RuntimeException ex) {
            throw new IOException("sim scene " + f + ": " + ex.getMessage(), ex);
        }
    }

    /** Parses the scene JSON (format of il_sim_scene.py). Throws IllegalArgumentException on a bad field. */
    public static SimScene of(JsonObject o) {
        int version = o.has("version") ? o.get("version").getAsInt() : 1;
        if (version != 1) {
            throw new IllegalArgumentException("sim scene version " + version + " is not supported (1)");
        }
        SimScene s = new SimScene();
        JsonObject b = obj(o, "board");
        if (b == null) {
            throw new IllegalArgumentException("sim scene has no board");
        }
        s.boardFile = b.has("file") ? b.get("file").getAsString() : null;
        double[] size = numbers(b, "size", 2);
        if (size != null) {
            s.boardWidthMm = size[0];
            s.boardHeightMm = size[1];
        }
        JsonObject seat = obj(b, "seat");
        if (seat == null) {
            throw new IllegalArgumentException("sim scene board has no seat");
        }
        s.seat = seatOf(seat);
        JsonObject surface = obj(b, "surface");
        if (surface != null) {
            s.boardSurface = planeOf(surface);
        }
        if (b.has("fiducials")) {
            s.fiducials = new ArrayList<>();
            for (JsonElement fe : b.getAsJsonArray("fiducials")) {
                JsonObject f = fe.getAsJsonObject();
                s.fiducials.add(new Fiducial(f.get("name").getAsString(), f.get("bx").getAsDouble(),
                        f.get("by").getAsDouble(), num(f, "d", 1.0), num(f, "mask", 3.0)));
            }
        }
        s.hideFiducials = strings(b, "hideFiducials");
        s.dirtyPads = strings(b, "dirtyPads");
        s.dirtySpots = points(b, "dirtySpots", "bx", "by");
        if (b.has("leakySpots")) {
            for (JsonElement le : b.getAsJsonArray("leakySpots")) {
                JsonObject l = le.getAsJsonObject();
                s.leakySpots.add(new Spot(l.get("bx").getAsDouble(), l.get("by").getAsDouble(), num(l, "r", 0.6)));
            }
        }
        if (o.has("blank") && o.get("blank").isJsonNull()) {
            s.blankRect = null;
        }
        JsonObject blank = obj(o, "blank");
        if (blank != null) {
            double[] rect = numbers(blank, "rect", 4);
            if (rect != null) {
                s.blankRect = new double[] {Math.min(rect[0], rect[2]), Math.min(rect[1], rect[3]),
                        Math.max(rect[0], rect[2]), Math.max(rect[1], rect[3])};
            }
            JsonObject bs = obj(blank, "surface");
            if (bs != null) {
                s.blankSurface = planeOf(bs);
            }
            s.blankDots = points(blank, "dots", "x", "y");
        }
        s.bedZ = num(o, "bedZ", s.bedZ);
        double[] n1 = numbers(o, "n1OffsetMm", 2);
        if (n1 != null) {
            s.n1OffsetX = n1[0];
            s.n1OffsetY = n1[1];
        }
        s.tipRadiusMm = num(o, "tipRadiusMm", s.tipRadiusMm);
        JsonObject v = obj(o, "vacuum");
        if (v != null) {
            Vacuum d = s.vacuum;
            d.openAir = num(v, "openAir", d.openAir);
            if (v.has("sealDrops")) {
                JsonArray a = v.getAsJsonArray("sealDrops");
                if (a.size() == 0) {
                    throw new IllegalArgumentException("vacuum.sealDrops is empty");
                }
                d.sealDrops = new double[a.size()];
                for (int i = 0; i < a.size(); i++) {
                    d.sealDrops[i] = a.get(i).getAsDouble();
                }
            }
            d.partialBandMm = num(v, "partialBandMm", d.partialBandMm);
            d.leakDrop = num(v, "leakDrop", d.leakDrop);
            d.offValue = num(v, "offValue", d.offValue);
            d.tauS = num(v, "tauS", d.tauS);
            d.sealTauS = num(v, "sealTauS", d.sealTauS);
            d.releaseTauS = num(v, "releaseTauS", d.releaseTauS);
            d.noise = num(v, "noise", d.noise);
            d.seed = v.has("seed") ? v.get("seed").getAsLong() : d.seed;
            d.tip = v.has("tip") ? v.get("tip").getAsString() : d.tip;
        }
        JsonObject c = obj(o, "camera");
        if (c != null) {
            double[] err = numbers(c, "uppError", 2);
            if (err != null) {
                s.camera.uppError = err;
            }
            s.camera.noise = (int) num(c, "noise", s.camera.noise);
            s.camera.blurPx = (int) num(c, "blurPx", s.camera.blurPx);
        }
        s.validate();
        return s;
    }

    /** Throws IllegalArgumentException for values the models cannot use. */
    public void validate() {
        if (seat == null || Math.abs(seat.getDeterminant()) < 1e-6) {
            throw new IllegalArgumentException("board.seat is missing or not invertible");
        }
        if (!Arrays.asList(TIP_STATES).contains(vacuum.tip)) {
            throw new IllegalArgumentException("vacuum.tip '" + vacuum.tip + "' is not one of "
                    + String.join(", ", TIP_STATES));
        }
        if (vacuum.tauS < 0 || vacuum.sealTauS < 0 || vacuum.releaseTauS < 0 || vacuum.noise < 0
                || vacuum.partialBandMm < 0 || tipRadiusMm < 0 || camera.noise < 0 || camera.blurPx < 0) {
            throw new IllegalArgumentException("negative time constant, noise, band, tip radius or blur");
        }
    }

    /**
     * {@code {"affine": [[a, b, tx], [c, d, ty]]}}, or {@code {x, y, rotationDeg, scaleX, scaleY, shearDeg}}
     * = T(x, y)·R(rotation)·Shear·Scale (the decomposition of Utils2D.affineInfo; shear is an x-shear).
     */
    static AffineTransform seatOf(JsonObject seat) {
        if (seat.has("affine")) {
            JsonArray rows = seat.getAsJsonArray("affine");
            if (rows.size() != 2) {
                throw new IllegalArgumentException("seat.affine must have 2 rows");
            }
            double[] r0 = row(rows.get(0).getAsJsonArray());
            double[] r1 = row(rows.get(1).getAsJsonArray());
            return new AffineTransform(r0[0], r1[0], r0[1], r1[1], r0[2], r1[2]);
        }
        return seat(num(seat, "x", 0), num(seat, "y", 0), num(seat, "rotationDeg", 0), num(seat, "scaleX", 1),
                num(seat, "scaleY", 1), num(seat, "shearDeg", 0));
    }

    /** T(x, y)·R(rotationDeg)·Shear(shearDeg)·Scale(scaleX, scaleY), as il_sim_scene.py seat(). */
    public static AffineTransform seat(double x, double y, double rotationDeg, double scaleX, double scaleY,
            double shearDeg) {
        AffineTransform t = AffineTransform.getTranslateInstance(x, y);
        t.rotate(Math.toRadians(rotationDeg));
        t.shear(Math.tan(Math.toRadians(shearDeg)), 0);
        t.scale(scaleX, scaleY);
        return t;
    }

    /** Board mm → machine mm through the true seat. */
    public Point2D.Double toMachine(double bx, double by) {
        Point2D.Double p = new Point2D.Double();
        seat.transform(new Point2D.Double(bx, by), p);
        return p;
    }

    /** Machine mm → board mm through the inverse of the true seat. */
    public Point2D.Double toBoard(double x, double y) {
        Point2D.Double p = new Point2D.Double();
        try {
            seat.inverseTransform(new Point2D.Double(x, y), p);
        }
        catch (NoninvertibleTransformException e) {
            throw new IllegalStateException("board.seat is not invertible", e);
        }
        return p;
    }

    /** True if the board point lies on the board (its outline is the rectangle 0..width × 0..height). */
    public boolean onBoard(double bx, double by) {
        return bx >= 0 && by >= 0 && bx <= boardWidthMm && by <= boardHeightMm;
    }

    public boolean onBlank(double x, double y) {
        return blankRect != null && x >= blankRect[0] && y >= blankRect[1] && x <= blankRect[2]
                && y <= blankRect[3];
    }

    /** N1 contact Z at a board point. */
    public double boardContactZ(double bx, double by) {
        return boardSurface.at(bx, by);
    }

    /** N1 contact Z at a machine point of the blank. */
    public double blankContactZ(double x, double y) {
        return blankSurface.at(x - blankRect[0], y - blankRect[1]);
    }

    /** What lies under a machine point: the board (on top), the blank, or the bed. */
    public enum Region {
        BOARD, BLANK, BED
    }

    public Region regionAt(double x, double y) {
        Point2D.Double b = toBoard(x, y);
        if (onBoard(b.x, b.y)) {
            return Region.BOARD;
        }
        return onBlank(x, y) ? Region.BLANK : Region.BED;
    }

    /** N1 contact Z at a machine point, on the given region. */
    public double contactZ(Region region, double x, double y) {
        switch (region) {
            case BOARD:
                Point2D.Double b = toBoard(x, y);
                return boardContactZ(b.x, b.y);
            case BLANK:
                return blankContactZ(x, y);
            default:
                return bedZ;
        }
    }

    /** The configuration's board whose file path ends with {@link #boardFile}, or null. */
    public Board findBoard(Configuration c) {
        if (c == null || boardFile == null || boardFile.isEmpty()) {
            return null;
        }
        String want = boardFile.replace('\\', '/');
        List<Board> boards;
        try {
            boards = c.getBoards();
        }
        catch (ConcurrentModificationException e) {
            return null; // a board is being loaded right now: the next call sees it
        }
        for (Board b : boards) {
            File f = b.getFile();
            if (f == null) {
                continue;
            }
            String path = f.getPath().replace('\\', '/');
            if (path.equals(want) || path.endsWith("/" + want)) {
                return b;
            }
        }
        return null;
    }

    /** True once the board outline is known (the scene's board.size, or filled from the board). */
    public boolean hasBoardSize() {
        return boardWidthMm > 0 && boardHeightMm > 0;
    }

    /** Fills a missing board size from the board's dimensions (the board file's {@code <dimensions>}). */
    public SimScene withBoardSize(Board board) {
        if (!hasBoardSize() && board != null) {
            Location d = board.getDimensions().convertToUnits(LengthUnit.Millimeters);
            boardWidthMm = d.getX();
            boardHeightMm = d.getY();
        }
        return this;
    }

    /** The scene's fiducials, or (when it has none) the board's. Hidden ones are left out. */
    public List<Fiducial> visibleFiducials(Board board) {
        List<Fiducial> all = fiducials != null ? fiducials : fiducialsOf(board);
        List<Fiducial> out = new ArrayList<>();
        for (Fiducial f : all) {
            if (!hideFiducials.contains(f.name)) {
                out.add(f);
            }
        }
        return out;
    }

    private static final Pattern FID_DIAMETER = Pattern.compile("(?i)fiducial[_-]?(\\d+(?:\\.\\d+)?)mm");
    private static final Pattern FID_MASK = Pattern.compile("(?i)mask(\\d+(?:\\.\\d+)?)mm");

    /**
     * The board's enabled top-side {@code Fiducial} placements: the diameter from the first round
     * footprint pad, else parsed from the package id ({@code Fiducial_1mm_Mask3mm}), else 1.0 mm; the
     * mask opening parsed, else 3.0 mm. il_sim_scene.py applies the same rule.
     */
    public static List<Fiducial> fiducialsOf(Board board) {
        List<Fiducial> out = new ArrayList<>();
        if (board == null) {
            return out;
        }
        for (Placement p : board.getPlacements()) {
            if (p.getType() != Placement.Type.Fiducial || p.getSide() != Side.Top || !p.isEnabled()) {
                continue;
            }
            Location l = p.getLocation().convertToUnits(LengthUnit.Millimeters);
            Package pkg = p.getPart() == null ? null : p.getPart().getPackage();
            String id = pkg != null ? pkg.getId() : (p.getPart() != null ? p.getPart().getId() : "");
            Double d = null;
            Footprint fp = pkg == null ? null : pkg.getFootprint();
            if (fp != null && fp.getPads() != null) {
                double f = new Length(1, fp.getUnits()).convertToUnits(LengthUnit.Millimeters).getValue();
                for (Footprint.Pad pad : fp.getPads()) {
                    if (pad.getRoundness() >= 100 && Math.abs(pad.getWidth() - pad.getHeight()) < 1e-9
                            && pad.getWidth() > 0) {
                        d = pad.getWidth() * f;
                        break;
                    }
                }
            }
            if (d == null) {
                d = parse(FID_DIAMETER, id, 1.0);
            }
            out.add(new Fiducial(p.getId(), l.getX(), l.getY(), d, parse(FID_MASK, id, 3.0)));
        }
        return out;
    }

    private static double parse(Pattern p, String s, double dflt) {
        Matcher m = p.matcher(s == null ? "" : s);
        return m.find() ? Double.parseDouble(m.group(1)) : dflt;
    }

    // --- JSON helpers -----------------------------------------------------------------------------

    private static JsonObject obj(JsonObject o, String name) {
        JsonElement e = o.get(name);
        return e == null || !e.isJsonObject() ? null : e.getAsJsonObject();
    }

    private static double num(JsonObject o, String name, double dflt) {
        JsonElement e = o.get(name);
        if (e == null || e.isJsonNull()) {
            return dflt;
        }
        double v = e.getAsDouble();
        if (!Double.isFinite(v)) {
            throw new IllegalArgumentException(name + " is not a finite number");
        }
        return v;
    }

    private static double[] numbers(JsonObject o, String name, int n) {
        JsonElement e = o.get(name);
        if (e == null || e.isJsonNull()) {
            return null;
        }
        double[] r = row(e.getAsJsonArray());
        if (r.length != n) {
            throw new IllegalArgumentException(name + " must have " + n + " numbers");
        }
        return r;
    }

    private static double[] row(JsonArray a) {
        double[] r = new double[a.size()];
        for (int i = 0; i < r.length; i++) {
            r[i] = a.get(i).getAsDouble();
            if (!Double.isFinite(r[i])) {
                throw new IllegalArgumentException("not a finite number: " + a);
            }
        }
        return r;
    }

    private static Plane planeOf(JsonObject o) {
        return new Plane(num(o, "z0", 0), num(o, "slopeX", 0), num(o, "slopeY", 0));
    }

    private static Set<String> strings(JsonObject o, String name) {
        Set<String> out = new HashSet<>();
        if (o.has(name)) {
            for (JsonElement e : o.getAsJsonArray(name)) {
                out.add(e.getAsString());
            }
        }
        return out;
    }

    /** [{x, y}] or [[x, y]] → list of {x, y}. */
    private static List<double[]> points(JsonObject o, String name, String kx, String ky) {
        List<double[]> out = new ArrayList<>();
        if (!o.has(name)) {
            return out;
        }
        for (JsonElement e : o.getAsJsonArray(name)) {
            if (e.isJsonArray()) {
                double[] r = row(e.getAsJsonArray());
                if (r.length < 2) {
                    throw new IllegalArgumentException(name + ": a point needs x and y");
                }
                out.add(new double[] {r[0], r[1]});
            }
            else {
                JsonObject p = e.getAsJsonObject();
                out.add(new double[] {p.get(kx).getAsDouble(), p.get(ky).getAsDouble()});
            }
        }
        return out;
    }

    // --- the scene file of a running session ------------------------------------------------------

    /**
     * The scene file, read again when it changes (file key, mtime or size; checked at most every
     * 250 ms), so a test can move the board in a running session. A missing file gives null (the
     * simulator is off); a file that does not parse keeps the last good scene.
     */
    public static final class Source implements Supplier<SimScene> {
        static final long CHECK_MS = 250;
        private final File file;
        private final LongSupplier clockMs;
        private final Function<SimScene, Board> boardOf;
        private long checkedAtMs;
        private boolean checked;
        private List<Object> stamp = Collections.emptyList();
        private SimScene scene;

        /**
         * @param boardOf the scene's board, for a size the scene does not give (may return null)
         */
        public Source(File file, LongSupplier clockMs, Function<SimScene, Board> boardOf) {
            this.file = file;
            this.clockMs = clockMs;
            this.boardOf = boardOf;
        }

        /** For the running configuration: its scene file, the wall clock, its boards. */
        public static Source forConfiguration(Configuration c) {
            return new Source(SimGuard.sceneFile(c), System::currentTimeMillis,
                    (sc) -> sc.findBoard(Configuration.get()));
        }

        public File getFile() {
            return file;
        }

        @Override
        public synchronized SimScene get() {
            long now = clockMs.getAsLong();
            if (checked && now - checkedAtMs < CHECK_MS) {
                return scene;
            }
            checked = true;
            checkedAtMs = now;
            List<Object> st = stampOf(file);
            if (st == null) {
                scene = null;
                stamp = Collections.emptyList();
                return null;
            }
            if (scene != null && st.equals(stamp)) {
                if (!scene.hasBoardSize()) {
                    scene.withBoardSize(boardOf.apply(scene)); // boards load on demand, maybe after the first read
                }
                return scene;
            }
            try {
                SimScene s = load(file);
                scene = s.withBoardSize(boardOf.apply(s));
                stamp = st;
            }
            catch (Exception e) {
                Logger.warn("sim scene {} not loaded, keeping the previous one: {}", file, e.getMessage());
            }
            return scene;
        }

        private static List<Object> stampOf(File f) {
            try {
                BasicFileAttributes a = Files.readAttributes(f.toPath(), BasicFileAttributes.class);
                List<Object> st = new ArrayList<>();
                st.add(Objects.toString(a.fileKey()));
                st.add(a.lastModifiedTime().toString());
                st.add(a.size());
                return st;
            }
            catch (IOException e) {
                return null;
            }
        }
    }
}
