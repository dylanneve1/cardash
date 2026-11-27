import ie.claudius.cardash.layout.Pill;

/** Pill bars: how far the fill reaches, readable text colours, and dividing a card into pills. */
public class PillTest {

    private static int failures = 0;

    private static void check(boolean ok, String what) {
        if (!ok) {
            failures++;
            System.out.println("FAIL: " + what);
        }
    }

    private static Pill.Cells car(int n, float w, float h) {
        return Pill.cells(n, w, h, 8, 560, 240, 40, 64, 120, true);
    }

    private static Pill.Cells engine(int n, float w, float h) {
        return Pill.cells(n, w, h, 8, 400, 240, 40, 56, 80, false);
    }

    private static void inside(Pill.Cells c, float w, float h, String what) {
        float area = 0;
        for (int i = 0; i < c.shown; i++) {
            float[] r = c.rect(i);
            check(r[0] >= 0 && r[1] >= 0 && r[0] + r[2] <= w + 0.5f && r[1] + r[3] <= h + 0.5f && r[2] > 0 && r[3] > 0,
                    what + ": pill " + i + " inside");
            for (int j = 0; j < i; j++) {
                float[] o = c.rect(j);
                check(r[0] >= o[0] + o[2] || o[0] >= r[0] + r[2] || r[1] >= o[1] + o[3] || o[1] >= r[1] + r[3],
                        what + ": pills " + i + " and " + j + " overlap");
            }
        }
    }

    public static void main(String[] args) {
        // Fill width.
        check(Pill.fillWidth(0.5f, 400, 56, false) == 200, "half");
        check(Pill.fillWidth(1f, 400, 56, false) == 400 && Pill.fillWidth(2f, 400, 56, false) == 400, "full, clamped");
        check(Pill.fillWidth(0f, 400, 56, false) == 0 && Pill.fillWidth(-1f, 400, 56, false) == 0
                && Pill.fillWidth(Float.NaN, 400, 56, false) == 0, "empty");
        check(Pill.fillWidth(0.01f, 400, 56, false) == 56, "never narrower than its rounded end");
        check(Pill.fillWidth(0f, 400, 56, true) == 56, "an empty tank still shows red");
        check(Pill.fillWidth(0.5f, 40, 56, false) == 40, "a tiny pill");

        // Contrast.
        check(Math.abs(Pill.contrast(0xFF000000, 0xFFFFFFFF) - 21) < 0.01
                && Math.abs(Pill.contrast(0xFF777777, 0xFF777777) - 1) < 0.01, "ratio ends");
        check(Pill.contrast(0xFF000000, 0xFFFFFFFF) == Pill.contrast(0xFFFFFFFF, 0xFF000000), "symmetric");
        int dark = 0xFF1D1B20, pink = 0xFF7D2950, onPink = 0xFFFFD9E3;
        check(Pill.readable(dark, 0xFFE6E0E9) == 0xFFE6E0E9 && Pill.readable(pink, onPink) == onPink,
                "a good colour is kept");
        check(Pill.readable(0xFFFFD9E3, onPink) == 0xFF000000, "pale on pale becomes black");
        check(Pill.readable(0xFF303030, 0xFF404040) == 0xFFFFFFFF, "dark on dark becomes white");
        for (int g = 0; g < 256; g += 5) {
            int bg = 0xFF000000 | g * 0x010101;
            check(Pill.contrast(bg, Pill.readable(bg, bg)) >= Pill.MIN_CONTRAST, "always readable on grey " + g);
        }

        // The car list: fuel, outside, battery, trip across the bottom of a 1280-wide page.
        Pill.Cells c = car(4, 1228, 217);
        check(c.cols == 2 && c.rows == 2 && c.shown == 4 && c.cellH > 88, "two by two: " + c.cols + "x" + c.rows);
        inside(c, 1228, 217, "car");
        c = car(3, 1228, 217);   // no battery reading: the trip takes the whole second row
        check(c.cols == 2 && c.rect(2)[2] == 1228 && c.rect(1)[2] == c.cellW, "last pill fills its row");
        inside(c, 1228, 217, "car of three");
        c = car(4, 400, 360);
        check(c.cols == 1 && c.rows == 4 && c.shown == 4, "a narrow card is one column");
        c = car(1, 1228, 217);
        check(c.cols == 1 && c.cellH == 120, "one pill is not stretched over the card");

        // The engine list over a whole 1280x720 page.
        c = engine(25, 1228, 636);
        check(c.cols == 3 && c.rows == 9 && c.shown == 25 && c.cellH >= 56, "all of it in three columns: " + c.cellH);
        inside(c, 1228, 636, "engine");
        check(c.rect(24)[2] == c.cellW, "a table's last pill keeps its width");
        c = engine(6, 1228, 636);
        check(c.cols == 3 && c.rows == 2 && c.cellH == 80, "few values keep a sensible height");
        c = engine(25, 1000, 400);   // a smaller screen: more columns before shorter rows
        check(c.shown == 25 && c.cols == 4 && c.cellH >= 40, "four columns: " + c.cols + " " + c.cellH);
        inside(c, 1000, 400, "small");
        c = engine(25, 400, 200);
        check(c.cols == 1 && c.shown == 4 && c.cellH >= 40, "what doesn't fit is left out: " + c.shown);
        inside(c, 400, 200, "tiny");
        c = car(4, 200, 30);
        check(c.shown == 1 && c.cellH <= 30, "a sliver shows one");
        inside(c, 200, 30, "sliver");
        check(car(0, 400, 400).shown == 0 && car(3, 0, 10).shown == 0, "nothing to show");
        for (int n = 1; n <= 26; n++) {
            for (int w = 100; w <= 1300; w += 150) {
                for (int h = 40; h <= 700; h += 110) inside(engine(n, w, h), w, h, n + " in " + w + "x" + h);
            }
        }

        if (failures > 0) {
            System.out.println(failures + " failures");
            System.exit(1);
        }
        System.out.println("PillTest: all passed");
    }
}
