package ie.claudius.cardash;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Small dialogs in the app's own palette: a rounded card with a title,
 * rows of option pills and stepper controls, and buttons. Every
 * control is at least 48dp so it can be hit without looking.
 */
final class Pop {

    interface Choice { void on(String value); }
    interface Count { void on(int value); }

    private final Context ctx;
    private final M3 m3;
    private final float d;
    private final Dialog dlg;
    final LinearLayout body;
    private final LinearLayout buttons;

    Pop(Context ctx, M3 m3, String title) {
        this.ctx = ctx;
        this.m3 = m3;
        this.d = ctx.getResources().getDisplayMetrics().density;
        dlg = new Dialog(ctx, android.R.style.Theme_Material_Dialog_NoActionBar);
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Shapes.round(d, m3.surfaceContainerHigh(), 28));
        card.setPadding(dp(24), dp(20), dp(24), dp(16));

        TextView t = new TextView(ctx);
        t.setText(title);
        t.setTypeface(Fonts.display(ctx));
        t.setTextColor(m3.onSurface());
        t.setTextSize(22);
        t.setPadding(0, 0, 0, dp(12));
        card.addView(t);

        body = new LinearLayout(ctx);
        body.setOrientation(LinearLayout.VERTICAL);
        ScrollView sv = new ScrollView(ctx);
        sv.addView(body);
        card.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        buttons = new LinearLayout(ctx);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.END);
        buttons.setPadding(0, dp(12), 0, 0);
        card.addView(buttons);

        dlg.setContentView(card);
        Window w = dlg.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            int width = Math.min(dp(640), ctx.getResources().getDisplayMetrics().widthPixels - dp(48));
            w.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.CENTER);
        }
    }

    private int dp(float v) { return Math.round(v * d); }

    /** A text pill, 48dp tall. */
    TextView pill(String text, boolean selected) {
        TextView p = new TextView(ctx);
        p.setText(text);
        p.setTypeface(Fonts.display(ctx));
        p.setTextSize(16);
        p.setGravity(Gravity.CENTER);
        p.setMinHeight(dp(48));
        p.setMinWidth(dp(56));
        p.setPadding(dp(18), dp(8), dp(18), dp(8));
        p.setClickable(true);
        style(p, selected);
        Shapes.springy(p);
        return p;
    }

    void style(TextView p, boolean selected) {
        int fill = selected ? m3.primaryContainer() : m3.surfaceContainer();
        int on = selected ? m3.onPrimaryContainer() : m3.onSurface();
        p.setTextColor(on);
        p.setBackground(Shapes.pill(d, fill, M3.withAlpha(on, 0x40)));
    }

    /** A label with a row of exclusive options. */
    Pop choices(String label, final String[] values, String current, final Choice cb) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(6), 0, dp(6));
        TextView l = new TextView(ctx);
        l.setText(label);
        l.setTypeface(Fonts.body(ctx));
        l.setTextColor(m3.onSurfaceVariant());
        l.setTextSize(14);
        l.setPadding(dp(4), 0, 0, dp(4));
        row.addView(l);
        final LinearLayout group = new LinearLayout(ctx);
        group.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < values.length; i++) {
            final String v = values[i];
            final TextView opt = pill(title(v), v.equals(current));
            opt.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) {
                    for (int j = 0; j < group.getChildCount(); j++) {
                        TextView o = (TextView) group.getChildAt(j);
                        style(o, o == opt);
                    }
                    cb.on(v);
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(8);
            group.addView(opt, lp);
        }
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(ctx);
        hs.setHorizontalScrollBarEnabled(false);
        hs.addView(group);
        row.addView(hs);
        body.addView(row);
        return this;
    }

    /** A label over exclusive options in rows of three, for long lists. */
    Pop grid(String label, String[] values, String[] labels, String current, final Choice cb) {
        TextView l = new TextView(ctx);
        l.setText(label);
        l.setTypeface(Fonts.body(ctx));
        l.setTextColor(m3.onSurfaceVariant());
        l.setTextSize(14);
        l.setPadding(dp(4), dp(6), 0, dp(4));
        body.addView(l);
        LinearLayout row = null;
        for (int i = 0; i < values.length; i++) {
            if (i % 3 == 0) {
                row = new LinearLayout(ctx);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setWeightSum(3f);
                body.addView(row);
            }
            final String v = values[i];
            TextView opt = pill(labels[i], v.equals(current));
            opt.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) { cb.on(v); }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(48), 1f);
            lp.setMargins(dp(4), dp(4), dp(4), dp(4));
            row.addView(opt, lp);
        }
        return this;
    }

    private static String title(String v) {
        if (v.isEmpty()) return v;
        return Character.toUpperCase(v.charAt(0)) + v.substring(1);
    }

    /** A label with  −  value  +  */
    Pop stepper(String label, int value, final int min, final int max, final int step, final Count cb) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(6), 0, dp(6));
        TextView l = new TextView(ctx);
        l.setText(label);
        l.setTypeface(Fonts.body(ctx));
        l.setTextColor(m3.onSurfaceVariant());
        l.setTextSize(14);
        row.addView(l, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        final int[] cur = { value };
        final TextView num = new TextView(ctx);
        num.setTypeface(Fonts.display(ctx));
        num.setTextColor(m3.onSurface());
        num.setTextSize(20);
        num.setGravity(Gravity.CENTER);
        num.setText(String.valueOf(value));
        row.addView(pill("−", false), stepParams());
        ((TextView) row.getChildAt(1)).setOnClickListener(stepper(cur, -step, min, max, num, cb));
        row.addView(num, new LinearLayout.LayoutParams(dp(84), ViewGroup.LayoutParams.WRAP_CONTENT));
        row.addView(pill("+", false), stepParams());
        ((TextView) row.getChildAt(3)).setOnClickListener(stepper(cur, step, min, max, num, cb));
        body.addView(row);
        return this;
    }

    private LinearLayout.LayoutParams stepParams() {
        return new LinearLayout.LayoutParams(dp(56), dp(48));
    }

    private View.OnClickListener stepper(final int[] cur, final int delta, final int min, final int max,
                                         final TextView num, final Count cb) {
        return new View.OnClickListener() {
            @Override public void onClick(View v) {
                cur[0] = Math.max(min, Math.min(max, cur[0] + delta));
                num.setText(String.valueOf(cur[0]));
                cb.on(cur[0]);
            }
        };
    }

    /** A plain line of text (a note, or the app an item launches). */
    Pop note(String text) {
        TextView n = new TextView(ctx);
        n.setText(text);
        n.setTypeface(Fonts.body(ctx));
        n.setTextColor(m3.onSurface());
        n.setTextSize(16);
        n.setPadding(dp(4), dp(8), dp(4), dp(8));
        body.addView(n);
        return this;
    }

    /** A full-width row button (menus). */
    Pop row(String text, final Runnable run) {
        TextView r = pill(text, false);
        r.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        r.setMinHeight(dp(56));
        r.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                dlg.dismiss();
                if (run != null) run.run();
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        body.addView(r, lp);
        return this;
    }

    /** An action in the button row; always closes the dialog. */
    Pop button(String text, final Runnable run, boolean primary) {
        TextView b = pill(text, primary);
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                dlg.dismiss();
                if (run != null) run.run();
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(8);
        buttons.addView(b, lp);
        return this;
    }

    Pop onDismiss(final Runnable r) {
        dlg.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
            @Override public void onDismiss(android.content.DialogInterface di) { r.run(); }
        });
        return this;
    }

    void dismiss() {
        dlg.dismiss();
    }

    void show() {
        dlg.show();
    }

    static void confirm(Context ctx, M3 m3, String message, String action, Runnable yes) {
        new Pop(ctx, m3, message)
                .button(ctx.getString(android.R.string.cancel), null, false)
                .button(action, yes, true)
                .show();
    }
}
