package ie.claudius.cardash;

import android.content.Context;
import android.graphics.Typeface;
import android.util.Log;

/**
 * Typefaces.
 *
 * Google Sans went open source under the SIL OFL 1.1, so Google Sans
 * Text ships in the APK rather than being hopefully requested from the
 * platform. The full family is 2.2 MB per weight covering 8,211 glyphs;
 * these are subset to Latin, Latin-1 and Latin Extended-A plus common
 * punctuation and symbols, which is 47 KB per weight and everything a
 * car launcher will ever render.
 *
 * Loading still falls back to Roboto if an asset is missing, because a
 * launcher that crashes at boot leaves the head unit with no home
 * screen at all.
 */
public final class Fonts {

    private static final String TAG = "CarDash";

    private static Typeface display;
    private static Typeface body;

    private Fonts() {}

    /** Medium weight — the clock and tile labels. */
    public static Typeface display(Context ctx) {
        if (display == null) {
            display = load(ctx, "fonts/GoogleSansText-Medium.ttf",
                    "sans-serif-medium");
        }
        return display;
    }

    /** Regular weight — everything else. */
    public static Typeface body(Context ctx) {
        if (body == null) {
            body = load(ctx, "fonts/GoogleSansText-Regular.ttf", "sans-serif");
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
