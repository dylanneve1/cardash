package ie.claudius.cardash;

import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.OvershootInterpolator;

/**
 * Material 3 Expressive shape + motion.
 *
 * Expressive's whole idea is that not every container is the same
 * rounded rectangle — shape carries hierarchy and rhythm. So tiles draw
 * from a family of corner treatments, and touch is answered with a
 * springy overshoot rather than a fade.
 */
public final class Shapes {

    /** Corner radii in dp, clockwise from top-left. One row per family. */
    private static final float[][] FAMILY = {
            { 28, 28, 28, 28 },   // full round — the calm default
            { 36, 12, 36, 12 },   // leaf, alternating
            { 12, 36, 12, 36 },   // leaf, mirrored
            { 44, 44, 16, 16 },   // arch, flat-bottomed
            { 16, 16, 44, 44 },   // arch, inverted
            { 40, 20, 40, 20 },   // soft leaf
    };

    private Shapes() {}

    public static Drawable tile(float density, int fill, int rippleColor, int index) {
        float[] f = FAMILY[((index % FAMILY.length) + FAMILY.length) % FAMILY.length];
        return ripple(radii(density, f, fill), rippleColor);
    }

    public static Drawable pill(float density, int fill, int rippleColor) {
        return ripple(radii(density, new float[] { 999, 999, 999, 999 }, fill), rippleColor);
    }

    public static Drawable round(float density, int fill, float dp) {
        return radii(density, new float[] { dp, dp, dp, dp }, fill);
    }

    private static GradientDrawable radii(float density, float[] dp, int fill) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(fill);
        // GradientDrawable wants 8 values: x,y per corner.
        d.setCornerRadii(new float[] {
                dp[0] * density, dp[0] * density,
                dp[1] * density, dp[1] * density,
                dp[2] * density, dp[2] * density,
                dp[3] * density, dp[3] * density,
        });
        return d;
    }

    private static Drawable ripple(Drawable content, int rippleColor) {
        return new RippleDrawable(ColorStateList.valueOf(rippleColor), content, null);
    }

    /**
     * Press feedback with a bit of spring in it. Material 3 Expressive
     * leans on overshoot rather than linear fades — a tile should feel
     * like it has mass, which matters more on a screen you poke without
     * looking at it.
     */
    public static void springy(final View v) {
        v.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View view, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        scale(view, 0.94f, 90, null);
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        scale(view, 1f, 320, new OvershootInterpolator(2.4f));
                        break;
                    default:
                        break;
                }
                return false; // let click/long-click handling continue
            }
        });
    }

    private static void scale(View v, float to, long ms, android.view.animation.Interpolator i) {
        v.animate().cancel();
        v.animate().scaleX(to).scaleY(to).setDuration(ms).setInterpolator(i).start();
    }
}
