package ie.claudius.cardash.layout;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** Collision, snapping and placement rules for items on a page. Android-free. */
public final class Arrange {

    private Arrange() {}

    public static boolean inBounds(HomeLayout l, int x, int y, int w, int h) {
        return x >= 0 && y >= 0 && w >= 1 && h >= 1 && x + w <= l.cols && y + h <= l.rows;
    }

    /** Is the rectangle inside the grid and clear of every item on the page except {@code ignore}? */
    public static boolean free(HomeLayout l, HomeLayout.Page p, HomeLayout.Item ignore,
                               int x, int y, int w, int h) {
        if (!inBounds(l, x, y, w, h)) return false;
        for (HomeLayout.Item o : p.items) {
            if (o != ignore && o.overlaps(x, y, w, h)) return false;
        }
        return true;
    }

    /**
     * The free position for a w x h item closest to (x, y) — what a drag
     * snaps to on release. Null if the page has no room for it at all.
     */
    public static int[] nearestFree(HomeLayout l, HomeLayout.Page p, HomeLayout.Item ignore,
                                    int x, int y, int w, int h) {
        int[] best = null;
        int bestD = Integer.MAX_VALUE;
        for (int yy = 0; yy + h <= l.rows; yy++) {
            for (int xx = 0; xx + w <= l.cols; xx++) {
                if (!free(l, p, ignore, xx, yy, w, h)) continue;
                int d = Math.abs(xx - x) + Math.abs(yy - y);
                if (d < bestD) { bestD = d; best = new int[] { xx, yy }; }
            }
        }
        return best;
    }

    /**
     * Size to snap a resize to: as close to the wanted size as the
     * kind's minimum, the grid edge and the neighbours allow. Never
     * smaller than the minimum; falls back to the current size.
     */
    public static int[] resize(HomeLayout l, HomeLayout.Page p, HomeLayout.Item it, int wantW, int wantH) {
        Kind k = Kind.of(it.type);
        int minW = k == null ? 1 : k.minW, minH = k == null ? 1 : k.minH;
        int maxW = Math.max(1, l.cols - it.x), maxH = Math.max(1, l.rows - it.y);
        int ww = Grid.clamp(wantW, Math.min(minW, maxW), maxW);
        int hh = Grid.clamp(wantH, Math.min(minH, maxH), maxH);
        int[] best = null;
        int bestCost = Integer.MAX_VALUE;
        for (int w = Math.min(minW, maxW); w <= ww; w++) {
            for (int h = Math.min(minH, maxH); h <= hh; h++) {
                if (!free(l, p, it, it.x, it.y, w, h)) continue;
                int cost = (wantW - w) * (wantW - w) + (wantH - h) * (wantH - h);
                if (cost < bestCost) { bestCost = cost; best = new int[] { w, h }; }
            }
        }
        return best != null ? best : new int[] { it.w, it.h };
    }

    /**
     * Where the arrow buttons move an item: the first free position going
     * in the direction (dx, dy), hopping over whatever is in the way.
     * Null when there is none before the grid edge.
     */
    public static int[] nudge(HomeLayout l, HomeLayout.Page p, HomeLayout.Item it, int dx, int dy) {
        if (dx == 0 && dy == 0) return null;
        for (int x = it.x + dx, y = it.y + dy; inBounds(l, x, y, it.w, it.h); x += dx, y += dy) {
            if (free(l, p, it, x, y, it.w, it.h)) return new int[] { x, y };
        }
        return null;
    }

    /**
     * The size after one press of a width or height button, the top-left
     * corner staying put. Null when the kind's minimum, the grid edge or a
     * neighbour forbids it.
     */
    public static int[] step(HomeLayout l, HomeLayout.Page p, HomeLayout.Item it, int dw, int dh) {
        Kind k = Kind.of(it.type);
        int w = it.w + dw, h = it.h + dh;
        if (dw < 0 && w < (k == null ? 1 : k.minW)) return null;
        if (dh < 0 && h < (k == null ? 1 : k.minH)) return null;
        return free(l, p, it, it.x, it.y, w, h) ? new int[] { w, h } : null;
    }

    /**
     * A new item of {@code kind} in the first free spot, at its default
     * size or the largest smaller size that fits. Null when the page is full.
     */
    public static HomeLayout.Item place(HomeLayout l, HomeLayout.Page p, Kind kind) {
        for (int w = Math.min(kind.defW, l.cols); w >= kind.minW; w--) {
            for (int h = Math.min(kind.defH, l.rows); h >= kind.minH; h--) {
                for (int y = 0; y + h <= l.rows; y++) {
                    for (int x = 0; x + w <= l.cols; x++) {
                        if (free(l, p, null, x, y, w, h)) {
                            HomeLayout.Item it = new HomeLayout.Item(kind.id, x, y, w, h);
                            p.items.add(it);
                            return it;
                        }
                    }
                }
            }
        }
        return null;
    }

    /** Clamp to the grid and the kind's minimum, then settle overlaps; drop what cannot be placed. */
    public static void repair(HomeLayout l) {
        for (HomeLayout.Page p : l.pages) {
            List<HomeLayout.Item> kept = new ArrayList<>();
            HomeLayout.Page settled = new HomeLayout.Page();
            for (Iterator<HomeLayout.Item> it = p.items.iterator(); it.hasNext(); ) {
                HomeLayout.Item i = it.next();
                Kind k = Kind.of(i.type);
                if (k == null) continue;
                i.w = Grid.clamp(i.w, Math.min(k.minW, l.cols), l.cols);
                i.h = Grid.clamp(i.h, Math.min(k.minH, l.rows), l.rows);
                i.x = Grid.clamp(i.x, 0, l.cols - i.w);
                i.y = Grid.clamp(i.y, 0, l.rows - i.h);
                if (free(l, settled, null, i.x, i.y, i.w, i.h)) {
                    settled.items.add(i);
                    kept.add(i);
                    continue;
                }
                int[] pos = nearestFree(l, settled, null, i.x, i.y, i.w, i.h);
                if (pos == null && k != null) {
                    // Last resort: shrink to the minimum and look again.
                    i.w = Math.min(k.minW, l.cols);
                    i.h = Math.min(k.minH, l.rows);
                    pos = nearestFree(l, settled, null, i.x, i.y, i.w, i.h);
                }
                if (pos != null) {
                    i.x = pos[0]; i.y = pos[1];
                    settled.items.add(i);
                    kept.add(i);
                }
            }
            p.items.clear();
            p.items.addAll(kept);
        }
    }

    /** Index of the first overlapping pair on a page, or null — used by tests and sanity checks. */
    public static boolean valid(HomeLayout l) {
        for (HomeLayout.Page p : l.pages) {
            for (int a = 0; a < p.items.size(); a++) {
                HomeLayout.Item i = p.items.get(a);
                if (!inBounds(l, i.x, i.y, i.w, i.h)) return false;
                for (int b = a + 1; b < p.items.size(); b++) {
                    if (i.overlaps(p.items.get(b))) return false;
                }
            }
        }
        return true;
    }
}
