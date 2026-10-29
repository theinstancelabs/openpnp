package org.openpnp.instancelabs.dispense;

import java.awt.geom.AffineTransform;
import java.awt.geom.NoninvertibleTransformException;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.BooleanSupplier;

import org.openpnp.model.BoardLocation;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;

/**
 * One fiducial registration of a board seat (Register board): the board -> machine affine fitted to
 * the fiducials the top camera found (bench register.py: an exact 3-point affine, because this
 * machine's distortion has scales 1.0013 / 0.9943 and a small shear that a rigid fit would leave as
 * ~0.2 mm at the board edge). Kept by board key, latest only ({@link DispenseConfig#boardRegistrations}),
 * so a re-check of the same seat keeps its identity. Gson-friendly public fields.
 */
public class BoardRegistration {
    /** Where the board was planned to sit, without this registration. */
    public static class Nominal {
        public double x, y, z, rotation;
        public String side;
        /**
         * Job boards: OpenPnP's own placements transform (status + its 6 matrix values to 6 decimals),
         * so a later OpenPnP fiducial check of the live job board is noticed. Null for a board file.
         */
        public String openPnpTransform;

        /** The nominal seat of a board location; {@code jobBoard} records OpenPnP's transform too. */
        public static Nominal of(BoardLocation bl, boolean jobBoard) {
            Nominal n = new Nominal();
            Location g = bl.getGlobalLocation().convertToUnits(LengthUnit.Millimeters);
            n.x = g.getX();
            n.y = g.getY();
            n.z = g.getZ();
            n.rotation = g.getRotation();
            n.side = String.valueOf(bl.getGlobalSide());
            if (jobBoard) {
                StringBuilder sb = new StringBuilder(String.valueOf(bl.getPlacementsTransformStatus())).append(';');
                AffineTransform t;
                try {
                    t = bl.getLocalToGlobalTransform();
                }
                catch (RuntimeException e) {
                    t = null;
                }
                if (t != null) {
                    double[] m = new double[6];
                    t.getMatrix(m);
                    for (double v : m) {
                        sb.append(String.format(Locale.ROOT, "%.6f,", v));
                    }
                }
                n.openPnpTransform = sb.toString();
            }
            return n;
        }
    }

    /** One fiducial as found for this registration (board mm, machine mm). */
    public static class Fid {
        public String name;
        public double bx, by;
        /** Found position (machine mm). */
        public double x, y;
        public double expectedX, expectedY;
        public int iterations, frames;
        public double offsetPx, diameterMm, score;
    }

    public String id;
    /** "file:<absolute .board.xml path>" or "job:<boardLocationId>". */
    public String boardKey;
    public String boardName;
    public Nominal nominal;
    /** Board mm -> machine mm, in {@link AffineTransform#getMatrix} order m00 m10 m01 m11 m02 m12. */
    public double[] affine;
    /** affine-3 | affine-lsq-N | similarity-2 */
    public String method;
    public double rotationDeg, scaleX, scaleY, shearDeg, maxResidualMm;
    public List<Fid> fiducials = new ArrayList<>();
    /** When the fiducials were measured. */
    public long atMs;
    /** The last "same seat" check that kept this record (not part of the board registration digest). */
    public Long lastCheckedAtMs;
    public Double lastCheckMovedMm;
    /** Made on OpenPnP's simulator: refused on a machine that is not. */
    public boolean simulated;
    public String runId;

    /** Board key of a board file. */
    public static String fileKey(File boardFile) {
        return "file:" + boardFile.getAbsolutePath();
    }

    /** Board key of a job board. */
    public static String jobKey(String boardLocationId) {
        return "job:" + boardLocationId;
    }

    /** The board -> machine transform. */
    public AffineTransform transform() {
        return new AffineTransform(affine);
    }

    /** Sets {@link #affine} from a transform. */
    public void setTransform(AffineTransform t) {
        affine = new double[6];
        t.getMatrix(affine);
    }

    /** Six finite values with a non-zero determinant. */
    public boolean isAffineValid() {
        if (affine == null || affine.length != 6) {
            return false;
        }
        for (double v : affine) {
            if (!Double.isFinite(v)) {
                return false;
            }
        }
        return Math.abs(affine[0] * affine[3] - affine[1] * affine[2]) > 1e-9;
    }

    /**
     * A copy of the nominal board location carrying this transform (the original is never changed).
     * The copy constructor is deep; the transform is set last, as setLocation would clear it.
     */
    public BoardLocation applyTo(BoardLocation nominal) throws NoninvertibleTransformException {
        BoardLocation c = new BoardLocation(nominal);
        c.setLocalToGlobalTransform(transform());
        return c;
    }

    /** Same board key, x/y/z/rotation within 1e-4, same side and the same OpenPnP transform. */
    public boolean matches(String boardKey, Nominal n) {
        if (n == null || nominal == null || !Objects.equals(this.boardKey, boardKey)) {
            return false;
        }
        return Math.abs(nominal.x - n.x) <= 1e-4 && Math.abs(nominal.y - n.y) <= 1e-4
                && Math.abs(nominal.z - n.z) <= 1e-4 && Math.abs(nominal.rotation - n.rotation) <= 1e-4
                && Objects.equals(nominal.side, n.side) && Objects.equals(nominal.openPnpTransform, n.openPnpTransform);
    }

    /** Largest distance between the fiducials found now and here, over common names; NaN if none. */
    public double maxMovedMm(List<Fid> now) {
        double worst = Double.NaN;
        if (now == null || fiducials == null) {
            return worst;
        }
        for (Fid a : now) {
            for (Fid b : fiducials) {
                if (a != null && b != null && Objects.equals(a.name, b.name)) {
                    double d = Math.hypot(a.x - b.x, a.y - b.y);
                    worst = Double.isNaN(worst) ? d : Math.max(worst, d);
                }
            }
        }
        return worst;
    }

    /** "3f2a…": the first 4 characters of the id, as the texts show it. */
    public String shortId() {
        return id == null ? "?" : (id.length() > 4 ? id.substring(0, 4) : id) + "…";
    }

    /**
     * Why this registration may not be applied to the board with this key and nominal seat, or null
     * if it may: made on the simulator while the machine is not one, another board or position, or a
     * job board re-checked by OpenPnP since.
     */
    public String refusal(String boardKey, Nominal n, BooleanSupplier simulatorConfirmed) {
        if (simulated && (simulatorConfirmed == null || !simulatorConfirmed.getAsBoolean())) {
            return "REG: registration " + shortId() + " was made on the simulator: register again";
        }
        if (!matches(boardKey, n)) {
            boolean onlyOpenPnp = n != null && nominal != null && Objects.equals(this.boardKey, boardKey)
                    && nominal.openPnpTransform != null && !Objects.equals(nominal.openPnpTransform, n.openPnpTransform)
                    && sameSeat(n);
            if (onlyOpenPnp) {
                return "REG: OpenPnP's fiducial check of this job board changed since registration " + shortId() + " ("
                        + ZCheck.clock(atMs) + "): register again";
            }
            return "REG: registration " + shortId() + " belongs to another board or position";
        }
        return null;
    }

    private boolean sameSeat(Nominal n) {
        return Math.abs(nominal.x - n.x) <= 1e-4 && Math.abs(nominal.y - n.y) <= 1e-4
                && Math.abs(nominal.z - n.z) <= 1e-4 && Math.abs(nominal.rotation - n.rotation) <= 1e-4
                && Objects.equals(nominal.side, n.side);
    }
}
