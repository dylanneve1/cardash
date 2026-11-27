package ie.claudius.cardash.vehicle;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Every vehicle value a widget can show: its name, units, the range its
 * bar fills over, its warning, and for the OBD ones the mode-01 PID,
 * how many bytes it answers with, how they decode and how often it is
 * worth asking. The list is in the order the engine data page shows it.
 * Android-free; see VehicleFieldTest.
 */
public final class VehicleField {

    /** What the number is, for the unit settings (°F, miles) and for how it is written. */
    public enum Type { PLAIN, TEMPERATURE, SPEED, DISTANCE, DURATION }

    public final String id, label, unit;
    public final Type type;
    /** Mode-01 PID, or -1 for none. */
    public final int pid;
    /** Data bytes the PID answers with. */
    public final int bytes;
    /** Bar range and warning, metric. */
    public final int min, max, warn;
    /** "above", "below" or "never". */
    public final String warnDir;
    public final int decimals, step;
    /** False for counters with no natural full scale: their pill has no fill. */
    public final boolean bar;
    /** How often the OBD poller wants it, ms (the core values have their own times in {@link ObdSchedule}). */
    public final int interval;

    private VehicleField(String id, String label, String unit, Type type, int pid, int bytes,
                         int min, int max, int warn, String warnDir, int decimals, int step,
                         boolean bar, int interval) {
        this.id = id; this.label = label; this.unit = unit; this.type = type;
        this.pid = pid; this.bytes = bytes;
        this.min = min; this.max = max; this.warn = warn; this.warnDir = warnDir;
        this.decimals = decimals; this.step = step; this.bar = bar; this.interval = interval;
    }

    private static final List<VehicleField> ALL;
    static {
        List<VehicleField> l = new ArrayList<>();
        Type n = Type.PLAIN, t = Type.TEMPERATURE;
        //      id            label              unit    type  pid   bytes min  max   warn  dir     dec step bar interval
        add(l, "rpm",         "RPM",             "rpm",  n,    0x0C, 2,    0,   8000, 5500, "above", 0, 250, true, 0);
        add(l, "speed",       "Speed",           "km/h", Type.SPEED, 0x0D, 1, 0, 220, 130,  "above", 0, 5, true, 0);
        add(l, "load",        "Engine load",     "%",    n,    0x04, 1,    0,   100,  100,  "never", 0, 5, true, 1000);
        add(l, "throttle",    "Throttle",        "%",    n,    0x11, 1,    0,   100,  100,  "never", 0, 5, true, 1000);
        add(l, "coolant",     "Coolant",         "°C",   t,    0x05, 1,    0,   120,  110,  "above", 0, 5, true, 2000);
        add(l, "oiltemp",     "Oil temp",        "°C",   t,    0x5C, 1,    0,   150,  130,  "above", 0, 5, true, 5000);
        add(l, "intake",      "Intake air",      "°C",   t,    0x0F, 1,    -20, 80,   70,   "never", 0, 5, true, 5000);
        add(l, "outside",     "Outside",         "°C",   t,    0x46, 1,    -20, 50,   40,   "above", 0, 5, true, 15000);
        add(l, "fuel",        "Fuel",            "%",    n,    0x2F, 1,    0,   100,  15,   "below", 0, 5, true, 10000);
        add(l, "fuelrate",    "Fuel rate",       "L/h",  n,    0x5E, 2,    0,   30,   30,   "never", 1, 5, true, 2000);
        add(l, "voltage",     "Battery",         "V",    n,    0x42, 2,    10,  16,   11,   "below", 1, 1, true, 5000);
        add(l, "maf",         "Air flow",        "g/s",  n,    0x10, 2,    0,   200,  200,  "never", 1, 5, true, 1000);
        add(l, "map",         "Manifold",        "kPa",  n,    0x0B, 1,    0,   255,  255,  "never", 0, 5, true, 1000);
        add(l, "baro",        "Barometer",       "kPa",  n,    0x33, 1,    50,  110,  110,  "never", 0, 5, true, 30000);
        add(l, "timing",      "Timing advance",  "°",    n,    0x0E, 1,    -20, 60,   60,   "never", 1, 5, true, 2000);
        add(l, "stft",        "Short fuel trim", "%",    n,    0x06, 1,    -25, 25,   25,   "never", 1, 5, true, 2000);
        add(l, "ltft",        "Long fuel trim",  "%",    n,    0x07, 1,    -25, 25,   25,   "never", 1, 5, true, 5000);
        add(l, "absload",     "Absolute load",   "%",    n,    0x43, 2,    0,   100,  100,  "never", 0, 5, true, 2000);
        add(l, "pedal",       "Accelerator",     "%",    n,    0x49, 1,    0,   100,  100,  "never", 0, 5, true, 1000);
        add(l, "fuelpressure","Fuel pressure",   "kPa",  n,    0x0A, 1,    0,   765,  765,  "never", 0, 5, true, 5000);
        add(l, "railpressure","Rail pressure",   "bar",  n,    0x23, 2,    0,   250,  250,  "never", 0, 5, true, 5000);
        add(l, "cattemp",     "Catalyst temp",   "°C",   t,    0x3C, 2,    0,   1000, 900,  "above", 0, 10, true, 5000);
        add(l, "runtime",     "Run time",        "",     Type.DURATION, 0x1F, 2, 0, 7200, 7200, "never", 0, 60, false, 5000);
        add(l, "mildist",     "Check-engine km", "km",   Type.DISTANCE, 0x21, 2, 0, 1000, 1, "above", 0, 1, false, 30000);
        add(l, "cleardist",   "Since codes cleared", "km", Type.DISTANCE, 0x31, 2, 0, 65535, 65535, "never", 0, 10, false, 30000);
        ALL = Collections.unmodifiableList(l);
    }

    private static void add(List<VehicleField> l, String id, String label, String unit, Type type, int pid, int bytes,
                            int min, int max, int warn, String dir, int decimals, int step, boolean bar, int interval) {
        l.add(new VehicleField(id, label, unit, type, pid, bytes, min, max, warn, dir, decimals, step, bar, interval));
    }

    public static List<VehicleField> all() { return ALL; }

    /** The field with this id, or null. */
    public static VehicleField of(String id) {
        for (VehicleField f : ALL) if (f.id.equals(id)) return f;
        return null;
    }

    /** The field a PID feeds, or null. */
    public static VehicleField byPid(int pid) {
        for (VehicleField f : ALL) if (f.pid == pid) return f;
        return null;
    }

    /** The value in metric units from a PID's data bytes (SAE J1979 formulas). */
    public static float decode(int pid, int[] b) {
        int a = b[0], ab = b.length > 1 ? b[0] * 256 + b[1] : b[0];
        switch (pid) {
            case 0x04: case 0x11: case 0x2F: case 0x49: return a * 100f / 255f;
            case 0x05: case 0x0F: case 0x46: case 0x5C: return a - 40;
            case 0x06: case 0x07: return (a - 128) * 100f / 128f;
            case 0x0A: return a * 3;
            case 0x0C: return ab / 4f;
            case 0x0E: return a / 2f - 64f;
            case 0x10: return ab / 100f;
            case 0x23: return ab * 10 / 100f;           // 10 kPa per bit, shown in bar
            case 0x3C: return ab / 10f - 40f;
            case 0x42: return ab / 1000f;
            case 0x43: return ab * 100f / 255f;
            case 0x5E: return ab / 20f;
            default: return ab;                         // 0B 0D 1F 21 31 33: as sent
        }
    }

    /** This field's value in {@code s} (metric), or null while unknown. */
    public Float value(VehicleState s) {
        if (s == null) return null;
        int u = VehicleState.UNKNOWN_INT, v;
        switch (id) {
            case "rpm": v = s.rpm; break;
            case "speed": v = s.speedKph; break;
            case "coolant": v = s.coolantC; break;
            case "fuel": v = s.fuelPercent; break;
            case "outside": v = s.outsideC; break;
            case "intake": v = s.intakeC; break;
            default: return s.engine.get(pid);
        }
        return v == u ? null : Float.valueOf(v);
    }

    /** A metric number in the units shown. */
    public float convert(float v, boolean fahrenheit, boolean miles) {
        if (type == Type.TEMPERATURE && fahrenheit) return v * 9f / 5f + 32f;
        if ((type == Type.SPEED || type == Type.DISTANCE) && miles) return v / 1.609344f;
        return v;
    }

    public String unit(boolean fahrenheit, boolean miles) {
        if (type == Type.TEMPERATURE && fahrenheit) return "°F";
        if (type == Type.SPEED && miles) return "mph";
        if (type == Type.DISTANCE && miles) return "mi";
        return unit;
    }

    /** The label, with the distance unit it names kept in step with the setting. */
    public String label(boolean miles) {
        return miles && "mildist".equals(id) ? "Check-engine mi" : label;
    }

    /** An already converted value as text. */
    public String format(float shown) {
        if (type == Type.DURATION) {
            long sec = Math.max(0, Math.round(shown));
            return sec < 3600 ? String.format(Locale.US, "%d:%02d", sec / 60, sec % 60)
                    : String.format(Locale.US, "%d h %02d", sec / 3600, sec % 3600 / 60);
        }
        if (decimals == 0) return String.valueOf(Math.round(shown));
        float scale = (float) Math.pow(10, decimals);
        float r = Math.round(shown * scale) / scale;   // no "-0.0"
        return String.format(Locale.US, "%." + decimals + "f", r == 0f ? 0f : r);
    }
}
