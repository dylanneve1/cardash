package ie.claudius.cardash;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

import ie.claudius.cardash.layout.Pill;
import ie.claudius.cardash.vehicle.VehicleField;

/**
 * The engine data card: a heading with the fault-code button, and under
 * it every value the car has given, as pills in headed sections that
 * scroll. A section with nothing to show has no heading; with nothing at
 * all the card says what it is waiting for.
 */
final class SectionList extends ViewGroup {

    private final float d;
    private final TextView title, codes, empty;
    private final ScrollArea scroll;
    private final Body body;

    /** @param onCodes what the fault-code button does */
    SectionList(Context ctx, M3 m3, String heading, String waiting, String codesLabel, final Runnable onCodes) {
        super(ctx);
        d = ctx.getResources().getDisplayMetrics().density;
        setBackground(Shapes.round(d, m3.surfaceContainer(), 32));
        title = ValueList.text(ctx, m3, heading, 16);
        title.setGravity(Gravity.CENTER_VERTICAL);
        codes = ValueList.text(ctx, m3, codesLabel, 15);
        codes.setGravity(Gravity.CENTER);
        codes.setTextColor(m3.onSecondaryContainer());
        codes.setBackground(Shapes.pill(d, m3.secondaryContainer(), M3.withAlpha(m3.onSecondaryContainer(), 0x40)));
        codes.setPadding(px(18), 0, px(18), 0);
        codes.setClickable(true);
        Shapes.springy(codes);
        codes.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                onCodes.run();
            }
        });
        empty = ValueList.text(ctx, m3, waiting, 18);
        empty.setGravity(Gravity.CENTER);
        body = new Body(ctx, m3);
        scroll = new ScrollArea(ctx);
        scroll.addView(body, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        addView(title);
        addView(codes);
        addView(scroll);
        addView(empty);
    }

    private int px(float dp) { return Math.round(dp * d); }

    /** A long press on the list means what it means everywhere on the home screen. */
    void onHold(OnLongClickListener l) {
        body.setOnLongClickListener(l);
    }

    /** Add a value; values must come section by section, in order. */
    void add(VehicleField.Section section, ValueView v) {
        body.add(section, v);
    }

    /** Show or hide a pill; a change lays the card out again. */
    void show(ValueView v, boolean on) {
        int vis = on ? View.VISIBLE : View.GONE;
        if (v.getVisibility() != vis) {
            v.setVisibility(vis);
            body.requestLayout();
            requestLayout();
        }
    }

    private int pad(int w, int h) {
        return Math.round(Math.max(4 * d, Math.min(12 * d, Math.min(w, h) * 0.07f)));
    }

    /** No room for the heading on a small card. */
    private int headerHeight(int w, int h) {
        return h >= 200 * d && w >= 280 * d ? px(48) : 0;
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec), h = MeasureSpec.getSize(hSpec);
        setMeasuredDimension(w, h);
        int p = pad(w, h), th = headerHeight(w, h), inW = Math.max(0, w - 2 * p);
        int inH = Math.max(0, h - 2 * p - th - (th > 0 ? px(4) : 0));
        codes.measure(MeasureSpec.makeMeasureSpec(inW, MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(th, MeasureSpec.EXACTLY));
        ValueList.exactly(title, Math.max(0, inW - codes.getMeasuredWidth() - px(18)), th);
        ValueList.exactly(scroll, inW, inH);
        ValueList.exactly(empty, inW, inH);
        boolean none = !body.anyVisible();
        empty.setVisibility(none ? View.VISIBLE : View.INVISIBLE);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int w = r - l, h = b - t, p = pad(w, h), th = headerHeight(w, h);
        title.layout(p + px(10), p, p + px(10) + title.getMeasuredWidth(), p + th);
        codes.layout(w - p - codes.getMeasuredWidth(), p, w - p, p + th);
        int top = p + th + (th > 0 ? px(4) : 0);
        scroll.layout(p, top, w - p, top + scroll.getMeasuredHeight());
        empty.layout(p, top, w - p, top + empty.getMeasuredHeight());
    }

    /** The scrolling part: headings and pills. */
    private final class Body extends ViewGroup {
        private final List<TextView> heads = new ArrayList<>();
        private final List<List<ValueView>> pills = new ArrayList<>();

        Body(Context ctx, M3 m3) {
            super(ctx);
            for (VehicleField.Section s : VehicleField.Section.values()) {
                TextView t = ValueList.text(ctx, m3, s.title, 14);
                t.setTextColor(m3.primary());
                t.setGravity(Gravity.CENTER_VERTICAL);
                heads.add(t);
                pills.add(new ArrayList<ValueView>());
                addView(t);
            }
        }

        void add(VehicleField.Section section, ValueView v) {
            pills.get(section.ordinal()).add(v);
            addView(v);
        }

        boolean anyVisible() {
            for (List<ValueView> l : pills) for (ValueView v : l) if (v.getVisibility() == VISIBLE) return true;
            return false;
        }

        private List<ValueView> visible(int[] counts) {
            List<ValueView> out = new ArrayList<>();
            for (int s = 0; s < pills.size(); s++) {
                counts[s] = 0;
                for (ValueView v : pills.get(s)) {
                    if (v.getVisibility() != VISIBLE) continue;
                    out.add(v);
                    counts[s]++;
                }
            }
            return out;
        }

        private Pill.Sections arrange(int[] counts, int w) {
            return Pill.sections(counts, w, 8 * d, 300 * d, 52 * d, 30 * d);
        }

        @Override
        protected void onMeasure(int wSpec, int hSpec) {
            int w = MeasureSpec.getSize(wSpec);
            int[] counts = new int[pills.size()];
            List<ValueView> on = visible(counts);
            Pill.Sections s = arrange(counts, w);
            for (int i = 0; i < on.size(); i++) {
                ValueList.exactly(on.get(i), Math.round(s.rects[i][2]), Math.round(s.rects[i][3]));
            }
            for (int i = 0; i < heads.size(); i++) {
                heads.get(i).setVisibility(counts[i] > 0 ? VISIBLE : GONE);
                ValueList.exactly(heads.get(i), Math.max(0, w - px(10)), px(30));
            }
            setMeasuredDimension(w, Math.round(s.height));
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int[] counts = new int[pills.size()];
            List<ValueView> on = visible(counts);
            Pill.Sections s = arrange(counts, r - l);
            for (int i = 0; i < on.size(); i++) {
                View v = on.get(i);
                int x = Math.round(s.rects[i][0]), y = Math.round(s.rects[i][1]);
                v.layout(x, y, x + v.getMeasuredWidth(), y + v.getMeasuredHeight());
            }
            for (int i = 0; i < heads.size(); i++) {
                if (counts[i] == 0) continue;
                TextView head = heads.get(i);
                int y = Math.round(s.headY[i]);
                head.layout(px(10), y, px(10) + head.getMeasuredWidth(), y + head.getMeasuredHeight());
            }
        }
    }
}
