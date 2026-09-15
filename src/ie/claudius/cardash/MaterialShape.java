package ie.claudius.cardash;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

/**
 * The Material 3 Expressive shape family — clover, flower, burst and
 * friends — as plain Paths.
 *
 * AndroidX has androidx.graphics.shapes for this, but pulling in
 * AndroidX would mean Gradle, and these shapes are just polar curves.
 * A lobed shape is r(theta) = R * (1 + amplitude * cos(lobes * theta)),
 * sampled finely enough that the polyline reads as a smooth curve at
 * any size a head unit can display. The squircle is a superellipse,
 * which is a different curve and worth having because it is the calm
 * one you want for most tiles.
 */
public final class MaterialShape extends Drawable {

    public enum Kind {
        CIRCLE(0, 0f),
        SQUIRCLE(-1, 0f),      // superellipse, handled separately
        CLOVER(4, 0.14f),
        FLOWER(6, 0.11f),
        BURST(8, 0.09f),
        SCALLOP(12, 0.055f);

        final int lobes;
        final float amplitude;

        Kind(int lobes, float amplitude) {
            this.lobes = lobes;
            this.amplitude = amplitude;
        }
    }

    /** Rotating order used across the tile grid. */
    public static final Kind[] CYCLE = {
            Kind.SQUIRCLE, Kind.CLOVER, Kind.FLOWER,
            Kind.SQUIRCLE, Kind.BURST, Kind.SCALLOP,
    };

    public static Kind forIndex(int i) {
        return CYCLE[((i % CYCLE.length) + CYCLE.length) % CYCLE.length];
    }

    private static final int SAMPLES = 240;

    private final Kind kind;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final float rotation;

    public MaterialShape(Kind kind, int color) {
        this(kind, color, 0f);
    }

    /** Rotation in degrees — a clover on its point reads differently. */
    public MaterialShape(Kind kind, int color, float rotation) {
        this.kind = kind;
        this.rotation = rotation;
        paint.setColor(color);
        paint.setStyle(Paint.Style.FILL);
    }

    @Override
    public void draw(Canvas canvas) {
        Rect b = getBounds();
        if (b.width() <= 0 || b.height() <= 0) return;

        float cx = b.exactCenterX();
        float cy = b.exactCenterY();
        float rx = b.width() / 2f;
        float ry = b.height() / 2f;

        path.reset();
        if (kind == Kind.SQUIRCLE) {
            superellipse(path, cx, cy, rx, ry, 4.0);
        } else if (kind == Kind.CIRCLE) {
            path.addOval(b.left, b.top, b.right, b.bottom, Path.Direction.CW);
        } else {
            lobed(path, cx, cy, rx, ry, kind.lobes, kind.amplitude);
        }
        canvas.drawPath(path, paint);
    }

    private void lobed(Path p, float cx, float cy, float rx, float ry,
                       int lobes, float amplitude) {
        // Keep the shape inside its bounds: the peaks are at 1+amplitude.
        float sx = rx / (1f + amplitude);
        float sy = ry / (1f + amplitude);
        double phase = Math.toRadians(rotation);
        for (int i = 0; i <= SAMPLES; i++) {
            double t = 2 * Math.PI * i / SAMPLES;
            double r = 1 + amplitude * Math.cos(lobes * (t + phase));
            float x = (float) (cx + sx * r * Math.cos(t));
            float y = (float) (cy + sy * r * Math.sin(t));
            if (i == 0) p.moveTo(x, y); else p.lineTo(x, y);
        }
        p.close();
    }

    /** |x/a|^n + |y/b|^n = 1 — the rounded square Material leans on. */
    private void superellipse(Path p, float cx, float cy,
                              float rx, float ry, double n) {
        for (int i = 0; i <= SAMPLES; i++) {
            double t = 2 * Math.PI * i / SAMPLES;
            double ct = Math.cos(t), st = Math.sin(t);
            float x = (float) (cx + rx * Math.signum(ct)
                    * Math.pow(Math.abs(ct), 2.0 / n));
            float y = (float) (cy + ry * Math.signum(st)
                    * Math.pow(Math.abs(st), 2.0 / n));
            if (i == 0) p.moveTo(x, y); else p.lineTo(x, y);
        }
        p.close();
    }

    @Override
    public void setAlpha(int alpha) {
        paint.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        paint.setColorFilter(cf);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
