package ie.claudius.cardash.vehicle;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
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
            { "mil", "Check-engine light" },
    };
    /** What a code read returns while the check-engine light is switched on. */
    private static final String[] MIL_CODES = { "P0420", "P0301" };
    /** Monitor status of a petrol car with every test it has run but the evaporative one. */
    private static final int[] MONITORS = { 0x00, 0x07, 0xE5, 0x04 };
    /** Length of the drive cycle. */
    public static final long CYCLE_MS = 90_000L;

    public static final SimVehicle SHARED = new SimVehicle();

    private final Map<String, Integer> values = new LinkedHashMap<>();
    /** Engine readings set by hand, by field id; NaN for "na". The rest follow rpm, speed and coolant. */
    private final Map<String, Float> engine = new LinkedHashMap<>();
    private long cycleSince = -1;

    public SimVehicle() {
        reset();
    }

    public synchronized void reset() {
        values.clear();
        for (String[] n : NUMBERS) values.put(n[0], Integer.parseInt(n[5]));
        for (String[] s : SWITCHES) values.put(s[0], 0);
        engine.clear();
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
            if (!values.containsKey(k) && isEngine(k)) {
                Float f = parseEngine(k, v);
                if (f == null) {
                    bad.append(k).append('=').append(v).append(' ');
                    continue;
                }
                stopCycle(now);
                engine.put(k, f);
                continue;
            }
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
        if ("1".equals(cycle)) {
            cycleSince = now;
            // The drive drives the engine readings too; only the "na" ones stay missing.
            for (java.util.Iterator<Float> i = engine.values().iterator(); i.hasNext(); ) {
                if (!i.next().isNaN()) i.remove();
            }
        }
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

    /** The stored fault codes: none until the check-engine light is switched on. */
    public synchronized List<String> codes() {
        List<String> out = new ArrayList<>();
        if (values.get("mil") == 1) Collections.addAll(out, MIL_CODES);
        return out;
    }

    private static boolean isEngine(String id) {
        if ("intake".equals(id)) return true;
        VehicleField f = VehicleField.of(id);
        // The monitor status goes with the check-engine light switch.
        return f != null && f.interval > 0 && f.type != VehicleField.Type.STATUS;
    }

    private static Float parseEngine(String id, String v) {
        if ("na".equalsIgnoreCase(v)) return Float.NaN;
        try {
            float f = Float.parseFloat(v);
            VehicleField field = VehicleField.of(id);
            if (Float.isNaN(f)) return null;
            int top = field.type == VehicleField.Type.DURATION || field.type == VehicleField.Type.DISTANCE
                    || field.type == VehicleField.Type.MINUTES ? 65535 : field.max;
            return Math.max(field.min, Math.min(top, f));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Engine readings that go with the driving: load and air follow speed
     * and revs, temperatures follow the coolant, an engine that is off
     * pulls no air and the battery rests. Missing inputs count as idle.
     *
     * @param t seconds, for the small wobble of a live engine and the run time
     */
    private static Map<String, Float> engineAt(Map<String, Integer> v, float t, float pull) {
        int rpm = known(v.get("rpm"), 850), speed = known(v.get("speed"), 0);
        int coolant = known(v.get("coolant"), 90), outside = known(v.get("outside"), 8);
        boolean on = rpm > 0;
        float revs = Math.min(1f, rpm / 6500f);
        float wobble = (float) Math.sin(t * 2.1);
        float load = on ? clamp(18f + 45f * revs + 0.15f * speed + 30f * pull + 2f * wobble, 0, 100) : 0;
        float throttle = on ? clamp(12f + 35f * revs + 45f * pull + wobble, 0, 100) : 12;
        float maf = on ? clamp(rpm * (0.0012f + 0.00028f * load), 0, 200) : 0;
        Map<String, Float> m = new LinkedHashMap<>();
        m.put("load", load);
        m.put("throttle", throttle);
        m.put("oiltemp", clamp(coolant - 6f, -30, 150));
        m.put("intake", outside + (on ? 12f - Math.min(8f, speed / 12f) : 4f));
        m.put("fuelrate", on ? maf * 0.33f : 0);                       // petrol, stoichiometric
        m.put("voltage", on ? 14.1f + 0.1f * wobble : 12.4f);
        m.put("maf", maf);
        m.put("map", on ? clamp(28f + 0.72f * load, 0, 101) : 101);
        m.put("baro", 101f);
        m.put("timing", on ? clamp(8f + 28f * revs - 10f * pull + wobble, -20, 60) : 0);
        m.put("stft", on ? 2.3f * wobble : 0);
        m.put("ltft", -1.6f);
        m.put("absload", load * 0.8f);
        m.put("pedal", on ? clamp(throttle - 12f, 0, 100) : 0);
        m.put("fuelpressure", on ? 380f : 0);
        m.put("railpressure", on ? clamp(40f + 110f * load / 100f, 0, 250) : 0);
        m.put("cattemp", on ? clamp(300f + 4f * load + 0.8f * coolant, 0, 1000) : coolant);
        m.put("runtime", on ? 600f + t : 0);
        boolean mil = known(v.get("mil"), 0) == 1;
        m.put("mildist", mil ? 42f : 0f);
        m.put("cleardist", 1840f);
        m.put("miltime", mil ? 95f : 0f);
        m.put("cleartime", 2710f + t / 60f);
        // The slow tier. Exhaust and particulate filter temperatures are left out: a petrol car has none.
        m.put("relthrottle", on ? clamp(throttle - 12f, 0, 100) : 0);
        m.put("pedale", on ? clamp((throttle - 12f) / 2f + 8f, 0, 100) : 8);
        m.put("pedalf", on ? clamp((throttle - 12f) / 2f + 4f, 0, 100) : 4);
        m.put("railrel", on ? clamp(300f + 0.5f * load, 0, 5200) : 0);
        m.put("stft2", on ? 1.8f * wobble : 0);
        m.put("ltft2", -0.8f);
        m.put("egr", on && speed > 0 ? clamp(0.25f * load, 0, 100) : 0);
        m.put("egrerr", on && speed > 0 ? 1.6f * wobble : 0);
        m.put("evappurge", on && coolant > 70 ? clamp(8f + 0.3f * load, 0, 100) : 0);
        m.put("evappressure", on ? -120f + 40f * wobble : 0);
        for (int i = 1; i <= 8; i++) {
            // The first sensor of each bank switches rich and lean; those after the catalyst sit still.
            boolean upstream = i == 1 || i == 5;
            float phase = (float) Math.sin(t * 5.0 + i);
            float lambda = on ? 1f + (upstream ? 0.02f * phase : 0.004f * phase) : 2f;
            m.put("o2v" + i, !on ? 0.45f : upstream ? 0.45f + 0.38f * phase : 0.62f + 0.04f * phase);
            m.put("o2t" + i, on && upstream ? 2.3f * wobble : 0);
            m.put("wbr" + i, lambda);
            m.put("wbv" + i, on ? 2.2f + 0.1f * phase : 0);
            m.put("wcr" + i, lambda);
            m.put("wci" + i, on ? 0.3f * phase : 0);
        }
        return m;
    }

    private static int known(Integer v, int otherwise) {
        return v == null || v == VehicleState.UNKNOWN_INT ? otherwise : v;
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
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
    public synchronized VehicleState state(long now) {
        Map<String, Integer> v = values(now);
        VehicleState s = new VehicleState();
        s.rpm = v.get("rpm");
        s.speedKph = v.get("speed");
        s.coolantC = v.get("coolant");
        s.fuelPercent = v.get("fuel");
        s.fuelUnsupported = s.fuelPercent == VehicleState.UNKNOWN_INT;
        s.outsideC = v.get("outside");
        s.odometerKm = v.get("odometer");
        float t = cycleSince >= 0 ? (now - cycleSince) / 1000f : 0f;
        // How hard the drive cycle is accelerating, 0 to 1.
        float pull = 0f;
        if (cycleSince >= 0) {
            int ahead = cycleAt(now - cycleSince + 1000, values).get("speed");
            pull = clamp((ahead - v.get("speed")) / 8f, 0, 1);
        }
        Map<String, Float> eng = engineAt(v, t, pull);
        eng.putAll(engine);
        for (Map.Entry<String, Float> e : eng.entrySet()) {
            if (e.getValue().isNaN()) continue;
            if ("intake".equals(e.getKey())) s.intakeC = Math.round(e.getValue());
            else s.engine.put(VehicleField.of(e.getKey()).key(), e.getValue());
        }
        boolean mil = v.get("mil") == 1;
        if (v.get("mil") != VehicleState.UNKNOWN_INT) {
            s.readiness = Readiness.parse(new int[] { mil ? 0x80 | MIL_CODES.length : MONITORS[0],
                    MONITORS[1], MONITORS[2], MONITORS[3] });
        }
        s.o2Sensors2Banks = 0xFF;
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
