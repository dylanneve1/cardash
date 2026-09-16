package ie.claudius.cardash;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

/**
 * Transport icons drawn as paths rather than typed as characters.
 *
 * U+23EE / U+23ED render as tofu on plenty of head-unit ROMs, whose
 * font sets are cut down to save space. Drawing them removes the
 * question entirely, and they scale cleanly to any tile size.
 */
public final class Glyph extends Drawable {

    public enum Kind { PREV, PLAY, PAUSE, NEXT, GEAR }

    private final Kind kind;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    public Glyph(Kind kind, int color) {
        this.kind = kind;
        paint.setColor(color);
        paint.setStyle(Paint.Style.FILL);
    }

    @Override
    public void draw(Canvas canvas) {
        Rect b = getBounds();
        float w = b.width();
        float h = b.height();
        // Work in a centred square so the icon stays balanced whatever
        // shape the button is.
        float s = Math.min(w, h) * 0.46f;
        float cx = b.exactCenterX();
        float cy = b.exactCenterY();

        path.reset();
        switch (kind) {
            case PLAY:
                // Slightly narrower than tall reads better optically.
                path.moveTo(cx - s * 0.42f, cy - s);
                path.lineTo(cx + s * 0.78f, cy);
                path.lineTo(cx - s * 0.42f, cy + s);
                path.close();
                break;
            case PAUSE:
                float bw = s * 0.30f;
                canvas.drawRect(cx - s * 0.62f, cy - s, cx - s * 0.62f + bw,
                        cy + s, paint);
                canvas.drawRect(cx + s * 0.62f - bw, cy - s, cx + s * 0.62f,
                        cy + s, paint);
                break;
            case GEAR:
                gear(cx, cy, s);
                break;
            case PREV:
                triangle(cx + s * 0.9f, cy, -s, s);
                triangle(cx + s * 0.05f, cy, -s, s);
                canvas.drawRect(cx - s * 0.95f, cy - s,
                        cx - s * 0.72f, cy + s, paint);
                break;
            default: // NEXT
                triangle(cx - s * 0.9f, cy, s, s);
                triangle(cx - s * 0.05f, cy, s, s);
                canvas.drawRect(cx + s * 0.72f, cy - s,
                        cx + s * 0.95f, cy + s, paint);
                break;
        }
        canvas.drawPath(path, paint);
    }

    /**
     * Eight rounded teeth unioned onto a disc, with the hub punched out.
     * Built with Path.op so the overlaps don't turn into even-odd holes.
     */
    private void gear(float cx, float cy, float s) {
        path.addCircle(cx, cy, s * 0.68f, Path.Direction.CW);
        Path tooth = new Path();
        Matrix m = new Matrix();
        for (int i = 0; i < 8; i++) {
            tooth.reset();
            tooth.addRoundRect(cx - s * 0.18f, cy - s, cx + s * 0.18f, cy - s * 0.5f,
                    s * 0.09f, s * 0.09f, Path.Direction.CW);
            m.setRotate(i * 45f, cx, cy);
            tooth.transform(m);
            path.op(tooth, Path.Op.UNION);
        }
        Path hub = new Path();
        hub.addCircle(cx, cy, s * 0.28f, Path.Direction.CW);
        path.op(hub, Path.Op.DIFFERENCE);
    }

    /** Triangle with its apex at (tipX ± width) and base at tipX. */
    private void triangle(float baseX, float cy, float width, float half) {
        path.moveTo(baseX, cy - half);
        path.lineTo(baseX + width, cy);
        path.lineTo(baseX, cy + half);
        path.close();
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
