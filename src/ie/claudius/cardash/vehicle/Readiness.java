package ie.claudius.cardash.vehicle;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Mode-01 PID 01, "monitor status since codes cleared" (SAE J1979): the
 * check-engine light (MIL), how many codes are stored, and which of the
 * car's self-tests have run. An emissions inspection wants the light off
 * and the tests complete. Android-free; see ReadinessTest.
 */
public final class Readiness {

    /** One self-test the car has. */
    public static final class Monitor {
        public final String name;
        public final boolean complete;

        Monitor(String name, boolean complete) {
            this.name = name;
            this.complete = complete;
        }
    }

    private static final String[] CONTINUOUS = { "Misfire", "Fuel system", "Components" };
    /** Bits 0 to 7 of bytes C and D, per ignition type; null for a reserved bit. */
    private static final String[] SPARK = { "Catalyst", "Heated catalyst", "Evaporative system",
            "Secondary air", "A/C refrigerant", "Oxygen sensor", "Oxygen sensor heater", "EGR / VVT" };
    private static final String[] COMPRESSION = { "NMHC catalyst", "NOx / SCR aftertreatment", null,
            "Boost pressure", null, "Exhaust gas sensor", "Particulate filter", "EGR / VVT" };

    /** The check-engine light is commanded on. */
    public final boolean mil;
    /** Confirmed codes stored. */
    public final int codes;
    /** Compression ignition (diesel), which has its own set of tests. */
    public final boolean diesel;
    /** The tests this car supports. */
    public final List<Monitor> monitors;

    private Readiness(boolean mil, int codes, boolean diesel, List<Monitor> monitors) {
        this.mil = mil;
        this.codes = codes;
        this.diesel = diesel;
        this.monitors = Collections.unmodifiableList(monitors);
    }

    /** From the four data bytes A to D; null for fewer. */
    public static Readiness parse(int[] b) {
        if (b == null || b.length < 4) return null;
        boolean diesel = (b[1] & 0x08) != 0;
        List<Monitor> m = new ArrayList<>();
        for (int i = 0; i < CONTINUOUS.length; i++) {
            // Byte B: bits 0-2 supported, bits 4-6 incomplete.
            if ((b[1] & (1 << i)) != 0) m.add(new Monitor(CONTINUOUS[i], (b[1] & (0x10 << i)) == 0));
        }
        String[] names = diesel ? COMPRESSION : SPARK;
        for (int i = 0; i < names.length; i++) {
            // Byte C supported, byte D incomplete.
            if (names[i] != null && (b[2] & (1 << i)) != 0) m.add(new Monitor(names[i], (b[3] & (1 << i)) == 0));
        }
        return new Readiness((b[0] & 0x80) != 0, b[0] & 0x7F, diesel, m);
    }

    public int incomplete() {
        int n = 0;
        for (Monitor m : monitors) if (!m.complete) n++;
        return n;
    }

    /** The short answer: whether the tests have all run. */
    public String headline() {
        if (monitors.isEmpty()) return "No monitors";
        int n = incomplete();
        return n == 0 ? "Ready" : n + " incomplete";
    }

    /** The light and the code count. */
    public String light() {
        String s = mil ? "MIL on" : "MIL off";
        if (codes == 0) return s;
        return s + " · " + codes + (codes == 1 ? " code" : " codes");
    }
}
