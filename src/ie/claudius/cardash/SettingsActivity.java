package ie.claudius.cardash;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import ie.claudius.cardash.dash.Trip;
import ie.claudius.cardash.diag.DiagnosticsActivity;

/**
 * Settings, built the same way as everything else: in code, from the
 * current palette. Every option is a segmented row of pills; the theme
 * row is the one exception because a colour is best shown as a colour.
 *
 * Changing the theme or background rebuilds this screen in place so the
 * choice is visible immediately, not after backing out to home.
 */
public class SettingsActivity extends Activity {

    private float density;
    private M3 m3;
    private FrameLayout shell;
    private View page;
    private Object themeWatch;
    private int builtSeed;
    private LinearLayout presetGroup;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        density = getResources().getDisplayMetrics().density;
        shell = new FrameLayout(this);
        setContentView(shell);
        rebuild(true);
    }

    @Override
    protected void onStart() {
        super.onStart();
        themeWatch = ThemeWatch.start(this, new Runnable() {
            @Override
            public void run() {
                // A bundled wallpaper was applied and its colours have
                // just landed: show the resulting palette right here.
                if (Prefs.themeSeed(SettingsActivity.this) != builtSeed) rebuild(false);
            }
        });
    }

    @Override
    protected void onStop() {
        super.onStop();
        ThemeWatch.stop(this, themeWatch);
        themeWatch = null;
    }

    private void rebuild(boolean first) {
        Motion.setCalm(Prefs.calmMotion(this));
        builtSeed = Prefs.themeSeed(this);
        m3 = M3.fromSeed(builtSeed);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(M3.withAlpha(m3.surface(), Prefs.scrimAlpha(this)));
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        column.setPadding(pad, pad, pad, pad);
        scroll.addView(column);

        // ---- header ----
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(8), 0, dp(8), dp(16));

        TextView title = new TextView(this);
        title.setText(R.string.settings);
        title.setTypeface(Fonts.display(this));
        title.setTextColor(m3.onSurface());
        title.setTextSize(26);
        header.addView(title, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView done = pill(getString(R.string.done), true);
        done.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        header.addView(done);
        column.addView(header);

        // ---- appearance ----
        LinearLayout card = card();
        if (Wallpapers.supported(this)) {
            card.addView(wallpaperRow());
            card.addView(divider());
        }
        card.addView(themeRow());
        card.addView(divider());
        card.addView(row(R.string.s_scrim, R.string.s_scrim_caption, Prefs.SCRIM, "balanced",
                new String[] { "glass", "balanced", "solid" },
                new int[] { R.string.s_scrim_glass, R.string.s_scrim_balanced, R.string.s_scrim_solid },
                true));
        card.addView(divider());
        card.addView(row(R.string.s_layout, R.string.s_layout_caption, Prefs.LAYOUT, "hero",
                new String[] { "hero", "grid" },
                new int[] { R.string.s_layout_hero, R.string.s_layout_grid }, false));
        card.addView(divider());
        card.addView(row(R.string.s_motion, R.string.s_motion_caption, Prefs.MOTION, "expressive",
                new String[] { "expressive", "calm" },
                new int[] { R.string.s_motion_expressive, R.string.s_motion_calm }, false));
        column.addView(card, cardParams(0));

        // ---- widgets ----
        card = card();
        card.addView(presetRow());
        card.addView(divider());
        card.addView(dashRow());
        card.addView(divider());
        card.addView(row(R.string.s_gauge, R.string.s_gauge_caption, Prefs.GAUGE, "arc",
                new String[] { "arc", "digits" },
                new int[] { R.string.s_gauge_arc, R.string.s_gauge_digits }, false));
        card.addView(divider());
        card.addView(row(R.string.s_scale, R.string.s_scale_caption, Prefs.SCALE, "auto",
                new String[] { "city", "auto", "fast" },
                new int[] { R.string.s_scale_city, R.string.s_scale_auto, R.string.s_scale_fast },
                false));
        card.addView(divider());
        card.addView(row(R.string.s_date, R.string.s_date_caption, Prefs.DATE, "full",
                new String[] { "full", "short", "hide" },
                new int[] { R.string.s_date_full, R.string.s_date_short, R.string.s_date_hide },
                false));
        card.addView(divider());
        card.addView(row(R.string.s_media, R.string.s_media_caption, Prefs.MEDIA, "full",
                new String[] { "full", "compact", "hide" },
                new int[] { R.string.s_media_full, R.string.s_media_compact, R.string.s_media_hide },
                false));
        card.addView(divider());
        card.addView(row(R.string.s_vehicle, R.string.s_vehicle_caption, Prefs.VEHICLE, "auto",
                new String[] { "auto", "obd", "can", "off" },
                new int[] { R.string.s_vehicle_auto, R.string.s_vehicle_obd,
                        R.string.s_vehicle_can, R.string.s_vehicle_off }, false));
        card.addView(divider());
        card.addView(actionRow(R.string.s_trip_reset, R.string.s_trip_reset_caption,
                R.string.s_reset_action, new Runnable() {
                    @Override
                    public void run() {
                        new Trip(SettingsActivity.this).reset();
                    }
                }));
        column.addView(card, cardParams(dp(16)));

        // ---- units ----
        card = card();
        card.addView(row(R.string.s_speed, R.string.s_speed_caption, Prefs.SPEED, "kmh",
                new String[] { "kmh", "mph" },
                new int[] { R.string.s_speed_kmh, R.string.s_speed_mph }, false));
        card.addView(divider());
        card.addView(row(R.string.s_temp, R.string.s_temp_caption, Prefs.TEMP, "c",
                new String[] { "c", "f" },
                new int[] { R.string.s_temp_c, R.string.s_temp_f }, false));
        card.addView(divider());
        card.addView(row(R.string.s_clock, R.string.s_clock_caption, Prefs.CLOCK, "system",
                new String[] { "system", "12", "24" },
                new int[] { R.string.s_clock_system, R.string.s_clock_12, R.string.s_clock_24 },
                false));
        column.addView(card, cardParams(dp(16)));

        // ---- home ----
        card = card();
        card.addView(row(R.string.s_hint, R.string.s_hint_caption, Prefs.HINT, "show",
                new String[] { "show", "hide" },
                new int[] { R.string.s_hint_show, R.string.s_hint_hide }, false));
        card.addView(divider());
        card.addView(actionRow(R.string.s_reset, R.string.s_reset_caption,
                R.string.s_reset_action, new Runnable() {
                    @Override
                    public void run() {
                        Apps.reset(SettingsActivity.this);
                    }
                }));
        card.addView(divider());
        card.addView(linkRow(R.string.s_diag, R.string.s_diag_caption, R.string.s_diag_open,
                new Intent(this, DiagnosticsActivity.class)));
        column.addView(card, cardParams(dp(16)));

        View old = page;
        page = scroll;
        Motion.crossfade(shell, old, scroll);

        // Cards rise in one after another, the same way home's tiles do.
        for (int i = 1; i < column.getChildCount(); i++) {
            Motion.enter(column.getChildAt(i), first ? 40L * i : 0L, 0f, dp(20));
        }
    }

    // ---- rows -------------------------------------------------------------

    private View row(int titleRes, int captionRes, final String key, final String def,
                     final String[] values, int[] labels, final boolean rethemes) {
        LinearLayout row = rowShell(titleRes, captionRes);

        final LinearLayout group = new LinearLayout(this);
        group.setOrientation(LinearLayout.HORIZONTAL);
        final String current = Prefs.get(this, key, def);
        for (int i = 0; i < values.length; i++) {
            final String value = values[i];
            final TextView opt = pill(getString(labels[i]), value.equals(current));
            opt.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (value.equals(Prefs.get(SettingsActivity.this, key, def))) return;
                    Prefs.set(SettingsActivity.this, key, value);
                    handTuned();
                    if (rethemes || Prefs.MOTION.equals(key)) {
                        rebuild(false);
                        return;
                    }
                    for (int j = 0; j < group.getChildCount(); j++) {
                        TextView o = (TextView) group.getChildAt(j);
                        style(o, o == opt);
                    }
                    Motion.pop(opt);
                }
            });
            group.addView(opt);
        }
        row.addView(group);
        return row;
    }

    /** Any widget change un-highlights the preset; the presets are shapes, not modes. */
    private void handTuned() {
        Prefs.touched(this);
        if (presetGroup == null) return;
        for (int j = 0; j < presetGroup.getChildCount(); j++) {
            style((TextView) presetGroup.getChildAt(j), false);
        }
    }

    /** Curated layouts. Applying one rebuilds so every row reflects it. */
    private View presetRow() {
        LinearLayout row = rowShell(R.string.s_preset, R.string.s_preset_caption);
        presetGroup = new LinearLayout(this);
        presetGroup.setOrientation(LinearLayout.HORIZONTAL);
        String current = Prefs.preset(this);
        int[] labels = { R.string.s_preset_classic, R.string.s_preset_driver,
                R.string.s_preset_launcher, R.string.s_preset_minimal };
        for (int i = 0; i < Prefs.PRESETS.length; i++) {
            final String preset = Prefs.PRESETS[i];
            TextView opt = pill(getString(labels[i]), preset.equals(current));
            opt.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Prefs.applyPreset(SettingsActivity.this, preset);
                    rebuild(false);
                }
            });
            presetGroup.addView(opt);
        }
        row.addView(presetGroup);
        return row;
    }

    /** Multi-select: each dash widget toggles independently. */
    private View dashRow() {
        LinearLayout row = rowShell(R.string.s_dash, R.string.s_dash_caption);
        LinearLayout group = new LinearLayout(this);
        group.setOrientation(LinearLayout.HORIZONTAL);
        int[] labels = { R.string.s_dash_speedo, R.string.s_dash_trip, R.string.s_dash_weather };
        java.util.List<String> on = Prefs.dashWidgets(this);
        for (int i = 0; i < Prefs.DASH_ALL.length; i++) {
            final String widget = Prefs.DASH_ALL[i];
            final TextView opt = pill(getString(labels[i]), on.contains(widget));
            opt.setTag(on.contains(widget));
            opt.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    boolean now = !(Boolean) opt.getTag();
                    opt.setTag(now);
                    Prefs.setDashWidget(SettingsActivity.this, widget, now);
                    handTuned();
                    style(opt, now);
                    Motion.pop(opt);
                }
            });
            group.addView(opt);
        }
        row.addView(group);
        return row;
    }

    /**
     * Thumbnails of the bundled designs, drawn by the same code that
     * draws the full-size wallpaper so what you see is what you get.
     */
    private View wallpaperRow() {
        LinearLayout row = rowShell(R.string.s_wallpaper, R.string.s_wallpaper_caption);

        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout group = new LinearLayout(this);
        group.setOrientation(LinearLayout.HORIZONTAL);
        group.setGravity(Gravity.CENTER_VERTICAL);
        hs.addView(group);

        String current = Prefs.get(this, Prefs.WALLPAPER, "");
        int tw = dp(88), th = dp(50), ring = dp(3);
        for (final Wallpapers.Design d : Wallpapers.ALL) {
            boolean selected = d.name.equals(current);

            ImageView thumb = new ImageView(this);
            thumb.setImageBitmap(Wallpapers.render(d, tw, th));
            thumb.setScaleType(ImageView.ScaleType.FIT_XY);
            thumb.setBackground(Shapes.round(density, m3.surfaceContainerHigh(), 12));
            thumb.setClipToOutline(true);
            thumb.setContentDescription(d.name);

            // The ring is a padded frame behind the thumbnail, so the
            // selected one grows a border without shifting its siblings.
            final FrameLayout frame = new FrameLayout(this);
            frame.setBackground(selected
                    ? Shapes.round(density, m3.onSurface(), 15) : null);
            frame.setPadding(ring, ring, ring, ring);
            frame.addView(thumb, new FrameLayout.LayoutParams(tw, th));
            frame.setClickable(true);
            Shapes.springy(frame);
            frame.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    frame.setAlpha(0.5f);
                    Wallpapers.apply(SettingsActivity.this, d, new Runnable() {
                        @Override
                        public void run() {
                            // Colours arrive via ThemeWatch; this only
                            // moves the ring.
                            rebuild(false);
                        }
                    });
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.leftMargin = dp(4);
            lp.rightMargin = dp(4);
            group.addView(frame, lp);
        }
        row.addView(hs);
        return row;
    }

    /** Wallpaper pill followed by a swatch per preset seed. */
    private View themeRow() {
        LinearLayout row = rowShell(R.string.s_theme, R.string.s_theme_caption);

        // On a 30 cm screen this fits; on a narrower unit it scrolls.
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout group = new LinearLayout(this);
        group.setOrientation(LinearLayout.HORIZONTAL);
        group.setGravity(Gravity.CENTER_VERTICAL);
        hs.addView(group);

        String current = Prefs.get(this, Prefs.SEED, "wallpaper");
        TextView auto = pill(getString(R.string.s_theme_wallpaper), "wallpaper".equals(current));
        auto.setOnClickListener(chooser(Prefs.SEED, "wallpaper"));
        group.addView(auto);

        for (int seed : Prefs.SEEDS) {
            String hex = String.format("%06X", seed & 0xFFFFFF);
            boolean selected = hex.equalsIgnoreCase(current);
            View dot = new View(this);
            // Show the seed's own primary tone, so the swatch is honest
            // about the palette it would produce.
            dot.setBackground(Shapes.dot(density, M3.fromSeed(seed).primary(),
                    selected ? m3.onSurface() : 0, selected ? 3 : 0));
            dot.setClickable(true);
            Shapes.springy(dot);
            dot.setOnClickListener(chooser(Prefs.SEED, hex));
            int sz = dp(40);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(sz, sz);
            lp.leftMargin = dp(6);
            lp.rightMargin = dp(6);
            group.addView(dot, lp);
        }
        row.addView(hs);
        return row;
    }

    /** A row whose button opens another screen. */
    private View linkRow(int titleRes, int captionRes, int actionRes, final Intent target) {
        LinearLayout row = rowShell(titleRes, captionRes);
        TextView button = pill(getString(actionRes), false);
        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(target);
            }
        });
        row.addView(button);
        return row;
    }

    /** A one-shot button that confirms itself by turning into a tick. */
    private View actionRow(int titleRes, int captionRes, int actionRes, final Runnable action) {
        LinearLayout row = rowShell(titleRes, captionRes);
        final TextView button = pill(getString(actionRes), false);
        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                action.run();
                button.setText(R.string.s_reset_done);
                style(button, true);
                Motion.pop(button);
            }
        });
        row.addView(button);
        return row;
    }

    private View.OnClickListener chooser(final String key, final String value) {
        return new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (value.equals(Prefs.get(SettingsActivity.this, key, ""))) return;
                Prefs.set(SettingsActivity.this, key, value);
                rebuild(false);
            }
        };
    }

    // ---- pieces -------------------------------------------------------------

    private LinearLayout rowShell(int titleRes, int captionRes) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(12), 0, dp(12));

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(this);
        title.setText(titleRes);
        title.setTypeface(Fonts.display(this));
        title.setTextColor(m3.onSurface());
        title.setTextSize(18);
        text.addView(title);

        TextView caption = new TextView(this);
        caption.setText(captionRes);
        caption.setTypeface(Fonts.body(this));
        caption.setTextColor(m3.onSurfaceVariant());
        caption.setTextSize(13);
        caption.setPadding(0, dp(2), 0, 0);
        text.addView(caption);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.rightMargin = dp(16);
        row.addView(text, lp);
        return row;
    }

    private TextView pill(String text, boolean selected) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTypeface(Fonts.display(this));
        t.setTextSize(15);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(18), dp(10), dp(18), dp(10));
        t.setClickable(true);
        Shapes.springy(t);
        style(t, selected);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
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

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Shapes.round(density, m3.surfaceContainer(), 28));
        card.setPadding(dp(24), dp(8), dp(24), dp(8));
        return card;
    }

    private LinearLayout.LayoutParams cardParams(int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = top;
        return lp;
    }

    private View divider() {
        View v = new View(this);
        v.setBackgroundColor(M3.withAlpha(m3.outline(), 0x33));
        v.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1))));
        return v;
    }

    private int dp(int v) {
        return Math.round(v * density);
    }
}
