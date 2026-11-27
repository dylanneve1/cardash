package ie.claudius.cardash;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Region;
import android.text.TextPaint;
import android.text.TextUtils;

import ie.claudius.cardash.layout.Kind;
import ie.claudius.cardash.layout.Pill;

/**
 * One value, drawn as a pill bar or a gauge between a minimum and a
 * maximum. The pill is the bar: its background fills from the left in
 * the accent container colour, with the label and the value on top, each
 * in a colour that reads on whichever part is behind it. Past the
 * warning threshold the pill's fill (or the gauge's whole box) turns to
 * the M3 error colours. Unknown stays unknown: no value draws an empty
 * track and a dash, never zero.
 */
public final class ValueView extends android.view.View {

    private final float d;
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mark = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint big = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint small = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final Path fillPath = new Path();

    private String label = "";
    private String unit = "";
    private boolean gauge, bar = true;
    private float min, max = 100, warn = 90;
    private String warnDir = "above";
    private float value;
    private boolean known;
    /** The value as written. */
    private String shown = "—";
    /** Shown beside the dash when the car cannot give this value, e.g. "Unavailable". */
    private String note;
    /** A second, quieter piece of information (the trip's time and average). */
    private String detail;
    private boolean hot;
    private int accent, trackColor, text, subtext;
    private android.graphics.drawable.Drawable calmBox, hotBox;
    private int hotAccent, onHot;
    private int pillTrack, pillFill, pillWarn, onTrack, onTrackDim, onFill, onWarn;

    public ValueView(Context ctx) {
        super(ctx);
        d = ctx.getResources().getDisplayMetrics().density;
        track.setStrokeCap(Paint.Cap.ROUND);
        big.setFontFeatureSettings("tnum");
        big.setTypeface(Fonts.hero(ctx));
        small.setTypeface(Fonts.display(ctx));
    }

    /** The gauge's colours and the card behind it, normally and past the warning threshold. */
    public void setGaugeColors(int trackColor, int accent, int text, int subtext,
                               android.graphics.drawable.Drawable normal, android.graphics.drawable.Drawable warning,
                               int warningAccent, int onWarning) {
        this.trackColor = trackColor;
        this.accent = accent;
        this.text = text;
        this.subtext = subtext;
        calmBox = normal;
        hotBox = warning;
        hotAccent = warningAccent;
        onHot = onWarning;
        applyBox();
    }

    /** The pill's colours; text colours are swapped for black or white where they would not read. */
    public void setPillColors(int track, int fill, int warning, int onTrack, int onTrackDim,
                              int onFill, int onWarning) {
        pillTrack = track;
        pillFill = fill;
        pillWarn = warning;
        this.onTrack = Pill.readable(track, onTrack);
        this.onTrackDim = Pill.readable(track, onTrackDim);
        this.onFill = Pill.readable(fill, onFill);
        this.onWarn = Pill.readable(warning, onWarning);
        invalidate();
    }

    public void configure(String label, String unit, boolean gauge, boolean bar,
                          float min, float max, float warn, String warnDir) {
        this.label = label;
        this.unit = unit;
        this.gauge = gauge;
        this.bar = bar;
        this.min = min;
        this.max = max;
        this.warn = warn;
        this.warnDir = warnDir;
        update();
        applyBox();
        invalidate();
    }

    /** Relabel (the outside temperature card becomes "Intake air" while that stands in for it). */
    public void setLabel(String label) {
        if (!label.equals(this.label)) {
            this.label = label;
            invalidate();
        }
    }

    /**
     * @param value in the units shown
     * @param text  the value as written
     */
    public void setValue(boolean known, float value, String text, String note) {
        String s = known ? text : "—";
        if (known == this.known && value == this.value && s.equals(shown) && same(note, this.note)) return;
        this.note = note;
        this.known = known;
        this.value = value;
        shown = s;
        update();
        invalidate();
    }

    /**
     * A value that is words, not a number (the emissions readiness).
     *
     * @param value only for the warning threshold
     * @param tail  said after the words, where a unit would be
     */
    public void setStatus(float value, String text, String tail) {
        if (known && value == this.value && text.equals(shown) && tail.equals(unit)) return;
        known = true;
        this.value = value;
        shown = text;
        unit = tail;
        note = null;
        update();
        invalidate();
    }

    /** A pill that only says something: no range, no fill (the trip). */
    public void setInfo(String value, String unit, String detail) {
        if (known && value.equals(shown) && unit.equals(this.unit) && same(detail, this.detail)) return;
        known = true;
        bar = false;
        shown = value;
        this.unit = unit;
        this.detail = detail;
        invalidate();
    }

    private static boolean same(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    private void update() {
        boolean now = known && Kind.warningHeld(hot, value, warn, warnDir, min, max);
        if (now == hot) return;
        hot = now;
        applyBox();
    }

    /** A gauge sits on a card of its own; a pill is its own background. */
    private void applyBox() {
        setBackground(gauge ? (hot ? hotBox : calmBox) : null);
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        if (gauge) drawGauge(c, w, h);
        else drawPill(c, w, h);
    }

    private void drawGauge(Canvas c, float w, float h) {
        boolean red = hot && hotBox != null;
        fill.setColor(red ? hotAccent : accent);
        track.setColor(red ? M3.withAlpha(onHot, 0x33) : trackColor);
        big.setColor(red ? onHot : text);
        small.setColor(red ? onHot : subtext);
        mark.setColor(red ? onHot : subtext);
        big.setTextAlign(Paint.Align.CENTER);
        small.setTextAlign(Paint.Align.CENTER);
        float frac = known ? Kind.fraction(value, min, max) : 0f;
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
        if (!"never".equals(warnDir)) {
            double a = Math.toRadians(135 + 270 * Kind.fraction(warn, min, max));
            float rr = s / 2f - stroke / 2f;
            mark.setStrokeWidth(2 * d);
            c.drawLine(cx + (float) Math.cos(a) * (rr - stroke * 0.75f), cy + (float) Math.sin(a) * (rr - stroke * 0.75f),
                    cx + (float) Math.cos(a) * (rr + stroke * 0.75f), cy + (float) Math.sin(a) * (rr + stroke * 0.75f), mark);
        }
        big.setTextSize(s * 0.30f);
        float room = (s - 2 * stroke) * 0.78f, wide = big.measureText(shown);
        if (wide > room) big.setTextSize(s * 0.30f * room / wide);   // four digits stay inside the ring
        c.drawText(shown, cx, cy + s * 0.10f, big);
        small.setTextSize(Math.max(11 * d, s * 0.09f));
        c.drawText(known ? unit : note != null ? note : "", cx, cy + s * 0.27f, small);
        small.setTextSize(Math.max(12 * d, Math.min(labelH, 16 * d)));
        c.drawText(label, cx, h - 8 * d, small);
    }

    private void drawPill(Canvas c, float w, float h) {
        float radius = Math.min(h / 2f, 28 * d);
        r.set(0, 0, w, h);
        track.setStyle(Paint.Style.FILL);
        track.setColor(pillTrack);
        c.drawRoundRect(r, radius, radius, track);
        // A counter has no scale to fill; it is all or nothing, for its warning.
        float frac = !known ? 0f : bar ? Kind.fraction(value, min, max) : hot ? 1f : 0f;
        float fw = Pill.fillWidth(frac, w, 2 * radius, hot);
        if (fw <= 0) {
            drawPillText(c, w, h, onTrack, onTrackDim);
            return;
        }
        r.set(0, 0, fw, h);
        fillPath.reset();
        fillPath.addRoundRect(r, radius, radius, Path.Direction.CW);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(hot ? pillWarn : pillFill);
        c.drawPath(fillPath, fill);
        // The text twice, each time clipped to one side of the fill's edge, in that side's colour.
        c.save();
        c.clipPath(fillPath, Region.Op.DIFFERENCE);
        drawPillText(c, w, h, onTrack, onTrackDim);
        c.restore();
        c.save();
        c.clipPath(fillPath);
        int on = hot ? onWarn : onFill;
        drawPillText(c, w, h, on, on);
        c.restore();
    }

    private void drawPillText(Canvas c, float w, float h, int main, int dim) {
        big.setColor(main);
        small.setColor(dim);
        big.setTextAlign(Paint.Align.LEFT);
        small.setTextAlign(Paint.Align.LEFT);
        String tail = known ? unit : note != null ? note : "";
        if (h >= Pill.STACK_DP * d) drawStacked(c, w, h, tail);
        else drawRow(c, w, h, tail);
    }

    /** Label over value, as a tall pill. */
    private void drawStacked(Canvas c, float w, float h, String tail) {
        float p = 22 * d, room = w - 2 * p;
        float lab = clamp(h * 0.16f, 14 * d, 20 * d);
        float val = clamp(h * 0.36f, 26 * d, 56 * d);
        float det = detail == null ? 0 : clamp(h * 0.13f, 13 * d, 18 * d);
        float gap = 6 * d;
        float detGap = 9 * d;
        float block = lab + gap + val * 0.9f + (det > 0 ? detGap + det : 0);
        float y = (h - block) / 2f + lab * 0.85f;
        small.setTextSize(lab);
        c.drawText(fit(label, small, room), p, y, small);
        y += gap + val * 0.9f;
        float tailSize = Math.max(13 * d, val * (known ? 0.42f : 0.34f));
        val = fitValue(val, tailSize, tail, room);
        big.setTextSize(val);
        c.drawText(shown, p, y, big);
        float used = big.measureText(shown) + 6 * d;
        small.setTextSize(tailSize);
        if (!tail.isEmpty()) c.drawText(fit(tail, small, room - used), p + used, y, small);
        if (det > 0) {
            small.setTextSize(det);
            c.drawText(fit(detail, small, room), p, y + detGap + det, small);
        }
    }

    /** Label on the left, value on the right. */
    private void drawRow(Canvas c, float w, float h, String tail) {
        float p = clamp(Math.min(h * 0.34f, w * 0.09f), 12 * d, 24 * d), room = w - 2 * p;
        float lab = clamp(h * 0.28f, 12 * d, 19 * d);
        float val = clamp(h * 0.42f, 15 * d, 32 * d);
        float tailSize = Math.max(11 * d, val * 0.6f);
        // The value is what is being read: it gives way to the label only past 60% of the pill.
        val = fitValue(val, tailSize, tail, room * 0.6f);
        small.setTextSize(tailSize);
        float tw = tail.isEmpty() ? 0 : Math.min(small.measureText(tail), room * 0.4f);
        String t = tail.isEmpty() ? "" : fit(tail, small, tw + 1);
        float base = h / 2f + val * 0.36f;
        float x = w - p;
        if (!t.isEmpty()) {
            x -= small.measureText(t);
            c.drawText(t, x, base, small);
            x -= 4 * d;
        }
        big.setTextSize(val);
        x -= big.measureText(shown);
        c.drawText(shown, x, base, big);
        float left = x - p - 10 * d;
        // A tight label gets a little smaller before it is cut short.
        small.setTextSize(lab);
        float wide = small.measureText(label);
        if (wide > left && wide > 0) small.setTextSize(Math.max(12 * d, lab * left / wide * 0.98f));
        String name = fit(label, small, left);
        c.drawText(name, p, h / 2f + lab * 0.36f, small);
        if (detail != null) {
            float after = left - small.measureText(name) - 14 * d;
            small.setTextSize(Math.max(11 * d, lab * 0.85f));
            float dw = small.measureText(detail);
            if (dw <= after) c.drawText(detail, x - 12 * d - dw, h / 2f + lab * 0.36f, small);
        }
    }

    /** The value size at which value and unit fit {@code room}, never under 12dp. */
    private float fitValue(float size, float tailSize, String tail, float room) {
        small.setTextSize(tailSize);
        float tw = tail.isEmpty() || !known ? 0 : small.measureText(tail) + 6 * d;
        big.setTextSize(size);
        float wide = big.measureText(shown);
        if (wide > room - tw && wide > 0) size = Math.max(12 * d, size * (room - tw) / wide);
        return size;
    }

    private static String fit(String s, TextPaint p, float room) {
        return room <= 0 ? "" : TextUtils.ellipsize(s, p, room, TextUtils.TruncateAt.END).toString();
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
