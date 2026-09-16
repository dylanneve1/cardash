package ie.claudius.cardash;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import ie.claudius.cardash.dash.SpeedSource;
import ie.claudius.cardash.dash.Speedo;
import ie.claudius.cardash.dash.Trip;
import ie.claudius.cardash.dash.Weather;
import ie.claudius.cardash.diag.DiagnosticsActivity;
import ie.claudius.cardash.media.NowPlaying;
import ie.claudius.cardash.vehicle.VehicleHub;
import ie.claudius.cardash.vehicle.VehicleState;

/**
 * The home screen. Everything is built in code so the theme can be
 * rebuilt from scratch whenever the wallpaper colour or a setting
 * changes — and only then. Coming back from an app used to rebuild and
 * replay the entrance every time; now a signature of everything that
 * feeds the layout is compared first, and an unchanged screen is left
 * exactly as it was.
 *
 * Three columns: the clock column (clock, date, buttons, now playing,
 * car data), the dash column (whichever of speed / trip / weather are
 * switched on — or nothing, in which case the tiles take its room), and
 * the tiles.
 */
public class HomeActivity extends Activity {

    private static final int REQ_PICK = 1;
    private static final int ROWS = 4;

    private float density;
    private M3 m3;
    private FrameLayout shell;
    private View root;
    private String builtSignature;
    private Object themeWatch;

    private TextView clock, date;
    private LinearLayout vehicleBar;
    private final Map<String, TextView> chips = new HashMap<>();
    private final VehicleHub vehicle = VehicleHub.shared();
    private final VehicleState.Listener vehicleListener = new VehicleState.Listener() {
        @Override
        public void onVehicleState(VehicleState state) {
            lastVehicle = state;
            if (state.speedKph != VehicleState.UNKNOWN_INT) {
                obdSpeedAt = System.currentTimeMillis();
                lastKph = state.speedKph;
                lastFix = true;
                if (speedo != null) speedo.setSpeed(lastKph, true);
            }
            renderVehicle();
        }
    };
    private NowPlaying nowPlaying;
    private ImageView albumArt;
    private ImageView playButton;
    private TextView trackTitle, trackArtist;
    private Speedo speedo;
    private TextView weatherTemp, weatherDesc;
    private TextView tripDistance, tripDetail;
    private SpeedSource speed;
    private Trip trip;
    private final Weather weather = new Weather();
    private int pendingSlot = -1;

    // Last known state from each source, so a rebuilt screen can be
    // filled in immediately instead of sitting empty until the next
    // callback arrives.
    private String npTitle, npArtist;
    private Bitmap npArt;
    private boolean npPlaying, npKnown, shownPlaying;
    private int wxTempC;
    private String wxDesc;
    private boolean wxKnown;
    private VehicleState lastVehicle;
    private float lastKph;
    private boolean lastFix;
    /** GPS speed yields to OBD speed for this long after each OBD reading. */
    private static final long OBD_SPEED_HOLD_MS = 3000L;
    private long obdSpeedAt;

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
        trip = new Trip(this);
        shell = new FrameLayout(this);
        setContentView(shell);
        rebuild(true);
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter f = new IntentFilter(Intent.ACTION_TIME_TICK);
        f.addAction(Intent.ACTION_TIME_CHANGED);
        f.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        Receivers.register(this, timeTick, f, false);
        if (speed == null) speed = new SpeedSource(this);
        speed.start(new SpeedSource.Listener() {
            @Override
            public void onSpeed(float kph, boolean hasFix) {
                // The car's own speed beats GPS — no lag under braking,
                // no dropout in a tunnel — so while OBD is reporting,
                // GPS only feeds the trip meter.
                if (System.currentTimeMillis() - obdSpeedAt < OBD_SPEED_HOLD_MS) return;
                lastKph = kph;
                lastFix = hasFix;
                if (speedo != null) speedo.setSpeed(kph, hasFix);
            }

            @Override
            public void onPosition(double lat, double lon) {
                weather.setLocation(lat, lon);
            }

            @Override
            public void onTravel(float metres, long dtMs, float kph) {
                trip.travel(metres, dtMs, kph);
            }
        });
        trip.setListener(new Trip.Listener() {
            @Override
            public void onTrip(float metres, long movingMs) {
                renderTrip();
            }
        });
        weather.start(this, new Weather.Listener() {
            @Override
            public void onWeather(int tempC, String description, int code) {
                wxTempC = tempC;
                wxDesc = description;
                wxKnown = true;
                renderWeather();
            }
        });
        if (nowPlaying == null) nowPlaying = new NowPlaying(this);
        nowPlaying.start(new NowPlaying.Listener() {
            @Override
            public void onNowPlaying(String title, String artist, Bitmap art, boolean playing) {
                npTitle = title;
                npArtist = artist;
                npArt = art;
                npPlaying = playing;
                npKnown = true;
                renderNowPlaying();
            }
        });
        if (Prefs.showVehicle(this)) vehicle.start(this, vehicleListener, Prefs.vehicleMode(this));

        // A setting, the wallpaper, or the set of installed apps may
        // have changed while we were away.
        rebuildIfChanged();
        // ...and the wallpaper's colours can land after we have already
        // resumed, if it was set a moment ago.
        themeWatch = ThemeWatch.start(this, new Runnable() {
            @Override
            public void run() {
                rebuildIfChanged();
            }
        });
    }

    private void rebuildIfChanged() {
        if (!signature().equals(builtSignature)) {
            rebuild(false);
        } else {
            updateClock();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        Receivers.unregister(this, timeTick);
        ThemeWatch.stop(this, themeWatch);
        themeWatch = null;
        vehicle.stop(vehicleListener);
        if (nowPlaying != null) nowPlaying.stop();
        if (speed != null) speed.stop();
        trip.setListener(null);
        trip.save();
        weather.stop();
    }

    /** Home is the bottom of the stack — back should do nothing at all. */
    @Override
    public void onBackPressed() {
        // intentionally empty
    }

    // ---- build ----------------------------------------------------------

    private String signature() {
        return Prefs.signature(this) + '#'
                + Integer.toHexString(Prefs.themeSeed(this)) + '#'
                + Apps.signature(this);
    }

    private void rebuild(boolean first) {
        builtSignature = signature();
        Motion.setCalm(Prefs.calmMotion(this));
        m3 = M3.fromSeed(Prefs.themeSeed(this));
        chips.clear();
        speedo = null;
        weatherTemp = weatherDesc = null;
        tripDistance = tripDetail = null;
        albumArt = playButton = null;
        trackTitle = trackArtist = null;

        FrameLayout page = new FrameLayout(this);
        // A tonal scrim so the wallpaper still reads through but text
        // stays legible in daylight. How much is a setting.
        page.setBackgroundColor(M3.withAlpha(m3.surface(), Prefs.scrimAlpha(this)));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        int pad = dp(20);
        row.setPadding(pad, pad, pad, pad);
        page.addView(row, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        List<String> widgets = Prefs.dashWidgets(this);
        boolean hasDash = !widgets.isEmpty();

        View clockPanel = buildClockPanel();
        View dashPanel = hasDash ? buildDashPanel(widgets) : null;
        // No dash column: the tiles take its width and gain a column,
        // so they stay roughly square rather than becoming letterboxes.
        View grid = buildGrid(hasDash ? 2 : 3);
        row.addView(clockPanel, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, hasDash ? 0.30f : 0.32f));
        if (hasDash) {
            row.addView(dashPanel, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.MATCH_PARENT, 0.30f));
        }
        row.addView(grid, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, hasDash ? 0.40f : 0.68f));

        View old = root;
        root = page;
        Motion.crossfade(shell, old, page);

        // Choreography: the clock slides in from the driver's side, the
        // dash rises, then the tiles cascade — one motion reading left
        // to right rather than everything at once.
        Motion.enter(clockPanel, 0, -dp(28), 0f);
        if (dashPanel != null) Motion.enter(dashPanel, 60, 0f, dp(28));
        // (tiles stagger themselves in buildGrid)

        updateClock();
        renderWeather();
        renderNowPlaying();
        renderVehicle();
        renderTrip();
        if (speedo != null) speedo.setSpeed(lastKph, lastFix);
    }

    private View buildClockPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER_VERTICAL);
        panel.setBackground(Shapes.round(density, m3.surfaceContainer(), 36));
        int p = dp(24);
        panel.setPadding(p, p, p, p);
        // The vehicle strip comes and goes; let the rest of the column
        // glide to make room rather than jump.
        Motion.animateChildren(panel);

        clock = new TextView(this);
        clock.setTextColor(m3.primary());
        clock.setTextSize(72);
        // Display cut: Google Sans Flex at optical size 72, which
        // tightens the counters the way a clock face wants.
        clock.setTypeface(Fonts.hero(this));
        clock.setLetterSpacing(-0.03f);
        // A 72sp line box carries ~40px of ascender/descender padding
        // the digits never use, which opened a hole between the clock
        // and the date on the real panel.
        clock.setIncludeFontPadding(false);
        // The diagnostics instrument hides behind the clock: needed
        // rarely, but needed from the car, without a keyboard.
        clock.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                startActivity(new Intent(HomeActivity.this, DiagnosticsActivity.class));
                return true;
            }
        });
        panel.addView(clock);

        date = new TextView(this);
        date.setIncludeFontPadding(false);
        date.setTypeface(Fonts.body(this));
        date.setTextColor(m3.onSurfaceVariant());
        date.setTextSize(18);
        date.setPadding(0, dp(10), 0, dp(20));
        if (Prefs.datePattern(this) == null) {
            date.setVisibility(View.GONE);
            clock.setPadding(0, 0, 0, dp(20));
        }
        panel.addView(date);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);

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
        actions.addView(allApps);

        ImageView gear = new ImageView(this);
        gear.setImageDrawable(new Glyph(Glyph.Kind.GEAR, m3.onSurfaceVariant()));
        gear.setBackground(Shapes.pill(density, m3.surfaceContainerHigh(),
                M3.withAlpha(m3.onSurface(), 0x40)));
        gear.setClickable(true);
        gear.setContentDescription(getString(R.string.settings));
        Shapes.springy(gear);
        gear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(HomeActivity.this, SettingsActivity.class));
            }
        });
        int gs = dp(50);
        gear.setPadding(dp(13), dp(13), dp(13), dp(13));
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(gs, gs);
        glp.leftMargin = dp(10);
        actions.addView(gear, glp);
        panel.addView(actions);

        String media = Prefs.media(this);
        if (!"hide".equals(media)) {
            panel.addView(buildNowPlaying("compact".equals(media)));
        }

        // Nothing plugged in means nothing to show — an empty strip of
        // dashes looks broken, so the whole row stays gone until a
        // source actually reports something. Three chips can outgrow a
        // 30% column, so overflow scrolls instead of clipping.
        HorizontalScrollView chipScroll = new HorizontalScrollView(this);
        chipScroll.setHorizontalScrollBarEnabled(false);
        chipScroll.setPadding(0, dp(16), 0, 0);
        chipScroll.setVisibility(View.GONE);
        vehicleBar = new LinearLayout(this);
        vehicleBar.setOrientation(LinearLayout.HORIZONTAL);
        Motion.animateChildren(vehicleBar);
        chipScroll.addView(vehicleBar);
        if (Prefs.showVehicle(this)) panel.addView(chipScroll);

        if (Prefs.showHint(this)) {
            TextView hint = new TextView(this);
            hint.setText(R.string.hint_long_press);
            hint.setTypeface(Fonts.body(this));
            hint.setTextColor(M3.withAlpha(m3.onSurfaceVariant(), 0x99));
            hint.setTextSize(12);
            hint.setPadding(0, dp(14), 0, 0);
            panel.addView(hint);
        }

        return wrapMargin(panel, 0, 0, dp(16), 0);
    }

    /**
     * Middle column. The speedometer, when present, is the tall one;
     * otherwise the first widget grows to fill and renders large. The
     * rest stack beneath as cards.
     */
    private View buildDashPanel(List<String> widgets) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        boolean tallTaken = false;
        for (String w : widgets) {
            boolean tall = !tallTaken;
            tallTaken = true;
            View card;
            if (Prefs.W_SPEEDO.equals(w)) {
                card = buildSpeedo();
            } else if (Prefs.W_TRIP.equals(w)) {
                card = buildTrip(tall);
            } else {
                card = buildWeather(tall);
            }
            LinearLayout.LayoutParams lp = tall
                    ? new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
                    : new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.leftMargin = dp(8);
            lp.rightMargin = dp(8);
            if (col.getChildCount() > 0) lp.topMargin = dp(12);
            col.addView(card, lp);
        }
        return col;
    }

    private View buildSpeedo() {
        LinearLayout gauge = new LinearLayout(this);
        gauge.setGravity(Gravity.CENTER);
        gauge.setBackground(Shapes.round(density, m3.surfaceContainer(), 36));
        speedo = new Speedo(this);
        speedo.setColors(M3.withAlpha(m3.onSurface(), 0x1F), m3.primary(),
                m3.onSurface(), m3.onSurfaceVariant());
        speedo.setTypefaces(Fonts.hero(this), Fonts.body(this));
        speedo.setUnit(Prefs.mph(this));
        speedo.setScale(Prefs.gaugeMax(this));
        speedo.setDigitsOnly(Prefs.digitsGauge(this));
        gauge.addView(speedo, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        return gauge;
    }

    private View buildWeather(boolean tall) {
        LinearLayout wx = new LinearLayout(this);
        wx.setOrientation(LinearLayout.VERTICAL);
        wx.setGravity(Gravity.CENTER);
        wx.setBackground(Shapes.round(density, m3.secondaryContainer(), tall ? 36 : 28));
        int p = dp(14);
        wx.setPadding(p, p, p, p);

        weatherTemp = new TextView(this);
        weatherTemp.setTypeface(tall ? Fonts.hero(this) : Fonts.display(this));
        weatherTemp.setTextColor(m3.onSecondaryContainer());
        weatherTemp.setTextSize(tall ? 64 : 30);
        weatherTemp.setIncludeFontPadding(false);
        weatherTemp.setText("--");
        wx.addView(weatherTemp);

        weatherDesc = new TextView(this);
        weatherDesc.setTypeface(Fonts.body(this));
        weatherDesc.setTextColor(M3.withAlpha(m3.onSecondaryContainer(), 0xCC));
        weatherDesc.setTextSize(tall ? 18 : 14);
        weatherDesc.setPadding(0, dp(tall ? 8 : 4), 0, 0);
        weatherDesc.setText(R.string.weather_waiting);
        wx.addView(weatherDesc);
        return wx;
    }

    /**
     * Trip meter: distance, moving time, moving average. Long-press to
     * reset — the same gesture as the tiles, so it needs no explaining.
     */
    private View buildTrip(boolean tall) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        card.setBackground(Shapes.tile(density, m3.tertiaryContainer(),
                M3.withAlpha(m3.onTertiaryContainer(), 0x33), 0));
        int p = dp(14);
        card.setPadding(p, p, p, p);
        card.setClickable(true);
        Shapes.springy(card);

        TextView title = new TextView(this);
        title.setText(R.string.trip);
        title.setTypeface(Fonts.display(this));
        title.setTextColor(M3.withAlpha(m3.onTertiaryContainer(), 0xB3));
        title.setTextSize(tall ? 16 : 13);
        card.addView(title);

        tripDistance = new TextView(this);
        tripDistance.setTypeface(tall ? Fonts.hero(this) : Fonts.display(this));
        tripDistance.setTextColor(m3.onTertiaryContainer());
        tripDistance.setTextSize(tall ? 64 : 30);
        tripDistance.setIncludeFontPadding(false);
        tripDistance.setPadding(0, dp(tall ? 8 : 2), 0, 0);
        card.addView(tripDistance);

        tripDetail = new TextView(this);
        tripDetail.setTypeface(Fonts.body(this));
        tripDetail.setTextColor(M3.withAlpha(m3.onTertiaryContainer(), 0xCC));
        tripDetail.setTextSize(tall ? 18 : 14);
        tripDetail.setPadding(0, dp(tall ? 8 : 4), 0, 0);
        card.addView(tripDetail);

        if (tall) {
            TextView hint = new TextView(this);
            hint.setText(R.string.trip_hint);
            hint.setTypeface(Fonts.body(this));
            hint.setTextColor(M3.withAlpha(m3.onTertiaryContainer(), 0x80));
            hint.setTextSize(12);
            hint.setPadding(0, dp(18), 0, 0);
            card.addView(hint);
        }

        card.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                trip.reset();
                Motion.pop(v);
                return true;
            }
        });
        return card;
    }

    private View buildGrid(int columns) {
        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(columns);
        grid.setRowCount(ROWS);

        int[][] spans = spans(columns, Prefs.heroLayout(this));
        for (int i = 0; i < Apps.TILES && i < spans.length; i++) {
            int[] sp = spans[i];
            boolean hero = sp[2] > 1;
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
            // slow head unit feel like it's doing something. Starts
            // after the panels so the eye is led across the screen.
            Motion.enter(tile, 100L + 35L * i, 0f, dp(24));
        }
        return grid;
    }

    /**
     * {column, row, colSpan, rowSpan} for each tile. Hero: one tile the
     * full width on top, singles below. Grid: all singles. Two columns
     * beside the dash, three without it.
     */
    static int[][] spans(int columns, boolean hero) {
        int count = hero ? 1 + columns * (ROWS - 1) : columns * ROWS;
        int[][] out = new int[count][];
        int i = 0;
        if (hero) out[i++] = new int[] { 0, 0, columns, 1 };
        for (int r = hero ? 1 : 0; r < ROWS; r++) {
            for (int c = 0; c < columns; c++) {
                out[i++] = new int[] { c, r, 1, 1 };
            }
        }
        return out;
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
        int hp = dp(11);
        holder.setPadding(hp, hp, hp, hp);

        ImageView icon = new ImageView(this);
        if (entry != null) {
            icon.setImageDrawable(entry.icon);
        } else {
            icon.setImageResource(android.R.drawable.ic_input_add);
            icon.setColorFilter(onFill);
        }
        // The hero is wide, not tall — a big icon plus a label no
        // longer fits its height. Row height on a 4-row grid is ~156px;
        // a 72dp icon plus label plus padding overflowed it.
        int isz = hero ? dp(54) : dp(64);
        holder.addView(icon, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        tile.addView(holder, new LinearLayout.LayoutParams(isz, isz));

        TextView label = new TextView(this);
        label.setText(entry != null ? entry.label : getString(R.string.empty_tile));
        label.setTextColor(onFill);
        label.setTypeface(Fonts.display(this));
        label.setTextSize(hero ? 18 : 15);
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
            // onResume notices the changed tile signature and rebuilds.
        }
    }

    // ---- live data ------------------------------------------------------

    /**
     * Draw only the fields we genuinely know. Chips are keyed so a
     * value that merely changed updates in place, and only a chip that
     * appears or disappears animates — an RPM tick every half second
     * must not make the whole strip flicker.
     */
    private void renderVehicle() {
        VehicleState s = lastVehicle;
        if (vehicleBar == null || s == null) return;

        boolean lowFuel = s.fuelPercent != VehicleState.UNKNOWN_INT && s.fuelPercent <= 15;
        chip("fuel", s.fuelPercent != VehicleState.UNKNOWN_INT ? "Fuel " + s.fuelPercent + "%" : null,
                lowFuel ? m3.tertiaryContainer() : m3.surfaceContainerHigh(),
                lowFuel ? m3.onTertiaryContainer() : m3.onSurface());
        chip("coolant", s.coolantC != VehicleState.UNKNOWN_INT ? s.coolantC + "°C" : null,
                m3.surfaceContainerHigh(), m3.onSurface());
        chip("rpm", s.rpm != VehicleState.UNKNOWN_INT && s.rpm > 0 ? s.rpm + " rpm" : null,
                m3.surfaceContainerHigh(), m3.onSurface());
        int open = s.openDoors();
        chip("doors", open > 0 ? (open == 1 ? "Door open" : open + " doors open") : null,
                m3.primaryContainer(), m3.onPrimaryContainer());

        View strip = (View) vehicleBar.getParent();
        strip.setVisibility(vehicleBar.getChildCount() > 0 ? View.VISIBLE : View.GONE);
    }

    private void chip(String key, String text, int fill, int onFill) {
        TextView t = chips.get(key);
        if (text == null) {
            if (t != null) {
                vehicleBar.removeView(t);
                chips.remove(key);
            }
            return;
        }
        if (t == null) {
            t = new TextView(this);
            t.setTypeface(Fonts.body(this));
            t.setTextSize(15);
            t.setPadding(dp(14), dp(8), dp(14), dp(8));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(8);
            t.setLayoutParams(lp);
            t.setText(text);
            chips.put(key, t);
            vehicleBar.addView(t);
        } else {
            Motion.swapText(t, text);
        }
        // Restyle only on a role change (fuel turning low), so the
        // ripple drawable isn't rebuilt on every update.
        Integer styled = (Integer) t.getTag();
        if (styled == null || styled != fill) {
            t.setTextColor(onFill);
            t.setBackground(Shapes.pill(density, fill, M3.withAlpha(onFill, 0x33)));
            t.setTag(fill);
            if (styled != null) Motion.pop(t);
        }
    }

    private void renderWeather() {
        if (weatherTemp == null || !wxKnown) return;
        int shown = Prefs.fahrenheit(this)
                ? Math.round(wxTempC * 9f / 5f + 32f) : wxTempC;
        Motion.swapText(weatherTemp, shown + "°");
        Motion.swapText(weatherDesc, wxDesc);
    }

    private void renderTrip() {
        if (tripDistance == null) return;
        boolean miles = Prefs.mph(this);
        float m = trip.metres();
        long ms = trip.movingMs();
        Motion.swapText(tripDistance, Trip.distance(m, miles) + (miles ? " mi" : " km"));
        int avg = Trip.average(m, ms, miles);
        String detail = Trip.duration(ms);
        if (avg >= 0) {
            detail += " · " + avg + (miles ? " mph " : " km/h ") + getString(R.string.trip_avg);
        }
        Motion.swapText(tripDetail, detail);
    }

    /**
     * Now playing: art, title, artist, transport. Compact drops the
     * transport row to a single play/pause beside the text, for people
     * who skip tracks from the wheel anyway.
     *
     * Kept in the left column where there was dead space anyway, and
     * where it is reachable from the driver's seat without leaning.
     */
    private View buildNowPlaying(boolean compact) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Shapes.round(density, m3.surfaceContainerHigh(), 28));
        int p = dp(compact ? 10 : 14);
        card.setPadding(p, p, p, p);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        int artSize = dp(compact ? 48 : 64);
        albumArt = new ImageView(this);
        albumArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
        albumArt.setBackground(new MaterialShape(
                MaterialShape.Kind.SQUIRCLE, M3.withAlpha(m3.onSurface(), 0x26)));
        albumArt.setClipToOutline(false);
        top.addView(albumArt, new LinearLayout.LayoutParams(artSize, artSize));

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setPadding(dp(12), 0, 0, 0);

        trackTitle = new TextView(this);
        trackTitle.setTypeface(Fonts.display(this));
        trackTitle.setTextColor(m3.onSurface());
        trackTitle.setTextSize(compact ? 15 : 17);
        trackTitle.setMaxLines(1);
        trackTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        trackTitle.setText(R.string.nothing_playing);
        text.addView(trackTitle);

        trackArtist = new TextView(this);
        trackArtist.setTypeface(Fonts.body(this));
        trackArtist.setTextColor(m3.onSurfaceVariant());
        trackArtist.setTextSize(compact ? 13 : 14);
        trackArtist.setMaxLines(1);
        trackArtist.setEllipsize(android.text.TextUtils.TruncateAt.END);
        trackArtist.setVisibility(View.GONE);
        text.addView(trackArtist);

        top.addView(text, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        card.addView(top);

        shownPlaying = false;
        if (compact) {
            playButton = (ImageView) transport(Glyph.Kind.PLAY, 1, true, dp(44));
            top.addView(playButton);
        } else {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER);
            row.setPadding(0, dp(12), 0, 0);
            row.addView(transport(Glyph.Kind.PREV, 0, false, dp(48)));
            playButton = (ImageView) transport(Glyph.Kind.PLAY, 1, true, dp(60));
            row.addView(playButton);
            row.addView(transport(Glyph.Kind.NEXT, 2, false, dp(48)));
            card.addView(row);
        }

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(18);
        card.setLayoutParams(lp);
        return card;
    }

    private View transport(Glyph.Kind kind, final int action, boolean big, int size) {
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
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
        lp.leftMargin = dp(6);
        lp.rightMargin = dp(6);
        b.setLayoutParams(lp);
        int pad = Math.round(size * 0.29f);
        b.setPadding(pad, pad, pad, pad);
        return b;
    }

    private void renderNowPlaying() {
        if (trackTitle == null || !npKnown) return;
        boolean known = npTitle != null && !npTitle.trim().isEmpty();
        String title = known ? npTitle : getString(R.string.nothing_playing);
        boolean trackChanged = !title.contentEquals(trackTitle.getText());

        Motion.swapText(trackTitle, title);
        String artist = npArtist == null ? "" : npArtist;
        Motion.swapText(trackArtist, artist);
        trackArtist.setVisibility(artist.isEmpty() ? View.GONE : View.VISIBLE);

        if (npArt != null) {
            boolean hadArt = albumArt.getDrawable() != null;
            albumArt.setImageBitmap(npArt);
            // Only a new track earns the reveal; the same bitmap comes
            // back on every poll and must not keep pulsing.
            if (trackChanged || !hadArt) Motion.reveal(albumArt);
        } else {
            albumArt.setImageDrawable(null);
        }
        if (npPlaying != shownPlaying) {
            shownPlaying = npPlaying;
            playButton.setImageDrawable(new Glyph(
                    npPlaying ? Glyph.Kind.PAUSE : Glyph.Kind.PLAY, m3.onPrimary()));
            Motion.pop(playButton);
        }
    }

    private void updateClock() {
        if (clock == null) return;
        Date now = new Date();
        Motion.swapText(clock, new SimpleDateFormat(
                Prefs.clockPattern(this), Locale.getDefault()).format(now));
        String datePattern = Prefs.datePattern(this);
        if (datePattern != null) {
            Motion.swapText(date, new SimpleDateFormat(datePattern, Locale.getDefault()).format(now));
        }
    }

    // ---- helpers --------------------------------------------------------

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
