package ie.claudius.cardash;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

import ie.claudius.cardash.layout.Pill;

/**
 * A card holding several pill bars, like the Pixel battery widget: rows
 * of pills, in as many columns as the width allows ({@link Pill#cells}).
 * Hidden pills take no room; with none to show it says what it is
 * waiting for. (The long engine data list is {@link SectionList}.)
 */
final class ValueList extends ViewGroup {

    private final float d;
    private final TextView empty;
    private final List<ValueView> pills = new ArrayList<>();

    ValueList(Context ctx, M3 m3, String waiting) {
        super(ctx);
        d = ctx.getResources().getDisplayMetrics().density;
        setBackground(Shapes.round(d, m3.surfaceContainer(), 32));
        empty = text(ctx, m3, waiting, 18);
        empty.setGravity(Gravity.CENTER);
        addView(empty);
    }

    static TextView text(Context ctx, M3 m3, String s, int dpSize) {
        TextView t = new TextView(ctx);
        t.setText(s);
        t.setTypeface(Fonts.display(ctx));
        t.setTextColor(m3.onSurfaceVariant());
        t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, dpSize);
        t.setMaxLines(1);
        return t;
    }

    void add(ValueView v) {
        pills.add(v);
        addView(v);
    }

    /** Show or hide a pill; a change lays the card out again. */
    void show(ValueView v, boolean on) {
        int vis = on ? View.VISIBLE : View.GONE;
        if (v.getVisibility() != vis) {
            v.setVisibility(vis);
            requestLayout();
        }
    }

    private int pad(int w, int h) {
        return Math.round(Math.max(4 * d, Math.min(12 * d, Math.min(w, h) * 0.07f)));
    }

    private Pill.Cells cells(int n, int w, int h) {
        int p = pad(w, h);
        return Pill.cells(n, w - 2 * p, h - 2 * p, 8 * d, 560 * d, 240 * d, 40 * d, 64 * d, 120 * d, true);
    }

    private List<ValueView> visible() {
        List<ValueView> out = new ArrayList<>();
        for (ValueView v : pills) if (v.getVisibility() == View.VISIBLE) out.add(v);
        return out;
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec), h = MeasureSpec.getSize(hSpec);
        setMeasuredDimension(w, h);
        int p = pad(w, h);
        exactly(empty, Math.max(0, w - 2 * p), Math.max(0, h - 2 * p));
        List<ValueView> on = visible();
        empty.setVisibility(on.isEmpty() ? View.VISIBLE : View.INVISIBLE);
        Pill.Cells c = cells(on.size(), w, h);
        for (int i = 0; i < on.size(); i++) {
            float[] f = i < c.shown ? c.rect(i) : new float[4];
            exactly(on.get(i), Math.round(f[2]), Math.round(f[3]));
        }
    }

    static void exactly(View v, int w, int h) {
        v.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY));
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int w = r - l, h = b - t, p = pad(w, h);
        List<ValueView> on = visible();
        empty.layout(p, p, w - p, h - p);
        Pill.Cells c = cells(on.size(), w, h);
        // The list sits in the middle of its card.
        float used = c.rows * c.cellH + Math.max(0, c.rows - 1) * c.gap;
        float top = p + Math.max(0, (h - 2 * p - used) / 2f);
        for (int i = 0; i < on.size(); i++) {
            View v = on.get(i);
            if (i >= c.shown) {
                v.layout(0, 0, 0, 0);
                continue;
            }
            float[] f = c.rect(i);
            int x = p + Math.round(f[0]), y = Math.round(top + f[1]);
            v.layout(x, y, x + v.getMeasuredWidth(), y + v.getMeasuredHeight());
        }
    }
}
