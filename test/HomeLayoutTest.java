import java.util.ArrayList;
import java.util.Arrays;

import ie.claudius.cardash.layout.Arrange;
import ie.claudius.cardash.layout.Defaults;
import ie.claudius.cardash.layout.Grid;
import ie.claudius.cardash.layout.HomeLayout;
import ie.claudius.cardash.layout.Json;
import ie.claudius.cardash.layout.Kind;

public class HomeLayoutTest {
    static int fails = 0;
    static void check(boolean ok, String what) {
        if (!ok) { fails++; System.out.println("FAIL: " + what); }
    }

    static int count(HomeLayout l, String type) {
        int n = 0;
        for (HomeLayout.Page p : l.pages) for (HomeLayout.Item i : p.items) if (i.type.equals(type)) n++;
        return n;
    }

    static Defaults.Options classic() {
        Defaults.Options o = new Defaults.Options();
        o.dash = new ArrayList<>(Arrays.asList("speedo", "weather"));
        return o;
    }

    public static void main(String[] a) {
        // ---- grid from screen size
        check(Grid.colsFor(1280) == 12 && Grid.rowsFor(720) == 8, "1280x720@160dpi is 12x8");
        check(Grid.colsFor(1024) == 10 && Grid.rowsFor(600) == 7, "1024x600");
        check(Grid.colsFor(100) == 6 && Grid.rowsFor(100) == 4, "grid has a floor");
        check(Grid.colsFor(9000) == 16 && Grid.rowsFor(9000) == 10, "grid has a ceiling");

        Grid g = new Grid(12, 8, 1280, 720, 20, 14);
        check(Math.abs(g.cellW - 103.33f) < 0.1f && Math.abs(g.cellH - 85f) < 0.1f, "cell size " + g.cellW + "x" + g.cellH);
        float[] r = g.rect(0, 0, 1, 1);
        check(r[0] == 27f && r[1] == 27f, "rect inset by half the gap");
        check(g.colAt(-50) == 0 && g.colAt(5000) == 11 && g.rowAt(5000) == 7, "colAt clamps");
        check(g.colAt(20 + 103.4f) == 1, "colAt");
        check(g.cellsX(60) == 1 && g.cellsX(40) == 0 && g.cellsX(-160) == -2, "drag snaps to nearest cell");

        // ---- default layout reproduces the old home
        HomeLayout d = Defaults.home(classic(), 12, 8);
        check(Arrange.valid(d), "default valid");
        check(d.pages.size() == 1, "one page");
        check(count(d, Kind.CLOCK) == 1 && count(d, Kind.DATE) == 1 && count(d, Kind.ALLAPPS) == 1
                && count(d, Kind.SETTINGS) == 1 && count(d, Kind.NOWPLAYING) == 1 && count(d, Kind.CHIPS) == 1,
                "clock column present");
        check(count(d, Kind.SPEEDO) == 1 && count(d, Kind.WEATHER) == 1 && count(d, Kind.TRIP) == 0, "dash widgets");
        check(count(d, Kind.APP) == 7, "hero + 3 rows of 2 = 7 tiles, got " + count(d, Kind.APP));
        Defaults.Options grid = classic();
        grid.hero = false;
        check(count(Defaults.home(grid, 12, 8), Kind.APP) == 8, "grid mode has 8 tiles");
        Defaults.Options launcher = classic();
        launcher.dash.clear();
        launcher.hero = false;
        launcher.media = "compact";
        HomeLayout ld = Defaults.home(launcher, 12, 8);
        check(Arrange.valid(ld) && count(ld, Kind.APP) == 12 && count(ld, Kind.SPEEDO) == 0, "no dash: wider tiles, capped at 12");
        Defaults.Options minimal = classic();
        minimal.media = "hide"; minimal.date = false; minimal.vehicle = false;
        HomeLayout md = Defaults.home(minimal, 12, 8);
        check(Arrange.valid(md) && count(md, Kind.NOWPLAYING) == 0 && count(md, Kind.DATE) == 0 && count(md, Kind.CHIPS) == 0,
                "hidden pieces are left out");
        // Tiles carry the legacy slot so the phone settings page still decides their apps.
        boolean slots = true;
        for (HomeLayout.Item i : d.pages.get(0).items) {
            if (i.type.equals(Kind.APP) && i.settings.get("slot") == null) slots = false;
        }
        check(slots, "default tiles keep their slot numbers");
        // Every grid size the app can derive stays valid, with every option mix.
        for (int c = 6; c <= 16; c++) {
            for (int rw = 4; rw <= 10; rw++) {
                for (Defaults.Options o : new Defaults.Options[] { classic(), launcher, minimal }) {
                    HomeLayout x = Defaults.home(o, c, rw);
                    check(Arrange.valid(x) && x.cols == c && x.rows == rw, "default valid on " + c + "x" + rw);
                    check(x.pages.get(0).items.size() >= 3, "default not gutted on " + c + "x" + rw);
                }
                check(Arrange.valid(wrap(Defaults.carPage(c, rw), c, rw)), "car page valid on " + c + "x" + rw);
                check(Defaults.carPage(c, rw).items.size() == 6, "car page complete on " + c + "x" + rw);
                HomeLayout full = Defaults.full(classic(), c, rw);
                check(Arrange.valid(full) && full.pages.size() == 3 && full.start == 1
                        && Defaults.infoPage(c, rw).items.size() == 5, "full default on " + c + "x" + rw);
            }
        }

        // ---- JSON
        HomeLayout rt = HomeLayout.fromJson(d.toJson());
        check(rt != null && rt.toJson().equals(d.toJson()), "json round trip");
        check(rt.cols == 12 && rt.rows == 8 && rt.pages.get(0).items.size() == d.pages.get(0).items.size(), "round trip shape");
        HomeLayout vp = new HomeLayout(12, 8);
        vp.pages.add(Defaults.carPage(12, 8));
        HomeLayout vrt = HomeLayout.fromJson(vp.toJson());
        check(vrt != null && vrt.toJson().equals(vp.toJson()), "vehicle page round trip");
        check("5500".equals(vrt.pages.get(0).items.get(0).settings.get("warn")), "rpm gauge warns at 5500");
        check("120".equals(vrt.pages.get(0).items.get(3).settings.get("max"))
                && "110".equals(vrt.pages.get(0).items.get(3).settings.get("warn")), "coolant 0-120 warn 110");

        // ---- default pages: car | home | information
        HomeLayout three = Defaults.full(classic(), 12, 8);
        check(count(wrap(three.pages.get(0), 12, 8), Kind.DOORS) == 1
                && count(wrap(three.pages.get(0), 12, 8), Kind.VEHICLE) == 5, "left page is the car");
        check(wrap(three.pages.get(1), 12, 8).toJson().equals(d.toJson()), "centre page is the old home");
        check(count(wrap(three.pages.get(2), 12, 8), Kind.TRIP) == 1, "right page has the trip");
        HomeLayout noCar = Defaults.full(minimal, 12, 8);
        check(noCar.pages.size() == 2 && noCar.start == 0, "no car page with vehicle data off");
        check(HomeLayout.fromJson(three.toJson()).start == 1 && three.copy().start == 1, "start page persists");
        check(HomeLayout.fromJson(d.toJson().replace("\"start\":0", "\"start\":9")).start == 0, "start clamped");
        check(HomeLayout.fromJson("{\"cols\":12,\"rows\":8,\"pages\":[{},{}]}").start == 0,
                "a layout saved before start pages opens on the first");
        three.insertPage(0, new HomeLayout.Page());
        check(three.start == 2, "inserting before keeps the start page");
        three.removePage(0);
        three.removePage(0);
        check(three.start == 0 && three.pages.size() == 2, "removing before keeps the start page");
        three.removePage(0);
        check(three.start == 0, "removing the start page");
        check(HomeLayout.fromJson(null) == null && HomeLayout.fromJson("") == null
                && HomeLayout.fromJson("{") == null && HomeLayout.fromJson("[1]") == null
                && HomeLayout.fromJson("{\"cols\":0,\"rows\":4,\"pages\":[]}") == null
                && HomeLayout.fromJson("{\"cols\":12,\"rows\":8}") == null, "corrupt input is rejected, not thrown");
        HomeLayout odd = HomeLayout.fromJson("{\"cols\":12,\"rows\":8,\"pages\":[{\"items\":["
                + "{\"t\":\"clock\",\"x\":0,\"y\":0,\"w\":4,\"h\":2},"
                + "{\"t\":\"warp-drive\",\"x\":4,\"y\":0,\"w\":1,\"h\":1},"       // unknown kind
                + "{\"t\":\"date\",\"x\":2,\"y\":1,\"w\":4,\"h\":1},"              // overlaps the clock
                + "{\"t\":\"weather\",\"x\":10,\"y\":7,\"w\":9,\"h\":9,\"s\":{\"a\":\"b\",\"n\":3}}"  // off grid
                + "]}]}");
        check(odd != null && Arrange.valid(odd), "hand-edited layout is repaired");
        check(count(odd, "warp-drive") == 0 && count(odd, Kind.CLOCK) == 1 && count(odd, Kind.DATE) == 1, "unknown dropped, others kept");
        check(odd.pages.get(0).items.get(0).w == 4, "untouched item untouched");
        HomeLayout empty = HomeLayout.fromJson("{\"cols\":12,\"rows\":8,\"pages\":[]}");
        check(empty != null && empty.pages.size() == 1 && empty.pages.get(0).items.isEmpty(), "no pages becomes one empty page");
        check("a\"b\\c\n".equals(((java.util.Map<?, ?>) Json.parse(Json.write(java.util.Collections.singletonMap("k", "a\"b\\c\n")))).get("k")),
                "json string escapes");

        // ---- mirror (steering side)
        HomeLayout m = d.copy();
        m.mirror();
        HomeLayout.Item clock = m.pages.get(0).items.get(0);
        check(clock.type.equals(Kind.CLOCK) && clock.x == 8 && clock.w == 4, "clock moves to the right edge");
        check(Arrange.valid(m), "mirrored layout valid");
        m.mirror();
        check(m.toJson().equals(d.toJson()), "mirroring twice is the identity");
        HomeLayout odd2 = new HomeLayout(11, 8);
        odd2.pages.add(new HomeLayout.Page());
        odd2.pages.get(0).items.add(new HomeLayout.Item(Kind.CLOCK, 1, 0, 3, 1));
        odd2.mirror();
        check(odd2.pages.get(0).items.get(0).x == 7, "mirror on an odd grid: 11-1-3");

        // ---- rescale to another screen
        HomeLayout rs = d.copy();
        rs.rescale(10, 6);
        check(Arrange.valid(rs) && rs.cols == 10 && rs.rows == 6 && count(rs, Kind.CLOCK) == 1, "rescale stays valid");

        // ---- collisions
        HomeLayout l = new HomeLayout(12, 8);
        HomeLayout.Page p = new HomeLayout.Page();
        l.pages.add(p);
        HomeLayout.Item c = new HomeLayout.Item(Kind.CLOCK, 0, 0, 4, 2);
        HomeLayout.Item w = new HomeLayout.Item(Kind.WEATHER, 4, 0, 4, 2);
        p.items.add(c);
        p.items.add(w);
        check(!Arrange.free(l, p, null, 3, 0, 2, 1), "overlap is not free");
        check(Arrange.free(l, p, c, 0, 0, 4, 2), "own cells are free to the item itself");
        check(!Arrange.free(l, p, null, 11, 0, 2, 1), "off the right edge is not free");
        check(!Arrange.free(l, p, null, -1, 0, 1, 1), "off the left edge is not free");
        int[] np = Arrange.nearestFree(l, p, c, 2, 0, 4, 2);
        check(np != null && np[0] == 0 && np[1] == 0 || np != null && np[1] >= 2 || np != null && np[0] >= 8,
                "dropped on a neighbour it snaps to a free spot, got " + (np == null ? "null" : np[0] + "," + np[1]));
        check(np != null && Arrange.free(l, p, c, np[0], np[1], 4, 2), "snap target is free");
        int[] sz = Arrange.resize(l, p, c, 6, 2);
        check(sz[0] == 4 && sz[1] == 2, "resize into a neighbour stops at the neighbour: " + sz[0] + "x" + sz[1]);
        sz = Arrange.resize(l, p, c, 4, 5);
        check(sz[0] == 4 && sz[1] == 5, "resize into free space grows");
        sz = Arrange.resize(l, p, c, 0, 0);
        check(sz[0] == 2 && sz[1] == 1, "resize never goes below the kind minimum: " + sz[0] + "x" + sz[1]);
        sz = Arrange.resize(l, p, w, 99, 99);
        check(sz[0] == 8 && sz[1] == 8, "resize stops at the grid edge: " + sz[0] + "x" + sz[1]);
        // Fill the page, then there is no room.
        HomeLayout f = new HomeLayout(2, 2);
        HomeLayout.Page fp = new HomeLayout.Page();
        f.pages.add(fp);
        HomeLayout.Item big = Arrange.place(f, fp, Kind.of(Kind.APP));
        check(big != null && big.w == 2 && big.h == 2, "place on empty page at default size");
        fp.items.clear();
        for (int i = 0; i < 4; i++) Arrange.place(f, fp, Kind.of(Kind.SETTINGS));
        check(fp.items.size() == 4 && Arrange.valid(f), "grid fills with no overlap");
        check(Arrange.place(f, fp, Kind.of(Kind.SETTINGS)) == null, "full page refuses");
        HomeLayout.Item added = Arrange.place(l, p, Kind.of(Kind.NOWPLAYING));
        check(added != null && Arrange.valid(l), "added item fits");

        // ---- vehicle data maths
        check(Kind.fraction(60, 0, 120) == 0.5f, "fraction mid");
        check(Kind.fraction(-5, 0, 120) == 0f && Kind.fraction(500, 0, 120) == 1f, "fraction clamps");
        check(Kind.fraction(5, 10, 10) == 0f && Kind.fraction(5, 10, 0) == 0f, "degenerate range is 0, not NaN");
        check(Kind.warning(111, 110, "above") && !Kind.warning(109, 110, "above") && Kind.warning(110, 110, "above"),
                "coolant warns from 110");
        check(Kind.warning(10, 15, "below") && !Kind.warning(40, 15, "below"), "fuel warns when low");
        check(Kind.vehicleRange("coolant")[1] == 120 && Kind.vehicleRange("coolant")[2] == 110, "coolant default range");

        check(Kind.vehicleRange("rpm")[2] == 5500 && "above".equals(Kind.vehicleWarnDir("rpm")), "rpm warns from 5500");
        check(Kind.vehicleStep("rpm") == 250 && Kind.vehicleStep("coolant") == 5, "stepper steps");
        check(!Kind.warningHeld(false, 5499, 5500, "above", 0, 8000), "not yet");
        check(Kind.warningHeld(false, 5500, 5500, "above", 0, 8000), "at the threshold");
        check(Kind.warningHeld(true, 5400, 5500, "above", 0, 8000)
                && !Kind.warningHeld(true, 5300, 5500, "above", 0, 8000), "holds, then clears");
        check(Kind.warningHeld(true, 16, 15, "below", 0, 100) && !Kind.warningHeld(true, 18, 15, "below", 0, 100),
                "hysteresis below");

        if (fails == 0) System.out.println("HomeLayoutTest: ok");
        else { System.out.println("HomeLayoutTest: " + fails + " failure(s)"); System.exit(1); }
    }

    static HomeLayout wrap(HomeLayout.Page p, int c, int r) {
        HomeLayout l = new HomeLayout(c, r);
        l.pages.add(p);
        return l;
    }
}
