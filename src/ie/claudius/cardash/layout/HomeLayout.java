package ie.claudius.cardash.layout;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The home screen as data: a grid of {@code cols x rows} cells and one or
 * more pages of placed items. Android-free; persisted as JSON.
 *
 * Coordinates are cells, origin top-left, as the layout is <em>shown</em>:
 * switching the steering-wheel side mirrors the stored items
 * ({@link #mirror}) instead of mirroring at draw time, so what you edit
 * is what you see.
 */
public final class HomeLayout {

    public static final int VERSION = 1;
    public static final int MAX_PAGES = 8;

    /** One placed thing. Mutable: the editor moves and resizes items in place. */
    public static final class Item {
        public String type;
        public int x, y, w, h;
        public final Map<String, String> settings = new LinkedHashMap<>();

        public Item(String type, int x, int y, int w, int h) {
            this.type = type; this.x = x; this.y = y; this.w = w; this.h = h;
        }

        public Item set(String k, String v) { settings.put(k, v); return this; }

        public String get(String k, String def) {
            String v = settings.get(k);
            return v == null ? def : v;
        }

        public int getInt(String k, int def) {
            String v = settings.get(k);
            if (v == null) return def;
            try { return Integer.parseInt(v.trim()); } catch (NumberFormatException e) { return def; }
        }

        public Item copy() {
            Item c = new Item(type, x, y, w, h);
            c.settings.putAll(settings);
            return c;
        }

        public boolean overlaps(Item o) { return overlaps(o.x, o.y, o.w, o.h); }

        public boolean overlaps(int ox, int oy, int ow, int oh) {
            return x < ox + ow && ox < x + w && y < oy + oh && oy < y + h;
        }
    }

    public static final class Page {
        public final List<Item> items = new ArrayList<>();

        public Page copy() {
            Page p = new Page();
            for (Item i : items) p.items.add(i.copy());
            return p;
        }
    }

    public int cols, rows;
    public final List<Page> pages = new ArrayList<>();
    /** The page the home screen opens on. */
    public int start;

    public HomeLayout(int cols, int rows) {
        this.cols = Math.max(1, cols);
        this.rows = Math.max(1, rows);
    }

    public HomeLayout copy() {
        HomeLayout l = new HomeLayout(cols, rows);
        for (Page p : pages) l.pages.add(p.copy());
        l.start = start;
        return l;
    }

    public boolean hasItem(String type) {
        for (Page p : pages) for (Item i : p.items) if (i.type.equals(type)) return true;
        return false;
    }

    // ---- pages ------------------------------------------------------------------

    /** Insert a page, keeping the start page the same page. */
    public void insertPage(int at, Page p) {
        if (at <= start && !pages.isEmpty()) start++;
        pages.add(at, p);
    }

    /** Remove a page; removing the start page leaves its neighbour as the start. */
    public void removePage(int at) {
        pages.remove(at);
        if (at < start) start--;
        start = Math.max(0, Math.min(start, pages.size() - 1));
    }

    // ---- mirror ---------------------------------------------------------------

    /** Flip every item left-to-right. Applying it twice gives the original. */
    public void mirror() {
        for (Page p : pages) for (Item i : p.items) i.x = cols - i.x - i.w;
    }

    // ---- grid changes -----------------------------------------------------------

    /**
     * Re-fit to a different grid (another screen, restored backup) by
     * scaling cell edges, so neighbours that shared an edge still do.
     * Whatever then still collides is repaired by {@link Arrange#repair}.
     */
    public void rescale(int newCols, int newRows) {
        if (newCols == cols && newRows == rows) return;
        for (Page p : pages) {
            for (Item i : p.items) {
                int x0 = Math.round(i.x * (float) newCols / cols);
                int x1 = Math.round((i.x + i.w) * (float) newCols / cols);
                int y0 = Math.round(i.y * (float) newRows / rows);
                int y1 = Math.round((i.y + i.h) * (float) newRows / rows);
                if (x1 <= x0) x1 = x0 + 1;
                if (y1 <= y0) y1 = y0 + 1;
                i.x = x0; i.y = y0; i.w = x1 - x0; i.h = y1 - y0;
            }
        }
        cols = newCols;
        rows = newRows;
        Arrange.repair(this);
    }

    // ---- JSON ---------------------------------------------------------------------

    public String toJson() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("v", (double) VERSION);
        root.put("cols", (double) cols);
        root.put("rows", (double) rows);
        root.put("start", (double) start);
        List<Object> pl = new ArrayList<>();
        for (Page p : pages) {
            Map<String, Object> pm = new LinkedHashMap<>();
            List<Object> il = new ArrayList<>();
            for (Item i : p.items) {
                Map<String, Object> im = new LinkedHashMap<>();
                im.put("t", i.type);
                im.put("x", (double) i.x);
                im.put("y", (double) i.y);
                im.put("w", (double) i.w);
                im.put("h", (double) i.h);
                if (!i.settings.isEmpty()) im.put("s", new LinkedHashMap<String, Object>(i.settings));
                il.add(im);
            }
            pm.put("items", il);
            pl.add(pm);
        }
        root.put("pages", pl);
        return Json.write(root);
    }

    /**
     * Parse, validate and repair. Returns null when the text is not a
     * layout at all (caller falls back to the default). Unknown item
     * types are dropped; out-of-range or overlapping items are repaired,
     * so a hand-edited or corrupted value can never crash the launcher.
     */
    @SuppressWarnings("unchecked")
    public static HomeLayout fromJson(String text) {
        Object o = Json.parse(text);
        if (!(o instanceof Map)) return null;
        Map<String, Object> root = (Map<String, Object>) o;
        int cols = num(root.get("cols"), 0), rows = num(root.get("rows"), 0);
        if (cols < 1 || rows < 1 || cols > 64 || rows > 64) return null;
        Object pl = root.get("pages");
        if (!(pl instanceof List)) return null;
        HomeLayout l = new HomeLayout(cols, rows);
        for (Object po : (List<Object>) pl) {
            if (!(po instanceof Map) || l.pages.size() >= MAX_PAGES) continue;
            Page page = new Page();
            Object il = ((Map<String, Object>) po).get("items");
            if (il instanceof List) {
                for (Object io : (List<Object>) il) {
                    if (!(io instanceof Map)) continue;
                    Map<String, Object> im = (Map<String, Object>) io;
                    Object t = im.get("t");
                    if (!(t instanceof String) || Kind.of((String) t) == null) continue;
                    Item it = new Item((String) t, num(im.get("x"), 0), num(im.get("y"), 0),
                            num(im.get("w"), 1), num(im.get("h"), 1));
                    Object so = im.get("s");
                    if (so instanceof Map) {
                        for (Map.Entry<String, Object> e : ((Map<String, Object>) so).entrySet()) {
                            if (e.getValue() instanceof String) it.settings.put(e.getKey(), (String) e.getValue());
                        }
                    }
                    page.items.add(it);
                }
            }
            l.pages.add(page);
        }
        if (l.pages.isEmpty()) l.pages.add(new Page());
        l.start = Math.max(0, Math.min(num(root.get("start"), 0), l.pages.size() - 1));
        Arrange.repair(l);
        return l;
    }

    private static int num(Object o, int def) {
        return o instanceof Number ? (int) Math.round(((Number) o).doubleValue()) : def;
    }
}
