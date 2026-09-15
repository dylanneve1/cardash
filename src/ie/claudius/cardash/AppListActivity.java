package ie.claudius.cardash;

import android.app.Activity;
import android.app.WallpaperManager;
import android.app.WallpaperColors;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/**
 * Every launchable app. Doubles as the tile picker — same grid, the
 * only difference is whether a tap launches or returns a result.
 */
public class AppListActivity extends Activity {

    public static final String EXTRA_PICK = "pick";
    public static final String EXTRA_PACKAGE = "package";

    private static final int COLUMNS = 5;
    private static final int FALLBACK_SEED = 0xFF4F7BD5;

    private float density;
    private M3 m3;
    private boolean picking;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        density = getResources().getDisplayMetrics().density;
        picking = getIntent().getBooleanExtra(EXTRA_PICK, false);
        m3 = M3.fromSeed(seed());

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(M3.withAlpha(m3.surface(), 0xF2));
        scroll.setFillViewport(true);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        column.setPadding(pad, pad, pad, pad);
        scroll.addView(column);

        TextView title = new TextView(this);
        title.setText(picking ? R.string.pick_app : R.string.all_apps);
        title.setTextColor(m3.onSurface());
        title.setTextSize(26);
        title.setPadding(dp(8), 0, 0, dp(16));
        column.addView(title);

        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(COLUMNS);
        column.addView(grid);

        List<Apps.Entry> entries = Apps.launchable(this);
        for (int i = 0; i < entries.size(); i++) {
            final Apps.Entry e = entries.get(i);
            View cell = cell(e, i);

            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = 0;
            lp.columnSpec = GridLayout.spec(i % COLUMNS, 1f);
            lp.rowSpec = GridLayout.spec(i / COLUMNS);
            int g = dp(6);
            lp.setMargins(g, g, g, g);
            grid.addView(cell, lp);

            if (i < COLUMNS * 3) { // only animate what's on screen
                cell.setAlpha(0f);
                cell.animate().alpha(1f)
                        .setStartDelay(20L * i)
                        .setDuration(260)
                        .setInterpolator(new DecelerateInterpolator())
                        .start();
            }
        }

        setContentView(scroll);
    }

    private View cell(final Apps.Entry e, int index) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER);
        cell.setBackground(Shapes.tile(density, m3.surfaceContainer(),
                M3.withAlpha(m3.onSurface(), 0x33), index));
        cell.setClickable(true);
        cell.setPadding(dp(10), dp(16), dp(10), dp(16));
        Shapes.springy(cell);

        ImageView icon = new ImageView(this);
        icon.setImageDrawable(e.icon);
        cell.addView(icon, new LinearLayout.LayoutParams(dp(48), dp(48)));

        TextView label = new TextView(this);
        label.setText(e.label);
        label.setTextColor(m3.onSurface());
        label.setTextSize(14);
        label.setMaxLines(1);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        label.setGravity(Gravity.CENTER);
        label.setPadding(0, dp(8), 0, 0);
        cell.addView(label);

        cell.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (picking) {
                    Intent out = new Intent();
                    out.putExtra(EXTRA_PACKAGE, e.pkg);
                    setResult(RESULT_OK, out);
                    finish();
                } else {
                    Apps.launch(AppListActivity.this, e.pkg);
                }
            }
        });
        return cell;
    }

    private int seed() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) return FALLBACK_SEED;
        try {
            WallpaperColors c = WallpaperManager.getInstance(this)
                    .getWallpaperColors(WallpaperManager.FLAG_SYSTEM);
            if (c != null && c.getPrimaryColor() != null) {
                return c.getPrimaryColor().toArgb();
            }
        } catch (Exception ignored) {
        }
        return FALLBACK_SEED;
    }

    private int dp(int v) {
        return Math.round(v * density);
    }
}
