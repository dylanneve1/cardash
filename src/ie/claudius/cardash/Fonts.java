package ie.claudius.cardash;

import android.graphics.Typeface;

/**
 * Typeface selection.
 *
 * Google Sans Text is proprietary and cannot be bundled, so this asks
 * the platform for it by family name and falls back cleanly. Pixels and
 * some OEM ROMs ship it; a Chinese head unit almost certainly does not,
 * in which case Roboto is what Material specifies anyway.
 *
 * Typeface.create() never fails — an unknown family silently returns
 * the default — so the fallback chain costs nothing and the UI simply
 * looks slightly better on devices that happen to have the font.
 */
public final class Fonts {

    private static final String[] PREFERRED = {
            "google-sans-text",
            "google-sans",
            "product-sans",
            "sans-serif-medium",
    };

    private static Typeface display;
    private static Typeface body;

    private Fonts() {}

    /** Large surfaces: the clock, hero labels. */
    public static Typeface display() {
        if (display == null) display = pick(Typeface.NORMAL);
        return display;
    }

    /** Everything else. */
    public static Typeface body() {
        if (body == null) body = Typeface.create("sans-serif", Typeface.NORMAL);
        return body;
    }

    private static Typeface pick(int style) {
        for (String family : PREFERRED) {
            Typeface t = Typeface.create(family, style);
            // A missing family resolves to the default; only accept a
            // result that is actually distinct from it.
            if (t != null && !t.equals(Typeface.DEFAULT)) return t;
        }
        return Typeface.create("sans-serif-medium", style);
    }
}
