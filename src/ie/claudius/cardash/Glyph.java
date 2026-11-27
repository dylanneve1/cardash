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

    public enum Kind { PREV, PLAY, PAUSE, NEXT, GEAR, PLUS, CLOSE, RESIZE,
        MINUS, CHECK, LEFT, RIGHT, UP, DOWN, MOVE, TRASH, PAGES, STYLE, RESET }

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
            case PLUS:
                path.addRoundRect(cx - s * 0.14f, cy - s * 0.72f, cx + s * 0.14f, cy + s * 0.72f,
                        s * 0.1f, s * 0.1f, Path.Direction.CW);
                path.addRoundRect(cx - s * 0.72f, cy - s * 0.14f, cx + s * 0.72f, cy + s * 0.14f,
                        s * 0.1f, s * 0.1f, Path.Direction.CW);
                break;
            case CLOSE:
                stroke(canvas, cx - s * 0.6f, cy - s * 0.6f, cx + s * 0.6f, cy + s * 0.6f, s * 0.26f);
                stroke(canvas, cx + s * 0.6f, cy - s * 0.6f, cx - s * 0.6f, cy + s * 0.6f, s * 0.26f);
                break;
            case RESIZE:
                // Three diagonal ticks hugging the bottom-right corner.
                stroke(canvas, cx + s * 0.7f, cy - s * 0.1f, cx - s * 0.1f, cy + s * 0.7f, s * 0.2f);
                stroke(canvas, cx + s * 0.7f, cy - s * 0.7f, cx - s * 0.7f, cy + s * 0.7f, s * 0.2f);
                break;
            case MINUS:
                path.addRoundRect(cx - s * 0.72f, cy - s * 0.14f, cx + s * 0.72f, cy + s * 0.14f,
                        s * 0.1f, s * 0.1f, Path.Direction.CW);
                break;
            case CHECK:
                lines(canvas, s * 0.24f, cx, cy, s, -0.7f, 0.05f, -0.2f, 0.55f, -0.2f, 0.55f, 0.75f, -0.5f);
                break;
            case LEFT:
                lines(canvas, s * 0.24f, cx, cy, s, 0.35f, -0.7f, -0.35f, 0f, -0.35f, 0f, 0.35f, 0.7f);
                break;
            case RIGHT:
                lines(canvas, s * 0.24f, cx, cy, s, -0.35f, -0.7f, 0.35f, 0f, 0.35f, 0f, -0.35f, 0.7f);
                break;
            case UP:
                lines(canvas, s * 0.24f, cx, cy, s, -0.7f, 0.35f, 0f, -0.35f, 0f, -0.35f, 0.7f, 0.35f);
                break;
            case DOWN:
                lines(canvas, s * 0.24f, cx, cy, s, -0.7f, -0.35f, 0f, 0.35f, 0f, 0.35f, 0.7f, -0.35f);
                break;
            case MOVE:
                // A cross with an arrowhead on each end.
                lines(canvas, s * 0.18f, cx, cy, s, -0.9f, 0f, 0.9f, 0f, 0f, -0.9f, 0f, 0.9f);
                lines(canvas, s * 0.18f, cx, cy, s, -0.3f, -0.62f, 0f, -0.92f, 0f, -0.92f, 0.3f, -0.62f,
                        -0.3f, 0.62f, 0f, 0.92f, 0f, 0.92f, 0.3f, 0.62f);
                lines(canvas, s * 0.18f, cx, cy, s, -0.62f, -0.3f, -0.92f, 0f, -0.92f, 0f, -0.62f, 0.3f,
                        0.62f, -0.3f, 0.92f, 0f, 0.92f, 0f, 0.62f, 0.3f);
                break;
            case TRASH:
                lines(canvas, s * 0.18f, cx, cy, s, -0.75f, -0.5f, 0.75f, -0.5f, -0.25f, -0.85f, 0.25f, -0.85f,
                        -0.55f, -0.5f, -0.45f, 0.85f, -0.45f, 0.85f, 0.45f, 0.85f, 0.45f, 0.85f, 0.55f, -0.5f);
                break;
            case PAGES:
                lines(canvas, s * 0.18f, cx, cy, s, -0.8f, -0.4f, 0.4f, -0.4f, 0.4f, -0.4f, 0.4f, 0.85f,
                        0.4f, 0.85f, -0.8f, 0.85f, -0.8f, 0.85f, -0.8f, -0.4f,
                        -0.4f, -0.8f, 0.8f, -0.8f, 0.8f, -0.8f, 0.8f, 0.45f);
                break;
            case STYLE:
                // A circle, half filled.
                path.addArc(cx - s * 0.8f, cy - s * 0.8f, cx + s * 0.8f, cy + s * 0.8f, 90f, 180f);
                path.close();
                ring(canvas, cx, cy, s * 0.8f, 0f, 360f, s * 0.16f);
                break;
            case RESET:
                // An anticlockwise arrow: back to how it was.
                ring(canvas, cx, cy, s * 0.7f, -150f, 300f, s * 0.2f);
                lines(canvas, s * 0.2f, cx, cy, s, -0.85f, -0.75f, -0.62f, -0.32f, -0.62f, -0.32f, -0.18f, -0.52f);
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

    private void stroke(Canvas canvas, float x0, float y0, float x1, float y1, float width) {
        Paint p = new Paint(paint);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(width);
        p.setStrokeCap(Paint.Cap.ROUND);
        canvas.drawLine(x0, y0, x1, y1, p);
    }

    /** Line segments given as x0, y0, x1, y1 ... in units of {@code s} about the centre. */
    private void lines(Canvas canvas, float width, float cx, float cy, float s, float... p) {
        for (int i = 0; i + 3 < p.length; i += 4) {
            stroke(canvas, cx + p[i] * s, cy + p[i + 1] * s, cx + p[i + 2] * s, cy + p[i + 3] * s, width);
        }
    }

    private void ring(Canvas canvas, float cx, float cy, float r, float from, float sweep, float width) {
        Paint p = new Paint(paint);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(width);
        p.setStrokeCap(Paint.Cap.ROUND);
        canvas.drawArc(cx - r, cy - r, cx + r, cy + r, from, sweep, false, p);
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
