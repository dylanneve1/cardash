package ie.claudius.cardash.layout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The kinds of thing that can sit on a home page, with their grid sizes
 * and the per-item settings each one understands. Android-free: the
 * settings dialog and the renderer are built from this table.
 */
public final class Kind {

    public static final String APP = "app", CLOCK = "clock", DATE = "date",
            NOWPLAYING = "nowplaying", SPEEDO = "speedo", TRIP = "trip",
            WEATHER = "weather", VEHICLE = "vehicle", DOORS = "doors",
            ALLAPPS = "allapps", SETTINGS = "settings", CHIPS = "chips";

    /** One editable setting of an item. CHOICE has {@code choices}; otherwise it is an integer. */
    public static final class Option {
        public final String key, label;
        public final String[] choices;   // null => integer
        public final int min, max, step;
        public final String def;

        Option(String key, String label, String def, String... choices) {
            this.key = key; this.label = label; this.def = def; this.choices = choices;
            this.min = 0; this.max = 0; this.step = 0;
        }

        Option(String key, String label, int def, int min, int max, int step) {
            this.key = key; this.label = label; this.def = String.valueOf(def);
            this.choices = null; this.min = min; this.max = max; this.step = step;
        }

        public boolean isChoice() { return choices != null; }
    }

    public final String id, label;
    public final int minW, minH, defW, defH;
    public final List<Option> options;

    private Kind(String id, String label, int minW, int minH, int defW, int defH, Option... opts) {
        this.id = id; this.label = label;
        this.minW = minW; this.minH = minH; this.defW = defW; this.defH = defH;
        List<Option> l = new ArrayList<>();
        Collections.addAll(l, opts);
        this.options = Collections.unmodifiableList(l);
    }

    private static final String[] VFIELDS = { "coolant", "fuel", "rpm", "speed" };

    private static final List<Kind> ALL;
    static {
        List<Kind> k = new ArrayList<>();
        k.add(new Kind(APP, "App", 1, 1, 2, 2,
                new Option("label", "Label", "show", "show", "hide")));
        k.add(new Kind(CLOCK, "Clock", 2, 1, 4, 2,
                new Option("format", "Format", "default", "default", "12", "24")));
        k.add(new Kind(DATE, "Date", 2, 1, 4, 1,
                new Option("format", "Format", "default", "default", "full", "short")));
        k.add(new Kind(NOWPLAYING, "Now playing", 3, 1, 4, 3,
                new Option("style", "Style", "default", "default", "full", "compact")));
        k.add(new Kind(SPEEDO, "Speedometer", 2, 2, 4, 6,
                new Option("gauge", "Gauge", "default", "default", "arc", "digits"),
                new Option("scale", "Scale", "default", "default", "auto", "city", "fast")));
        k.add(new Kind(TRIP, "Trip meter", 2, 1, 4, 2));
        k.add(new Kind(WEATHER, "Weather", 2, 1, 4, 2));
        k.add(new Kind(VEHICLE, "Vehicle data", 2, 1, 4, 2,
                new Option("field", "Value", "coolant", VFIELDS),
                new Option("style", "Style", "bar", "bar", "gauge"),
                new Option("min", "Minimum", 0, -1000, 100000, 10),
                new Option("max", "Maximum", 120, -1000, 100000, 10),
                new Option("warn", "Warning at", 110, -1000, 100000, 5),
                new Option("warndir", "Warn when", "above", "above", "below")));
        k.add(new Kind(DOORS, "Doors", 2, 2, 2, 3));
        k.add(new Kind(ALLAPPS, "All apps button", 2, 1, 3, 1));
        k.add(new Kind(SETTINGS, "Settings button", 1, 1, 1, 1));
        k.add(new Kind(CHIPS, "Vehicle strip", 3, 1, 4, 1));
        ALL = Collections.unmodifiableList(k);
    }

    public static List<Kind> all() { return ALL; }

    public static Kind of(String id) {
        for (Kind k : ALL) if (k.id.equals(id)) return k;
        return null;
    }

    public Option option(String key) {
        for (Option o : options) if (o.key.equals(key)) return o;
        return null;
    }

    /** Settings a freshly added item of this kind starts with (only keys that differ from nothing). */
    public java.util.Map<String, String> defaults() {
        java.util.Map<String, String> m = new java.util.LinkedHashMap<>();
        for (Option o : options) m.put(o.key, o.def);
        return m;
    }

    // ---- vehicle data -----------------------------------------------------------

    /** {min, max, warn} for a vehicle field, and whether the warning is for low values. */
    public static int[] vehicleRange(String field) {
        if ("fuel".equals(field)) return new int[] { 0, 100, 15 };
        if ("rpm".equals(field)) return new int[] { 0, 8000, 6500 };
        if ("speed".equals(field)) return new int[] { 0, 220, 130 };
        return new int[] { 0, 120, 110 }; // coolant
    }

    public static String vehicleWarnDir(String field) {
        return "fuel".equals(field) ? "below" : "above";
    }

    public static String vehicleUnit(String field) {
        if ("fuel".equals(field)) return "%";
        if ("rpm".equals(field)) return " rpm";
        if ("speed".equals(field)) return " km/h";
        return "°C";
    }

    public static String vehicleLabel(String field) {
        if ("fuel".equals(field)) return "Fuel";
        if ("rpm".equals(field)) return "RPM";
        if ("speed".equals(field)) return "Speed";
        return "Coolant";
    }

    /** Settings for a vehicle item showing {@code field}, with that field's usual range and warning. */
    public static java.util.Map<String, String> vehicleDefaults(String field) {
        java.util.Map<String, String> m = new java.util.LinkedHashMap<>();
        int[] r = vehicleRange(field);
        m.put("field", field);
        m.put("style", "bar");
        m.put("min", String.valueOf(r[0]));
        m.put("max", String.valueOf(r[1]));
        m.put("warn", String.valueOf(r[2]));
        m.put("warndir", vehicleWarnDir(field));
        return m;
    }

    /**
     * Where a value falls between min and max, 0..1, clamped. A zero or
     * inverted range gives 0 rather than dividing by it.
     */
    public static float fraction(float value, float min, float max) {
        if (max <= min) return 0f;
        float f = (value - min) / (max - min);
        return f < 0f ? 0f : (f > 1f ? 1f : f);
    }

    /** True when {@code value} is past the warning threshold in the configured direction. */
    public static boolean warning(float value, float warn, String dir) {
        return "below".equals(dir) ? value <= warn : value >= warn;
    }
}
