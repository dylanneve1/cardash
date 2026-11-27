package ie.claudius.cardash;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;

import ie.claudius.cardash.car.CarGeometry;
import ie.claudius.cardash.vehicle.VehicleState;

/**
 * The car seen from above, front up, in thin double-stroke lines: doors
 * swing out around their front hinge and turn orange with a gap left in
 * the body, the tailgate reaches past the rear, the bonnet panel is
 * outlined. A door nobody has reported is drawn broken (dashed), never
 * as shut. The shapes are in {@link CarGeometry}.
 */
public final class CarView extends android.view.View {

    public static final int LINE = 0xFFEBEEF2, DIM = 0xFF78808C, OPEN = 0xFFFFAA28;

    private static final int FL = 0, FR = 1, RL = 2, RR = 3, BOOT = 4, BONNET = 5, PARTS = 6;
    private static final long SWING_MS = 250L;

    private final float d;
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final int background;
    private final VehicleState.Tri[] parts = new VehicleState.Tri[PARTS];
    private final float[] open = new float[PARTS];
    private final float[] from = new float[PARTS];
    private ValueAnimator anim;
    private boolean threeDoor;
    private boolean caption = true;
    private boolean shownOnce;

    /** @param background the colour behind the car: used to cut the gap an open door leaves */
    public CarView(Context ctx, int background) {
        super(ctx);
        d = ctx.getResources().getDisplayMetrics().density;
        this.background = background;
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeJoin(Paint.Join.ROUND);
        text.setColor(DIM);
        text.setTypeface(Fonts.display(ctx));
        text.setTextAlign(Paint.Align.CENTER);
        for (int i = 0; i < PARTS; i++) parts[i] = VehicleState.Tri.UNKNOWN;
        setContentDescription("Car doors");
    }

    public void setCaption(boolean on) {
        caption = on;
        invalidate();
    }

    public void setThreeDoor(boolean value) {
        if (value == threeDoor) return;
        threeDoor = value;
        invalidate();
    }

    public void setState(VehicleState s) {
        VehicleState.Tri u = VehicleState.Tri.UNKNOWN;
        VehicleState.Tri[] now = s == null ? new VehicleState.Tri[] { u, u, u, u, u, u }
                : new VehicleState.Tri[] { s.doorFrontLeft, s.doorFrontRight, s.doorRearLeft,
                        s.doorRearRight, s.boot, s.bonnet };
        boolean changed = false, moved = false;
        for (int i = 0; i < PARTS; i++) {
            changed |= now[i] != parts[i];
            moved |= (now[i] == VehicleState.Tri.YES) != (parts[i] == VehicleState.Tri.YES);
            parts[i] = now[i];
        }
        if (!changed) return;
        if (moved) swing();
        shownOnce = true;
        invalidate();
    }

    /** Move every part from where it is now to where it should be; Calm motion just puts it there. */
    private void swing() {
        if (anim != null) anim.cancel();
        if (Motion.isCalm() || !shownOnce || !isShown()) {
            for (int i = 0; i < PARTS; i++) open[i] = target(i);
            return;
        }
        System.arraycopy(open, 0, from, 0, PARTS);
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(SWING_MS);
        anim.setInterpolator(Motion.STANDARD);
        anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                float t = (Float) a.getAnimatedValue();
                for (int i = 0; i < PARTS; i++) open[i] = from[i] + (target(i) - from[i]) * t;
                invalidate();
            }
        });
        anim.start();
    }

    private float target(int i) {
        return parts[i] == VehicleState.Tri.YES ? 1f : 0f;
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        float label = caption ? Math.max(12 * d, Math.min(18 * d, h * 0.07f)) : 0f;
        float ch = h - (caption ? label + 14 * d : 0f) - 8 * d, cw = w - 8 * d;
        float[] b = CarGeometry.BOUNDS;
        float s = Math.min(cw / (b[2] - b[0]), ch / (b[3] - b[1]));
        if (s <= 0) return;
        // Line widths are the design's dp, thinned when the car is drawn very small.
        float k = d * Math.min(1f, s / (0.7f * d)) / s;

        c.save();
        c.translate(w / 2f - CarGeometry.CX * s, 4 * d + (ch - (b[3] - b[1]) * s) / 2f - b[1] * s);
        c.scale(s, s);

        stroke(c, CarGeometry.body(), true, LINE, 2.4f * k);
        stroke(c, CarGeometry.inner(), true, DIM, 1.4f * k);
        stroke(c, CarGeometry.bonnetLine(), false, LINE, 2f * k);
        if (open[BONNET] > 0f) {
            int col = fade(OPEN, open[BONNET]);
            stroke(c, CarGeometry.bonnetPanel(), true, col, 2.4f * k);
            stroke(c, CarGeometry.bonnetLine(), false, col, 2.2f * k);
        }
        stroke(c, CarGeometry.windscreen(), true, LINE, 2f * k);
        stroke(c, CarGeometry.roofEdge(CarGeometry.LEFT), false, DIM, 1.4f * k);
        stroke(c, CarGeometry.roofEdge(CarGeometry.RIGHT), false, DIM, 1.4f * k);
        stroke(c, CarGeometry.rearWindow(), true, LINE, 2f * k);

        if (open[BOOT] > 0f) {
            stroke(c, CarGeometry.tailgate(open[BOOT]), true, fade(OPEN, open[BOOT]), 2.6f * k);
        }
        if (open[BOOT] < 1f) {
            line.setPathEffect(parts[BOOT] == VehicleState.Tri.UNKNOWN ? dashes() : null);
            stroke(c, CarGeometry.tailLine(), false, fade(DIM, 1f - open[BOOT]), 1.4f * k);
            line.setPathEffect(null);
        }
        stroke(c, CarGeometry.mirror(CarGeometry.LEFT), false, LINE, 2f * k);
        stroke(c, CarGeometry.mirror(CarGeometry.RIGHT), false, LINE, 2f * k);

        door(c, FL, CarGeometry.LEFT, CarGeometry.frontDoor(threeDoor), k);
        door(c, FR, CarGeometry.RIGHT, CarGeometry.frontDoor(threeDoor), k);
        if (!threeDoor) {
            door(c, RL, CarGeometry.LEFT, CarGeometry.rearDoor(), k);
            door(c, RR, CarGeometry.RIGHT, CarGeometry.rearDoor(), k);
        }
        c.restore();

        if (caption) {
            String t = summary();
            text.setTextSize(label);
            text.setColor(anyOpen() ? OPEN : DIM);
            c.drawText(t, w / 2f, h - 8 * d, text);
        }
    }

    private void door(Canvas c, int part, int side, float[] span, float k) {
        float x = CarGeometry.CX + side * CarGeometry.HALF;
        float p = open[part];
        boolean unknown = parts[part] == VehicleState.Tri.UNKNOWN;
        if (p > 0f || unknown) {
            // The gap in the body where the door was.
            line.setStrokeCap(Paint.Cap.BUTT);
            stroke(c, new float[] { x, span[0] + 2, x, span[1] - 2 }, false,
                    unknown ? background : fade(background, p), 5f * Math.max(1f, k));
        }
        if (unknown) {
            line.setPathEffect(dashes());
            stroke(c, new float[] { x, span[0] + 2, x, span[1] - 2 }, false, DIM, 1.6f * k);
            line.setPathEffect(null);
        } else if (p > 0f) {
            stroke(c, CarGeometry.door(side, span, p), true, fade(OPEN, Math.min(1f, 0.3f + p)), 2.6f * k);
        } else {
            stroke(c, new float[] { x - side * 3, span[0], x + side * 1.5f, span[0] }, false, DIM, 1.4f * k);
            stroke(c, new float[] { x - side * 3, span[1], x + side * 1.5f, span[1] }, false, DIM, 1.4f * k);
        }
    }

    private static DashPathEffect dashes() {
        return new DashPathEffect(new float[] { 6f, 6f }, 0f);
    }

    private static int fade(int color, float a) {
        return M3.withAlpha(color, Math.round(255 * Math.max(0f, Math.min(1f, a))));
    }

    private void stroke(Canvas c, float[] pts, boolean closed, int color, float width) {
        path.rewind();
        path.moveTo(pts[0], pts[1]);
        for (int i = 2; i < pts.length; i += 2) path.lineTo(pts[i], pts[i + 1]);
        if (closed) path.close();
        line.setColor(color);
        line.setStrokeWidth(width);
        c.drawPath(path, line);
    }

    private boolean drawn(int i) {
        return !(threeDoor && (i == RL || i == RR));
    }

    private boolean anyOpen() {
        for (int i = 0; i < PARTS; i++) if (drawn(i) && parts[i] == VehicleState.Tri.YES) return true;
        return false;
    }

    private String summary() {
        int openCount = 0, unknown = 0, total = 0;
        for (int i = 0; i < PARTS; i++) {
            if (!drawn(i)) continue;
            if (parts[i] == VehicleState.Tri.YES) openCount++;
            if (i == BONNET) continue;   // few cars report it: its silence is not news
            total++;
            if (parts[i] == VehicleState.Tri.UNKNOWN) unknown++;
        }
        if (openCount > 0) return openCount == 1 ? openName() : openCount + " open";
        if (unknown == total) return "Doors: no data";
        return unknown > 0 ? "Some doors unknown" : "All closed";
    }

    private String openName() {
        String[] names = { "Front left open", "Front right open", "Rear left open", "Rear right open",
                "Boot open", "Bonnet open" };
        for (int i = 0; i < PARTS; i++) if (drawn(i) && parts[i] == VehicleState.Tri.YES) return names[i];
        return "";
    }
}
