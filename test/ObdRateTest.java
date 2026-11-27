import ie.claudius.cardash.vehicle.ObdSchedule;
import ie.claudius.cardash.vehicle.VehicleField;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * How often RPM and speed come round, on a model of an ELM327 over
 * Bluetooth, and that the slow tier takes little from it. The model is
 * a model: a fixed turnaround per request, a cost for each CAN frame of
 * the answer and one for each byte sent back as text.
 */
public class ObdRateTest {

    /** The values CarDash asked before the slow tier existed. */
    static final int[] BEFORE = { 0x04, 0x05, 0x06, 0x07, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x10, 0x11, 0x1F,
            0x21, 0x23, 0x2F, 0x31, 0x33, 0x3C, 0x42, 0x43, 0x46, 0x49, 0x5C, 0x5E };

    private static final double TURNAROUND_MS = 50, FRAME_MS = 5, BYTE_MS = 0.6;

    private static int failures = 0;

    private static void check(boolean ok, String what) {
        if (!ok) {
            failures++;
            System.out.println("FAIL: " + what);
        }
    }

    private static double cost(int[] request) {
        int bytes = 1;
        for (int pid : request) bytes += 1 + VehicleField.request(pid).bytes;
        int frames = bytes <= 7 ? 1 : 1 + (bytes - 6 + 6) / 7;
        return TURNAROUND_MS + FRAME_MS * frames + BYTE_MS * bytes;
    }

    /** RPM readings per second over ten minutes; also checks nothing goes stale between its turns. */
    private static double rate(Set<Integer> pids, boolean can, String what) {
        ObdSchedule s = new ObdSchedule(pids, can);
        Map<Integer, Long> last = new HashMap<>();
        double t = 0;
        int rpm = 0, stale = 0;
        while (t < 600_000) {
            long now = (long) t;
            for (int[] q : s.next(now)) {
                t += cost(q);
                for (int p : q) {
                    if (p == ObdSchedule.RPM) rpm++;
                    long keep = s.staleFor(p);
                    Long was = last.put(p, (long) t);
                    if (was != null && (long) t - was > keep) stale++;
                }
            }
        }
        check(stale == 0, what + ": " + stale + " readings went stale between turns");
        check(last.keySet().containsAll(pids), what + ": every PID asked");
        return rpm / (t / 1000);
    }

    private static Set<Integer> set(int... pids) {
        Set<Integer> s = new TreeSet<>();
        for (int p : pids) s.add(p);
        return s;
    }

    private static void compare(String name, Set<Integer> old, Set<Integer> now, boolean can) {
        double a = rate(old, can, name + " old set"), b = rate(now, can, name + " new set");
        System.out.printf("  %s: RPM %.2f/s -> %.2f/s (%.1f%%), %d -> %d PIDs%n",
                name, a, b, (b / a - 1) * 100, old.size(), now.size());
        check(b >= a * 0.9, name + ": the core rate dropped more than 10%");
    }

    public static void main(String[] args) {
        Set<Integer> old = set(BEFORE), all = new TreeSet<>();
        for (VehicleField.Request r : VehicleField.requests()) all.add(r.pid);
        check(all.containsAll(old), "the old set is still there");
        compare("CAN, a car with everything", old, all, true);
        compare("one PID per request, a car with everything", old, all, false);
        // A guess at what a small petrol engine of about 2010 lists.
        Set<Integer> small = set(0x04, 0x05, 0x06, 0x07, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x11, 0x1F, 0x21, 0x2F,
                0x31, 0x33, 0x3C, 0x42, 0x43, 0x46, 0x49);
        Set<Integer> more = new TreeSet<>(small);
        more.addAll(set(0x01, 0x13, 0x15, 0x2C, 0x2D, 0x2E, 0x34, 0x45, 0x4A, 0x4D, 0x4E));
        compare("CAN, small petrol engine", small, more, true);
        compare("one PID per request, small petrol engine", small, more, false);

        if (failures > 0) {
            System.out.println(failures + " failures");
            System.exit(1);
        }
        System.out.println("ObdRateTest: all passed");
    }
}
