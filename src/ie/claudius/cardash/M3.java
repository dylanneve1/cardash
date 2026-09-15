package ie.claudius.cardash;

/**
 * A small Material 3 colour system.
 *
 * Android 12 ships dynamic colour in the framework; this head unit is
 * Android 11, so the framework can't do it. We generate the tonal
 * palettes ourselves from the wallpaper's extracted seed colour, which
 * gets us real Material You behaviour on a platform that never had it.
 *
 * HCT proper needs CAM16. CIE LCh(ab) is the close cousin that is a
 * few hundred lines shorter and perceptually good enough for a palette:
 * hold hue and chroma, sweep lightness, clamp back into sRGB gamut.
 */
public final class M3 {

    // ---- tonal palette -------------------------------------------------

    public static final class Tonal {
        private final double hue, chroma;

        Tonal(double hue, double chroma) {
            this.hue = hue;
            this.chroma = chroma;
        }

        /** Tone 0 (black) .. 100 (white), matching the M3 tone scale. */
        public int tone(double t) {
            return labToSrgb(t, chroma * Math.cos(Math.toRadians(hue)),
                    chroma * Math.sin(Math.toRadians(hue)));
        }
    }

    public final Tonal primary, secondary, tertiary, neutral, neutralVariant;

    private M3(int seed) {
        double[] lch = srgbToLch(seed);
        double h = lch[2];
        double c = lch[1];
        // A washed-out wallpaper shouldn't produce a washed-out UI.
        double base = Math.max(c, 48);
        primary = new Tonal(h, base);
        secondary = new Tonal(h, base / 3.0);
        tertiary = new Tonal(h + 60.0, base / 2.0);
        neutral = new Tonal(h, Math.min(base / 12.0, 4.0));
        neutralVariant = new Tonal(h, Math.min(base / 6.0, 8.0));
    }

    public static M3 fromSeed(int seed) {
        return new M3(seed);
    }

    // ---- dark scheme roles ---------------------------------------------
    // Head units are used at night far more than a phone, and a bright
    // surface in a windscreen is genuinely dangerous, so this is
    // dark-only by design.

    public int primary()            { return primary.tone(80); }
    public int onPrimary()          { return primary.tone(20); }
    public int primaryContainer()   { return primary.tone(30); }
    public int onPrimaryContainer() { return primary.tone(90); }

    public int secondaryContainer()   { return secondary.tone(30); }
    public int onSecondaryContainer() { return secondary.tone(90); }

    public int tertiaryContainer()   { return tertiary.tone(30); }
    public int onTertiaryContainer() { return tertiary.tone(90); }

    public int surface()            { return neutral.tone(6); }
    public int surfaceContainer()   { return neutral.tone(12); }
    public int surfaceContainerHigh(){ return neutral.tone(17); }
    public int onSurface()          { return neutral.tone(90); }
    public int onSurfaceVariant()   { return neutralVariant.tone(80); }
    public int outline()            { return neutralVariant.tone(60); }

    /** Same hue, alpha applied — for scrims over the wallpaper. */
    public static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | ((alpha & 0xFF) << 24);
    }

    // ---- colour space plumbing -----------------------------------------

    private static double[] srgbToLch(int argb) {
        double r = linear(((argb >> 16) & 0xFF) / 255.0);
        double g = linear(((argb >> 8) & 0xFF) / 255.0);
        double b = linear((argb & 0xFF) / 255.0);

        double x = r * 0.4124564 + g * 0.3575761 + b * 0.1804375;
        double y = r * 0.2126729 + g * 0.7151522 + b * 0.0721750;
        double z = r * 0.0193339 + g * 0.1191920 + b * 0.9503041;

        double fx = f(x / 0.95047), fy = f(y), fz = f(z / 1.08883);
        double L = 116 * fy - 16;
        double A = 500 * (fx - fy);
        double B = 200 * (fy - fz);

        double c = Math.sqrt(A * A + B * B);
        double h = Math.toDegrees(Math.atan2(B, A));
        if (h < 0) h += 360;
        return new double[] { L, c, h };
    }

    private static int labToSrgb(double L, double A, double B) {
        double fy = (L + 16) / 116.0;
        double fx = fy + A / 500.0;
        double fz = fy - B / 200.0;

        double x = 0.95047 * fInv(fx);
        double y = 1.0 * fInv(fy);
        double z = 1.08883 * fInv(fz);

        double r = x * 3.2404542 + y * -1.5371385 + z * -0.4985314;
        double g = x * -0.9692660 + y * 1.8760108 + z * 0.0415560;
        double b = x * 0.0556434 + y * -0.2040259 + z * 1.0572252;

        return 0xFF000000 | (channel(r) << 16) | (channel(g) << 8) | channel(b);
    }

    private static int channel(double linear) {
        double v = linear <= 0.0031308
                ? 12.92 * linear
                : 1.055 * Math.pow(Math.max(linear, 0), 1 / 2.4) - 0.055;
        int i = (int) Math.round(v * 255.0);
        return i < 0 ? 0 : (i > 255 ? 255 : i);
    }

    private static double linear(double c) {
        return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    private static double f(double t) {
        return t > 0.008856 ? Math.cbrt(t) : (7.787 * t) + (16.0 / 116.0);
    }

    private static double fInv(double t) {
        double t3 = t * t * t;
        return t3 > 0.008856 ? t3 : (t - 16.0 / 116.0) / 7.787;
    }
}
