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
    private static final int MAX_MPH = 120;
    private static final float KPH_PER_MPH = 1.609344f;

    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint value = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ticks = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint number = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint unit = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arc = new RectF();

    private float kph = 0f;
    private float shown = 0f;
    private boolean hasFix;
    private boolean mph;
    private int max = MAX_KPH;
    private boolean digitsOnly;

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

    /** Display unit only — the source always speaks km/h. */
    public void setUnit(boolean useMph) {
        mph = useMph;
        if (max == MAX_KPH || max == MAX_MPH) max = useMph ? MAX_MPH : MAX_KPH;
        invalidate();
    }

    /** Full-scale in the display unit; ticks fall every 20. */
    public void setScale(int fullScale) {
        max = Math.max(20, fullScale);
        invalidate();
    }

    /** Just the number, bigger — for people who never look at the arc. */
    public void setDigitsOnly(boolean digits) {
        digitsOnly = digits;
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

        // Ease toward the target so the needle doesn't twitch on every
        // GPS update; ~4 frames to settle, which reads as smooth without
        // feeling laggy.
        shown += (kph - shown) * 0.25f;
        if (Math.abs(kph - shown) < 0.2f) shown = kph;

        // The gauge is laid out in the display unit so the ticks land on
        // round numbers whichever one is chosen.
        float display = mph ? shown / KPH_PER_MPH : shown;

        if (digitsOnly) {
            number.setTextSize(size * 0.40f);
            unit.setTextSize(size * 0.085f);
            canvas.drawText(hasFix ? String.valueOf(Math.round(display)) : "--",
                    cx, cy + size * 0.13f, number);
            canvas.drawText(hasFix ? (mph ? "mph" : "km/h") : "no fix",
                    cx, cy + size * 0.27f, unit);
            if (shown != kph) postInvalidateOnAnimation();
            return;
        }

        canvas.drawArc(arc, START_ANGLE, SWEEP, false, track);
        float frac = Math.min(1f, display / max);
        // No fix means no number, so no arc either — a confident bar
        // beside "no fix" would be the gauge contradicting itself.
        if (hasFix && frac > 0.001f) {
            canvas.drawArc(arc, START_ANGLE, SWEEP * frac, false, value);
        }

        // Ticks every 20 units.
        for (int k = 0; k <= max; k += 20) {
            double a = Math.toRadians(START_ANGLE + SWEEP * (k / (float) max));
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
        String text = hasFix ? String.valueOf(Math.round(display)) : "--";
        canvas.drawText(text, cx, cy + size * 0.07f, number);
        // Keep the caption inside the ring: the arc's gap is at the
        // bottom and a longer string used to run straight through it.
        canvas.drawText(hasFix ? (mph ? "mph" : "km/h") : "no fix",
                cx, cy + size * 0.20f, unit);

        // The card is taller than the dial; say where the number comes
        // from in the room above it, when there is room.
        float headroom = cy - r - stroke;
        if (headroom > size * 0.12f) {
            unit.setTextSize(size * 0.06f);
            canvas.drawText("GPS", cx, headroom / 2f + unit.getTextSize() * 0.35f, unit);
        }

        if (shown != kph) postInvalidateOnAnimation();
    }
}
