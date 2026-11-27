package ie.claudius.cardash;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

import ie.claudius.cardash.vehicle.VehicleState;

/**
 * Top-down car, front up: each door and the boot drawn open (warning
 * colour), shut (solid) or unknown (outline only) — an unreported door
 * is never drawn as closed.
 */
public final class DoorsView extends android.view.View {

    private final float d;
    private final Paint body = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint part = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final int shut, unknown;
    private VehicleState.Tri fl = VehicleState.Tri.UNKNOWN, fr = fl, rl = fl, rr = fl, boot = fl;

    public DoorsView(Context ctx, int bodyColor, int shutColor, int unknownColor, int textColor) {
        super(ctx);
        d = ctx.getResources().getDisplayMetrics().density;
        body.setColor(bodyColor);
        shut = shutColor;
        unknown = unknownColor;
        text.setColor(textColor);
        text.setTypeface(Fonts.display(ctx));
        text.setTextAlign(Paint.Align.CENTER);
    }

    public void setState(VehicleState s) {
        if (s == null) {
            fl = fr = rl = rr = boot = VehicleState.Tri.UNKNOWN;
        } else {
            fl = s.doorFrontLeft; fr = s.doorFrontRight; rl = s.doorRearLeft;
            rr = s.doorRearRight; boot = s.boot;
        }
        invalidate();
    }

    private void paint(Canvas c, VehicleState.Tri t, float l, float tp, float rt, float b) {
        r.set(l, tp, rt, b);
        if (t == VehicleState.Tri.UNKNOWN) {
            part.setStyle(Paint.Style.STROKE);
            part.setStrokeWidth(1.5f * d);
            part.setColor(unknown);
        } else {
            part.setStyle(Paint.Style.FILL);
            part.setColor(t == VehicleState.Tri.YES ? 0xFFFF5449 : shut);
        }
        c.drawRoundRect(r, 5 * d, 5 * d, part);
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        float label = Math.min(18 * d, h * 0.12f);
        float ch = h - label - 12 * d;
        float cw = Math.min(w - 16 * d, ch * 0.5f);
        if (cw <= 0 || ch <= 0) return;
        float cx = w / 2f, top = 6 * d, left = cx - cw / 2f;
        r.set(left + cw * 0.12f, top, left + cw * 0.88f, top + ch);
        c.drawRoundRect(r, cw * 0.28f, cw * 0.28f, body);
        float dw = cw * 0.2f, dh = ch * 0.2f, g = 3 * d;
        float y1 = top + ch * 0.26f, y2 = top + ch * 0.50f;
        paint(c, fl, left - dw * 0.3f, y1, left + dw * 0.7f, y1 + dh);
        paint(c, fr, left + cw - dw * 0.7f, y1, left + cw + dw * 0.3f, y1 + dh);
        paint(c, rl, left - dw * 0.3f, y2 + g, left + dw * 0.7f, y2 + g + dh);
        paint(c, rr, left + cw - dw * 0.7f, y2 + g, left + cw + dw * 0.3f, y2 + g + dh);
        paint(c, boot, cx - cw * 0.26f, top + ch * 0.82f, cx + cw * 0.26f, top + ch * 0.95f);
        text.setTextSize(Math.max(12 * d, label));
        int open = (fl == VehicleState.Tri.YES ? 1 : 0) + (fr == VehicleState.Tri.YES ? 1 : 0)
                + (rl == VehicleState.Tri.YES ? 1 : 0) + (rr == VehicleState.Tri.YES ? 1 : 0)
                + (boot == VehicleState.Tri.YES ? 1 : 0);
        boolean any = fl != VehicleState.Tri.UNKNOWN || fr != VehicleState.Tri.UNKNOWN
                || rl != VehicleState.Tri.UNKNOWN || rr != VehicleState.Tri.UNKNOWN
                || boot != VehicleState.Tri.UNKNOWN;
        c.drawText(!any ? "Doors" : open == 0 ? "All closed" : open == 1 ? "1 open" : open + " open",
                cx, h - 6 * d, text);
    }
}
