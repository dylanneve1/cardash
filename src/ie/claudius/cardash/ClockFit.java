package ie.claudius.cardash;

/**
 * Picks the largest text size that fits a box. Android-free so it can be
 * tested on a plain JVM; HomeActivity supplies a {@link Measure} backed by
 * Paint. TextView autosize needs API 26, the unit supports API 21.
 */
public final class ClockFit {
    private ClockFit() {}

    /** Text extent at a given size (any consistent unit, px in practice). */
    public interface Measure {
        float width(float size);
        float height(float size);
    }

    /**
     * Largest size in [min, max] whose width is within availW and whose
     * height is within availH. A non-positive limit is ignored. If even
     * min does not fit, min is returned (never smaller than readable).
     */
    public static float fit(Measure m, float availW, float availH, float min, float max) {
        if (max < min) max = min;
        if (fits(m, max, availW, availH)) return max;
        if (!fits(m, min, availW, availH)) return min;
        float lo = min, hi = max;
        for (int i = 0; i < 24; i++) {
            float mid = (lo + hi) / 2f;
            if (fits(m, mid, availW, availH)) lo = mid; else hi = mid;
        }
        return lo;
    }

    private static boolean fits(Measure m, float size, float availW, float availH) {
        if (availW > 0 && m.width(size) > availW) return false;
        if (availH > 0 && m.height(size) > availH) return false;
        return true;
    }

    /** Date line size follows the clock, within [min, max]. */
    public static float dateSize(float clockSize, float min, float max) {
        float s = clockSize * 0.25f;
        return s < min ? min : (s > max ? max : s);
    }

    /** Widest plausible string for a clock pattern: every digit slot as 8. */
    public static String widest(String pattern) {
        StringBuilder sb = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '\'') { quoted = !quoted; continue; }
            if (!quoted && (c == 'H' || c == 'h' || c == 'm' || c == 's' || c == 'k' || c == 'K')) {
                sb.append('8');
            } else if (!quoted && c == 'a') {
                sb.append("AM");
            } else {
                sb.append(c);
            }
        }
        // h:mm and H:mm collapse to 1 digit for the hour; pad to two.
        String s = sb.toString();
        if (s.length() > 0 && s.charAt(0) == '8' && s.length() > 1 && s.charAt(1) == ':') s = "8" + s;
        return s;
    }
}
