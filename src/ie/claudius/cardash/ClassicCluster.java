package ie.claudius.cardash;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The "Classic VW" car page, after the monochrome cluster display: heading
 * and time on top, the car in the middle, the outside temperature under a
 * snowflake, then odometer and trip. White on near-black. Values nobody
 * reports show a dash. {@link Widgets} feeds it.
 */
final class ClassicCluster extends LinearLayout {

    static final int BACKGROUND = 0xFF05070A;
    private static final int WHITE = CarView.LINE, GREY = 0xFF9AA2AE;

    private final float d;
    final TextView heading, time, temperature, warning, odometer, trip;
    final CarView car;

    ClassicCluster(Context ctx, String editNote) {
        super(ctx);
        d = ctx.getResources().getDisplayMetrics().density;
        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER_HORIZONTAL);
        setBackgroundColor(BACKGROUND);
        setPadding(dp(32), dp(14), dp(32), dp(22));

        LinearLayout top = row();
        heading = text(top, 34, WHITE, Gravity.START);
        time = text(top, 34, WHITE, Gravity.END);
        addView(top, width());
        addView(rule(), ruleParams());

        car = new CarView(ctx, BACKGROUND);
        car.setMonochrome(true);
        addView(car, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        warning = text(null, 20, CarView.OPEN, Gravity.CENTER);
        warning.setVisibility(View.GONE);
        addView(warning, width());
        temperature = text(null, 38, WHITE, Gravity.CENTER);
        addView(temperature, width());
        addView(rule(), ruleParams());

        LinearLayout bottom = row();
        odometer = text(bottom, 24, WHITE, Gravity.START);
        trip = text(bottom, 24, WHITE, Gravity.END);
        addView(bottom, width());
        if (editNote != null) {
            TextView note = text(null, 15, GREY, Gravity.CENTER);
            note.setText(editNote);
            addView(note, width());
        }
    }

    private int dp(float v) { return Math.round(v * d); }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(getContext());
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    private LayoutParams width() {
        return new LayoutParams(Math.min(dp(640), getResources().getDisplayMetrics().widthPixels),
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private View rule() {
        View v = new View(getContext());
        v.setBackgroundColor(0x66EBEEF2);
        return v;
    }

    private LayoutParams ruleParams() {
        LayoutParams lp = width();
        lp.height = Math.max(1, dp(1));
        lp.topMargin = dp(6);
        lp.bottomMargin = dp(6);
        return lp;
    }

    private TextView text(LinearLayout into, int sp, int color, int gravity) {
        TextView t = new TextView(getContext());
        t.setTypeface(Fonts.display(getContext()));
        t.setFontFeatureSettings("tnum");
        t.setTextColor(color);
        t.setTextSize(sp);
        t.setGravity(gravity);
        t.setSingleLine(true);
        if (into != null) into.addView(t, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return t;
    }
}
