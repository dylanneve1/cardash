package ie.claudius.cardash.layout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import ie.claudius.cardash.vehicle.VehicleField;

/**
 * The kinds of thing that can sit on a home page, with their grid sizes
 * and the per-item settings each one understands. Android-free: the
 * settings dialog and the renderer are built from this table.
 */
public final class Kind {

    public static final String APP = "app", CLOCK = "clock", DATE = "date",
            NOWPLAYING = "nowplaying", SPEEDO = "speedo", TRIP = "trip",
            WEATHER = "weather", VEHICLE = "vehicle", DOORS = "doors",
            ALLAPPS = "allapps", SETTINGS = "settings", CHIPS = "chips", LIST = "list";

    /** What a value list holds. */
    public static final String LIST_CAR = "car", LIST_ENGINE = "engine";

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

    private static String[] fieldIds() {
        List<VehicleField> all = VehicleField.all();
        String[] ids = new String[all.size()];
        for (int i = 0; i < ids.length; i++) ids[i] = all.get(i).id;
        return ids;
    }

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
                new Option("field", "Value", "coolant", fieldIds()),
                new Option("style", "Style", "bar", "bar", "gauge"),
                new Option("min", "Minimum", 0, -1000, 100000, 10),
                new Option("max", "Maximum", 120, -1000, 100000, 10),
                new Option("warn", "Warning at", 110, -1000, 100000, 5),
                new Option("warndir", "Warn when", "above", "above", "below", "never")));
        k.add(new Kind(LIST, "Value list", 2, 1, 4, 4,
                new Option("set", "Values", LIST_CAR, LIST_CAR, LIST_ENGINE)));
        k.add(new Kind(DOORS, "Car illustration", 2, 2, 3, 4));
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

    /** The field a vehicle item shows; coolant for one this version doesn't know. */
    public static VehicleField field(String id) {
        VehicleField f = VehicleField.of(id);
        return f != null ? f : VehicleField.of("coolant");
    }

    /** {min, max, warn} for a vehicle field. */
    public static int[] vehicleRange(String field) {
        VehicleField f = field(field);
        return new int[] { f.min, f.max, f.warn };
    }

    public static String vehicleWarnDir(String field) {
        return field(field).warnDir;
    }

    public static String vehicleLabel(String field) {
        return field(field).label;
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

    /** The stepper increment for a vehicle field's minimum, maximum and warning. */
    public static int vehicleStep(String field) {
        return field(field).step;
    }

    /**
     * {@link #warning} with a little hysteresis, so a value hovering on the
     * threshold doesn't flash the box: once warning, it takes 2% of the
     * range back past the threshold to clear.
     */
    public static boolean warningHeld(boolean was, float value, float warn, String dir, float min, float max) {
        if (warning(value, warn, dir)) return true;
        if ("never".equals(dir)) return false;
        float slack = Math.max(0f, (max - min) * 0.02f);
        return was && warning(value, "below".equals(dir) ? warn + slack : warn - slack, dir);
    }

    /** True when {@code value} is past the warning threshold in the configured direction. */
    public static boolean warning(float value, float warn, String dir) {
        if ("never".equals(dir)) return false;
        return "below".equals(dir) ? value <= warn : value >= warn;
    }
}
