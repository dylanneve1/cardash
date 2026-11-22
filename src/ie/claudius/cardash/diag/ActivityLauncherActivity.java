package ie.claudius.cardash.diag;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import ie.claudius.cardash.Fonts;
import ie.claudius.cardash.M3;
import ie.claudius.cardash.Motion;
import ie.claudius.cardash.Prefs;
import ie.claudius.cardash.R;
import ie.claudius.cardash.Shapes;

/**
 * Every exported activity of every app, grouped by app with its icon and
 * label: the on-screen twin of {@code content call ... --method launch}
 * for head units with no adb. Tapping a row starts it by explicit
 * component; risky names confirm first and failures show on the row.
 * The rules (what is exported, risky, filtered) live in
 * {@link ActivityTargets}; the manifest scan runs off the UI thread and
 * so do the icon loads.
 */
public class ActivityLauncherActivity extends Activity {

    private static final int WARN = 0xFFFFB4AB;
    private static final int TYPE_APP = 0, TYPE_ACTIVITY = 1;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService icons = Executors.newFixedThreadPool(2);
    private final LruCache<String, AppInfo> appCache = new LruCache<>(200);
    private final Set<String> loading = new HashSet<>();
    private final Map<String, String> errors = new HashMap<>();

    private M3 m3;
    private float density;
    private List<ActivityTargets.Target> targets = new ArrayList<>();
    private final List<Object> items = new ArrayList<>(); // String (pkg header) or Target
    private boolean hiddenOnly = true;
    private boolean loaded;
    private EditText filter;
    private TextView count, hiddenPill;
    private ListView list;
    private Adapter adapter;

    private static final class AppInfo {
        final String label;
        final Drawable icon; // may be null
        AppInfo(String label, Drawable icon) {
            this.label = label;
            this.icon = icon;
        }
    }

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        density = getResources().getDisplayMetrics().density;
        Motion.setCalm(true);
        m3 = M3.fromSeed(Prefs.themeSeed(this));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(m3.surface());
        root.setPadding(dp(20), dp(16), dp(20), 0);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText(R.string.diag_launch);
        title.setTypeface(Fonts.display(this));
        title.setTextColor(m3.onSurface());
        title.setTextSize(26);
        title.setPadding(dp(8), 0, 0, 0);
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView done = pill(getString(R.string.done), true, new Runnable() {
            @Override
            public void run() {
                finish();
            }
        });
        done.setMinHeight(dp(56));
        header.addView(done);
        root.addView(header);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        filter = new EditText(this);
        filter.setSingleLine(true);
        filter.setImeOptions(EditorInfo.IME_ACTION_DONE);
        filter.setTextSize(16);
        filter.setMinHeight(dp(56));
        filter.setTextColor(m3.onSurface());
        filter.setHintTextColor(M3.withAlpha(m3.onSurfaceVariant(), 0x99));
        filter.setHint(R.string.diag_launch_filter_hint);
        filter.setBackground(Shapes.round(density, m3.surfaceContainerHigh(), 16));
        filter.setPadding(dp(16), dp(8), dp(16), dp(8));
        filter.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {}
            public void afterTextChanged(Editable s) {
                main.removeCallbacks(rebuild);
                main.postDelayed(rebuild, 150);
            }
        });
        bar.addView(filter, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        hiddenPill = pill(getString(R.string.diag_launch_hidden), hiddenOnly, new Runnable() {
            @Override
            public void run() {
                hiddenOnly = !hiddenOnly;
                style(hiddenPill, hiddenOnly);
                rebuild();
            }
        });
        hiddenPill.setMinHeight(dp(56));
        LinearLayout.LayoutParams plp = (LinearLayout.LayoutParams) hiddenPill.getLayoutParams();
        plp.leftMargin = dp(10);
        bar.addView(hiddenPill, plp);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(8);
        root.addView(bar, blp);

        count = new TextView(this);
        count.setTypeface(Fonts.body(this));
        count.setTextColor(m3.onSurfaceVariant());
        count.setTextSize(13);
        count.setText(R.string.diag_working);
        count.setPadding(dp(4), dp(10), 0, dp(8));
        root.addView(count);

        list = new ListView(this);
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setSelector(new android.graphics.drawable.ColorDrawable(0));
        list.setClipToPadding(false);
        list.setPadding(0, 0, 0, dp(20));
        list.setVerticalScrollBarEnabled(false);
        adapter = new Adapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                Object o = items.get(pos);
                if (o instanceof ActivityTargets.Target) launch((ActivityTargets.Target) o);
            }
        });
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

        final Context ctx = getApplicationContext();
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                final List<ActivityTargets.Target> all = Probe.exportedActivities(ctx, null);
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing()) return;
                        targets = all;
                        loaded = true;
                        rebuild();
                    }
                });
            }
        }, "cardash-activities");
        worker.setDaemon(true);
        worker.start();
    }

    @Override
    protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        icons.shutdownNow();
        super.onDestroy();
    }

    private final Runnable rebuild = new Runnable() {
        @Override
        public void run() {
            rebuild();
        }
    };

    /** Re-filter and regroup by app; targets arrive sorted by package, so groups are contiguous. */
    private void rebuild() {
        if (!loaded) return;
        List<ActivityTargets.Target> shown = ActivityTargets.filter(targets,
                filter.getText().toString(), hiddenOnly);
        items.clear();
        String last = null;
        int apps = 0;
        for (ActivityTargets.Target t : shown) {
            if (!t.pkg.equals(last)) {
                items.add(t.pkg);
                last = t.pkg;
                apps++;
            }
            items.add(t);
        }
        count.setText(shown.isEmpty() ? getString(R.string.diag_launch_none)
                : getString(R.string.diag_launch_count_all, shown.size(), targets.size(), apps));
        adapter.notifyDataSetChanged();
        list.setSelection(0);
    }

    // ---- launching ------------------------------------------------------------------

    private void launch(final ActivityTargets.Target t) {
        if (t.risk == null) {
            launchNow(t);
            return;
        }
        if (isFinishing()) return;
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("⚠ " + getString(R.string.diag_launch_risky_title))
                .setMessage(t.risk + "\n\n" + t.flat())
                .setNegativeButton(R.string.diag_launch_cancel, null)
                .setPositiveButton(R.string.diag_launch_anyway, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        launchNow(t);
                    }
                })
                .show();
    }

    /** startActivity with an explicit component; any failure lands on the row, never as a crash. */
    private void launchNow(ActivityTargets.Target t) {
        String err = Probe.launch(this, t.pkg, t.name);
        if (err == null) errors.remove(t.flat());
        else errors.put(t.flat(), err);
        adapter.notifyDataSetChanged();
    }

    // ---- app icon and label, loaded off the UI thread and cached ----------------------

    private void ensureApp(final String pkg) {
        if (appCache.get(pkg) != null || !loading.add(pkg)) return;
        final PackageManager pm = getPackageManager();
        try {
            icons.execute(new Runnable() {
                @Override
                public void run() {
                    String label = pkg;
                    Drawable icon = null;
                    try {
                        label = String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
                    } catch (Exception ignored) {
                        // keep the package name
                    }
                    try {
                        icon = pm.getApplicationIcon(pkg);
                    } catch (Exception ignored) {
                        // no icon: the placeholder stays
                    }
                    final AppInfo info = new AppInfo(label, icon);
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            loading.remove(pkg);
                            appCache.put(pkg, info);
                            adapter.notifyDataSetChanged();
                        }
                    });
                }
            });
        } catch (RuntimeException rejected) {
            loading.remove(pkg); // shut down with the screen
        }
    }

    // ---- list -------------------------------------------------------------------------

    private final class Adapter extends BaseAdapter {
        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int i) { return items.get(i); }
        @Override public long getItemId(int i) { return i; }
        @Override public int getViewTypeCount() { return 2; }
        @Override public int getItemViewType(int i) {
            return items.get(i) instanceof String ? TYPE_APP : TYPE_ACTIVITY;
        }
        @Override public boolean areAllItemsEnabled() { return false; }
        @Override public boolean isEnabled(int i) { return items.get(i) instanceof ActivityTargets.Target; }

        @Override
        public View getView(int pos, View convert, ViewGroup parent) {
            Object o = items.get(pos);
            if (o instanceof String) {
                AppHolder h = convert == null ? null : (AppHolder) convert.getTag();
                if (h == null) {
                    h = new AppHolder();
                    convert = h.build();
                    convert.setTag(h);
                }
                h.bind((String) o);
            } else {
                RowHolder h = convert == null ? null : (RowHolder) convert.getTag();
                if (h == null) {
                    h = new RowHolder();
                    convert = h.build();
                    convert.setTag(h);
                }
                h.bind((ActivityTargets.Target) o);
            }
            return convert;
        }
    }

    private final class AppHolder {
        ImageView icon;
        TextView label, pkg;

        View build() {
            LinearLayout row = new LinearLayout(ActivityLauncherActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(6), dp(14), dp(6), dp(6));
            icon = new ImageView(ActivityLauncherActivity.this);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            row.addView(icon, new LinearLayout.LayoutParams(dp(44), dp(44)));
            LinearLayout text = new LinearLayout(ActivityLauncherActivity.this);
            text.setOrientation(LinearLayout.VERTICAL);
            text.setPadding(dp(14), 0, 0, 0);
            label = new TextView(ActivityLauncherActivity.this);
            label.setTypeface(Fonts.display(ActivityLauncherActivity.this));
            label.setTextColor(m3.onSurface());
            label.setTextSize(18);
            label.setSingleLine(true);
            text.addView(label);
            pkg = new TextView(ActivityLauncherActivity.this);
            pkg.setTypeface(Typeface.MONOSPACE);
            pkg.setTextColor(m3.onSurfaceVariant());
            pkg.setTextSize(12);
            pkg.setSingleLine(true);
            text.addView(pkg);
            row.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            return row;
        }

        void bind(String p) {
            pkg.setText(p);
            AppInfo info = appCache.get(p);
            if (info == null) {
                label.setText(p);
                icon.setImageDrawable(null);
                ensureApp(p);
            } else {
                label.setText(info.label);
                icon.setImageDrawable(info.icon == null ? null : info.icon.getConstantState() == null
                        ? info.icon : info.icon.getConstantState().newDrawable().mutate());
            }
        }
    }

    private final class RowHolder {
        TextView name, sub, warn, need, error;

        View build() {
            Context c = ActivityLauncherActivity.this;
            LinearLayout outer = new LinearLayout(c);
            outer.setPadding(0, 0, 0, dp(8));
            LinearLayout row = new LinearLayout(c);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(dp(72));
            row.setBackground(Shapes.round(density, m3.surfaceContainerHigh(), 16));
            row.setPadding(dp(18), dp(8), dp(10), dp(8));

            LinearLayout text = new LinearLayout(c);
            text.setOrientation(LinearLayout.VERTICAL);
            name = new TextView(c);
            name.setTypeface(Fonts.display(c));
            name.setTextColor(m3.onSurface());
            name.setTextSize(16);
            text.addView(name);
            sub = new TextView(c);
            sub.setTypeface(Typeface.MONOSPACE);
            sub.setTextColor(m3.onSurfaceVariant());
            sub.setTextSize(12);
            text.addView(sub);
            warn = new TextView(c);
            warn.setText(R.string.diag_launch_risky_tag);
            warn.setTextColor(WARN);
            warn.setTextSize(13);
            text.addView(warn);
            need = new TextView(c);
            need.setTextColor(m3.onSurfaceVariant());
            need.setTextSize(12);
            text.addView(need);
            error = new TextView(c);
            error.setTextColor(WARN);
            error.setTextSize(13);
            text.addView(error);
            row.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            // Visual only: the whole row is the tap target.
            TextView go = new TextView(c);
            go.setText(R.string.diag_launch_button);
            go.setTypeface(Fonts.display(c));
            go.setTextSize(15);
            go.setGravity(Gravity.CENTER);
            go.setMinHeight(dp(56));
            go.setMinWidth(dp(120));
            go.setPadding(dp(18), dp(10), dp(18), dp(10));
            go.setTextColor(m3.onPrimaryContainer());
            go.setBackground(Shapes.pill(density, m3.primaryContainer(),
                    M3.withAlpha(m3.onPrimaryContainer(), 0x40)));
            row.addView(go);
            outer.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return outer;
        }

        void bind(ActivityTargets.Target t) {
            name.setText(t.shortName());
            sub.setText(t.launcher ? t.pkg : getString(R.string.diag_launch_no_icon, t.pkg));
            warn.setVisibility(t.risk == null ? View.GONE : View.VISIBLE);
            need.setVisibility(t.permission == null ? View.GONE : View.VISIBLE);
            if (t.permission != null) need.setText(getString(R.string.diag_launch_needs, t.permission));
            String err = errors.get(t.flat());
            error.setVisibility(err == null ? View.GONE : View.VISIBLE);
            if (err != null) error.setText(err);
        }
    }

    // ---- pieces -----------------------------------------------------------------------

    private TextView pill(String text, boolean primary, final Runnable action) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTypeface(Fonts.display(this));
        t.setTextSize(15);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(18), dp(10), dp(18), dp(10));
        t.setClickable(true);
        Shapes.springy(t);
        style(t, primary);
        t.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                action.run();
                Motion.pop(v);
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(3);
        lp.rightMargin = dp(3);
        t.setLayoutParams(lp);
        return t;
    }

    private void style(TextView t, boolean selected) {
        int fill = selected ? m3.primaryContainer() : m3.surfaceContainerHigh();
        int on = selected ? m3.onPrimaryContainer() : m3.onSurfaceVariant();
        t.setTextColor(on);
        t.setBackground(Shapes.pill(density, fill, M3.withAlpha(on, 0x40)));
    }

    private int dp(int v) {
        return Math.round(v * density);
    }
}
