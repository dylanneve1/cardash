package ie.claudius.cardash;

import android.content.Context;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;

/**
 * A list on a home page that scrolls up and down. The pager asks
 * {@link #under} before taking an upward swipe for "all apps", so the
 * swipe scrolls the list instead.
 */
final class ScrollArea extends ScrollView {

    ScrollArea(Context ctx) {
        super(ctx);
        setVerticalScrollBarEnabled(true);
        setScrollbarFadingEnabled(true);
    }

    private float downX, downY;

    private boolean scrolls() {
        return canScrollVertically(1) || canScrollVertically(-1);
    }

    /**
     * ScrollView takes a drag on its vertical travel alone, which would
     * steal a slightly slanted page swipe: only a mostly vertical one is
     * the list's, and none at all while everything fits.
     */
    @Override
    public boolean onInterceptTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
            downX = e.getX();
            downY = e.getY();
        } else if (e.getActionMasked() == MotionEvent.ACTION_MOVE
                && Math.abs(e.getX() - downX) >= Math.abs(e.getY() - downY)) {
            return false;
        }
        return scrolls() && super.onInterceptTouchEvent(e);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        return scrolls() && super.onTouchEvent(e);
    }

    /** True when ({@code x}, {@code y}), in {@code v}'s coordinates, is on a list that has somewhere to scroll. */
    static boolean under(View v, float x, float y) {
        if (v instanceof ScrollArea && ((ScrollArea) v).scrolls()) return true;
        if (!(v instanceof ViewGroup)) return false;
        ViewGroup g = (ViewGroup) v;
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            if (c.getVisibility() != VISIBLE) continue;
            float cx = x + g.getScrollX() - c.getX(), cy = y + g.getScrollY() - c.getY();
            if (cx >= 0 && cy >= 0 && cx < c.getWidth() && cy < c.getHeight() && under(c, cx, cy)) return true;
        }
        return false;
    }
}
