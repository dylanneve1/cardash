package ie.claudius.cardash;

import android.content.Context;
import android.util.DisplayMetrics;

import ie.claudius.cardash.layout.Arrange;
import ie.claudius.cardash.layout.Defaults;
import ie.claudius.cardash.layout.Grid;
import ie.claudius.cardash.layout.HomeLayout;

/**
 * Loads and saves the home layout (JSON in the same SharedPreferences as
 * everything else). With nothing saved the layout is generated from the
 * Settings options ({@link Defaults#full}); a saved one is left alone.
 */
public final class LayoutStore {

    private LayoutStore() {}

    /** Grid dimensions for this screen: ~105 x 90 dp cells, 12 x 8 on a 1280x720 / 160 dpi unit. */
    public static int[] gridFor(Context ctx) {
        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        int w = Math.max(dm.widthPixels, dm.heightPixels);   // the launcher is landscape-only
        int h = Math.min(dm.widthPixels, dm.heightPixels);
        return new int[] { Grid.colsFor(w / dm.density), Grid.rowsFor(h / dm.density) };
    }

    public static boolean isCustom(Context ctx) {
        return Prefs.get(ctx, Prefs.HOME_LAYOUT, null) != null;
    }

    /** The layout to show: the saved one (re-fitted to this screen), else the default. */
    public static HomeLayout load(Context ctx) {
        int[] g = gridFor(ctx);
        HomeLayout l = HomeLayout.fromJson(Prefs.get(ctx, Prefs.HOME_LAYOUT, null));
        if (l != null) {
            l.rescale(g[0], g[1]);
            return l;
        }
        return defaults(ctx);
    }

    public static HomeLayout defaults(Context ctx) {
        int[] g = gridFor(ctx);
        Defaults.Options o = new Defaults.Options();
        o.dash = Prefs.dashWidgets(ctx);
        o.hero = Prefs.heroLayout(ctx);
        o.media = Prefs.media(ctx);
        o.date = Prefs.datePattern(ctx) != null;
        o.vehicle = Prefs.showVehicle(ctx);
        o.tiles = Apps.TILES;
        HomeLayout l = Defaults.full(o, g[0], g[1]);
        if (Prefs.rightHandDrive(ctx)) l.mirror();
        return l;
    }

    public static void save(Context ctx, HomeLayout l) {
        Prefs.set(ctx, Prefs.HOME_LAYOUT, l.toJson());
    }

    /** Back to the generated default (what the old Settings options describe). */
    public static void reset(Context ctx) {
        ctx.getSharedPreferences("cardash", Context.MODE_PRIVATE).edit()
                .remove(Prefs.HOME_LAYOUT).apply();
    }

    /**
     * Change the steering-wheel side. A saved layout is mirrored in place
     * so it flips to the other side of the screen; the generated default
     * simply regenerates mirrored.
     */
    public static void setSide(Context ctx, boolean rhd) {
        if (rhd == Prefs.rightHandDrive(ctx)) return;
        HomeLayout saved = HomeLayout.fromJson(Prefs.get(ctx, Prefs.HOME_LAYOUT, null));
        if (saved != null) {
            saved.mirror();
            save(ctx, saved);
        }
        Prefs.set(ctx, Prefs.SIDE, rhd ? "rhd" : "lhd");
    }

    /** A fresh car page sized for this screen. */
    public static HomeLayout.Page carPage(Context ctx) {
        int[] g = gridFor(ctx);
        HomeLayout.Page p = Defaults.carPage(g[0], g[1]);
        if (Prefs.rightHandDrive(ctx)) {
            HomeLayout t = new HomeLayout(g[0], g[1]);
            t.pages.add(p);
            t.mirror();
        }
        return p;
    }

    /** A fresh engine data page sized for this screen. */
    public static HomeLayout.Page enginePage(Context ctx) {
        int[] g = gridFor(ctx);
        return Defaults.enginePage(g[0], g[1]);
    }

    /**
     * The car page style chosen in Settings: car pages of a saved layout
     * go back to following it.
     */
    public static void setCarTheme(Context ctx, String theme) {
        Prefs.set(ctx, Prefs.CAR_THEME, theme);
        HomeLayout saved = HomeLayout.fromJson(Prefs.get(ctx, Prefs.HOME_LAYOUT, null));
        if (saved == null) return;
        for (HomeLayout.Page p : saved.pages) if (p.isCarPage()) p.theme = HomeLayout.AUTO;
        save(ctx, saved);
    }

    /** Sanity: a layout the editor produced must be valid; repair if not. */
    public static void check(HomeLayout l) {
        if (!Arrange.valid(l)) Arrange.repair(l);
    }
}
