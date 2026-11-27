package ie.claudius.cardash;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/** The strips that sit over the home screen: things that must not be missed. */
final class Banners {

    private Banners() {}

    /** Always on screen while the vehicle data is pretend. Not touchable, so nothing is hidden behind it. */
    static View simulated(Context ctx) {
        float d = ctx.getResources().getDisplayMetrics().density;
        TextView t = new TextView(ctx);
        t.setText(R.string.sim_badge);
        t.setTypeface(Fonts.display(ctx));
        t.setTextSize(13);
        t.setTextColor(0xFF201400);
        t.setBackground(Shapes.round(d, CarView.OPEN, 12));
        t.setPadding(Math.round(14 * d), Math.round(3 * d), Math.round(14 * d), Math.round(3 * d));
        t.setElevation(12 * d);
        return t;
    }

    /** "Add car pages?", with Add and Not now. */
    static View carPages(Context ctx, M3 m3, Runnable add, Runnable no) {
        float d = ctx.getResources().getDisplayMetrics().density;
        LinearLayout bar = bar(ctx, d, m3.secondaryContainer());
        bar.addView(words(ctx, d, R.string.car_pages_offer, m3.onSecondaryContainer()));
        button(ctx, d, bar, R.string.car_pages_no, m3.onSecondaryContainer(), m3.secondaryContainer(), no);
        button(ctx, d, bar, R.string.car_pages_add, m3.onPrimary(), m3.primary(), add);
        return bar;
    }

    /** "Unplug the OBD dongle", with an OK that puts it away. */
    static View unplug(Context ctx, M3 m3, Runnable ok) {
        float d = ctx.getResources().getDisplayMetrics().density;
        LinearLayout bar = bar(ctx, d, m3.errorContainer());
        bar.addView(words(ctx, d, R.string.unplug_text, m3.onErrorContainer()));
        button(ctx, d, bar, android.R.string.ok, m3.errorContainer(), m3.onErrorContainer(), ok);
        return bar;
    }

    private static LinearLayout bar(Context ctx, float d, int fill) {
        LinearLayout bar = new LinearLayout(ctx);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackground(Shapes.round(d, fill, 36));
        bar.setPadding(Math.round(24 * d), Math.round(8 * d), Math.round(8 * d), Math.round(8 * d));
        bar.setElevation(10 * d);
        return bar;
    }

    private static TextView words(Context ctx, float d, int string, int colour) {
        TextView text = new TextView(ctx);
        text.setText(string);
        text.setTypeface(Fonts.display(ctx));
        text.setTextColor(colour);
        text.setTextSize(19);
        text.setMaxWidth(Math.round(760 * d));
        return text;
    }

    private static void button(Context ctx, float d, LinearLayout bar, int string, int ink, int fill,
                               final Runnable run) {
        TextView button = new TextView(ctx);
        button.setText(string);
        button.setTypeface(Fonts.display(ctx));
        button.setTextSize(18);
        button.setGravity(Gravity.CENTER);
        button.setMinWidth(Math.round(80 * d));
        button.setPadding(Math.round(18 * d), 0, Math.round(18 * d), 0);
        button.setTextColor(ink);
        button.setBackground(Shapes.pill(d, fill, M3.withAlpha(ink, 0x40)));
        button.setClickable(true);
        Shapes.springy(button);
        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                run.run();
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, Math.round(56 * d));
        lp.leftMargin = Math.round(12 * d);
        bar.addView(button, lp);
    }
}
