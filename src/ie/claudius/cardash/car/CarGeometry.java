package ie.claudius.cardash.car;

import java.util.ArrayList;
import java.util.List;

/**
 * The top-down car drawing, front at the top, on a 360 x 560 canvas: a
 * port of the approved design (the reference is a small Python script).
 * Shapes are point lists {x0, y0, x1, y1, ...}; opening parts take a
 * progress from 0 (shut) to 1 (fully open). Android-free; see
 * CarGeometryTest. {@code CarView} draws it.
 */
public final class CarGeometry {

    private CarGeometry() {}

    public static final float CX = 180f;
    public static final float TOP = 95f, BOT = 455f;
    /** Half the width of the body. */
    public static final float HALF = 72f;
    public static final float BONNET_Y = TOP + 95f;
    public static final float ROOF_TOP = BONNET_Y + 58f, ROOF_BOT = BOT - 108f;
    public static final float TAIL_Y = ROOF_BOT + 34f;
    /** How far a door swings, in degrees. */
    public static final float SWING = 38f;

    /** Everything that can be drawn, doors open and all: {left, top, right, bottom}. */
    public static final float[] BOUNDS = { 44f, 85f, 316f, 478f };

    public static final int LEFT = -1, RIGHT = 1;

    /** Door spans along the side, hinge (front edge) first. */
    private static final float[] FRONT = { TOP + 138, TOP + 195 };
    private static final float[] REAR = { TOP + 197, TOP + 251 };
    private static final float[] FRONT_3 = { TOP + 138, TOP + 226 };   // a longer door on a 3-door

    public static float[] frontDoor(boolean threeDoor) {
        return (threeDoor ? FRONT_3 : FRONT).clone();
    }

    public static float[] rearDoor() {
        return REAR.clone();
    }

    /** The closed outline of the body, slightly tapered nose and tail. */
    public static float[] body() {
        List<float[]> p = new ArrayList<>();
        for (int i = 0; i <= 180; i += 10) {
            double a = Math.toRadians(180 + i);
            p.add(new float[] { CX + (HALF - 4) * (float) Math.cos(a), TOP + 26 + 30 * (float) Math.sin(a) });
        }
        p.add(new float[] { CX + HALF, TOP + 60 });
        p.add(new float[] { CX + HALF, BOT - 50 });
        for (int i = 0; i <= 180; i += 10) {
            double a = Math.toRadians(i);
            p.add(new float[] { CX + (HALF - 3) * (float) Math.cos(a), BOT - 26 + 26 * (float) Math.sin(a) });
        }
        p.add(new float[] { CX - HALF, BOT - 50 });
        p.add(new float[] { CX - HALF, TOP + 60 });
        float[] out = new float[p.size() * 2];
        for (int i = 0; i < p.size(); i++) {
            out[i * 2] = p.get(i)[0];
            out[i * 2 + 1] = p.get(i)[1];
        }
        return out;
    }

    /** The second, inner line of the double outline. */
    public static float[] inner() {
        float[] p = body();
        float mid = (TOP + BOT) / 2f;
        for (int i = 0; i < p.length; i += 2) {
            p[i] = (p[i] - CX) * 0.90f + CX;
            p[i + 1] = (p[i + 1] - mid) * 0.955f + mid;
        }
        return p;
    }

    public static float[] bonnetLine() {
        return new float[] { CX - HALF + 10, BONNET_Y, CX - 20, BONNET_Y + 6,
                CX + 20, BONNET_Y + 6, CX + HALF - 10, BONNET_Y };
    }

    /** The bonnet panel outlined when open: the nose of the inner line, down to the bonnet line (open path). */
    public static float[] bonnetPanel() {
        float[] in = inner();
        List<float[]> nose = new ArrayList<>();
        for (int i = 0; i < in.length; i += 2) {
            if (in[i + 1] <= BONNET_Y) nose.add(new float[] { in[i], in[i + 1] });
        }
        // Sort by angle seen from the middle of the bonnet line: left side, over the nose, right side.
        for (int i = 1; i < nose.size(); i++) {
            for (int j = i; j > 0 && angle(nose.get(j)) < angle(nose.get(j - 1)); j--) {
                float[] t = nose.get(j);
                nose.set(j, nose.get(j - 1));
                nose.set(j - 1, t);
            }
        }
        float[] out = new float[nose.size() * 2 + 4];
        out[0] = CX - HALF + 10;
        out[1] = BONNET_Y;
        for (int i = 0; i < nose.size(); i++) {
            out[2 + i * 2] = nose.get(i)[0];
            out[3 + i * 2] = nose.get(i)[1];
        }
        out[out.length - 2] = CX + HALF - 10;
        out[out.length - 1] = BONNET_Y;
        return out;
    }

    private static double angle(float[] q) {
        return Math.atan2(q[1] - BONNET_Y, q[0] - CX);
    }

    public static float[] windscreen() {
        return new float[] { CX - HALF + 12, BONNET_Y + 14, CX + HALF - 12, BONNET_Y + 14,
                CX + HALF - 18, ROOF_TOP, CX - HALF + 18, ROOF_TOP };
    }

    public static float[] roofEdge(int side) {
        float x = CX + side * (HALF - 18);
        return new float[] { x, ROOF_TOP, x, ROOF_BOT };
    }

    public static float[] rearWindow() {
        return new float[] { CX - HALF + 18, ROOF_BOT, CX + HALF - 18, ROOF_BOT,
                CX + HALF - 13, TAIL_Y, CX - HALF + 13, TAIL_Y };
    }

    public static float[] mirror(int side) {
        float x = CX + side * HALF;
        return new float[] { x, BONNET_Y + 26, x + side * 13, BONNET_Y + 22,
                x + side * 13, BONNET_Y + 34, x, BONNET_Y + 38 };
    }

    /** The tailgate's shut line. */
    public static float[] tailLine() {
        return new float[] { CX - HALF + 16, BOT - 14, CX + HALF - 16, BOT - 14 };
    }

    /**
     * The tailgate swinging up and back: seen from above its lower edge
     * moves from the shut line to past the bumper.
     */
    public static float[] tailgate(float open) {
        float y = BOT - 14 + 30 * open;
        float in = 16 + 6 * open;
        return new float[] { CX - HALF + 16, TAIL_Y, CX + HALF - 16, TAIL_Y,
                CX + HALF - in, y, CX - HALF + in, y };
    }

    /** A door swung out around its front hinge: a closed four-point shape. */
    public static float[] door(int side, float[] span, float open) {
        float x = CX + side * HALF, y0 = span[0], y1 = span[1];
        float ang = -side * SWING * open;
        float[] p1 = rotate(x, y1, x, y0, ang);
        float[] p2 = rotate(x - side * 7, y1, x, y0, ang);
        float[] p3 = rotate(x - side * 7, y0 + 4, x, y0, ang);
        return new float[] { x, y0, p1[0], p1[1], p2[0], p2[1], p3[0], p3[1] };
    }

    /** Rotate (px, py) around (ox, oy); positive is clockwise on screen. */
    public static float[] rotate(float px, float py, float ox, float oy, float degrees) {
        double a = Math.toRadians(degrees);
        double dx = px - ox, dy = py - oy;
        return new float[] { (float) (ox + dx * Math.cos(a) - dy * Math.sin(a)),
                (float) (oy + dx * Math.sin(a) + dy * Math.cos(a)) };
    }
}
