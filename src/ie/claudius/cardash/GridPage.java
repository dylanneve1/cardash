package ie.claudius.cardash;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.util.HashMap;
import java.util.Map;

import ie.claudius.cardash.layout.Arrange;
import ie.claudius.cardash.layout.Grid;
import ie.claudius.cardash.layout.HomeLayout;
import ie.claudius.cardash.layout.HomeLayout.Item;
import ie.claudius.cardash.layout.Kind;

/**
 * One home page: lays every item of a layout page out on the grid. In
 * edit mode a tap selects an item (outlined); the selected one can be
 * dragged (snaps to cells, never onto a neighbour) or, while resizing,
 * pulled by its corner handle. Everything else that acts on it is on
 * the editor's toolbar. A tap on empty space deselects and a long press
 * there adds something; the grid is drawn and a ghost shows where a drop
 * will land.
 */
final class GridPage extends ViewGroup {

    interface Factory {
        View create(Item it, int w, int h, int appIndex, boolean editing);
    }

    interface Edit {
        /** The selected item, or null. */
        Item selected();
        /** True while the selection is being resized: it shows a corner handle. */
        boolean resizing();
        /** Select an item, or nothing (null). */
        void select(Item it);
        /** Empty space was long-pressed. */
        void addHere();
        /** An item moved ({@code structural} false) or resized ({@code true}: views need rebuilding). */
        void changed(boolean structural);
        /** Drag started/ended: the toolbar steps aside. */
        void dragging(boolean on);
    }

    private static final int HANDLE_DP = 48;

    private final HomeLayout layout;
    final HomeLayout.Page page;
    private final Factory factory;
    private final boolean editing;
    private final Edit edit;
    private final M3 m3;
    private final float d;
    private final Map<View, Item> owner = new HashMap<>();
    private final Paint dots = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ghostFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ghostLine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private Grid grid;
    private int[] ghost;          // x, y, w, h in cells
    private boolean animateIn;
    private int builtW, builtH;
    private String emptyHint;

    GridPage(Context ctx, M3 m3, HomeLayout layout, HomeLayout.Page page, Factory factory,
             boolean editing, Edit edit, boolean animateIn, String emptyHint) {
        super(ctx);
        this.m3 = m3;
        this.layout = layout;
        this.page = page;
        this.factory = factory;
        this.editing = editing;
        this.edit = edit;
        this.animateIn = animateIn;
        this.emptyHint = emptyHint;
        d = ctx.getResources().getDisplayMetrics().density;
        setWillNotDraw(false);
        dots.setColor(M3.withAlpha(m3.onSurface(), 0x40));
        ghostFill.setColor(M3.withAlpha(m3.primary(), 0x30));
        ghostLine.setColor(m3.primary());
        ghostLine.setStyle(Paint.Style.STROKE);
        ghostLine.setStrokeWidth(3 * d);
        hint.setColor(M3.withAlpha(m3.onSurfaceVariant(), 0xAA));
        hint.setTextAlign(Paint.Align.CENTER);
        hint.setTextSize(18 * d);
        hint.setTypeface(Fonts.body(ctx));
        if (editing) {
            setOnClickListener(new OnClickListener() {
                @Override public void onClick(View v) { GridPage.this.edit.select(null); }
            });
            setOnLongClickListener(new OnLongClickListener() {
                @Override public boolean onLongClick(View v) {
                    v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                    GridPage.this.edit.addHere();
                    return true;
                }
            });
        }
    }

    /** The selection or the resize mode changed: restyle the frames. */
    void refreshSelection() {
        for (int i = 0; i < getChildCount(); i++) {
            View c = getChildAt(i);
            if (c instanceof EditFrame) ((EditFrame) c).refresh();
        }
    }

    private int dp(float v) { return Math.round(v * d); }

    // ---- build / layout -------------------------------------------------------

    private Grid gridFor(int w, int h) {
        return new Grid(layout.cols, layout.rows, w, h, dp(20), dp(14));
    }

    private void populate() {
        removeAllViews();
        owner.clear();
        int appIdx = 0, order = 0;
        for (Item it : page.items) {
            float[] r = grid.rect(it.x, it.y, it.w, it.h);
            int w = Math.round(r[2] - r[0]), h = Math.round(r[3] - r[1]);
            View content = factory.create(it, w, h, appIdx, editing);
            if (Kind.APP.equals(it.type)) appIdx++;
            View v = editing ? new EditFrame(getContext(), it, content) : content;
            owner.put(v, it);
            addView(v);
            if (animateIn) Motion.enter(v, 100L + 35L * order++, 0f, dp(24));
        }
        animateIn = false;
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec), h = MeasureSpec.getSize(hSpec);
        setMeasuredDimension(w, h);
        grid = gridFor(w, h);
        // Item views are built once the real size is known (they size their text and icons from it).
        if (w > 0 && h > 0 && (w != builtW || h != builtH)) {
            builtW = w;
            builtH = h;
            populate();
        }
        for (int i = 0; i < getChildCount(); i++) {
            View c = getChildAt(i);
            Item it = owner.get(c);
            if (it == null) continue;
            float[] r = grid.rect(it.x, it.y, it.w, it.h);
            c.measure(MeasureSpec.makeMeasureSpec(Math.round(r[2] - r[0]), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(Math.round(r[3] - r[1]), MeasureSpec.EXACTLY));
        }
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        for (int i = 0; i < getChildCount(); i++) {
            View c = getChildAt(i);
            Item it = owner.get(c);
            if (it == null) continue;
            float[] f = grid.rect(it.x, it.y, it.w, it.h);
            c.layout(Math.round(f[0]), Math.round(f[1]), Math.round(f[2]), Math.round(f[3]));
        }
    }

    @Override
    protected void dispatchDraw(Canvas c) {
        if (grid != null && editing) {
            for (int x = 0; x <= layout.cols; x++) {
                for (int y = 0; y <= layout.rows; y++) {
                    c.drawCircle(grid.left + x * grid.cellW, grid.top + y * grid.cellH, 2.2f * d, dots);
                }
            }
        }
        if (page.items.isEmpty() && emptyHint != null && getWidth() > 0) {
            c.drawText(emptyHint, getWidth() / 2f, getHeight() / 2f, hint);
        }
        super.dispatchDraw(c);
        if (ghost != null && grid != null) {
            float[] f = grid.rect(ghost[0], ghost[1], ghost[2], ghost[3]);
            rect.set(f[0], f[1], f[2], f[3]);
            c.drawRoundRect(rect, 24 * d, 24 * d, ghostFill);
            c.drawRoundRect(rect, 24 * d, 24 * d, ghostLine);
        }
    }

    // ---- the edit frame -------------------------------------------------------

    /** Wraps one item in edit mode: a shield that selects and drags, and the resize handle. */
    private final class EditFrame extends FrameLayout {
        final Item item;
        float sx, sy;
        boolean dragging, tap;
        private final View shield;
        private final ImageView handle;

        EditFrame(Context ctx, final Item it, View content) {
            super(ctx);
            item = it;
            setClipChildren(false);
            addView(content, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            shield = new View(ctx);
            shield.setClickable(true);
            Kind k = Kind.of(it.type);
            shield.setContentDescription(k == null ? it.type : k.label);
            addView(shield, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            shield.setOnTouchListener(new OnTouchListener() {
                @Override public boolean onTouch(View v, MotionEvent e) { return drag(e); }
            });
            // Accessibility and keyboard clicks do not come through the touch listener.
            shield.setOnClickListener(new OnClickListener() {
                @Override public void onClick(View v) { edit.select(item); }
            });

            handle = new ImageView(ctx);
            handle.setImageDrawable(new Glyph(Glyph.Kind.RESIZE, m3.onPrimary()));
            handle.setBackground(Shapes.dot(d, m3.primary(), m3.onPrimary(), 2));
            handle.setPadding(dp(10), dp(10), dp(10), dp(10));
            handle.setContentDescription(ctx.getString(R.string.resize));
            handle.setClickable(true);
            handle.setOnTouchListener(new OnTouchListener() {
                float hx, hy;
                @Override public boolean onTouch(View v, MotionEvent e) {
                    switch (e.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN:
                            hx = e.getRawX(); hy = e.getRawY();
                            requestDisallow(true);
                            edit.dragging(true);
                            return true;
                        case MotionEvent.ACTION_MOVE:
                            ghost = resizeGhost(it, e.getRawX() - hx, e.getRawY() - hy);
                            invalidateGrid();
                            return true;
                        case MotionEvent.ACTION_UP: {
                            int[] g = ghost;
                            ghost = null;
                            edit.dragging(false);
                            if (g == null || (g[2] == it.w && g[3] == it.h)) {
                                invalidateGrid();
                                return true;
                            }
                            it.w = g[2];
                            it.h = g[3];
                            edit.changed(true);
                            return true;
                        }
                        case MotionEvent.ACTION_CANCEL:
                            ghost = null;
                            edit.dragging(false);
                            invalidateGrid();
                            return true;
                        default:
                            return true;
                    }
                }
            });
            addView(handle, new LayoutParams(dp(HANDLE_DP), dp(HANDLE_DP), Gravity.BOTTOM | Gravity.END));
            refresh();
        }

        void refresh() {
            boolean on = edit.selected() == item;
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(24 * d);
            g.setColor(on ? M3.withAlpha(m3.primary(), 0x38) : 0x40000000);
            g.setStroke(dp(on ? 5 : 1), on ? m3.primary() : M3.withAlpha(m3.onSurface(), 0x59));
            shield.setBackground(g);
            shield.setSelected(on);
            handle.setVisibility(on && edit.resizing() ? VISIBLE : GONE);
        }

        /** A tap selects; only the selected item follows a drag. */
        private boolean drag(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    sx = e.getRawX(); sy = e.getRawY();
                    dragging = false;
                    tap = true;
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dx = e.getRawX() - sx, dy = e.getRawY() - sy;
                    int slop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
                    if (tap && Math.hypot(dx, dy) > slop) {
                        tap = false;
                        if (edit.selected() == item) {
                            dragging = true;
                            requestDisallow(true);
                            bringToFront();
                            edit.dragging(true);
                        }
                    }
                    if (dragging) {
                        setTranslationX(dx);
                        setTranslationY(dy);
                        ghost = moveGhost(item, dx, dy);
                        invalidateGrid();
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (dragging) drop(e.getActionMasked() == MotionEvent.ACTION_UP);
                    else if (tap && e.getActionMasked() == MotionEvent.ACTION_UP) edit.select(item);
                    dragging = false;
                    tap = false;
                    return true;
                default:
                    return true;
            }
        }

        private void drop(boolean apply) {
            final int[] g = ghost;
            ghost = null;
            edit.dragging(false);
            if (!apply || g == null) {
                animate().translationX(0).translationY(0).setDuration(160).start();
                invalidateGrid();
                return;
            }
            float[] from = grid.rect(item.x, item.y, item.w, item.h);
            float[] to = grid.rect(g[0], g[1], item.w, item.h);
            final int nx = g[0], ny = g[1];
            ghost = null;
            animate().translationX(to[0] - from[0]).translationY(to[1] - from[1])
                    .setDuration(Motion.isCalm() ? 120 : 200).setInterpolator(Motion.STANDARD)
                    .withEndAction(new Runnable() {
                        @Override public void run() {
                            item.x = nx;
                            item.y = ny;
                            setTranslationX(0);
                            setTranslationY(0);
                            requestLayout();
                            edit.changed(false);
                        }
                    }).start();
        }
    }

    private void requestDisallow(boolean on) {
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(on);
    }

    private void invalidateGrid() {
        invalidate();
    }

    /** Where a dragged item would land: snapped to cells, the nearest free spot. */
    private int[] moveGhost(Item it, float dx, float dy) {
        int wx = Grid.clamp(it.x + grid.cellsX(dx), 0, layout.cols - it.w);
        int wy = Grid.clamp(it.y + grid.cellsY(dy), 0, layout.rows - it.h);
        int[] p = Arrange.nearestFree(layout, page, it, wx, wy, it.w, it.h);
        return p == null ? new int[] { it.x, it.y, it.w, it.h } : new int[] { p[0], p[1], it.w, it.h };
    }

    private int[] resizeGhost(Item it, float dx, float dy) {
        int[] s = Arrange.resize(layout, page, it, it.w + grid.cellsX(dx), it.h + grid.cellsY(dy));
        return new int[] { it.x, it.y, s[0], s[1] };
    }

    // ---- empty-space gestures -------------------------------------------------

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        // Be the target for touches no item took, so a long-press on empty space works.
        super.onTouchEvent(e);
        return true;
    }
}
