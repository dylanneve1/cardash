package ie.claudius.cardash;

import android.app.Activity;
import android.app.WallpaperManager;
import android.app.WallpaperColors;
import android.media.AudioManager;
import android.view.KeyEvent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import ie.claudius.cardash.vehicle.VehicleHub;
import ie.claudius.cardash.vehicle.VehicleState;

/** The home screen. Everything is built in code so the theme can be
 *  rebuilt from scratch whenever the wallpaper colour changes. */
public class HomeActivity extends Activity {

    private static final int REQ_PICK = 1;
    private static final int COLUMNS = 6;
    private static final int ROWS = 3;

    /**
     * The mosaic: {column, row, colSpan, rowSpan} per tile.
     *
     * Material 3 Expressive varies container SIZE, not just corner
     * radius — a grid of identical squares is the thing it exists to
     * get away from. The 2x2 hero is also the one you want to hit
     * without looking, so the biggest target is the most-used app.
     */
    private static final int[][] SPANS = {
            { 0, 0, 2, 2 },
            { 2, 0, 2, 1 }, { 4, 0, 2, 1 },
            { 2, 1, 1, 1 }, { 3, 1, 1, 1 }, { 4, 1, 2, 1 },
            { 0, 2, 1, 1 }, { 1, 2, 1, 1 }, { 2, 2, 1, 1 },
            { 3, 2, 1, 1 }, { 4, 2, 1, 1 }, { 5, 2, 1, 1 },
    };
    /** Used when the wallpaper has no extractable colour (solid black, etc). */
    private static final int FALLBACK_SEED = 0xFF4F7BD5;

    private float density;
    private M3 m3;
    private FrameLayout root;
    private TextView clock, date;
    private LinearLayout vehicleBar;
    private final VehicleHub vehicle = new VehicleHub();
    private int pendingSlot = -1;

    private final BroadcastReceiver timeTick = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            updateClock();
        }
    };

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        density = getResources().getDisplayMetrics().density;
        Apps.seedIfEmpty(this);
        rebuild();
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter f = new IntentFilter(Intent.ACTION_TIME_TICK);
        f.addAction(Intent.ACTION_TIME_CHANGED);
        f.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        registerReceiver(timeTick, f);
        updateClock();
        vehicle.start(this, new VehicleState.Listener() {
            @Override
            public void onVehicleState(VehicleState state) {
                renderVehicle(state);
            }
        });
        // An app may have been installed or removed while we were away.
        rebuild();
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            unregisterReceiver(timeTick);
        } catch (IllegalArgumentException ignored) {
            // never registered; harmless
        }
        vehicle.stop();
    }

    /** Home is the bottom of the stack — back should do nothing at all. */
    @Override
    public void onBackPressed() {
        // intentionally empty
    }

    // ---- theme ----------------------------------------------------------

    private int wallpaperSeed() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) return FALLBACK_SEED;
        try {
            WallpaperColors c = WallpaperManager.getInstance(this)
                    .getWallpaperColors(WallpaperManager.FLAG_SYSTEM);
            if (c != null && c.getPrimaryColor() != null) {
                return c.getPrimaryColor().toArgb();
            }
        } catch (Exception ignored) {
            // some vendor ROMs throw here; fall through
        }
        return FALLBACK_SEED;
    }

    // ---- build ----------------------------------------------------------

    private void rebuild() {
        m3 = M3.fromSeed(wallpaperSeed());

        root = new FrameLayout(this);
        // A tonal scrim so the wallpaper still reads through but text
        // stays legible in daylight.
        root.setBackgroundColor(M3.withAlpha(m3.surface(), 0xD8));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        int pad = dp(20);
        row.setPadding(pad, pad, pad, pad);
        root.addView(row, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        row.addView(buildClockPanel(), new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 0.34f));
        row.addView(buildGrid(), new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 0.66f));

        setContentView(root);
        updateClock();
    }

    private View buildClockPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER_VERTICAL);
        panel.setBackground(Shapes.round(density, m3.surfaceContainer(), 36));
        int p = dp(24);
        panel.setPadding(p, p, p, p);

        clock = new TextView(this);
        clock.setTextColor(m3.primary());
        clock.setTextSize(72);
        // Expressive leans on weight contrast rather than decoration.
        clock.setTypeface(Fonts.display());
        clock.setLetterSpacing(-0.03f);
        panel.addView(clock);

        date = new TextView(this);
        date.setTextColor(m3.onSurfaceVariant());
        date.setTextSize(18);
        date.setPadding(0, dp(4), 0, dp(20));
        panel.addView(date);

        TextView allApps = new TextView(this);
        allApps.setText(R.string.all_apps);
        allApps.setTextColor(m3.onPrimaryContainer());
        allApps.setTextSize(18);
        allApps.setGravity(Gravity.CENTER);
        allApps.setBackground(Shapes.pill(density, m3.primaryContainer(),
                M3.withAlpha(m3.onPrimaryContainer(), 0x40)));
        allApps.setPadding(dp(28), dp(14), dp(28), dp(14));
        allApps.setClickable(true);
        Shapes.springy(allApps);
        allApps.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pendingSlot = -1;
                startActivity(new Intent(HomeActivity.this, AppListActivity.class));
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        panel.addView(allApps, lp);

        // Media transport. dispatchMediaKeyEvent needs no permission and
        // reaches whatever is actually playing — the vendor music app,
        // Bluetooth audio, Android Auto — without binding to any of them.
        LinearLayout media = new LinearLayout(this);
        media.setOrientation(LinearLayout.HORIZONTAL);
        media.setPadding(0, dp(18), 0, 0);
        media.addView(mediaKey(Glyph.Kind.PREV, KeyEvent.KEYCODE_MEDIA_PREVIOUS,
                m3.surfaceContainerHigh(), m3.onSurface(), false));
        media.addView(mediaKey(Glyph.Kind.PLAY, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                m3.primary(), m3.onPrimary(), true));
        media.addView(mediaKey(Glyph.Kind.NEXT, KeyEvent.KEYCODE_MEDIA_NEXT,
                m3.surfaceContainerHigh(), m3.onSurface(), false));
        panel.addView(media);

        vehicleBar = new LinearLayout(this);
        vehicleBar.setOrientation(LinearLayout.HORIZONTAL);
        vehicleBar.setPadding(0, dp(16), 0, 0);
        // Nothing plugged in means nothing to show — an empty strip of
        // dashes looks broken, so the whole row stays gone until a
        // source actually reports something.
        vehicleBar.setVisibility(View.GONE);
        panel.addView(vehicleBar);

        TextView hint = new TextView(this);
        hint.setText(R.string.hint_long_press);
        hint.setTextColor(M3.withAlpha(m3.onSurfaceVariant(), 0x99));
        hint.setTextSize(12);
        hint.setPadding(0, dp(14), 0, 0);
        panel.addView(hint);

        return wrapMargin(panel, 0, 0, dp(16), 0);
    }

    private View buildGrid() {
        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(COLUMNS);
        grid.setRowCount(ROWS);

        for (int i = 0; i < Apps.TILES && i < SPANS.length; i++) {
            int[] sp = SPANS[i];
            boolean hero = sp[2] > 1 && sp[3] > 1;
            View tile = buildTile(i, hero);

            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = 0;
            lp.height = 0;
            lp.columnSpec = GridLayout.spec(sp[0], sp[2], 1f);
            lp.rowSpec = GridLayout.spec(sp[1], sp[3], 1f);
            int g = dp(7);
            lp.setMargins(g, g, g, g);
            grid.addView(tile, lp);

            // Staggered entry — expressive motion, and it also makes a
            // slow head unit feel like it's doing something.
            tile.setAlpha(0f);
            tile.setTranslationY(dp(24));
            tile.animate()
                    .alpha(1f).translationY(0f)
                    .setStartDelay(35L * i)
                    .setDuration(340)
                    .setInterpolator(new DecelerateInterpolator(2f))
                    .start();
        }
        return grid;
    }

    private View buildTile(final int slot, boolean hero) {
        final String pkg = Apps.tile(this, slot);
        Apps.Entry entry = Apps.byPackage(this, pkg);

        // Rotate through the container roles so the grid has rhythm
        // instead of eight identical boxes.
        int fill;
        int onFill;
        switch (slot % 4) {
            case 0: fill = m3.primaryContainer();   onFill = m3.onPrimaryContainer();   break;
            case 1: fill = m3.secondaryContainer(); onFill = m3.onSecondaryContainer(); break;
            case 2: fill = m3.tertiaryContainer();  onFill = m3.onTertiaryContainer();  break;
            default: fill = m3.surfaceContainerHigh(); onFill = m3.onSurface();         break;
        }

        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER);
        tile.setBackground(Shapes.tile(density, fill,
                M3.withAlpha(onFill, 0x40), slot));
        tile.setClickable(true);
        tile.setPadding(dp(8), dp(8), dp(8), dp(8));
        Shapes.springy(tile);

        // Material 3 Expressive icon holder — clover, flower, burst,
        // squircle. The shape carries the variety; the app icon stays
        // recognisable inside it.
        FrameLayout holder = new FrameLayout(this);
        holder.setBackground(new MaterialShape(
                MaterialShape.forIndex(slot), M3.withAlpha(onFill, 0x30),
                slot * 11f));
        int hp = hero ? dp(18) : dp(11);
        holder.setPadding(hp, hp, hp, hp);

        ImageView icon = new ImageView(this);
        if (entry != null) {
            icon.setImageDrawable(entry.icon);
        } else {
            icon.setImageResource(android.R.drawable.ic_input_add);
            icon.setColorFilter(onFill);
        }
        int isz = hero ? dp(104) : dp(64);
        holder.addView(icon, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        tile.addView(holder, new LinearLayout.LayoutParams(isz, isz));

        TextView label = new TextView(this);
        label.setText(entry != null ? entry.label : getString(R.string.empty_tile));
        label.setTextColor(onFill);
        label.setTypeface(Fonts.display());
        label.setTextSize(hero ? 22 : 15);
        label.setMaxLines(1);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        label.setGravity(Gravity.CENTER);
        label.setPadding(0, dp(8), 0, 0);
        tile.addView(label);

        tile.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (pkg == null || !Apps.launch(HomeActivity.this, pkg)) {
                    pick(slot);
                }
            }
        });
        tile.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                v.performHapticFeedback(
                        android.view.HapticFeedbackConstants.LONG_PRESS);
                pick(slot);
                return true;
            }
        });
        return tile;
    }

    private void pick(int slot) {
        pendingSlot = slot;
        Intent i = new Intent(this, AppListActivity.class);
        i.putExtra(AppListActivity.EXTRA_PICK, true);
        startActivityForResult(i, REQ_PICK);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_PICK && res == RESULT_OK && data != null && pendingSlot >= 0) {
            String pkg = data.getStringExtra(AppListActivity.EXTRA_PACKAGE);
            if (pkg != null) Apps.setTile(this, pendingSlot, pkg);
            pendingSlot = -1;
            rebuild();
        }
    }

    // ---- helpers --------------------------------------------------------

    /** Draw only the fields we genuinely know. */
    private void renderVehicle(VehicleState s) {
        if (vehicleBar == null) return;
        vehicleBar.removeAllViews();

        if (s.fuelPercent != VehicleState.UNKNOWN_INT) {
            boolean low = s.fuelPercent <= 15;
            vehicleBar.addView(chip("Fuel " + s.fuelPercent + "%",
                    low ? m3.tertiaryContainer() : m3.surfaceContainerHigh(),
                    low ? m3.onTertiaryContainer() : m3.onSurface()));
        }
        if (s.coolantC != VehicleState.UNKNOWN_INT) {
            vehicleBar.addView(chip(s.coolantC + "\u00B0C",
                    m3.surfaceContainerHigh(), m3.onSurface()));
        }
        if (s.rpm != VehicleState.UNKNOWN_INT && s.rpm > 0) {
            vehicleBar.addView(chip(s.rpm + " rpm",
                    m3.surfaceContainerHigh(), m3.onSurface()));
        }
        int open = s.openDoors();
        if (open > 0) {
            vehicleBar.addView(chip(open == 1 ? "Door open" : open + " doors open",
                    m3.primaryContainer(), m3.onPrimaryContainer()));
        }

        vehicleBar.setVisibility(
                vehicleBar.getChildCount() > 0 ? View.VISIBLE : View.GONE);
    }

    /** One circular transport button. */
    private View mediaKey(Glyph.Kind kind, final int keyCode,
                          int fill, int onFill, boolean big) {
        ImageView b = new ImageView(this);
        b.setImageDrawable(new Glyph(kind, onFill));
        b.setBackground(Shapes.pill(density, fill, M3.withAlpha(onFill, 0x40)));
        b.setClickable(true);
        Shapes.springy(b);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sendMediaKey(keyCode);
            }
        });
        int size = big ? dp(64) : dp(52);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
        lp.rightMargin = dp(10);
        lp.gravity = Gravity.CENTER_VERTICAL;
        b.setLayoutParams(lp);
        int p = big ? dp(18) : dp(15);
        b.setPadding(p, p, p, p);
        return b;
    }

    private void sendMediaKey(int keyCode) {
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (am == null) return;
        am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, keyCode));
        am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, keyCode));
    }

    private View chip(String text, int fill, int onFill) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(onFill);
        t.setTextSize(15);
        t.setBackground(Shapes.pill(density, fill, M3.withAlpha(onFill, 0x33)));
        t.setPadding(dp(14), dp(8), dp(14), dp(8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(8);
        t.setLayoutParams(lp);
        return t;
    }

    private void updateClock() {
        if (clock == null) return;
        Date now = new Date();
        String pattern = android.text.format.DateFormat.is24HourFormat(this)
                ? "HH:mm" : "h:mm";
        clock.setText(new SimpleDateFormat(pattern, Locale.getDefault()).format(now));
        date.setText(new SimpleDateFormat("EEEE d MMMM", Locale.getDefault()).format(now));
    }

    private View wrapMargin(View v, int l, int t, int r, int b) {
        FrameLayout holder = new FrameLayout(this);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
        lp.setMargins(l, t, r, b);
        holder.addView(v, lp);
        return holder;
    }

    private int dp(int v) {
        return Math.round(v * density);
    }
}
