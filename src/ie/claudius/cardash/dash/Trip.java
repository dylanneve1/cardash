package ie.claudius.cardash.dash;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * A trip meter fed by GPS: distance covered, time spent moving, and the
 * moving average that falls out of the two.
 *
 * It counts only while the car is actually moving, so ten minutes at a
 * level crossing don't drag the average down — that is what a trip
 * computer in a dashboard does, and it is the number people mean when
 * they say "we averaged 60". It persists across restarts and is reset
 * by a long press on the widget or from settings.
 */
public final class Trip {

    /** Below this we're parked and jittering, not travelling. */
    private static final float MOVING_KPH = 3f;
    private static final long SAVE_EVERY_MS = 15_000L;

    private static final String KEY_METRES = "trip_m";
    private static final String KEY_MOVING = "trip_ms";
    private static final String KEY_STARTED = "trip_since";

    public interface Listener {
        void onTrip(float metres, long movingMs);
    }

    private final SharedPreferences prefs;
    private float metres;
    private long movingMs;
    private long lastSave;
    private Listener listener;

    public Trip(Context ctx) {
        prefs = ctx.getApplicationContext()
                .getSharedPreferences("cardash", Context.MODE_PRIVATE);
        metres = prefs.getFloat(KEY_METRES, 0f);
        movingMs = prefs.getLong(KEY_MOVING, 0L);
    }

    public void setListener(Listener l) {
        listener = l;
        if (l != null) l.onTrip(metres, movingMs);
    }

    public void travel(float m, long dtMs, float kph) {
        if (kph < MOVING_KPH) return;
        metres += m;
        movingMs += dtMs;
        long now = System.currentTimeMillis();
        if (now - lastSave > SAVE_EVERY_MS) {
            lastSave = now;
            save();
        }
        if (listener != null) listener.onTrip(metres, movingMs);
    }

    public void reset() {
        metres = 0f;
        movingMs = 0L;
        prefs.edit().putLong(KEY_STARTED, System.currentTimeMillis()).apply();
        save();
        if (listener != null) listener.onTrip(metres, movingMs);
    }

    /** Flush on pause so a reboot doesn't lose the last quarter-hour. */
    public void save() {
        prefs.edit().putFloat(KEY_METRES, metres).putLong(KEY_MOVING, movingMs).apply();
    }

    public float metres() {
        return metres;
    }

    public long movingMs() {
        return movingMs;
    }

    // ---- formatting ---------------------------------------------------------

    /** "12.4" or "128" — one decimal until it stops being meaningful. */
    public static String distance(float metres, boolean miles) {
        float v = metres / (miles ? 1609.344f : 1000f);
        return v < 100f ? String.format(java.util.Locale.US, "%.1f", v)
                : String.valueOf(Math.round(v));
    }

    /** "38 min" or "2 h 05". */
    public static String duration(long ms) {
        long min = ms / 60_000L;
        if (min < 60) return min + " min";
        return String.format(java.util.Locale.US, "%d h %02d", min / 60, min % 60);
    }

    /** Moving average in the display unit, or -1 with nothing to go on. */
    public static int average(float metres, long movingMs, boolean miles) {
        if (movingMs < 30_000L) return -1;
        float perHour = metres / (movingMs / 3_600_000f);
        return Math.round(perHour / (miles ? 1609.344f : 1000f));
    }
}
