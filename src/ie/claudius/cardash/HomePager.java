package ie.claudius.cardash;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * Horizontal paging between home pages, plus the swipe-up-for-all-apps
 * gesture. Page views are siblings positioned by translation, so the
 * pages stay alive (their live data keeps flowing) while off screen.
 * Both gestures are taken only on a clearly horizontal / vertical drag,
 * so taps, long-presses and the tiles' own touch handling are untouched.
 */
final class HomePager extends FrameLayout {

    interface Listener {
        void onSwipeUp();
        void onPage(int index);
    }

    private final float d;
    private final int slop;
    private final List<View> pages = new ArrayList<>();
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Listener listener;
    private boolean swipes = true;
    private int current;
    private float pos;                 // fractional page position while dragging/animating
    private float downX, downY;
    private int mode;                  // 0 none, 1 horizontal, 2 up
    private VelocityTracker vt;
    private ValueAnimator anim;
    private int dotOn, dotOff;

    HomePager(Context ctx, Listener l, int dotOn, int dotOff) {
        super(ctx);
        d = ctx.getResources().getDisplayMetrics().density;
        slop = ViewConfiguration.get(ctx).getScaledTouchSlop() * 2;
        listener = l;
        this.dotOn = dotOn;
        this.dotOff = dotOff;
        setWillNotDraw(false);
    }

    void setPages(List<? extends View> list, int start) {
        removeAllViews();
        pages.clear();
        pages.addAll(list);
        for (View v : pages) {
            addView(v, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        }
        current = Math.max(0, Math.min(start, pages.size() - 1));
        pos = current;
        position();
    }

    /** Edit mode turns the swipes off: dragging an item must not turn the page. */
    void setSwipes(boolean on) { swipes = on; }

    int current() { return current; }

    int count() { return pages.size(); }

    void goTo(int index, boolean animate) {
        index = Math.max(0, Math.min(index, pages.size() - 1));
        if (animate) settle(index);
        else { current = index; pos = index; position(); }
        if (listener != null) listener.onPage(current);
    }

    private void position() {
        int w = getWidth();
        for (int i = 0; i < pages.size(); i++) {
            View v = pages.get(i);
            v.setTranslationX((i - pos) * w);
            v.setVisibility(Math.abs(i - pos) < 1.01f ? VISIBLE : INVISIBLE);
        }
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        position();
    }

    private void settle(final int target) {
        if (anim != null) anim.cancel();
        anim = ValueAnimator.ofFloat(pos, target);
        anim.setDuration(Motion.isCalm() ? 160 : 260);
        anim.setInterpolator(Motion.STANDARD);
        anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator a) {
                pos = (Float) a.getAnimatedValue();
                position();
            }
        });
        current = target;
        anim.start();
    }

    // ---- gestures -------------------------------------------------------------

    @Override
    public boolean onInterceptTouchEvent(MotionEvent e) {
        if (!swipes) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX(); downY = e.getY();
                mode = 0;
                if (anim != null) anim.cancel();
                return false;
            case MotionEvent.ACTION_MOVE: {
                float dx = e.getX() - downX, dy = e.getY() - downY;
                if (pages.size() > 1 && Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy) * 1.3f) {
                    begin(1, e);
                    return true;
                }
                if (-dy > slop && Math.abs(dy) > Math.abs(dx) * 1.3f) {
                    begin(2, e);
                    return true;
                }
                return false;
            }
            default:
                return false;
        }
    }

    private void begin(int m, MotionEvent e) {
        mode = m;
        if (vt != null) vt.recycle();
        vt = VelocityTracker.obtain();
        vt.addMovement(e);
        getParent().requestDisallowInterceptTouchEvent(true);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (vt != null) vt.addMovement(e);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX(); downY = e.getY();
                return mode != 0 || swipes;
            case MotionEvent.ACTION_MOVE:
                if (mode == 1 && getWidth() > 0) {
                    float dx = e.getX() - downX;
                    float p = current - dx / getWidth();
                    // Resist at the ends instead of dragging into nothing.
                    if (p < 0) p = p / 3f;
                    if (p > pages.size() - 1) p = pages.size() - 1 + (p - (pages.size() - 1)) / 3f;
                    pos = p;
                    position();
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                boolean up = e.getActionMasked() == MotionEvent.ACTION_UP;
                float dx = e.getX() - downX, dy = e.getY() - downY;
                float vx = 0, vy = 0;
                if (vt != null) {
                    vt.computeCurrentVelocity(1000);
                    vx = vt.getXVelocity();
                    vy = vt.getYVelocity();
                    vt.recycle();
                    vt = null;
                }
                if (mode == 1) {
                    int target = current;
                    if (up && (Math.abs(dx) > getWidth() * 0.2f || Math.abs(vx) > 500 * d)) {
                        target = dx < 0 || vx < -500 * d ? current + 1 : current - 1;
                    }
                    target = Math.max(0, Math.min(target, pages.size() - 1));
                    settle(target);
                    if (listener != null) listener.onPage(target);
                } else if (mode == 2 && up && (-dy > 72 * d || vy < -700 * d)) {
                    if (listener != null) listener.onSwipeUp();
                }
                mode = 0;
                return true;
            }
            default:
                return true;
        }
    }

    // ---- page dots ------------------------------------------------------------

    @Override
    protected void dispatchDraw(Canvas c) {
        super.dispatchDraw(c);
        int n = pages.size();
        if (n < 2) return;
        float r = 3.5f * d, gap = 14 * d;
        float x0 = getWidth() / 2f - (n - 1) * gap / 2f;
        float y = getHeight() - 9 * d;
        for (int i = 0; i < n; i++) {
            dot.setColor(i == current ? dotOn : dotOff);
            c.drawCircle(x0 + i * gap, y, i == current ? r * 1.3f : r, dot);
        }
    }
}
