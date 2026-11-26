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
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

import ie.claudius.cardash.dash.SpeedSource;
import ie.claudius.cardash.dash.Trip;
import ie.claudius.cardash.dash.Weather;
import ie.claudius.cardash.diag.DiagnosticsActivity;
import ie.claudius.cardash.layout.HomeLayout;
import ie.claudius.cardash.layout.Kind;
import ie.claudius.cardash.media.NowPlaying;
import ie.claudius.cardash.remote.RemoteServer;
import ie.claudius.cardash.vehicle.VehicleHub;
import ie.claudius.cardash.vehicle.VehicleState;

/**
 * The home screen. The layout is data ({@link HomeLayout}): a grid of
 * cells and pages of placed items, rendered by {@link GridPage} and
 * {@link Widgets}. Until the user edits it, the layout is generated
 * from the old Settings options and looks as the fixed home always did.
 *
 * Everything is built in code so the theme can be rebuilt from scratch
 * whenever the wallpaper colour or a setting changes — and only then:
 * a signature of everything that feeds the layout is compared first, and
 * an unchanged screen is left exactly as it was.
 *
 * Long-press empty space or any item to edit (see {@link HomeEditor});
 * swipe up for all apps; swipe sideways between pages.
 */
public class HomeActivity extends Activity implements Widgets.Host {

    private static final int REQ_PICK = 1;
    /** Start (or return to) the home screen in edit mode. */
    public static final String EXTRA_EDIT = "edit";

    private float density;
    private M3 m3;
    private FrameLayout shell;
    private View root;
    private String builtSignature;
    private Object themeWatch;
    private Widgets widgets;
    private HomePager pager;

    private HomeLayout layout;
    private HomeEditor editor;
    private int pageIndex;
    private String editBaseSig;

    private final VehicleHub vehicle = VehicleHub.shared();
    private boolean vehicleOn;
    private final VehicleState.Listener vehicleListener = new VehicleState.Listener() {
        @Override
        public void onVehicleState(VehicleState state) {
            widgets.lastVehicle = state;
            if (state.speedKph != VehicleState.UNKNOWN_INT) {
                obdSpeedAt = System.currentTimeMillis();
                widgets.lastKph = state.speedKph;
                widgets.lastFix = true;
                widgets.refresh(Widgets.SPEED);
            }
            widgets.refresh(Widgets.VEH);
        }
    };
    private NowPlaying nowPlaying;
    private SpeedSource speed;
    private Trip trip;
    private final Weather weather = new Weather();
    private HomeLayout.Item pendingTile;
    private boolean pendingNew;

    /** GPS speed yields to OBD speed for this long after each OBD reading. */
    private static final long OBD_SPEED_HOLD_MS = 3000L;
    private long obdSpeedAt;

    private final BroadcastReceiver timeTick = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            widgets.refresh(Widgets.CLOCK);
        }
    };

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        density = getResources().getDisplayMetrics().density;
        Apps.seedIfEmpty(this);
        trip = new Trip(this);
        widgets = new Widgets(this, this);
        shell = new FrameLayout(this);
        setContentView(shell);
        rebuild(true, true);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null && intent.getBooleanExtra(EXTRA_EDIT, false)) {
            intent.removeExtra(EXTRA_EDIT);
            enterEdit();
        }
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
                widgets.lastKph = kph;
                widgets.lastFix = hasFix;
                widgets.refresh(Widgets.SPEED);
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
                widgets.refresh(Widgets.TRIP);
            }
        });
        weather.start(this, new Weather.Listener() {
            @Override
            public void onWeather(int tempC, String description, int code) {
                widgets.wxTempC = tempC;
                widgets.wxDesc = description;
                widgets.wxKnown = true;
                widgets.refresh(Widgets.WX);
            }
        });
        if (nowPlaying == null) nowPlaying = new NowPlaying(this);
        nowPlaying.start(new NowPlaying.Listener() {
            @Override
            public void onNowPlaying(String title, String artist, Bitmap art, boolean playing) {
                widgets.npTitle = title;
                widgets.npArtist = artist;
                widgets.npArt = art;
                widgets.npPlaying = playing;
                widgets.npKnown = true;
                widgets.refresh(Widgets.NP);
            }
        });
        syncVehicle();

        // A setting, the wallpaper, or the set of installed apps may
        // have changed while we were away.
        rebuildIfChanged();
        // Phone settings (#1): a change from the LAN page shows at once.
        RemoteServer.sync(this);
        RemoteServer.setListener(new Runnable() {
            @Override
            public void run() {
                rebuildIfChanged();
            }
        });
        // ...and the wallpaper's colours can land after we have already
        // resumed, if it was set a moment ago.
        themeWatch = ThemeWatch.start(this, new Runnable() {
            @Override
            public void run() {
                rebuildIfChanged();
            }
        });
        maybeHint();
    }

    /** The vehicle sources only run while something on the home screen shows them. */
    private void syncVehicle() {
        boolean want = Prefs.showVehicle(this) && layout != null
                && (layout.hasItem(Kind.VEHICLE) || layout.hasItem(Kind.DOORS) || layout.hasItem(Kind.CHIPS));
        if (want && !vehicleOn) {
            vehicle.start(this, vehicleListener, Prefs.vehicleMode(this));
            vehicleOn = true;
        } else if (!want && vehicleOn) {
            vehicle.stop(vehicleListener);
            vehicleOn = false;
        }
    }

    /** Once, tell people the screen is editable — nothing on it says so otherwise. */
    private void maybeHint() {
        if (!Prefs.showHint(this) || Prefs.get(this, "edit_hint", null) != null) return;
        Prefs.set(this, "edit_hint", "shown");
        Toast.makeText(this, R.string.hint_long_press, Toast.LENGTH_LONG).show();
    }

    private void rebuildIfChanged() {
        String sig = signature();
        if (!sig.equals(builtSignature)) {
            if (editor != null && !layoutSig().equals(editBaseSig)) {
                // The saved layout changed under us (a preset, the steering side): the
                // working copy no longer matches it, so drop the edit.
                editor = null;
            }
            rebuild(false, true);
        } else {
            widgets.refresh(Widgets.CLOCK);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        Receivers.unregister(this, timeTick);
        RemoteServer.setListener(null);
        ThemeWatch.stop(this, themeWatch);
        themeWatch = null;
        vehicle.stop(vehicleListener);
        vehicleOn = false;
        if (nowPlaying != null) nowPlaying.stop();
        if (speed != null) speed.stop();
        trip.setListener(null);
        trip.save();
        weather.stop();
    }

    /** Home is the bottom of the stack — back should do nothing at all (but leaves edit mode). */
    @Override
    public void onBackPressed() {
        if (editor != null) editor.back();
    }

    // ---- build ----------------------------------------------------------------

    private String layoutSig() {
        return Prefs.get(this, Prefs.HOME_LAYOUT, "") + '|' + Prefs.get(this, Prefs.SIDE, "");
    }

    private String signature() {
        StringBuilder tiles = new StringBuilder();
        HomeLayout l = editor != null ? editor.working : layout;
        if (l != null) {
            for (HomeLayout.Page p : l.pages) {
                for (HomeLayout.Item it : p.items) {
                    String pkg = it.settings.get("pkg");
                    if (pkg != null) {
                        tiles.append(pkg).append(getPackageManager().getLaunchIntentForPackage(pkg) != null ? '+' : '-');
                    }
                }
            }
        }
        return Prefs.signature(this) + '#'
                + Integer.toHexString(Prefs.themeSeed(this)) + '#'
                + Apps.signature(this) + '#' + tiles;
    }

    /**
     * @param first   play the entrance
     * @param fade    crossfade from the old screen (false for edit-mode refreshes, which must not flash)
     */
    private void rebuild(boolean first, boolean fade) {
        builtSignature = signature();
        Motion.setCalm(Prefs.calmMotion(this));
        m3 = M3.fromSeed(Prefs.themeSeed(this));
        widgets.reset(m3);
        layout = editor != null ? editor.working : LayoutStore.load(this);
        if (editor != null) {
            // Same working copy, new colours.
            editor = new HomeEditor(this, m3, editor.working, editor.originalJson());
        }
        pageIndex = Math.max(0, Math.min(pageIndex, layout.pages.size() - 1));

        FrameLayout page = new FrameLayout(this);
        // A tonal scrim so the wallpaper still reads through but text
        // stays legible in daylight. How much is a setting.
        page.setBackgroundColor(M3.withAlpha(m3.surface(), Prefs.scrimAlpha(this)));

        List<View> pages = new ArrayList<>();
        final boolean editing = editor != null;
        for (HomeLayout.Page p : layout.pages) {
            GridPage gp = new GridPage(this, m3, layout, p, new GridPage.Factory() {
                @Override
                public View create(HomeLayout.Item it, int w, int h, int appIdx, boolean ed) {
                    return widgets.build(it, w, h, appIdx, ed);
                }
            }, editing, editor, first, getString(editing ? R.string.edit_empty : R.string.empty_home));
            if (!editing) {
                gp.setLongClickable(true);
                gp.setOnLongClickListener(new View.OnLongClickListener() {
                    @Override
                    public boolean onLongClick(View v) {
                        v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                        enterEdit();
                        return true;
                    }
                });
            }
            pages.add(gp);
        }
        pager = new HomePager(this, new HomePager.Listener() {
            @Override public void onSwipeUp() { openApps(); }
            @Override public void onPage(int index) {
                pageIndex = index;
                if (editor != null) editor.label();
            }
        }, m3.primary(), M3.withAlpha(m3.onSurfaceVariant(), 0x66));
        pager.setPages(pages, pageIndex);
        pager.setSwipes(!editing);
        page.addView(pager, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (editing) {
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            lp.bottomMargin = Math.round(14 * density);
            page.addView(editor.toolbar(), lp);
        }

        View old = root;
        root = page;
        if (fade) {
            Motion.crossfade(shell, old, page);
        } else {
            if (old != null) shell.removeView(old);
            shell.addView(page, 0, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        widgets.refresh(Widgets.CLOCK | Widgets.NP | Widgets.WX | Widgets.VEH | Widgets.TRIP | Widgets.SPEED);
        syncVehicle();
    }

    // ---- edit mode --------------------------------------------------------------

    int pageIndex() { return pageIndex; }

    void setPageIndex(int i) { pageIndex = i; }

    void goToPage(int i) {
        if (pager == null) return;
        pager.goTo(i, true);
        pageIndex = pager.current();
    }

    /** Long-press: switch the screen to edit mode on a working copy of the layout. */
    @Override
    public void enterEdit() {
        if (editor != null) return;
        editBaseSig = layoutSig();
        editor = new HomeEditor(this, M3.fromSeed(Prefs.themeSeed(this)), layout.copy(), null);
        rebuild(false, true);
        Toast.makeText(this, R.string.edit_hint, Toast.LENGTH_LONG).show();
    }

    /** Structure changed in edit mode (item added, removed, resized, or its settings changed). */
    void rebuildEditing() {
        if (editor != null) rebuild(false, false);
    }

    void exitEdit(boolean save) {
        if (editor == null) return;
        if (save) {
            HomeLayout w = editor.working;
            LayoutStore.check(w);
            LayoutStore.save(this, w);
        }
        editor = null;
        rebuild(false, true);
    }

    // ---- Widgets.Host ---------------------------------------------------------------

    @Override
    public void openApps() {
        pendingTile = null;
        startActivity(new Intent(this, AppListActivity.class));
    }

    @Override
    public void openSettings() {
        startActivity(new Intent(this, SettingsActivity.class));
    }

    /** Long-press on the clock: the things you can't otherwise reach if the screen is bare. */
    @Override
    public void openMenu() {
        new Pop(this, m3, getString(R.string.app_name))
                .row(getString(R.string.menu_edit), new Runnable() {
                    @Override public void run() { enterEdit(); }
                })
                .row(getString(R.string.settings), new Runnable() {
                    @Override public void run() { openSettings(); }
                })
                .row(getString(R.string.s_diag), new Runnable() {
                    @Override public void run() {
                        startActivity(new Intent(HomeActivity.this, DiagnosticsActivity.class));
                    }
                })
                .show();
    }

    @Override
    public NowPlaying nowPlaying() { return nowPlaying; }

    @Override
    public Trip trip() { return trip; }

    /** Pick an app for a tile (an existing one, or null for a new one in edit mode). */
    @Override
    public void pickApp(HomeLayout.Item tile) {
        pendingTile = tile;
        pendingNew = tile == null;
        Intent i = new Intent(this, AppListActivity.class);
        i.putExtra(AppListActivity.EXTRA_PICK, true);
        startActivityForResult(i, REQ_PICK);
    }

    String tilePackage(HomeLayout.Item it) {
        return widgets.tilePackage(it);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_PICK) return;
        HomeLayout.Item tile = pendingTile;
        boolean isNew = pendingNew;
        pendingTile = null;
        pendingNew = false;
        if (res != RESULT_OK || data == null) return;
        String pkg = data.getStringExtra(AppListActivity.EXTRA_PACKAGE);
        if (pkg == null) return;
        if (editor != null) {
            // Part of the edit: Cancel throws it away like everything else.
            if (isNew) editor.addApp(pkg);
            else if (tile != null) { tile.settings.put("pkg", pkg); rebuildEditing(); }
            return;
        }
        if (tile == null) return;
        String slot = tile.settings.get("slot");
        if (slot != null && tile.settings.get("pkg") == null) {
            try { Apps.setTile(this, Integer.parseInt(slot), pkg); } catch (NumberFormatException ignored) { }
        } else {
            tile.settings.put("pkg", pkg);
            LayoutStore.save(this, layout);
        }
        // onResume notices the changed signature and rebuilds.
    }
}
