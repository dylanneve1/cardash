package ie.claudius.cardash;

import android.content.Context;
import android.graphics.Paint;
import android.util.TypedValue;
import android.widget.TextView;

/**
 * A TextView that picks the largest size at which its sample text fits
 * the view (width and height, minus padding). Manual measuring with
 * Paint: TextView autosize needs API 26 and minSdk is 21. The sample is
 * the widest text the view will ever show (e.g. "88:88"), so the size
 * does not jump as the text changes.
 */
public class FitText extends TextView {

    private float minPx = 12, maxPx = 200;
    private String sample;
    private float applied;

    public FitText(Context ctx) {
        super(ctx);
        setIncludeFontPadding(false);
        setMaxLines(1);
    }

    public void setRange(float minPx, float maxPx) {
        this.minPx = minPx;
        this.maxPx = maxPx;
        fit();
    }

    /** Text to size against; null sizes against the current text. */
    public void setSample(String s) {
        sample = s;
        fit();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        fit();
    }

    @Override
    protected void onTextChanged(CharSequence text, int start, int before, int after) {
        super.onTextChanged(text, start, before, after);
        if (sample == null) fit();
    }

    private void fit() {
        int w = getWidth() - getPaddingLeft() - getPaddingRight();
        int h = getHeight() - getPaddingTop() - getPaddingBottom();
        if (w <= 0 || h <= 0) return;
        String s = sample != null ? sample : String.valueOf(getText());
        if (s.isEmpty()) return;
        Paint p = new Paint(getPaint());
        float lo = minPx, hi = Math.max(minPx, maxPx);
        for (int i = 0; i < 14 && hi - lo > 0.5f; i++) {
            float mid = (lo + hi) / 2f;
            p.setTextSize(mid);
            Paint.FontMetrics fm = p.getFontMetrics();
            if (p.measureText(s) <= w && fm.descent - fm.ascent <= h) lo = mid; else hi = mid;
        }
        if (Math.abs(lo - applied) >= 1f) {
            applied = lo;
            setTextSize(TypedValue.COMPLEX_UNIT_PX, lo);
        }
    }
}
