package ie.claudius.cardash;

import android.animation.LayoutTransition;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Interpolator;
import android.view.animation.OvershootInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.TextView;

/**
 * Material 3 motion, in one place.
 *
 * Every animation in the app routes through here so the timings agree
 * with each other, and so the "Calm" setting can quieten all of them at
 * once: a driver who finds the spring distracting should get short
 * fades everywhere, not a hunt through the code for each one.
 */
public final class Motion {

    /** M3 emphasized-decelerate: fast out of the gate, long soft landing. */
    public static final Interpolator EMPHASIZED = new PathInterpolator(0.05f, 0.7f, 0.1f, 1f);
    /** M3 standard: the everyday curve for fades and small moves. */
    public static final Interpolator STANDARD = new PathInterpolator(0.2f, 0f, 0f, 1f);
    /** Emphasized-accelerate, for things leaving the screen. */
    public static final Interpolator EXIT = new PathInterpolator(0.3f, 0f, 0.8f, 0.15f);

    private static boolean calm;

    private Motion() {}

    /** Set from the preference before a screen is built. */
    public static void setCalm(boolean value) {
        calm = value;
    }

    public static boolean isCalm() {
        return calm;
    }

    /** The overshoot for touch release — or a plain landing when calm. */
    public static Interpolator spring() {
        return calm ? EMPHASIZED : new OvershootInterpolator(2.4f);
    }

    /** Multiplies durations so the calm variant is brisk, not just flat. */
    private static long ms(long full) {
        return calm ? full * 6 / 10 : full;
    }

    /**
     * Entrance: rise from a slight offset with a fade. The stagger is
     * the caller's business — it knows how many siblings there are.
     */
    public static void enter(View v, long delay, float dx, float dy) {
        v.setAlpha(0f);
        v.setTranslationX(calm ? 0f : dx);
        v.setTranslationY(calm ? 0f : dy);
        v.animate().cancel();
        v.animate()
                .alpha(1f).translationX(0f).translationY(0f)
                .setStartDelay(calm ? delay / 2 : delay)
                .setDuration(ms(420))
                .setInterpolator(EMPHASIZED)
                .start();
    }

    /** A state change: a small pop so the eye is drawn to what flipped. */
    public static void pop(View v) {
        v.animate().cancel();
        if (calm) {
            v.setAlpha(0.4f);
            v.animate().alpha(1f).setDuration(160).setInterpolator(STANDARD).start();
            return;
        }
        v.setScaleX(0.8f);
        v.setScaleY(0.8f);
        v.animate().scaleX(1f).scaleY(1f)
                .setDuration(380)
                .setInterpolator(new OvershootInterpolator(3f))
                .start();
    }

    /**
     * Change a label without the text snapping: fade down, swap, fade
     * back. No-op if the text is already what was asked for, so callers
     * can fire this on every poll without the view flickering.
     */
    public static void swapText(final TextView t, final CharSequence text) {
        if (text == null || text.toString().contentEquals(t.getText())) return;
        if (t.getAlpha() == 0f || t.getVisibility() != View.VISIBLE || !t.isLaidOut()) {
            t.setText(text);
            return;
        }
        t.animate().cancel();
        t.animate().alpha(0f).translationY(calm ? 0f : -t.getHeight() * 0.12f)
                .setDuration(ms(110)).setInterpolator(EXIT)
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        t.setText(text);
                        t.setTranslationY(calm ? 0f : t.getHeight() * 0.12f);
                        t.animate().alpha(1f).translationY(0f)
                                .setDuration(ms(220)).setInterpolator(EMPHASIZED)
                                .start();
                    }
                }).start();
    }

    /** Fade a freshly-set image in rather than letting it snap. */
    public static void reveal(View v) {
        v.animate().cancel();
        v.setAlpha(0f);
        v.setScaleX(calm ? 1f : 0.92f);
        v.setScaleY(calm ? 1f : 0.92f);
        v.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(ms(320)).setInterpolator(EMPHASIZED).start();
    }

    /**
     * Swap one screen for another inside {@code shell}: the new one is
     * added underneath and fades up while the old one fades away, then
     * the old one is removed. Rebuilding on a theme change used to be a
     * hard cut; this makes it read as the colour flowing in.
     */
    public static void crossfade(final ViewGroup shell, final View old, View fresh) {
        shell.addView(fresh, 0, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        if (old == null) return;
        old.animate().cancel();
        old.animate().alpha(0f)
                .setDuration(ms(260)).setInterpolator(EXIT)
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        shell.removeView(old);
                    }
                }).start();
    }

    /** Children that appear, disappear or resize animate into place. */
    public static void animateChildren(ViewGroup g) {
        LayoutTransition lt = new LayoutTransition();
        lt.enableTransitionType(LayoutTransition.CHANGING);
        lt.setDuration(ms(300));
        lt.setInterpolator(LayoutTransition.APPEARING, EMPHASIZED);
        lt.setInterpolator(LayoutTransition.DISAPPEARING, EXIT);
        lt.setInterpolator(LayoutTransition.CHANGE_APPEARING, STANDARD);
        lt.setInterpolator(LayoutTransition.CHANGE_DISAPPEARING, STANDARD);
        lt.setInterpolator(LayoutTransition.CHANGING, STANDARD);
        lt.setStartDelay(LayoutTransition.APPEARING, ms(120));
        lt.setStartDelay(LayoutTransition.CHANGE_APPEARING, 0);
        lt.setStartDelay(LayoutTransition.CHANGE_DISAPPEARING, 0);
        lt.setStartDelay(LayoutTransition.CHANGING, 0);
        g.setLayoutTransition(lt);
    }
}
