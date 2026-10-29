package org.openpnp.instancelabs.dispense;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Package id (+ optional footprint pad) -> current pad type. Order: explicit package mapping
 * (package_pad_type), then the QFN/DFN centre-pad rule, then package_pattern regexes with newer
 * (user-added) types first.
 */
public class PadTypeResolver {
    private final List<PadType> types;
    private final Map<String, String> packageMap;

    public PadTypeResolver(List<PadType> currentTypes, Map<String, String> packageMap) {
        this.types = new ArrayList<>(currentTypes);
        this.packageMap = packageMap == null ? Collections.emptyMap() : packageMap;
    }

    /**
     * Identity of what this resolver can answer: every pad type with its version (an edit of the pattern or status is a
     * new version) and every package mapping. A plan made with another resolver may resolve its packages differently.
     */
    public String fingerprint() {
        StringBuilder sb = new StringBuilder();
        for (PadType t : types) {
            sb.append(t.typeKey).append(':').append(t.version).append(';');
        }
        sb.append('|');
        for (Map.Entry<String, String> e : new java.util.TreeMap<>(packageMap).entrySet()) {
            sb.append(e.getKey()).append('=').append(e.getValue()).append(';');
        }
        return sb.toString();
    }

    public PadType byKey(String key) {
        for (PadType t : types) {
            if (t.typeKey.equals(key)) {
                return t;
            }
        }
        return null;
    }

    /** How a pad type was chosen: the type (null if none) and the rule that chose it. */
    public static final class Resolution {
        public static final String PACKAGE_MAP = "package-map";
        public static final String CENTRE_PAD = "centre-pad";
        public static final String PATTERN = "pattern";
        public static final String NONE = "none";

        public final PadType type;
        /** {@link #PACKAGE_MAP}, {@link #CENTRE_PAD}, {@link #PATTERN} or {@link #NONE}. */
        public final String rule;
        /** package-map: the mapped type key; centre-pad: the type whose pattern matched; pattern: the pattern. */
        public final String detail;
        /** centre-pad and pattern: the text the pattern found in the package id (separators trimmed), else null. */
        public final String matched;

        Resolution(PadType type, String rule, String detail, String matched) {
            this.type = type;
            this.rule = rule;
            this.detail = detail;
            this.matched = matched;
        }
    }

    /**
     * @param packageId OpenPnP package id
     * @param padX footprint pad centre (mm) or NaN when not from a footprint
     * @param padY footprint pad centre (mm) or NaN
     * @param padAreaMm2 footprint pad area or NaN
     */
    public PadType resolve(String packageId, double padX, double padY, double padAreaMm2) {
        return explain(packageId, padX, padY, padAreaMm2).type;
    }

    /** As {@link #resolve}, with the rule that chose the type (the pad inspector and the import preview). */
    public Resolution explain(String packageId, double padX, double padY, double padAreaMm2) {
        if (packageId == null) {
            return new Resolution(null, Resolution.NONE, null, null);
        }
        String mapped = packageMap.get(packageId);
        if (mapped != null) {
            return new Resolution(byKey(mapped), Resolution.PACKAGE_MAP, mapped, null);
        }
        PadType byPattern = null;
        String found = null;
        for (int i = types.size() - 1; i >= 0; i--) {
            PadType t = types.get(i);
            if (t.packagePattern != null && !t.packagePattern.isEmpty()) {
                Matcher m = Pattern.compile(t.packagePattern, Pattern.CASE_INSENSITIVE).matcher(packageId);
                if (m.find()) {
                    byPattern = t;
                    found = trimSeparators(m.group());
                    break;
                }
            }
        }
        if (byPattern == null) {
            return new Resolution(null, Resolution.NONE, null, null);
        }
        if ("qfn-dfn-perimeter".equals(byPattern.typeKey)
                && !Double.isNaN(padX) && Math.hypot(padX, padY) < 0.05 && padAreaMm2 >= 1.0) {
            PadType centre = byKey("qfn-centre");
            if (centre != null) {
                return new Resolution(centre, Resolution.CENTRE_PAD, byPattern.typeKey, found);
            }
        }
        return new Resolution(byPattern, Resolution.PATTERN, byPattern.packagePattern, found);
    }

    /**
     * The seeded patterns match a separator on each side ({@code (^|[^0-9])0603([^0-9]|$)} finds
     * {@code _0603_}): the text shown is what lies between them. Unchanged if nothing would be left.
     */
    static String trimSeparators(String s) {
        int a = 0, b = s.length();
        while (a < b && !Character.isLetterOrDigit(s.charAt(a))) {
            a++;
        }
        while (b > a && !Character.isLetterOrDigit(s.charAt(b - 1))) {
            b--;
        }
        return a < b ? s.substring(a, b) : s;
    }
}
