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

import ie.claudius.cardash.media.NowPlaying;
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
    private NowPlaying nowPlaying;
    private ImageView albumArt;
    private ImageView playButton;
    private TextView trackTitle, trackArtist;
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
        if (nowPlaying == null) nowPlaying = new NowPlaying(this);
        nowPlaying.start(new NowPlaying.Listener() {
            @Override
            public void onNowPlaying(String title, String artist,
                                     android.graphics.Bitmap art, boolean playing) {
                renderNowPlaying(title, artist, art, playing);
            }
        });
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
        if (nowPlaying != null) nowPlaying.stop();
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
        clock.setTypeface(Fonts.display(this));
        clock.setLetterSpacing(-0.03f);
        // A 72sp line box carries ~40px of ascender/descender padding
        // the digits never use, which opened a hole between the clock
        // and the date on the real panel.
        clock.setIncludeFontPadding(false);
        panel.addView(clock);

        date = new TextView(this);
        date.setIncludeFontPadding(false);
        date.setTypeface(Fonts.body(this));
        date.setTextColor(m3.onSurfaceVariant());
        date.setTextSize(18);
        date.setPadding(0, dp(10), 0, dp(20));
        panel.addView(date);

        TextView allApps = new TextView(this);
        allApps.setText(R.string.all_apps);
        allApps.setTypeface(Fonts.display(this));
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

        panel.addView(buildNowPlaying());

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
        hint.setTypeface(Fonts.body(this));
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
        label.setTypeface(Fonts.display(this));
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

    /**
     * Now playing: art, title, artist, transport.
     *
     * Kept in the left column where there was dead space anyway, and
     * where it is reachable from the driver's seat without leaning.
     */
    private View buildNowPlaying() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Shapes.round(density, m3.surfaceContainerHigh(), 28));
        int p = dp(14);
        card.setPadding(p, p, p, p);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        albumArt = new ImageView(this);
        albumArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
        albumArt.setBackground(new MaterialShape(
                MaterialShape.Kind.SQUIRCLE, M3.withAlpha(m3.onSurface(), 0x26)));
        albumArt.setClipToOutline(false);
        top.addView(albumArt, new LinearLayout.LayoutParams(dp(64), dp(64)));

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setPadding(dp(12), 0, 0, 0);

        trackTitle = new TextView(this);
        trackTitle.setTypeface(Fonts.display(this));
        trackTitle.setTextColor(m3.onSurface());
        trackTitle.setTextSize(17);
        trackTitle.setMaxLines(1);
        trackTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        text.addView(trackTitle);

        trackArtist = new TextView(this);
        trackArtist.setTypeface(Fonts.body(this));
        trackArtist.setTextColor(m3.onSurfaceVariant());
        trackArtist.setTextSize(14);
        trackArtist.setMaxLines(1);
        trackArtist.setEllipsize(android.text.TextUtils.TruncateAt.END);
        text.addView(trackArtist);

        top.addView(text, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        card.addView(top);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.setPadding(0, dp(12), 0, 0);
        row.addView(transport(Glyph.Kind.PREV, 0, false));
        playButton = (ImageView) transport(Glyph.Kind.PLAY, 1, true);
        row.addView(playButton);
        row.addView(transport(Glyph.Kind.NEXT, 2, false));
        card.addView(row);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(18);
        card.setLayoutParams(lp);
        return card;
    }

    private View transport(Glyph.Kind kind, final int action, boolean big) {
        ImageView b = new ImageView(this);
        int fill = big ? m3.primary() : m3.surfaceContainer();
        int on = big ? m3.onPrimary() : m3.onSurface();
        b.setImageDrawable(new Glyph(kind, on));
        b.setBackground(Shapes.pill(density, fill, M3.withAlpha(on, 0x40)));
        b.setClickable(true);
        Shapes.springy(b);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (nowPlaying == null) return;
                if (action == 0) nowPlaying.previous();
                else if (action == 1) nowPlaying.playPause();
                else nowPlaying.next();
                // The session reports back asynchronously; nudge it so
                // the button flips even on a player that is slow to
                // publish its new state.
                v.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        nowPlaying.refresh();
                    }
                }, 350);
            }
        });
        int size = big ? dp(60) : dp(48);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
        lp.leftMargin = dp(6);
        lp.rightMargin = dp(6);
        b.setLayoutParams(lp);
        int pad = big ? dp(17) : dp(14);
        b.setPadding(pad, pad, pad, pad);
        return b;
    }

    private void renderNowPlaying(String title, String artist,
                                  android.graphics.Bitmap art, boolean playing) {
        if (trackTitle == null) return;
        boolean known = title != null && !title.trim().isEmpty();
        trackTitle.setText(known ? title : getString(R.string.nothing_playing));
        trackArtist.setText(artist == null ? "" : artist);
        trackArtist.setVisibility(
                artist == null || artist.isEmpty() ? View.GONE : View.VISIBLE);

        if (art != null) {
            albumArt.setImageBitmap(art);
        } else {
            albumArt.setImageDrawable(null);
        }
        if (playButton != null) {
            playButton.setImageDrawable(new Glyph(
                    playing ? Glyph.Kind.PAUSE : Glyph.Kind.PLAY, m3.onPrimary()));
        }
    }

    private View chip(String text, int fill, int onFill) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTypeface(Fonts.body(this));
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
