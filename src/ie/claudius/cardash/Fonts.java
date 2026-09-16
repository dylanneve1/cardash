package ie.claudius.cardash;

import android.content.Context;
import android.graphics.Typeface;
import android.util.Log;

/**
 * Typefaces.
 *
 * Google Sans Flex is the face Pixel phones and Material 3 Expressive
 * use, and it went open source under the SIL OFL 1.1 in 2025. The
 * upstream file is a 4 MB six-axis variable font; the head unit does
 * not need slant, grade or roundness, so three static instances are
 * cut from it with fontTools and subset to Latin, Latin-1 and Latin
 * Extended-A plus punctuation, which is ~66 KB per cut.
 *
 * The third cut exists because the clock and the speed read-out are
 * huge. Optical size is one of the variable axes: at opsz 72 the
 * counters tighten and the spacing pulls in, which is what a display
 * face is for. Text-size labels stay at opsz 18.
 *
 * Loading still falls back to Roboto if an asset is missing, because a
 * launcher that crashes at boot leaves the head unit with no home
 * screen at all.
 */
public final class Fonts {

    private static final String TAG = "CarDash";

    private static Typeface hero;
    private static Typeface display;
    private static Typeface body;

    private Fonts() {}

    /** Medium weight at display optical size — the clock and the speed. */
    public static Typeface hero(Context ctx) {
        if (hero == null) {
            hero = load(ctx, "fonts/GoogleSansFlex-Display.ttf",
                    "sans-serif-medium");
        }
        return hero;
    }

    /** Medium weight — titles, tile labels, buttons. */
    public static Typeface display(Context ctx) {
        if (display == null) {
            display = load(ctx, "fonts/GoogleSansFlex-Medium.ttf",
                    "sans-serif-medium");
        }
        return display;
    }

    /** Regular weight — everything else. */
    public static Typeface body(Context ctx) {
        if (body == null) {
            body = load(ctx, "fonts/GoogleSansFlex-Regular.ttf", "sans-serif");
        }
        return body;
    }

    private static Typeface load(Context ctx, String asset, String fallback) {
        try {
            return Typeface.createFromAsset(ctx.getAssets(), asset);
        } catch (Exception e) {
            Log.w(TAG, "font " + asset + " missing, falling back: " + e);
            return Typeface.create(fallback, Typeface.NORMAL);
        }
    }
}
