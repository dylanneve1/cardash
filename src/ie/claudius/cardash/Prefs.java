package ie.claudius.cardash;

import android.app.WallpaperColors;
import android.app.WallpaperManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import java.util.ArrayList;
import java.util.List;

/**
 * User settings. Same SharedPreferences file as the tile assignments and
 * the cached weather position, so one clear-data wipes everything.
 *
 * Every setting is stored as a string from a small fixed set, which is
 * what lets {@link SettingsActivity} render them all as the same kind of
 * segmented control and lets {@link #signature} be a cheap "has anything
 * changed since we last built the home screen" check.
 */
public final class Prefs {

    private static final String PREFS = "cardash";

    public static final String SPEED = "speed_unit";  // kmh | mph
    public static final String TEMP = "temp_unit";    // c | f
    public static final String CLOCK = "clock";       // system | 12 | 24
    public static final String SEED = "seed";         // wallpaper | RRGGBB
    public static final String SCRIM = "scrim";       // glass | balanced | solid
    public static final String MOTION = "motion";     // expressive | calm
    public static final String LAYOUT = "layout";     // hero | grid
    public static final String HINT = "hint";         // show | hide
    public static final String WALLPAPER = "wallpaper"; // last bundled design applied

    // ---- widgets ----
    public static final String DATE = "date";         // full | short | hide
    public static final String MEDIA = "media";       // full | compact | hide
    public static final String VEHICLE = "vehicle";   // auto | obd | can | off
    public static final String DASH = "dash";         // comma list of speedo,trip,weather
    public static final String GAUGE = "gauge";       // arc | digits
    public static final String SCALE = "scale";       // auto | city | fast
    public static final String PRESET = "preset";     // classic | driver | launcher | minimal | ""
    /** Typed by hand from the factory settings menu; see DiagnosticsActivity. */
    public static final String CANBOX_PROTOCOLS = "canbox_protocols";

    public static final String W_SPEEDO = "speedo", W_TRIP = "trip", W_WEATHER = "weather";
    public static final String[] DASH_ALL = { W_SPEEDO, W_TRIP, W_WEATHER };
    private static final String DASH_DEFAULT = W_SPEEDO + "," + W_WEATHER;

    /**
     * Preset seeds for people whose wallpaper is a photo of their dog.
     * These are seeds, not final colours: M3 still builds the palette.
     */
    public static final int[] SEEDS = {
            0xFF4F7BD5, // ocean
            0xFF3AA7A3, // teal
            0xFF2E8B57, // forest
            0xFFC96A1E, // amber
            0xFFC4506A, // rose
            0xFF8E4FB8, // violet
    };

    private Prefs() {}

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static String get(Context ctx, String key, String def) {
        return prefs(ctx).getString(key, def);
    }

    public static void set(Context ctx, String key, String value) {
        prefs(ctx).edit().putString(key, value).apply();
    }

    // ---- typed readers ----------------------------------------------------

    public static boolean mph(Context ctx) {
        return "mph".equals(get(ctx, SPEED, "kmh"));
    }

    public static boolean fahrenheit(Context ctx) {
        return "f".equals(get(ctx, TEMP, "c"));
    }

    /** Clock pattern honouring the override, else the system setting. */
    public static String clockPattern(Context ctx) {
        String c = get(ctx, CLOCK, "system");
        boolean h24 = "24".equals(c)
                || ("system".equals(c) && android.text.format.DateFormat.is24HourFormat(ctx));
        return h24 ? "HH:mm" : "h:mm";
    }

    /** Used when the wallpaper has no extractable colour (solid black, etc). */
    public static final int FALLBACK_SEED = 0xFF4F7BD5;

    /** The seed to theme from: a preset, or the wallpaper's own colour. */
    public static int themeSeed(Context ctx) {
        String s = get(ctx, SEED, "wallpaper");
        if (!"wallpaper".equals(s)) {
            try {
                return (int) Long.parseLong(s, 16) | 0xFF000000;
            } catch (NumberFormatException ignored) {
                // corrupt value; behave as "wallpaper"
            }
        }
        return wallpaperSeed(ctx);
    }

    public static int wallpaperSeed(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) return FALLBACK_SEED;
        try {
            WallpaperColors c = WallpaperManager.getInstance(ctx)
                    .getWallpaperColors(WallpaperManager.FLAG_SYSTEM);
            if (c != null && c.getPrimaryColor() != null) {
                return c.getPrimaryColor().toArgb();
            }
        } catch (Exception ignored) {
            // some vendor ROMs throw here; fall through
        }
        return FALLBACK_SEED;
    }

    /** Alpha of the tonal layer over the wallpaper. */
    public static int scrimAlpha(Context ctx) {
        String s = get(ctx, SCRIM, "balanced");
        if ("glass".equals(s)) return 0x8C;
        if ("solid".equals(s)) return 0xFF;
        return 0xD8;
    }

    public static boolean calmMotion(Context ctx) {
        return "calm".equals(get(ctx, MOTION, "expressive"));
    }

    public static boolean heroLayout(Context ctx) {
        return !"grid".equals(get(ctx, LAYOUT, "hero"));
    }

    public static boolean showHint(Context ctx) {
        return !"hide".equals(get(ctx, HINT, "show"));
    }

    /** SimpleDateFormat pattern for the date line, or null to hide it. */
    public static String datePattern(Context ctx) {
        String d = get(ctx, DATE, "full");
        if ("hide".equals(d)) return null;
        return "short".equals(d) ? "EEE d MMM" : "EEEE d MMMM";
    }

    public static String media(Context ctx) {
        return get(ctx, MEDIA, "full");
    }

    /** Which vehicle sources to run — one of VehicleHub.MODE_*. */
    public static String vehicleMode(Context ctx) {
        return get(ctx, VEHICLE, "auto");
    }

    public static boolean showVehicle(Context ctx) {
        return !"off".equals(vehicleMode(ctx));
    }

    /** The middle-column widgets, in DASH_ALL order; may be empty. */
    public static List<String> dashWidgets(Context ctx) {
        String raw = get(ctx, DASH, DASH_DEFAULT);
        List<String> out = new ArrayList<>(3);
        for (String w : DASH_ALL) {
            if (("," + raw + ",").contains("," + w + ",")) out.add(w);
        }
        return out;
    }

    public static void setDashWidget(Context ctx, String widget, boolean on) {
        List<String> now = dashWidgets(ctx);
        if (on && !now.contains(widget)) now.add(widget);
        if (!on) now.remove(widget);
        StringBuilder sb = new StringBuilder();
        for (String w : DASH_ALL) if (now.contains(w)) sb.append(w).append(',');
        set(ctx, DASH, sb.toString());
    }

    public static boolean digitsGauge(Context ctx) {
        return "digits".equals(get(ctx, GAUGE, "arc"));
    }

    /** Full-scale of the gauge in the display unit. */
    public static int gaugeMax(Context ctx) {
        String s = get(ctx, SCALE, "auto");
        boolean mph = mph(ctx);
        if ("city".equals(s)) return mph ? 60 : 100;
        if ("fast".equals(s)) return mph ? 160 : 260;
        return mph ? 120 : 180;
    }

    // ---- presets ------------------------------------------------------------

    public static final String[] PRESETS = { "classic", "driver", "launcher", "minimal" };

    /**
     * Curated combinations of the widget options. Presets touch layout
     * only — never colour, wallpaper or units — so switching between
     * them is safe to do on a whim. Each is a combination that was
     * looked at, which is the point: the individual toggles let you
     * tune, but the presets are the shapes that are known to look right.
     */
    public static void applyPreset(Context ctx, String preset) {
        SharedPreferences.Editor e = prefs(ctx).edit();
        switch (preset) {
            case "driver":
                // Numbers a driver glances at, nothing else.
                e.putString(DASH, W_SPEEDO + "," + W_TRIP + ",");
                e.putString(GAUGE, "arc").putString(MEDIA, "compact")
                        .putString(DATE, "short").putString(LAYOUT, "hero")
                        .putString(VEHICLE, "auto").putString(HINT, "hide");
                break;
            case "launcher":
                // The apps are the point; the dash column goes away and
                // the grid takes the room.
                e.putString(DASH, "").putString(MEDIA, "compact")
                        .putString(DATE, "full").putString(LAYOUT, "grid")
                        .putString(VEHICLE, "auto").putString(HINT, "hide");
                break;
            case "minimal":
                // Clock, a speed, tiles. Calm motion to match.
                e.putString(DASH, W_SPEEDO + ",").putString(GAUGE, "digits")
                        .putString(MEDIA, "hide").putString(DATE, "short")
                        .putString(LAYOUT, "hero").putString(VEHICLE, "off")
                        .putString(HINT, "hide").putString(MOTION, "calm");
                break;
            default: // classic — the defaults
                e.remove(DASH).remove(GAUGE).remove(SCALE).remove(MEDIA)
                        .remove(DATE).remove(LAYOUT).remove(VEHICLE)
                        .remove(HINT).remove(MOTION);
                break;
        }
        e.putString(PRESET, preset).apply();
    }

    /** The last preset applied, or "" once something was hand-tuned. */
    public static String preset(Context ctx) {
        return get(ctx, PRESET, "classic");
    }

    /** Hand-tuning any widget option clears the preset highlight. */
    public static void touched(Context ctx) {
        set(ctx, PRESET, "");
    }

    /** Everything that changes how the home screen is built, in one string. */
    public static String signature(Context ctx) {
        String[] keys = { SPEED, TEMP, CLOCK, SEED, SCRIM, MOTION, LAYOUT, HINT,
                DATE, MEDIA, VEHICLE, DASH, GAUGE, SCALE };
        StringBuilder sb = new StringBuilder();
        for (String k : keys) sb.append(get(ctx, k, "")).append('|');
        return sb.toString();
    }
}
