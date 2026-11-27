package ie.claudius.cardash.vehicle;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A pretend car, for trying the dash without one (Waydroid, an emulator,
 * a unit on the bench). Values are set by name from the phone settings
 * page or adb, or played as a repeating drive. Android-free; see
 * SimVehicleTest.
 */
public final class SimVehicle {

    /** Numbers: {name, label, min, max, step, default}. "na" makes one unknown, to see how that looks. */
    public static final String[][] NUMBERS = {
            { "rpm", "RPM", "0", "8000", "50", "850" },
            { "speed", "Speed km/h", "0", "220", "1", "0" },
            { "coolant", "Coolant °C", "-30", "130", "1", "90" },
            { "fuel", "Fuel %", "0", "100", "1", "60" },
            { "outside", "Outside °C", "-30", "50", "1", "8" },
            { "odometer", "Odometer km", "0", "999999", "1", "123456" },
    };
    /** Switches: {name, label}; 0 or 1. */
    public static final String[][] SWITCHES = {
            { "door_fl", "Front left door" }, { "door_fr", "Front right door" },
            { "door_rl", "Rear left door" }, { "door_rr", "Rear right door" },
            { "boot", "Boot" }, { "bonnet", "Bonnet" },
            { "handbrake", "Handbrake" }, { "reverse", "Reverse" },
    };
    /** Length of the drive cycle. */
    public static final long CYCLE_MS = 90_000L;

    public static final SimVehicle SHARED = new SimVehicle();

    private final Map<String, Integer> values = new LinkedHashMap<>();
    private long cycleSince = -1;

    public SimVehicle() {
        reset();
    }

    public synchronized void reset() {
        values.clear();
        for (String[] n : NUMBERS) values.put(n[0], Integer.parseInt(n[5]));
        for (String[] s : SWITCHES) values.put(s[0], 0);
        cycleSince = -1;
    }

    public synchronized boolean cycling() {
        return cycleSince >= 0;
    }

    /**
     * Apply "name=value" pairs. {@code cycle=1} starts the drive cycle and
     * {@code cycle=0} stops it where it is; {@code reset=1} restores the
     * defaults; setting any value by hand also stops the cycle. Numbers are
     * clamped to their range.
     *
     * @return what was not understood, empty when all was
     */
    public synchronized String apply(Map<String, String> changes, long now) {
        StringBuilder bad = new StringBuilder();
        if ("1".equals(changes.get("reset"))) reset();
        for (Map.Entry<String, String> e : changes.entrySet()) {
            String k = e.getKey(), v = e.getValue() == null ? "" : e.getValue().trim();
            if ("reset".equals(k) || "cycle".equals(k)) continue;
            if (!values.containsKey(k)) {
                bad.append(k).append(' ');
                continue;
            }
            Integer parsed = parse(k, v);
            if (parsed == null) {
                bad.append(k).append('=').append(v).append(' ');
                continue;
            }
            stopCycle(now);
            values.put(k, parsed);
        }
        String cycle = changes.get("cycle");
        if ("1".equals(cycle)) cycleSince = now;
        else if ("0".equals(cycle)) stopCycle(now);
        else if (cycle != null) bad.append("cycle=").append(cycle).append(' ');
        return bad.toString().trim();
    }

    /** "rpm=3000,door_fl=1" as pairs. */
    public static Map<String, String> pairs(String spec) {
        Map<String, String> out = new LinkedHashMap<>();
        if (spec == null) return out;
        for (String part : spec.split("[,;]")) {
            if (part.trim().isEmpty()) continue;
            int e = part.indexOf('=');
            out.put((e < 0 ? part : part.substring(0, e)).trim(), e < 0 ? "" : part.substring(e + 1).trim());
        }
        return out;
    }

    private static Integer parse(String key, String v) {
        for (String[] n : NUMBERS) {
            if (!n[0].equals(key)) continue;
            if ("na".equalsIgnoreCase(v)) return VehicleState.UNKNOWN_INT;
            try {
                int i = Integer.parseInt(v);
                return Math.max(Integer.parseInt(n[2]), Math.min(Integer.parseInt(n[3]), i));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        if ("na".equalsIgnoreCase(v)) return VehicleState.UNKNOWN_INT;
        if ("1".equals(v) || "on".equals(v)) return 1;
        if ("0".equals(v) || "off".equals(v)) return 0;
        return null;
    }

    private void stopCycle(long now) {
        if (cycleSince < 0) return;
        values.putAll(cycleAt(now - cycleSince, values));
        cycleSince = -1;
    }

    /** The values as they are at {@code now}, by name. */
    public synchronized Map<String, Integer> values(long now) {
        Map<String, Integer> out = new LinkedHashMap<>(values);
        if (cycleSince >= 0) out.putAll(cycleAt(now - cycleSince, values));
        return out;
    }

    /** What the pretend car reports, as a vehicle state. */
    public VehicleState state(long now) {
        Map<String, Integer> v = values(now);
        VehicleState s = new VehicleState();
        s.rpm = v.get("rpm");
        s.speedKph = v.get("speed");
        s.coolantC = v.get("coolant");
        s.fuelPercent = v.get("fuel");
        s.fuelUnsupported = s.fuelPercent == VehicleState.UNKNOWN_INT;
        s.outsideC = v.get("outside");
        s.odometerKm = v.get("odometer");
        s.doorFrontLeft = tri(v.get("door_fl"));
        s.doorFrontRight = tri(v.get("door_fr"));
        s.doorRearLeft = tri(v.get("door_rl"));
        s.doorRearRight = tri(v.get("door_rr"));
        s.boot = tri(v.get("boot"));
        s.bonnet = tri(v.get("bonnet"));
        s.handbrake = tri(v.get("handbrake"));
        s.reverse = tri(v.get("reverse"));
        s.obdLinked = true;
        s.obdReplyMillis = now;
        return s;
    }

    private static VehicleState.Tri tri(int v) {
        if (v == VehicleState.UNKNOWN_INT) return VehicleState.Tri.UNKNOWN;
        return v != 0 ? VehicleState.Tri.YES : VehicleState.Tri.NO;
    }

    // ---- the drive cycle --------------------------------------------------

    /** {seconds, rpm, speed, coolant}; values are interpolated between the rows. */
    private static final int[][] DRIVE = {
            { 0, 850, 0, 60 }, { 5, 850, 0, 62 }, { 6, 1300, 5, 63 }, { 9, 900, 0, 65 },
            { 10, 900, 0, 65 }, { 16, 4200, 35, 72 }, { 17, 2200, 37, 73 }, { 24, 6000, 75, 84 },
            { 25, 3600, 76, 85 }, { 34, 4200, 110, 94 }, { 40, 2800, 110, 98 }, { 52, 2700, 105, 112 },
            { 58, 2600, 100, 113 }, { 62, 1800, 60, 105 }, { 70, 900, 0, 96 }, { 78, 850, 0, 92 },
            { 79, 0, 0, 92 }, { 89, 0, 0, 90 },
    };

    /** The values the cycle drives, at {@code elapsed} ms into it (it repeats). */
    private static Map<String, Integer> cycleAt(long elapsed, Map<String, Integer> base) {
        float t = (elapsed % CYCLE_MS) / 1000f;
        int i = 0;
        while (i + 1 < DRIVE.length && DRIVE[i + 1][0] <= t) i++;
        int[] a = DRIVE[i], b = DRIVE[Math.min(i + 1, DRIVE.length - 1)];
        float f = b[0] == a[0] ? 0f : Math.min(1f, (t - a[0]) / (b[0] - a[0]));
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put("rpm", Math.round(a[1] + (b[1] - a[1]) * f));
        m.put("speed", Math.round(a[2] + (b[2] - a[2]) * f));
        m.put("coolant", Math.round(a[3] + (b[3] - a[3]) * f));
        m.put("reverse", t >= 5 && t < 9 ? 1 : 0);
        m.put("handbrake", t < 5 || t >= 73 ? 1 : 0);
        m.put("door_fl", t >= 81 && t < 85 ? 1 : 0);
        m.put("boot", t >= 85 && t < 88 ? 1 : 0);
        Integer fuel = base.get("fuel");
        if (fuel != null && fuel != VehicleState.UNKNOWN_INT) m.put("fuel", Math.max(0, fuel - (int) (t / 30)));
        return m;
    }
}
