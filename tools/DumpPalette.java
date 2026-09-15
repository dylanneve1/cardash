import ie.claudius.cardash.M3;

/** Prints the generated M3 roles for a seed, so the mockup renderer
 *  uses the app's real colour maths instead of eyeballed hexes. */
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
    }
    static void p(String k, int c) {
        System.out.printf("%s=#%06X%n", k, c & 0xFFFFFF);
    }
}
