package ie.claudius.cardash.vehicle;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What to ask the car next. RPM and speed go in every cycle; the slow
 * values join when they are due. On CAN they share one request (up to
 * six PIDs); on older protocols each PID is its own request and at most
 * one slow one is added per cycle. PIDs the car says it lacks are never
 * asked. Android-free; see ObdScheduleTest.
 */
public final class ObdSchedule {

    public static final int RPM = 0x0C, SPEED = 0x0D, COOLANT = 0x05, FUEL = 0x2F,
            AMBIENT = 0x46, INTAKE = 0x0F;
    public static final int MAX_PER_REQUEST = 6;

    private static final int[] FAST = { RPM, SPEED };
    /** {pid, interval ms} */
    private static final int[][] SLOW = {
            { COOLANT, 2000 }, { FUEL, 10000 }, { AMBIENT, 15000 }, { INTAKE, 15000 },
    };

    private final Set<Integer> supported;   // null: not known, ask and see
    private boolean multi;
    private final Map<Integer, Long> asked = new HashMap<>();

    public ObdSchedule(Set<Integer> supported, boolean can) {
        this.supported = supported;
        this.multi = can;
    }

    public boolean multi() { return multi; }

    /** A clone that can't answer multi-PID requests drops back to one at a time. */
    public void singleOnly() { multi = false; }

    public boolean wants(int pid) {
        // Intake air only stands in for outside air: not asked when the car has the real thing.
        if (pid == INTAKE && supported != null && supported.contains(AMBIENT)) return false;
        return supported == null || supported.contains(pid);
    }

    /** How long a value stays believable without a fresh reading. */
    public static long staleAfter(int pid) {
        for (int[] s : SLOW) if (s[0] == pid) return s[1] * 3L;
        return 3000L;
    }

    /** The requests for one cycle, each an array of PIDs. May be empty. */
    public List<int[]> next(long now) {
        List<Integer> pids = new ArrayList<>();
        for (int p : FAST) if (wants(p)) pids.add(p);
        List<int[]> due = new ArrayList<>();   // {pid, overdue ms}
        for (int[] s : SLOW) {
            if (!wants(s[0])) continue;
            Long last = asked.get(s[0]);
            long over = last == null ? Integer.MAX_VALUE : now - last - s[1];
            if (over >= 0) due.add(new int[] { s[0], (int) Math.min(over, Integer.MAX_VALUE) });
        }
        // Most overdue first, so nothing starves when there isn't room for all.
        for (int i = 1; i < due.size(); i++) {
            for (int j = i; j > 0 && due.get(j)[1] > due.get(j - 1)[1]; j--) {
                int[] t = due.get(j);
                due.set(j, due.get(j - 1));
                due.set(j - 1, t);
            }
        }
        int room = multi ? MAX_PER_REQUEST - pids.size() : 1;
        for (int i = 0; i < due.size() && i < room; i++) {
            pids.add(due.get(i)[0]);
            asked.put(due.get(i)[0], now);
        }
        List<int[]> out = new ArrayList<>();
        if (pids.isEmpty()) return out;
        if (multi) {
            int[] one = new int[pids.size()];
            for (int i = 0; i < one.length; i++) one[i] = pids.get(i);
            out.add(one);
        } else {
            for (int p : pids) out.add(new int[] { p });
        }
        return out;
    }
}
