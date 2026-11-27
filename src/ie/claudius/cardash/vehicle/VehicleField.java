package ie.claudius.cardash.vehicle;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Every vehicle value a widget can show: its name, units, the range its
 * bar fills over, its warning, and for the OBD ones the mode-01 PID,
 * how many bytes it answers with, how they decode and how often it is
 * worth asking. Some PIDs carry several values (an O2 sensor's voltage
 * and trim, the exhaust gas temperatures): each is a field of its own,
 * told apart by its {@link #part}. The list is in the order the engine
 * data page shows it, section by section.
 * Android-free; see VehicleFieldTest.
 */
public final class VehicleField {

    /** What the number is, for the unit settings (°F, miles) and for how it is written. */
    public enum Type { PLAIN, TEMPERATURE, SPEED, DISTANCE, DURATION, MINUTES, STATUS }

    /** The headings of the engine data page, in order. */
    public enum Section {
        CORE("Core"), TEMPERATURES("Temperatures & pressures"), FUEL("Air & fuel"), LAMBDA("Lambda"),
        EMISSIONS("Emissions / diesel"), TRIPS("Distances & times");

        public final String title;

        Section(String title) { this.title = title; }
    }

    /** The oxygen-sensor bitmaps: which positions are fitted, on up to two banks or up to four. */
    public static final int O2_PRESENT_2_BANKS = 0x13, O2_PRESENT_4_BANKS = 0x1D;
    /** How often the sensor bitmaps are asked: they don't change. */
    private static final int PRESENCE_INTERVAL = 60_000;

    /** One PID the poller asks for. */
    public static final class Request {
        public final int pid, bytes, interval;
        /** One of the many that take turns in the slow tier. */
        public final boolean slow;

        private Request(int pid, int bytes, int interval, boolean slow) {
            this.pid = pid; this.bytes = bytes; this.interval = interval; this.slow = slow;
        }
    }

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
    /** Which of the PID's values this is, from 0. */
    public final int part;
    /** Polled in the rotating slow tier, which never crowds out the others. */
    public final boolean slow;
    public final Section section;

    private VehicleField(Section section, String id, String label, String unit, Type type, int pid, int part,
                         int bytes, int min, int max, int warn, String warnDir, int decimals, int step,
                         boolean bar, int interval, boolean slow) {
        this.section = section; this.id = id; this.label = label; this.unit = unit; this.type = type;
        this.pid = pid; this.part = part; this.bytes = bytes;
        this.min = min; this.max = max; this.warn = warn; this.warnDir = warnDir;
        this.decimals = decimals; this.step = step; this.bar = bar; this.interval = interval; this.slow = slow;
    }

    private static final List<VehicleField> ALL;
    private static final List<Request> REQUESTS;

    /** The table being written: the section its next rows go in. */
    private static final class Table {
        final List<VehicleField> l = new ArrayList<>();
        Section section;

        /** A value of the original set: asked in whatever room the fast ones leave. */
        void add(String id, String label, String unit, Type type, int pid, int bytes,
                 int min, int max, int warn, String dir, int decimals, int step, boolean bar, int interval) {
            l.add(new VehicleField(section, id, label, unit, type, pid, 0, bytes, min, max, warn, dir,
                    decimals, step, bar, interval, false));
        }

        /** A value of the slow tier; {@code part} picks one of a PID's several. */
        void slow(String id, String label, String unit, Type type, int pid, int part, int bytes,
                  int min, int max, int warn, String dir, int decimals, int step, boolean bar, int interval) {
            l.add(new VehicleField(section, id, label, unit, type, pid, part, bytes, min, max, warn, dir,
                    decimals, step, bar, interval, true));
        }

        /** A slow percentage, 0 to 100, no warning. */
        void percent(String id, String label, int pid, int interval) {
            slow(id, label, "%", Type.PLAIN, pid, 0, 1, 0, 100, 100, "never", 0, 5, true, interval);
        }

        void trim(String id, String label, int pid, int part, int bytes, int interval) {
            slow(id, label, "%", Type.PLAIN, pid, part, bytes, -25, 25, 25, "never", 1, 5, true, interval);
        }
    }

    /** "B1S2" for sensor {@code i} (0 to 7) where the car has up to two banks, or up to four. */
    static String sensorName(int i, boolean fourBanks) {
        int per = fourBanks ? 2 : 4;
        return "B" + (i / per + 1) + "S" + (i % per + 1);
    }

    static {
        Table l = new Table();
        Type n = Type.PLAIN, t = Type.TEMPERATURE;
        l.section = Section.CORE;
        //    id            label              unit    type  pid   bytes min  max   warn  dir     dec step bar interval
        l.add("rpm",         "RPM",             "rpm",  n,    0x0C, 2,    0,   8000, 5500, "above", 0, 250, true, 0);
        l.add("speed",       "Speed",           "km/h", Type.SPEED, 0x0D, 1, 0, 220, 130,  "above", 0, 5, true, 0);
        l.add("load",        "Engine load",     "%",    n,    0x04, 1,    0,   100,  100,  "never", 0, 5, true, 1000);
        l.add("absload",     "Absolute load",   "%",    n,    0x43, 2,    0,   100,  100,  "never", 0, 5, true, 2000);
        l.add("throttle",    "Throttle",        "%",    n,    0x11, 1,    0,   100,  100,  "never", 0, 5, true, 1000);
        l.percent("relthrottle", "Relative throttle", 0x45, 2000);
        l.add("pedal",       "Accelerator",     "%",    n,    0x49, 1,    0,   100,  100,  "never", 0, 5, true, 1000);
        l.percent("pedale",      "Accelerator E",     0x4A, 5000);
        l.percent("pedalf",      "Accelerator F",     0x4B, 5000);
        l.add("voltage",     "Battery",         "V",    n,    0x42, 2,    10,  16,   11,   "below", 1, 1, true, 5000);

        l.section = Section.TEMPERATURES;
        l.add("coolant",     "Coolant",         "°C",   t,    0x05, 1,    0,   120,  110,  "above", 0, 5, true, 2000);
        l.add("oiltemp",     "Oil temp",        "°C",   t,    0x5C, 1,    0,   150,  130,  "above", 0, 5, true, 5000);
        l.add("intake",      "Intake air",      "°C",   t,    0x0F, 1,    -20, 80,   70,   "never", 0, 5, true, 5000);
        l.add("outside",     "Outside",         "°C",   t,    0x46, 1,    -20, 50,   40,   "above", 0, 5, true, 15000);
        l.add("cattemp",     "Catalyst temp",   "°C",   t,    0x3C, 2,    0,   1000, 900,  "above", 0, 10, true, 5000);
        l.add("map",         "Manifold",        "kPa",  n,    0x0B, 1,    0,   255,  255,  "never", 0, 5, true, 1000);
        l.add("baro",        "Barometer",       "kPa",  n,    0x33, 1,    50,  110,  110,  "never", 0, 5, true, 30000);
        l.add("fuelpressure","Fuel pressure",   "kPa",  n,    0x0A, 1,    0,   765,  765,  "never", 0, 5, true, 5000);
        l.slow("railrel",    "Rail vs manifold", "kPa", n,    0x22, 0, 2, 0,   5200, 5200, "never", 0, 50, true, 5000);
        l.add("railpressure","Rail pressure",   "bar",  n,    0x23, 2,    0,   250,  250,  "never", 0, 5, true, 5000);

        l.section = Section.FUEL;
        l.add("fuel",        "Fuel",            "%",    n,    0x2F, 1,    0,   100,  15,   "below", 0, 5, true, 10000);
        l.add("fuelrate",    "Fuel rate",       "L/h",  n,    0x5E, 2,    0,   30,   30,   "never", 1, 5, true, 2000);
        l.add("maf",         "Air flow",        "g/s",  n,    0x10, 2,    0,   200,  200,  "never", 1, 5, true, 1000);
        l.add("timing",      "Timing advance",  "°",    n,    0x0E, 1,    -20, 60,   60,   "never", 1, 5, true, 2000);
        l.add("stft",        "Short fuel trim", "%",    n,    0x06, 1,    -25, 25,   25,   "never", 1, 5, true, 2000);
        l.add("ltft",        "Long fuel trim",  "%",    n,    0x07, 1,    -25, 25,   25,   "never", 1, 5, true, 5000);
        l.trim("stft2",      "Short trim bank 2", 0x08, 0, 1, 5000);
        l.trim("ltft2",      "Long trim bank 2",  0x09, 0, 1, 10000);

        l.section = Section.LAMBDA;
        // Narrowband sensors: voltage and that sensor's short trim.
        for (int i = 0; i < 8; i++) {
            String s = sensorName(i, false);
            l.slow("o2v" + (i + 1), "O2 " + s, "V", n, 0x14 + i, 0, 2, 0, 1, 1, "never", 2, 1, true, 5000);
            l.trim("o2t" + (i + 1), "O2 " + s + " trim", 0x14 + i, 1, 2, 5000);
        }
        // Wideband sensors, reporting a voltage (24-2B) or a current (34-3B).
        for (int i = 0; i < 8; i++) {
            String s = sensorName(i, false);
            l.slow("wbr" + (i + 1), "Lambda " + s, "λ", n, 0x24 + i, 0, 4, 0, 2, 2, "never", 2, 1, true, 5000);
            l.slow("wbv" + (i + 1), "Lambda " + s + " volts", "V", n, 0x24 + i, 1, 4, 0, 8, 8, "never", 2, 1,
                    true, 5000);
        }
        for (int i = 0; i < 8; i++) {
            String s = sensorName(i, false);
            l.slow("wcr" + (i + 1), "Lambda " + s, "λ", n, 0x34 + i, 0, 4, 0, 2, 2, "never", 2, 1, true, 5000);
            l.slow("wci" + (i + 1), "Lambda " + s + " current", "mA", n, 0x34 + i, 1, 4, -128, 128, 128, "never",
                    2, 1, true, 5000);
        }

        l.section = Section.EMISSIONS;
        // Warns while the check-engine light is on; its text comes from Readiness, not from a number.
        l.slow("readiness",  "Emissions readiness", "", Type.STATUS, 0x01, 0, 4, 0, 1, 1, "above", 0, 1, false,
                30000);
        l.percent("egr",         "Commanded EGR",     0x2C, 5000);
        l.slow("egrerr",     "EGR error",       "%",    n,    0x2D, 0, 1, -100, 100, 100, "never", 1, 5, true, 5000);
        l.percent("evappurge",   "EVAP purge",        0x2E, 5000);
        l.slow("evappressure", "EVAP pressure", "Pa",   n,    0x32, 0, 2, -8192, 8192, 8192, "never", 0, 50, true,
                10000);
        for (int bank = 1; bank <= 2; bank++) {
            for (int i = 1; i <= 4; i++) {
                l.slow("egt" + bank + i, "Exhaust B" + bank + "S" + i, "°C", t, 0x77 + bank, i - 1, 9,
                        0, 1000, 850, "above", 0, 10, true, 10000);
            }
        }
        String[][] dpf = { { "dpfin1", "DPF inlet B1" }, { "dpfout1", "DPF outlet B1" },
                { "dpfin2", "DPF inlet B2" }, { "dpfout2", "DPF outlet B2" } };
        for (int i = 0; i < dpf.length; i++) {
            l.slow(dpf[i][0], dpf[i][1], "°C", t, 0x7C, i, 9, 0, 1000, 700, "above", 0, 10, true, 10000);
        }

        l.section = Section.TRIPS;
        l.add("runtime",     "Run time",        "",     Type.DURATION, 0x1F, 2, 0, 7200, 7200, "never", 0, 60, false, 5000);
        l.add("mildist",     "Check-engine km", "km",   Type.DISTANCE, 0x21, 2, 0, 1000, 1, "above", 0, 1, false, 30000);
        l.add("cleardist",   "Since codes cleared", "km", Type.DISTANCE, 0x31, 2, 0, 65535, 65535, "never", 0, 10, false, 30000);
        l.slow("miltime",    "Check-engine time", "",   Type.MINUTES, 0x4D, 0, 2, 0, 600, 1, "above", 0, 1, false, 30000);
        l.slow("cleartime",  "Time since cleared", "",  Type.MINUTES, 0x4E, 0, 2, 0, 65535, 65535, "never", 0, 10,
                false, 30000);
        ALL = Collections.unmodifiableList(l.l);

        List<Request> r = new ArrayList<>();
        Set<Integer> have = new HashSet<>();
        for (VehicleField f : ALL) if (have.add(f.pid)) r.add(new Request(f.pid, f.bytes, f.interval, f.slow));
        r.add(new Request(O2_PRESENT_2_BANKS, 1, PRESENCE_INTERVAL, true));
        r.add(new Request(O2_PRESENT_4_BANKS, 1, PRESENCE_INTERVAL, true));
        REQUESTS = Collections.unmodifiableList(r);
    }

    public static List<VehicleField> all() { return ALL; }

    /** Every mode-01 PID there is something to ask for, each once. */
    public static List<Request> requests() { return REQUESTS; }

    /** What is known about asking for a PID, or null. */
    public static Request request(int pid) {
        for (Request r : REQUESTS) if (r.pid == pid) return r;
        return null;
    }

    /** Where this value sits in {@link VehicleState#engine}. */
    public int key() { return pid | part << 8; }

    /** The field with this id, or null. */
    public static VehicleField of(String id) {
        for (VehicleField f : ALL) if (f.id.equals(id)) return f;
        return null;
    }

    /** The first field a PID feeds, or null. */
    public static VehicleField byPid(int pid) {
        for (VehicleField f : ALL) if (f.pid == pid) return f;
        return null;
    }

    /** The value in metric units from a PID's data bytes (SAE J1979 formulas). */
    public static float decode(int pid, int[] b) {
        int a = b[0], ab = b.length > 1 ? b[0] * 256 + b[1] : b[0];
        if (pid >= 0x14 && pid <= 0x1B) return a / 200f;                        // narrowband volts
        if (pid >= 0x24 && pid <= 0x2B || pid >= 0x34 && pid <= 0x3B) return ab * 2f / 65536f;   // lambda
        switch (pid) {
            case 0x04: case 0x11: case 0x2C: case 0x2E: case 0x2F: case 0x45: case 0x49: case 0x4A: case 0x4B:
                return a * 100f / 255f;
            case 0x05: case 0x0F: case 0x46: case 0x5C: return a - 40;
            case 0x06: case 0x07: case 0x08: case 0x09: case 0x2D: return (a - 128) * 100f / 128f;
            case 0x0A: return a * 3;
            case 0x0C: return ab / 4f;
            case 0x0E: return a / 2f - 64f;
            case 0x10: return ab / 100f;
            case 0x22: return ab * 0.079f;
            case 0x23: return ab * 10 / 100f;           // 10 kPa per bit, shown in bar
            case 0x32: return (short) ab / 4f;          // two's complement, 0.25 Pa per bit
            case 0x3C: return ab / 10f - 40f;
            case 0x42: return ab / 1000f;
            case 0x43: return ab * 100f / 255f;
            case 0x5E: return ab / 20f;
            default: return ab;                         // 0B 0D 1F 21 31 33 4D 4E: as sent
        }
    }

    /**
     * This field's value from its PID's data bytes, or null where the
     * reply says there is none: a sensor an exhaust temperature or filter
     * PID marks as missing, a trim an O2 sensor doesn't take part in.
     */
    public Float read(int[] b) {
        if (b == null || b.length < bytes) return null;
        if (type == Type.STATUS) {
            Readiness r = Readiness.parse(b);
            return r == null ? null : Float.valueOf(r.mil ? 1f : 0f);
        }
        if (pid == 0x78 || pid == 0x79 || pid == 0x7C) {
            // Byte A says which of the four two-byte readings are there.
            if ((b[0] & (1 << part)) == 0) return null;
            return (b[1 + 2 * part] * 256 + b[2 + 2 * part]) / 10f - 40f;
        }
        if (part == 0) return decode(pid, b);
        if (pid >= 0x14 && pid <= 0x1B) return b[1] == 0xFF ? null : Float.valueOf((b[1] - 128) * 100f / 128f);
        int cd = b[2] * 256 + b[3];
        if (pid >= 0x24 && pid <= 0x2B) return cd * 8f / 65536f;     // volts
        return cd / 256f - 128f;                                     // 34-3B: mA
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
            case "readiness": return s.readiness == null ? null : Float.valueOf(s.readiness.mil ? 1f : 0f);
            default: return s.engine.get(key());
        }
        return v == u ? null : Float.valueOf(v);
    }

    /** True for an oxygen sensor's values, whose name depends on how many banks the car has. */
    private boolean oxygen() {
        return section == Section.LAMBDA;
    }

    /** The label for this car: O2 sensors are numbered by the positions it says it has. */
    public String label(VehicleState s, boolean miles) {
        if (oxygen() && s != null && s.o2FourBanks()) {
            int i = (pid & 0x0F) - 4;   // 14..1B end in 4..B, as do 24..2B and 34..3B
            return label.replace(sensorName(i, false), sensorName(i, true));
        }
        return label(miles);
    }

    /** The name where values are picked from a list: tells the two kinds of wideband sensor apart. */
    public String pickName() {
        if (id.startsWith("wb")) return label + " (V type)";
        if (id.startsWith("wc")) return label + " (mA type)";
        return label;
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
        if (type == Type.MINUTES) {
            long min = Math.max(0, Math.round(shown));
            return min < 60 ? min + " min" : String.format(Locale.US, "%d h %02d", min / 60, min % 60);
        }
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
