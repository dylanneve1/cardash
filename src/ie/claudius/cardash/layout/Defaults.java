package ie.claudius.cardash.layout;

import java.util.List;

/**
 * The layouts the app starts with ({@link #full}). {@link #home}
 * reproduces the three-column home screen the app has always had (clock
 * column, dash column, tiles), honouring the old Settings options; it is
 * the middle page, between a car page and an information page. A layout
 * the user has saved is never replaced by these.
 */
public final class Defaults {

    /** The legacy settings that shaped the fixed layout. */
    public static final class Options {
        /** Dash column widgets in order: "speedo", "trip", "weather"; empty = none. */
        public List<String> dash = new java.util.ArrayList<>();
        public boolean hero = true;
        /** full | compact | hide */
        public String media = "full";
        public boolean date = true;
        public boolean vehicle = true;
        /** How many app tiles at most (Apps.TILES). */
        public int tiles = 12;
    }

    /** The grid the templates are drawn on; other grids are rescaled from it. */
    private static final int TC = 12, TR = 8;

    private Defaults() {}

    public static HomeLayout home(Options o, int cols, int rows) {
        HomeLayout l = new HomeLayout(TC, TR);
        HomeLayout.Page p = new HomeLayout.Page();
        l.pages.add(p);

        // Clock column: clock, date, buttons, now playing, vehicle strip.
        p.items.add(new HomeLayout.Item(Kind.CLOCK, 0, 0, 4, 2));
        if (o.date) p.items.add(new HomeLayout.Item(Kind.DATE, 0, 2, 4, 1));
        p.items.add(new HomeLayout.Item(Kind.ALLAPPS, 0, 3, 3, 1));
        p.items.add(new HomeLayout.Item(Kind.SETTINGS, 3, 3, 1, 1));
        boolean np = !"hide".equals(o.media);
        if (np) {
            p.items.add(new HomeLayout.Item(Kind.NOWPLAYING, 0, 4, 4, o.vehicle ? 3 : 4)
                    .set("style", "compact".equals(o.media) ? "compact" : "full"));
        }
        if (o.vehicle) p.items.add(new HomeLayout.Item(Kind.CHIPS, 0, 7, 4, 1));

        // Dash column.
        int n = o.dash.size();
        int[][] spans = n == 1 ? new int[][] { { 0, 8 } }
                : n == 2 ? new int[][] { { 0, 6 }, { 6, 2 } }
                : new int[][] { { 0, 4 }, { 4, 2 }, { 6, 2 } };
        for (int i = 0; i < n && i < spans.length; i++) {
            String w = o.dash.get(i);
            String type = "speedo".equals(w) ? Kind.SPEEDO : "trip".equals(w) ? Kind.TRIP : Kind.WEATHER;
            p.items.add(new HomeLayout.Item(type, 4, spans[i][0], 4, spans[i][1]));
        }

        // Tiles: 2x2 cells each, two per row beside the dash, four per row without.
        int tx = n > 0 ? 8 : 4, tw = n > 0 ? 4 : 8, slot = 0;
        int perRow = tw / 2;
        int y = 0;
        if (o.hero && slot < o.tiles) {
            p.items.add(new HomeLayout.Item(Kind.APP, tx, 0, tw, 2).set("slot", String.valueOf(slot++)));
            y = 2;
        }
        for (; y + 2 <= TR; y += 2) {
            for (int c = 0; c < perRow && slot < o.tiles; c++) {
                p.items.add(new HomeLayout.Item(Kind.APP, tx + c * 2, y, 2, 2)
                        .set("slot", String.valueOf(slot++)));
            }
        }
        l.rescale(cols, rows);
        return l;
    }

    /**
     * The layout a fresh install gets: car information on the left, the
     * home page in the middle (where it starts), useful information on
     * the right. With vehicle data switched off there is no car page.
     */
    public static HomeLayout full(Options o, int cols, int rows) {
        HomeLayout l = home(o, cols, rows);
        if (o.vehicle) l.insertPage(0, carPage(cols, rows));
        l.pages.add(infoPage(cols, rows));
        return l;
    }

    /** Car information: RPM, speed, coolant, fuel and outside temperature round the car. */
    public static HomeLayout.Page carPage(int cols, int rows) {
        HomeLayout t = new HomeLayout(TC, TR);
        HomeLayout.Page p = new HomeLayout.Page();
        t.pages.add(p);
        p.theme = HomeLayout.AUTO;
        p.items.add(vehicle("rpm", "gauge", 0, 0, 4, 4));
        p.items.add(new HomeLayout.Item(Kind.DOORS, 4, 0, 4, 8));
        p.items.add(vehicle("speed", "gauge", 8, 0, 4, 4));
        p.items.add(vehicle("coolant", "gauge", 0, 4, 4, 4));
        p.items.add(vehicle("fuel", "bar", 8, 4, 4, 2));
        p.items.add(vehicle("outside", "bar", 8, 6, 4, 2));
        t.rescale(cols, rows);
        return t.pages.get(0);
    }

    /** Clock and date, now playing, weather and the trip meter. */
    public static HomeLayout.Page infoPage(int cols, int rows) {
        HomeLayout t = new HomeLayout(TC, TR);
        HomeLayout.Page p = new HomeLayout.Page();
        t.pages.add(p);
        p.items.add(new HomeLayout.Item(Kind.CLOCK, 0, 0, 6, 3));
        p.items.add(new HomeLayout.Item(Kind.DATE, 0, 3, 6, 1));
        p.items.add(new HomeLayout.Item(Kind.NOWPLAYING, 0, 4, 6, 4));
        p.items.add(new HomeLayout.Item(Kind.WEATHER, 6, 0, 6, 4));
        p.items.add(new HomeLayout.Item(Kind.TRIP, 6, 4, 6, 4));
        t.rescale(cols, rows);
        return t.pages.get(0);
    }

    private static HomeLayout.Item vehicle(String field, String style, int x, int y, int w, int h) {
        HomeLayout.Item it = new HomeLayout.Item(Kind.VEHICLE, x, y, w, h);
        it.settings.putAll(Kind.vehicleDefaults(field));
        it.settings.put("style", style);
        return it;
    }
}
