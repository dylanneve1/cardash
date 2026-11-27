package ie.claudius.cardash.car;

/** A GPS bearing as a compass point. Android-free; see CarGeometryTest. */
public final class Compass {

    private Compass() {}

    private static final String[] POINTS = { "N", "NE", "E", "SE", "S", "SW", "W", "NW" };

    public static String point(float degrees) {
        float d = ((degrees % 360f) + 360f) % 360f;
        return POINTS[Math.round(d / 45f) % 8];
    }
}
