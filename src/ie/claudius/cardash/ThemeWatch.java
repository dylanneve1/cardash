package ie.claudius.cardash;

import android.app.WallpaperColors;
import android.app.WallpaperManager;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

/**
 * Tells a screen when the wallpaper's extracted colours change.
 *
 * Setting a wallpaper is asynchronous on the system side — the bitmap is
 * stored, then colours are extracted a moment later — so a screen that
 * re-themed itself right after {@code setBitmap} would still read the old
 * seed. Listening is the honest way; API 27+ only, and a no-op below.
 */
public final class ThemeWatch {

    private ThemeWatch() {}

    /** Returns a handle for {@link #stop}, or null when unsupported. */
    public static Object start(Context ctx, final Runnable onChange) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) return null;
        WallpaperManager.OnColorsChangedListener l = new WallpaperManager.OnColorsChangedListener() {
            @Override
            public void onColorsChanged(WallpaperColors colors, int which) {
                if ((which & WallpaperManager.FLAG_SYSTEM) != 0) onChange.run();
            }
        };
        try {
            WallpaperManager.getInstance(ctx).addOnColorsChangedListener(
                    l, new Handler(Looper.getMainLooper()));
        } catch (Exception e) {
            return null; // vendor ROM without the service; theme still works, just not live
        }
        return l;
    }

    public static void stop(Context ctx, Object handle) {
        if (handle == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) return;
        try {
            WallpaperManager.getInstance(ctx).removeOnColorsChangedListener(
                    (WallpaperManager.OnColorsChangedListener) handle);
        } catch (Exception ignored) {
            // already gone
        }
    }
}
