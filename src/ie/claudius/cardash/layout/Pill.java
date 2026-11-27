package ie.claudius.cardash.layout;

/**
 * The maths of the pill bar: how far its fill reaches, which text colour
 * stays readable on it, and how a card of several pills is divided up.
 * Android-free; see PillTest.
 */
public final class Pill {

    /** A pill at least this tall (dp) stacks its label over its value. */
    public static final float STACK_DP = 88f;
    /** The contrast text needs against what is behind it (WCAG AA). */
    public static final double MIN_CONTRAST = 4.5;

    private Pill() {}

    /**
     * Width of the fill in a pill {@code width} wide. Nothing for an empty
     * bar; otherwise never narrower than {@code cap} (the pill's rounded
     * end), so a nearly empty tank still shows its colour. {@code warning}
     * shows at least the cap even at the minimum.
     */
    public static float fillWidth(float fraction, float width, float cap, boolean warning) {
        float f = fraction > 0f ? Math.min(1f, fraction) : 0f;   // NaN is empty too
        if (f == 0f && !warning) return 0f;
        return Math.min(width, Math.max(cap, width * f));
    }

    private static double channel(int c) {
        double v = c / 255.0;
        return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
    }

    /** WCAG relative luminance of an opaque colour. */
    public static double luminance(int argb) {
        return 0.2126 * channel((argb >> 16) & 0xFF) + 0.7152 * channel((argb >> 8) & 0xFF)
                + 0.0722 * channel(argb & 0xFF);
    }

    /** WCAG contrast ratio, 1 to 21. */
    public static double contrast(int a, int b) {
        double x = luminance(a), y = luminance(b);
        return (Math.max(x, y) + 0.05) / (Math.min(x, y) + 0.05);
    }

    /** {@code preferred} if it reads on {@code background}, else black or white, whichever reads better. */
    public static int readable(int background, int preferred) {
        if (contrast(background, preferred) >= MIN_CONTRAST) return preferred;
        return contrast(background, 0xFFFFFFFF) >= contrast(background, 0xFF000000) ? 0xFFFFFFFF : 0xFF000000;
    }

    /** How a card divides into pills. Cells are in the same unit as the card's size. */
    public static final class Cells {
        public final int cols, rows, shown;
        public final float cellW, cellH, gap;
        private final boolean stretch;

        Cells(int cols, int rows, int shown, float cellW, float cellH, float gap, boolean stretch) {
            this.cols = cols; this.rows = rows; this.shown = shown; this.stretch = stretch;
            this.cellW = cellW; this.cellH = cellH; this.gap = gap;
        }

        /** {x, y, w, h} of pill {@code i}; a stretching card's last one takes up the rest of its row. */
        public float[] rect(int i) {
            int c = i % cols, r = i / cols;
            float w = cellW;
            if (stretch && i == shown - 1) w = (cellW + gap) * (cols - c) - gap;
            return new float[] { c * (cellW + gap), r * (cellH + gap), w, cellH };
        }
    }

    /**
     * Divide {@code w x h} among {@code n} pills, row by row. Columns start
     * at what {@code idealColW} gives and grow (never under {@code minColW})
     * while rows would be shorter than {@code comfortH}. Rows are never
     * taller than {@code maxH} nor shorter than {@code minH}; what then
     * doesn't fit is not shown.
     *
     * @param stretch the last pill fills its row (a card of a few values, not a long table)
     */
    public static Cells cells(int n, float w, float h, float gap,
                              float idealColW, float minColW, float minH, float comfortH, float maxH,
                              boolean stretch) {
        if (n <= 0 || w <= 0 || h <= 0) return new Cells(1, 0, 0, 0, 0, gap, false);
        int most = Math.max(1, (int) ((w + gap) / (minColW + gap)));
        int cols = Math.max(1, Math.min(most, Math.min(n, (int) ((w + gap) / (idealColW + gap)))));
        while (cols < Math.min(most, n) && rowHeight(n, cols, h, gap) < comfortH) cols++;
        int rows = (n + cols - 1) / cols;
        float rh = Math.min(maxH, rowHeight(n, cols, h, gap));
        if (rh < minH) {
            rows = Math.max(1, (int) ((h + gap) / (minH + gap)));
            rh = Math.min(h, (h + gap) / rows - gap);
        }
        return new Cells(cols, rows, Math.min(n, rows * cols), (w - gap * (cols - 1)) / cols, rh, gap, stretch);
    }

    /** A long list cut into headed sections, all in the same columns. */
    public static final class Sections {
        public final int cols;
        public final float cellW, cellH;
        /** The top of each section's heading; -1 for an empty section, which has none. */
        public final float[] headY;
        /** {x, y, w, h} of every pill, section after section. */
        public final float[][] rects;
        /** The height of it all. */
        public final float height;

        Sections(int cols, float cellW, float cellH, float[] headY, float[][] rects, float height) {
            this.cols = cols; this.cellW = cellW; this.cellH = cellH;
            this.headY = headY; this.rects = rects; this.height = height;
        }
    }

    /**
     * Stack sections of {@code counts[i]} pills in a width {@code w}: as
     * many columns of about {@code idealColW} as fit, rows {@code rowH}
     * tall, each section that has pills under a heading {@code headH} tall.
     * It is as tall as it needs to be; the card scrolls it.
     */
    public static Sections sections(int[] counts, float w, float gap, float idealColW, float rowH, float headH) {
        int cols = Math.max(1, (int) ((w + gap) / (idealColW + gap)));
        float cellW = Math.max(0f, (w - gap * (cols - 1)) / cols);
        int total = 0;
        for (int n : counts) total += n;
        float[] headY = new float[counts.length];
        float[][] rects = new float[total][];
        float y = 0;
        int at = 0;
        for (int s = 0; s < counts.length; s++) {
            if (counts[s] <= 0) {
                headY[s] = -1;
                continue;
            }
            if (y > 0) y += gap;
            headY[s] = y;
            y += headH;
            for (int i = 0; i < counts[s]; i++) {
                int c = i % cols, r = i / cols;
                rects[at++] = new float[] { c * (cellW + gap), y + r * (rowH + gap), cellW, rowH };
            }
            int rows = (counts[s] + cols - 1) / cols;
            y += rows * rowH + (rows - 1) * gap;
        }
        return new Sections(cols, cellW, rowH, headY, rects, y);
    }

    private static float rowHeight(int n, int cols, float h, float gap) {
        int rows = (n + cols - 1) / cols;
        return (h - gap * (rows - 1)) / rows;
    }
}
