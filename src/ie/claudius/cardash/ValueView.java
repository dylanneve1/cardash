package ie.claudius.cardash;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

import ie.claudius.cardash.layout.Kind;

/**
 * One vehicle value drawn as a bar or a gauge between a minimum and a
 * maximum, turning to the warning colour past a threshold. Unknown
 * stays unknown: no value draws an empty track and a dash, never zero.
 */
public final class ValueView extends android.view.View {

    public static final int WARN = 0xFFFF5449;

    private final float d;
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mark = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint big = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint small = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();

    private String label = "";
    private String unit = "";
    private boolean gauge;
    private float min, max = 100, warn = 90;
    private String warnDir = "above";
    private float value;
    private boolean known;
    /** Shown under the dash when the car cannot give this value, e.g. "Unavailable". */
    private String note;
    private int accent, warnColor = WARN;

    public ValueView(Context ctx, int trackColor, int accent, int text, int subtext) {
        super(ctx);
        d = ctx.getResources().getDisplayMetrics().density;
        this.accent = accent;
        track.setColor(trackColor);
        track.setStrokeCap(Paint.Cap.ROUND);
        mark.setColor(subtext);
        big.setColor(text);
        big.setTypeface(Fonts.hero(ctx));
        big.setTextAlign(Paint.Align.CENTER);
        small.setColor(subtext);
        small.setTypeface(Fonts.display(ctx));
        small.setTextAlign(Paint.Align.CENTER);
    }

    public void configure(String label, String unit, boolean gauge,
                          float min, float max, float warn, String warnDir) {
        this.label = label;
        this.unit = unit;
        this.gauge = gauge;
        this.min = min;
        this.max = max;
        this.warn = warn;
        this.warnDir = warnDir;
        invalidate();
    }

    public void setValue(boolean known, float value) {
        setValue(known, value, null);
    }

    /** Relabel (the outside temperature card becomes "Intake air" while that stands in for it). */
    public void setLabel(String label) {
        if (!label.equals(this.label)) {
            this.label = label;
            invalidate();
        }
    }

    public void setValue(boolean known, float value, String note) {
        if (known == this.known && value == this.value
                && (note == null ? this.note == null : note.equals(this.note))) return;
        this.note = note;
        this.known = known;
        this.value = value;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        boolean hot = known && Kind.warning(value, warn, warnDir);
        fill.setColor(hot ? warnColor : accent);
        String text = known ? String.valueOf(Math.round(value)) : "—";
        float frac = known ? Kind.fraction(value, min, max) : 0f;
        float wfrac = Kind.fraction(warn, min, max);

        if (gauge) {
            float labelH = Math.min(h * 0.14f, 18 * d);
            float s = Math.min(w, h - labelH) - 20 * d;
            if (s <= 0) return;
            float stroke = s * 0.11f;
            float cx = w / 2f, cy = (h - labelH) / 2f + 4 * d;
            r.set(cx - s / 2f + stroke / 2f, cy - s / 2f + stroke / 2f,
                    cx + s / 2f - stroke / 2f, cy + s / 2f - stroke / 2f);
            track.setStyle(Paint.Style.STROKE);
            track.setStrokeWidth(stroke);
            fill.setStyle(Paint.Style.STROKE);
            fill.setStrokeCap(Paint.Cap.ROUND);
            fill.setStrokeWidth(stroke);
            c.drawArc(r, 135f, 270f, false, track);
            if (frac > 0f) c.drawArc(r, 135f, 270f * frac, false, fill);
            // Warning threshold tick on the rim.
            double a = Math.toRadians(135 + 270 * wfrac);
            float rr = s / 2f - stroke / 2f;
            mark.setStrokeWidth(2 * d);
            c.drawLine(cx + (float) Math.cos(a) * (rr - stroke * 0.75f), cy + (float) Math.sin(a) * (rr - stroke * 0.75f),
                    cx + (float) Math.cos(a) * (rr + stroke * 0.75f), cy + (float) Math.sin(a) * (rr + stroke * 0.75f), mark);
            big.setTextSize(s * 0.30f);
            c.drawText(text, cx, cy + s * 0.10f, big);
            small.setTextSize(Math.max(11 * d, s * 0.09f));
            c.drawText(known ? unit.trim() : note != null ? note : "", cx, cy + s * 0.27f, small);
            small.setTextSize(Math.max(12 * d, Math.min(labelH, 16 * d)));
            c.drawText(label, cx, h - 8 * d, small);
        } else {
            float p = 14 * d;
            small.setTextAlign(Paint.Align.LEFT);
            big.setTextAlign(Paint.Align.LEFT);
            float barH = Math.max(6 * d, Math.min(h * 0.16f, 18 * d));
            float lab = Math.max(12 * d, Math.min(h * 0.17f, 17 * d));
            small.setTextSize(lab);
            c.drawText(label, p, p + lab * 0.85f, small);
            float valueSize = Math.max(18 * d, Math.min(h - p * 2 - lab - barH - 8 * d, 56 * d));
            big.setTextSize(valueSize);
            float baseY = h - p - barH - 6 * d;
            c.drawText(text, p, baseY, big);
            if (known || note != null) {
                float tw = big.measureText(text);
                small.setTextSize(Math.max(12 * d, valueSize * (known ? 0.32f : 0.28f)));
                c.drawText(known ? unit.trim() : note, p + tw + 6 * d, baseY, small);
            }
            float top = h - p - barH;
            track.setStyle(Paint.Style.FILL);
            r.set(p, top, w - p, top + barH);
            c.drawRoundRect(r, barH / 2f, barH / 2f, track);
            if (frac > 0f) {
                fill.setStyle(Paint.Style.FILL);
                r.set(p, top, p + (w - 2 * p) * frac, top + barH);
                if (r.width() < barH) r.right = r.left + barH;
                c.drawRoundRect(r, barH / 2f, barH / 2f, fill);
            }
            float mx = p + (w - 2 * p) * wfrac;
            mark.setStrokeWidth(2 * d);
            c.drawLine(mx, top - 3 * d, mx, top + barH + 3 * d, mark);
            small.setTextAlign(Paint.Align.CENTER);
            big.setTextAlign(Paint.Align.CENTER);
        }
    }
}
