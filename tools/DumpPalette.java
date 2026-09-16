import ie.claudius.cardash.M3;

/** Prints the generated M3 roles for a seed, so the mockup renderer
 *  uses the app's real colour maths instead of eyeballed hexes. Also
 *  prints the raw tones Wallpapers.java reaches for, so the mockup
 *  backgrounds are the real bundled designs. */
public class DumpPalette {
    public static void main(String[] a) {
        int seed = (int) Long.parseLong(a[0].replace("#", ""), 16) | 0xFF000000;
        M3 m = M3.fromSeed(seed);
        p("primary", m.primary());
        p("onPrimary", m.onPrimary());
        p("primaryContainer", m.primaryContainer());
        p("onPrimaryContainer", m.onPrimaryContainer());
        p("secondaryContainer", m.secondaryContainer());
        p("onSecondaryContainer", m.onSecondaryContainer());
        p("tertiaryContainer", m.tertiaryContainer());
        p("onTertiaryContainer", m.onTertiaryContainer());
        p("surface", m.surface());
        p("surfaceContainer", m.surfaceContainer());
        p("surfaceContainerHigh", m.surfaceContainerHigh());
        p("onSurface", m.onSurface());
        p("onSurfaceVariant", m.onSurfaceVariant());
        p("outline", m.outline());
        for (int t = 0; t <= 100; t += 5) {
            p("p" + t, m.primary.tone(t));
            p("t" + t, m.tertiary.tone(t));
            p("n" + t, m.neutral.tone(t));
        }
        // Wallpapers.java uses a few off-grid tones.
        p("p24", m.primary.tone(24));
    }
    static void p(String k, int c) {
        System.out.printf("%s=#%06X%n", k, c & 0xFFFFFF);
    }
}
