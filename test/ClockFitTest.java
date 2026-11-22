import ie.claudius.cardash.ClockFit;

public class ClockFitTest {
    static int fails = 0;
    static void check(boolean ok, String what) {
        if (!ok) { fails++; System.out.println("FAIL: " + what); }
    }
    /** Fake font: width = chars * 0.6 * size, height = 1.0 * size. */
    static ClockFit.Measure fake(final int chars) {
        return new ClockFit.Measure() {
            public float width(float s) { return chars * 0.6f * s; }
            public float height(float s) { return s; }
        };
    }
    public static void main(String[] a) {
        // Width-limited: 5 chars in 300px -> 100px.
        float s = ClockFit.fit(fake(5), 300, 0, 10, 500);
        check(Math.abs(s - 100) < 0.1f, "width-limited " + s);
        // Height-limited.
        s = ClockFit.fit(fake(5), 300, 60, 10, 500);
        check(Math.abs(s - 60) < 0.1f, "height-limited " + s);
        // Capped by max.
        s = ClockFit.fit(fake(5), 3000, 0, 10, 200);
        check(s == 200f, "max cap " + s);
        // Never below min.
        s = ClockFit.fit(fake(5), 10, 0, 40, 200);
        check(s == 40f, "min floor " + s);
        // Unknown limits ignored.
        s = ClockFit.fit(fake(5), 0, 0, 10, 200);
        check(s == 200f, "no limits " + s);
        // Date scaling.
        check(ClockFit.dateSize(40, 18, 30) == 18f, "date min");
        check(ClockFit.dateSize(100, 18, 30) == 25f, "date prop");
        check(ClockFit.dateSize(400, 18, 30) == 30f, "date max");
        // Widest strings.
        check(ClockFit.widest("HH:mm").equals("88:88"), "widest 24h " + ClockFit.widest("HH:mm"));
        check(ClockFit.widest("h:mm").equals("88:88"), "widest 12h " + ClockFit.widest("h:mm"));
        check(ClockFit.widest("h:mm a").equals("88:88 AM"), "widest 12h am/pm " + ClockFit.widest("h:mm a"));
        if (fails > 0) { System.out.println(fails + " failure(s)"); System.exit(1); }
        System.out.println("ClockFitTest ok");
    }
}
