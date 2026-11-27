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

    /** "Unplug the OBD dongle", with an OK that puts it away. */
    static View unplug(Context ctx, M3 m3, final Runnable ok) {
        float d = ctx.getResources().getDisplayMetrics().density;
        LinearLayout bar = new LinearLayout(ctx);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackground(Shapes.round(d, m3.errorContainer(), 36));
        bar.setPadding(Math.round(24 * d), Math.round(8 * d), Math.round(8 * d), Math.round(8 * d));
        bar.setElevation(10 * d);
        TextView text = new TextView(ctx);
        text.setText(R.string.unplug_text);
        text.setTypeface(Fonts.display(ctx));
        text.setTextColor(m3.onErrorContainer());
        text.setTextSize(19);
        text.setMaxWidth(Math.round(760 * d));
        bar.addView(text);
        TextView button = new TextView(ctx);
        button.setText(android.R.string.ok);
        button.setTypeface(Fonts.display(ctx));
        button.setTextSize(18);
        button.setGravity(Gravity.CENTER);
        button.setMinWidth(Math.round(80 * d));
        button.setTextColor(m3.errorContainer());
        button.setBackground(Shapes.pill(d, m3.onErrorContainer(), M3.withAlpha(m3.errorContainer(), 0x40)));
        button.setClickable(true);
        Shapes.springy(button);
        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ok.run();
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, Math.round(56 * d));
        lp.leftMargin = Math.round(20 * d);
        bar.addView(button, lp);
        return bar;
    }
}
