package org.openpnp.instancelabs.vision;

import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.util.Arrays;
import java.util.Locale;

/**
 * Look before touching: is the spot under the camera bare surface, or does it carry paste, a part
 * or print? N1 must not touch wet paste or a loose part (paste in the tip fouls N1).
 *
 * In a frame centred on the spot:
 * <ul>
 * <li>reference colour = the per-channel median BGR in the ring 1.5 .. 3.0 mm from the centre
 * (the local surface);</li>
 * <li>foreign pixels = pixels in the disc of radius 1.0 mm (tip radius 0.35 + N1 offset error 0.4
 * + 0.25 margin) whose BGR distance from the reference exceeds colourDelta;</li>
 * <li>ring spread = the median BGR distance of the ring pixels from the reference (a MAD);</li>
 * <li>not bare if the foreign share is above maxForeignShare, or the ring spread is above
 * maxRingSpread (an edge, the blank's border, tape: the reference is not one surface).</li>
 * </ul>
 *
 * The defaults were fixed on bench frames (0.01074 x 0.01106 mm/px). Under the blue ring light
 * paste is far from the blank (~52) but close to the board's solder mask (~20 .. 26; the SIM
 * colours 22.6), so colourDelta is 20. Bare mask and blank then read shares of 0 .. 1.3 % when
 * the camera is centred on the spot (a silkscreen tip), up to 3.8 % 3 mm off-centre in the ring
 * light's reflections, and ring spreads up to 22; purge dots, silkscreen and a board edge across
 * the spot read 21 .. 89 %, the blank's edge a spread of 76. BareSpotCheckTest records the
 * margins.
 *
 * Pure: no camera, no OpenCV. Offsets follow {@link DotInspector}: mm from the image centre
 * (pixel index - size/2), +x right, +y up.
 */
public final class BareSpotCheck {
    private BareSpotCheck() {}

    /** Code constants (not persisted); tests set them directly. */
    public static final class Settings {
        /** Where N1 may land: tip radius 0.35 + N1 offset error 0.4 + 0.25 margin, mm. */
        public double discRadiusMm = 1.0;
        /** The reference ring, mm from the centre. */
        public double ringInnerMm = 1.5, ringOuterMm = 3.0;
        /**
         * A disc pixel is foreign above this BGR distance from the reference (paste is ~52 from
         * the blank, ~20 .. 26 from the board's solder mask).
         */
        public double colourDelta = 20;
        /** Not bare above this foreign share of the disc, 0..1 (a 0.45 mm dot is 5 %). */
        public double maxForeignShare = 0.05;
        /** Not bare above this ring spread (median BGR distance from the reference). */
        public double maxRingSpread = 30;

        /** Throws IllegalArgumentException if a field is out of range. */
        public void validate() {
            if (!(discRadiusMm > 0) || !(ringInnerMm >= discRadiusMm)
                    || !(ringOuterMm > ringInnerMm) || Double.isInfinite(ringOuterMm)) {
                throw new IllegalArgumentException("need 0 < disc <= ring inner < ring outer,"
                        + " got " + discRadiusMm + ", " + ringInnerMm + ", " + ringOuterMm + " mm");
            }
            if (!(colourDelta >= 0) || !(maxRingSpread >= 0) || Double.isInfinite(colourDelta)) {
                throw new IllegalArgumentException("colourDelta and maxRingSpread must be >= 0,"
                        + " got " + colourDelta + ", " + maxRingSpread);
            }
            if (!(maxForeignShare >= 0 && maxForeignShare <= 1)) {
                throw new IllegalArgumentException(
                        "maxForeignShare must be 0..1, got " + maxForeignShare);
            }
        }
    }

    /** One look. */
    public static final class Result {
        public final boolean bare;
        /** Foreign pixels / disc pixels, 0..1. */
        public final double foreignShare;
        /** Median BGR distance of the ring pixels from the reference. */
        public final double ringSpread;
        /** Why the spot is not bare; null when it is. */
        public final String reason;
        /** The reference colour (ring median). */
        public final double refB, refG, refR;
        public final int discPx, ringPx;

        Result(double foreignShare, double ringSpread, double[] ref, int discPx, int ringPx,
                Settings s) {
            this.foreignShare = foreignShare;
            this.ringSpread = ringSpread;
            this.refB = ref[0];
            this.refG = ref[1];
            this.refR = ref[2];
            this.discPx = discPx;
            this.ringPx = ringPx;
            String why = null;
            if (!(ringSpread <= s.maxRingSpread)) {
                why = String.format(Locale.ROOT, "the surface around the spot is not uniform"
                        + " (spread %.1f, limit %.1f): an edge, a border or tape",
                        ringSpread, s.maxRingSpread);
            }
            if (!(foreignShare <= s.maxForeignShare)) {
                String f = String.format(Locale.ROOT, "%.1f %% of the spot differs from the surface"
                        + " around it (limit %.1f %%): paste, a part or print",
                        100 * foreignShare, 100 * s.maxForeignShare);
                why = why == null ? f : why + "; " + f;
            }
            this.reason = why;
            this.bare = why == null;
        }

        @Override
        public String toString() {
            return String.format(Locale.ROOT,
                    "BareSpot[%s, foreign=%.4f (%d px disc) spread=%.2f (%d px ring)"
                            + " ref=%.0f,%.0f,%.0f%s]",
                    bare ? "bare" : "not bare", foreignShare, discPx, ringSpread, ringPx, refB,
                    refG, refR, reason == null ? "" : " " + reason);
        }
    }

    /**
     * Looks at the spot at the frame centre. The ring must lie inside the frame. Null settings
     * mean the defaults.
     */
    public static Result check(BufferedImage img, double mmPerPxX, double mmPerPxY, Settings s) {
        if (img == null) {
            throw new IllegalArgumentException("image is required");
        }
        if (!(mmPerPxX > 0) || !(mmPerPxY > 0) || Double.isInfinite(mmPerPxX)
                || Double.isInfinite(mmPerPxY)) {
            throw new IllegalArgumentException("mm per pixel must be positive and finite");
        }
        if (s == null) {
            s = new Settings();
        }
        s.validate();
        int w = img.getWidth();
        int h = img.getHeight();
        double cx = w / 2.0;
        double cy = h / 2.0;
        long x0 = (long) Math.ceil(cx - s.ringOuterMm / mmPerPxX);
        long x1 = (long) Math.floor(cx + s.ringOuterMm / mmPerPxX);
        long y0 = (long) Math.ceil(cy - s.ringOuterMm / mmPerPxY);
        long y1 = (long) Math.floor(cy + s.ringOuterMm / mmPerPxY);
        if (x0 < 0 || y0 < 0 || x1 > w - 1 || y1 > h - 1) {
            throw new IllegalArgumentException(String.format(Locale.ROOT,
                    "the %.1f mm look ring does not fit in the %dx%d frame", s.ringOuterMm, w, h));
        }
        int bw = (int) (x1 - x0 + 1);
        int bh = (int) (y1 - y0 + 1);
        int[][] bgr = pixels(img, (int) x0, (int) y0, bw, bh);
        double disc2 = s.discRadiusMm * s.discRadiusMm;
        double in2 = s.ringInnerMm * s.ringInnerMm;
        double out2 = s.ringOuterMm * s.ringOuterMm;
        byte[] zone = new byte[bw * bh];
        int[][] hist = new int[3][256];
        int ringPx = 0;
        int discPx = 0;
        for (int j = 0; j < bh; j++) {
            double dy = (y0 + j - cy) * mmPerPxY;
            for (int i = 0; i < bw; i++) {
                double dx = (x0 + i - cx) * mmPerPxX;
                double r2 = dx * dx + dy * dy;
                int k = j * bw + i;
                if (r2 <= disc2) {
                    zone[k] = 1;
                    discPx++;
                }
                else if (r2 >= in2 && r2 <= out2) {
                    zone[k] = 2;
                    ringPx++;
                    for (int c = 0; c < 3; c++) {
                        hist[c][bgr[c][k]]++;
                    }
                }
            }
        }
        if (discPx == 0 || ringPx == 0) {
            throw new IllegalArgumentException(String.format(Locale.ROOT,
                    "%.4f x %.4f mm per pixel is too coarse for the look", mmPerPxX, mmPerPxY));
        }
        double[] ref = new double[3];
        for (int c = 0; c < 3; c++) {
            ref[c] = median(hist[c], ringPx);
        }
        double[] ringDist = new double[ringPx];
        int n = 0;
        int foreign = 0;
        double delta2 = s.colourDelta * s.colourDelta;
        for (int k = 0; k < zone.length; k++) {
            if (zone[k] == 0) {
                continue;
            }
            double db = bgr[0][k] - ref[0];
            double dg = bgr[1][k] - ref[1];
            double dr = bgr[2][k] - ref[2];
            double d2 = db * db + dg * dg + dr * dr;
            if (zone[k] == 2) {
                ringDist[n++] = Math.sqrt(d2);
            }
            else if (d2 > delta2) {
                foreign++;
            }
        }
        Arrays.sort(ringDist);
        double spread = (ringPx % 2 == 1) ? ringDist[ringPx / 2]
                : 0.5 * (ringDist[ringPx / 2 - 1] + ringDist[ringPx / 2]);
        return new Result(foreign / (double) discPx, spread, ref, discPx, ringPx, s);
    }

    /** Median of n values given as a 0..255 histogram (the mean of the middle two for even n). */
    private static double median(int[] hist, int n) {
        int lo = -1;
        int hi = -1;
        int seen = 0;
        for (int v = 0; v < hist.length && hi < 0; v++) {
            seen += hist[v];
            if (lo < 0 && seen > (n - 1) / 2) {
                lo = v;
            }
            if (seen > n / 2) {
                hi = v;
            }
        }
        return (n % 2 == 1) ? hi : 0.5 * (lo + hi);
    }

    /**
     * B, G, R planes (0..255) of a box. Grey images are read from the raster (getRGB would apply a
     * linear-to-sRGB curve to TYPE_BYTE_GRAY) and give three equal planes.
     */
    private static int[][] pixels(BufferedImage img, int x0, int y0, int bw, int bh) {
        int[][] out = new int[3][bw * bh];
        if (DotInspector.isGray(img)) {
            Raster r = img.getRaster();
            int bits = img.getColorModel().getComponentSize(0);
            double scale = 255.0 / ((1L << bits) - 1);
            int[] v = r.getSamples(r.getMinX() + x0, r.getMinY() + y0, bw, bh, 0, (int[]) null);
            for (int k = 0; k < v.length; k++) {
                int g = (int) Math.round(v[k] * scale);
                out[0][k] = g;
                out[1][k] = g;
                out[2][k] = g;
            }
            return out;
        }
        int[] rgb = img.getRGB(x0, y0, bw, bh, null, 0, bw);
        for (int k = 0; k < rgb.length; k++) {
            int p = rgb[k];
            out[0][k] = p & 0xff;
            out[1][k] = (p >> 8) & 0xff;
            out[2][k] = (p >> 16) & 0xff;
        }
        return out;
    }
}
