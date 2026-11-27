package ie.claudius.cardash.layout;

/**
 * Cell size and pixel maths for the home grid. Android-free: the view
 * layer feeds in pixels, this turns them into cells and back.
 */
public final class Grid {

    /** Target cell size in dp: ~105 wide and ~90 tall gives 12x8 at 1280x720 / 160 dpi. */
    public static final float CELL_W_DP = 105f, CELL_H_DP = 90f;

    public final int cols, rows;
    public final float left, top, cellW, cellH, gap;

    public Grid(int cols, int rows, float widthPx, float heightPx, float padPx, float gapPx) {
        this.cols = cols;
        this.rows = rows;
        this.left = padPx;
        this.top = padPx;
        this.cellW = (widthPx - 2 * padPx) / cols;
        this.cellH = (heightPx - 2 * padPx) / rows;
        this.gap = gapPx;
    }

    public static int colsFor(float widthDp) {
        return clamp(Math.round(widthDp / CELL_W_DP), 6, 16);
    }

    public static int rowsFor(float heightDp) {
        return clamp(Math.round(heightDp / CELL_H_DP), 4, 10);
    }

    /** {x, y, w, h} in px of the cells, inset by half the gap on every side. */
    public float[] rect(int x, int y, int w, int h) {
        float g = gap / 2f;
        return new float[] {
                left + x * cellW + g, top + y * cellH + g,
                left + (x + w) * cellW - g, top + (y + h) * cellH - g };
    }

    /** The cell column a pixel x falls in, clamped to the grid. */
    public int colAt(float px) {
        return clamp((int) Math.floor((px - left) / cellW), 0, cols - 1);
    }

    public int rowAt(float py) {
        return clamp((int) Math.floor((py - top) / cellH), 0, rows - 1);
    }

    /** Whole cells a pixel distance spans, rounded to nearest — what a drag snaps to. */
    public int cellsX(float dxPx) { return Math.round(dxPx / cellW); }

    public int cellsY(float dyPx) { return Math.round(dyPx / cellH); }

    public static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
