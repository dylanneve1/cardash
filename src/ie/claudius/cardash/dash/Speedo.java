package ie.claudius.cardash.dash;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

/**
 * A speedometer.
 *
 * Fed by GPS rather than the car, deliberately: no canbox is fitted and
 * an OBD dongle isn't plugged in, but the unit already has a GPS fix,
 * and `Location.getSpeed()` on a modern chipset is Doppler-derived
 * rather than differentiated positions — so it is smooth and accurate
 * once moving, and it needs no hardware at all.
 *
 * It is honest about the two things GPS does badly: it reads zero until
 * the fix has velocity, and it lags a hard brake by a second or so.
 * Hence the "GPS" label — this is an indication, not the legal speed.
 */
public class Speedo extends View {

    private static final float START_ANGLE = 140f;
    private static final float SWEEP = 260f;
    private static final int MAX_KPH = 180;

    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint value = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ticks = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint number = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint unit = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arc = new RectF();

    private float kph = 0f;
    private float shown = 0f;
    private boolean hasFix;

    public Speedo(Context ctx) {
        super(ctx);
        track.setStyle(Paint.Style.STROKE);
        track.setStrokeCap(Paint.Cap.ROUND);
        value.setStyle(Paint.Style.STROKE);
        value.setStrokeCap(Paint.Cap.ROUND);
        ticks.setStyle(Paint.Style.STROKE);
        ticks.setStrokeCap(Paint.Cap.ROUND);
        number.setTextAlign(Paint.Align.CENTER);
        unit.setTextAlign(Paint.Align.CENTER);
    }

    public void setColors(int trackColor, int valueColor,
                          int numberColor, int labelColor) {
        track.setColor(trackColor);
        value.setColor(valueColor);
        ticks.setColor(labelColor);
        number.setColor(numberColor);
        unit.setColor(labelColor);
        invalidate();
    }

    public void setTypefaces(Typeface big, Typeface small) {
        number.setTypeface(big);
        unit.setTypeface(small);
        invalidate();
    }

    public void setSpeed(float newKph, boolean fix) {
        kph = Math.max(0f, newKph);
        hasFix = fix;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight();
        float size = Math.min(w, h);
        if (size <= 0) return;

        float stroke = size * 0.075f;
        track.setStrokeWidth(stroke);
        value.setStrokeWidth(stroke);
        ticks.setStrokeWidth(Math.max(2f, size * 0.008f));

        float inset = stroke * 0.7f + size * 0.06f;
        float cx = w / 2f, cy = h / 2f;
        float r = size / 2f - inset;
        arc.set(cx - r, cy - r, cx + r, cy + r);

        canvas.drawArc(arc, START_ANGLE, SWEEP, false, track);

        // Ease toward the target so the needle doesn't twitch on every
        // GPS update; ~4 frames to settle, which reads as smooth without
        // feeling laggy.
        shown += (kph - shown) * 0.25f;
        if (Math.abs(kph - shown) < 0.2f) shown = kph;

        float frac = Math.min(1f, shown / MAX_KPH);
        if (frac > 0.001f) {
            canvas.drawArc(arc, START_ANGLE, SWEEP * frac, false, value);
        }

        // Ticks every 20 km/h.
        for (int k = 0; k <= MAX_KPH; k += 20) {
            double a = Math.toRadians(START_ANGLE + SWEEP * (k / (float) MAX_KPH));
            float inner = r - stroke * 0.85f;
            float outer = r - stroke * 1.35f;
            canvas.drawLine(
                    cx + (float) (Math.cos(a) * outer),
                    cy + (float) (Math.sin(a) * outer),
                    cx + (float) (Math.cos(a) * inner),
                    cy + (float) (Math.sin(a) * inner), ticks);
        }

        number.setTextSize(size * 0.28f);
        unit.setTextSize(size * 0.072f);
        String text = hasFix ? String.valueOf(Math.round(shown)) : "--";
        canvas.drawText(text, cx, cy + size * 0.07f, number);
        // Keep the caption inside the ring: the arc's gap is at the
        // bottom and a longer string used to run straight through it.
        canvas.drawText(hasFix ? "km/h" : "no fix",
                cx, cy + size * 0.20f, unit);

        if (shown != kph) postInvalidateOnAnimation();
    }
}
