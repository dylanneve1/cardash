package ie.claudius.cardash;

import android.app.WallpaperManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * Bundled wallpapers that weigh nothing.
 *
 * Shipping six 1280x720 PNGs would multiply the APK several times over,
 * so these are drawn: a tonal gradient off a seed colour with a few soft
 * radial blooms laid over it, which is the same visual language Pixel's
 * own Material You wallpapers use. Because the colours come from the
 * app's M3 palette for that seed, whatever the system extracts from the
 * result themes the launcher in harmony with it.
 *
 * Everything here is arithmetic on a canvas — no assets, no native code,
 * and fast enough to redraw on a 32-bit Cortex-A35 without anyone
 * noticing.
 */
public final class Wallpapers {

    private static final String TAG = "CarDash/Wallpaper";

    /** Blob placement families. */
    public static final int DUSK = 0, AURORA = 1, ORBIT = 2;

    public static final class Design {
        public final String name;
        public final int seed;
        public final int style;

        Design(String name, int seed, int style) {
            this.name = name;
            this.seed = seed;
            this.style = style;
        }
    }

    public static final Design[] ALL = {
            new Design("Harbour", 0xFF4F7BD5, DUSK),
            new Design("Lagoon", 0xFF3AA7A3, AURORA),
            new Design("Moss", 0xFF2E8B57, ORBIT),
            new Design("Ember", 0xFFC96A1E, DUSK),
            new Design("Plum", 0xFF8E4FB8, AURORA),
            new Design("Blush", 0xFFC4506A, ORBIT),
    };

    private Wallpapers() {}

    /** Some head-unit ROMs stub the wallpaper service out entirely. */
    public static boolean supported(Context ctx) {
        try {
            WallpaperManager wm = WallpaperManager.getInstance(ctx);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !wm.isWallpaperSupported()) {
                return false;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && !wm.isSetWallpaperAllowed()) {
                return false;
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static Design byName(String name) {
        for (Design d : ALL) if (d.name.equals(name)) return d;
        return null;
    }

    public static Bitmap render(Design d, int w, int h) {
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        M3 m = M3.fromSeed(d.seed);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);

        // Base: a diagonal tonal ramp, dark enough that a scrim on top
        // is a choice rather than a necessity.
        p.setShader(new LinearGradient(0, 0, w * 0.35f, h,
                new int[] { m.primary.tone(24), m.neutral.tone(10), m.neutral.tone(5) },
                new float[] { 0f, 0.55f, 1f }, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);

        switch (d.style) {
            case AURORA:
                bloom(c, p, w, h, 0.80f, 0.15f, 0.70f, m.primary.tone(50), 0.75f);
                bloom(c, p, w, h, 0.30f, 0.05f, 0.55f, m.tertiary.tone(45), 0.55f);
                bloom(c, p, w, h, 0.55f, 0.95f, 0.65f, m.primary.tone(40), 0.55f);
                break;
            case ORBIT:
                ring(c, p, w, h, 0.72f, 0.55f, 0.70f, m.primary.tone(50), 0.42f);
                bloom(c, p, w, h, 0.72f, 0.55f, 0.28f, m.primary.tone(35), 0.35f);
                bloom(c, p, w, h, 0.10f, 0.10f, 0.45f, m.tertiary.tone(40), 0.45f);
                break;
            default: // DUSK
                bloom(c, p, w, h, 0.85f, 0.85f, 0.85f, m.primary.tone(45), 0.80f);
                bloom(c, p, w, h, 0.15f, 0.20f, 0.50f, m.tertiary.tone(45), 0.50f);
                bloom(c, p, w, h, 0.60f, 0.40f, 0.35f, m.primary.tone(60), 0.30f);
                break;
        }
        return bmp;
    }

    /** A soft radial glow: opaque at the centre, gone at the edge. */
    private static void bloom(Canvas c, Paint p, int w, int h,
                              float fx, float fy, float fr, int color, float alpha) {
        float cx = w * fx, cy = h * fy, r = h * fr;
        p.setShader(new RadialGradient(cx, cy, r,
                new int[] { M3.withAlpha(color, (int) (alpha * 255)),
                        M3.withAlpha(color, (int) (alpha * 90)), M3.withAlpha(color, 0) },
                new float[] { 0f, 0.45f, 1f }, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, r, p);
    }

    /** A soft annulus — a halo rather than a spot. Wide shoulders so it
     *  reads as light, not as a neon tube. */
    private static void ring(Canvas c, Paint p, int w, int h,
                             float fx, float fy, float fr, int color, float alpha) {
        float cx = w * fx, cy = h * fy, r = h * fr;
        p.setShader(new RadialGradient(cx, cy, r,
                new int[] { M3.withAlpha(color, 0), M3.withAlpha(color, (int) (alpha * 40)),
                        M3.withAlpha(color, (int) (alpha * 255)),
                        M3.withAlpha(color, (int) (alpha * 70)), M3.withAlpha(color, 0) },
                new float[] { 0.30f, 0.48f, 0.64f, 0.82f, 1f }, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, r, p);
    }

    /**
     * Render at the display's size and hand it to the system on a
     * worker thread; {@code done} runs on the main thread afterwards.
     * The theme follows by itself — the home screen watches wallpaper
     * colours — so this deliberately does not touch the colour setting.
     */
    public static void apply(final Context ctx, final Design d, final Runnable done) {
        final Context app = ctx.getApplicationContext();
        final Handler main = new Handler(Looper.getMainLooper());
        final android.util.DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    int w = Math.max(dm.widthPixels, dm.heightPixels);
                    int h = Math.min(dm.widthPixels, dm.heightPixels);
                    Bitmap bmp = render(d, w, h);
                    WallpaperManager wm = WallpaperManager.getInstance(app);
                    wm.setBitmap(bmp);
                    Prefs.set(app, Prefs.WALLPAPER, d.name);
                } catch (Exception e) {
                    Log.w(TAG, "set wallpaper failed: " + e);
                }
                if (done != null) main.post(done);
            }
        }, "wallpaper").start();
    }
}
