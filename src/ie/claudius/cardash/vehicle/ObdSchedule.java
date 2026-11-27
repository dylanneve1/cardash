package ie.claudius.cardash.vehicle;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What to ask the car next. RPM and speed go in every cycle; the other
 * values join when they are due: first the core ones (coolant, fuel,
 * outside air), then, in the room left, the main engine data. The long
 * tail ({@link VehicleField#slow}: monitor status, O2 sensors, EGR,
 * exhaust temperatures and so on) rotates through a place of its own
 * that is small enough not to slow RPM and speed much.
 *
 * On CAN they share one request (up to six PIDs), with at most one of
 * the slow tier in every other cycle. On older protocols each PID is its
 * own request, at most one besides RPM and speed per cycle, the engine
 * data only every other cycle, and every third of those turns goes to
 * the slow tier. PIDs the car says it lacks are never asked, and the
 * engine data only when its bitmaps were read. Android-free; see
 * ObdReplyTest and ObdRateTest.
 */
public final class ObdSchedule {

    public static final int RPM = 0x0C, SPEED = 0x0D, COOLANT = 0x05, FUEL = 0x2F,
            AMBIENT = 0x46, INTAKE = 0x0F;
    public static final int MAX_PER_REQUEST = 6;

    private static final int[] FAST = { RPM, SPEED };
    /** {pid, interval ms} */
    private static final int[][] CORE = {
            { COOLANT, 2000 }, { FUEL, 10000 }, { AMBIENT, 15000 }, { INTAKE, 15000 },
    };
    /** The shortest an engine value is believed without a fresh reading: CAN, and one PID per request. */
    private static final long EXTRA_KEEP_MULTI_MS = 10_000L, EXTRA_KEEP_SINGLE_MS = 90_000L;
    private static final long SLOW_KEEP_MULTI_MS = 30_000L;
    /** CAN: one request in this many may carry a slow-tier PID. */
    private static final int SLOW_EVERY = 2;
    /** One PID per request: one engine-data turn in this many goes to the slow tier. */
    private static final int SLOW_TURN = 3;
    /** One PID per request: a generous guess at a cycle, for how long a round of the slow tier takes. */
    private static final long SINGLE_CYCLE_MS = 1500L;

    private final Set<Integer> supported;   // null: not known, ask and see
    private boolean multi;
    private final Map<Integer, Long> asked = new HashMap<>();
    /** The time between the last two requests for a PID. */
    private final Map<Integer, Long> gap = new HashMap<>();
    /** One PID per request: whether the last cycle's slow slot went to a core / an engine value. */
    private boolean lastWasCore, lastWasExtra;
    private int cycle, extraTurns;

    public ObdSchedule(Set<Integer> supported, boolean can) {
        this.supported = supported;
        this.multi = can;
    }

    public boolean multi() { return multi; }

    /** A clone that can't answer multi-PID requests drops back to one at a time. */
    public void singleOnly() { multi = false; }

    public boolean wants(int pid) {
        return supported == null ? isCore(pid) : supported.contains(pid);
    }

    /** Fast and core values. Intake air is one only while it stands in for outside air. */
    private boolean isCore(int pid) {
        if (pid == RPM || pid == SPEED) return true;
        if (pid == INTAKE && supported != null && supported.contains(AMBIENT)) return false;
        for (int[] s : CORE) if (s[0] == pid) return true;
        return false;
    }

    /** How long a fast or core value stays believable without a fresh reading. */
    public static long staleAfter(int pid) {
        for (int[] s : CORE) if (s[0] == pid) return s[1] * 3L;
        return 3000L;
    }

    /**
     * {@link #staleAfter} for any PID: an engine value waits for its turn,
     * which on a slow link can be long, so it is kept for as long as the
     * link is actually taking to come round to it.
     */
    public long staleFor(int pid) {
        long keep;
        VehicleField.Request r = VehicleField.request(pid);
        if (isCore(pid) || r == null) keep = staleAfter(pid);
        else if (!r.slow) keep = Math.max(r.interval * 3L, multi ? EXTRA_KEEP_MULTI_MS : EXTRA_KEEP_SINGLE_MS);
        else if (multi) keep = Math.max(r.interval * 3L, SLOW_KEEP_MULTI_MS);
        else {
            // Before a second reading shows the real gap, allow for two whole rounds of the tier.
            long round = slowCount() * 2L * SLOW_TURN * SINGLE_CYCLE_MS;
            keep = Math.max(Math.max(r.interval * 3L, EXTRA_KEEP_SINGLE_MS), round * 2);
        }
        Long g = gap.get(pid);
        return g == null ? keep : Math.max(keep, g * 5 / 2);
    }

    /** The requests for one cycle, each an array of PIDs. May be empty. */
    public List<int[]> next(long now) {
        List<Integer> pids = new ArrayList<>();
        for (int p : FAST) if (wants(p)) pids.add(p);
        List<long[]> core = new ArrayList<>();   // {pid, overdue ms}
        for (int[] s : CORE) if (isCore(s[0]) && wants(s[0])) due(core, s[0], s[1], now);
        List<long[]> extra = new ArrayList<>();
        List<long[]> slow = new ArrayList<>();
        for (VehicleField.Request r : VehicleField.requests()) {
            if (!isCore(r.pid) && wants(r.pid)) due(r.slow ? slow : extra, r.pid, r.interval, now);
        }
        mostOverdueFirst(core);
        mostOverdueFirst(extra);
        mostOverdueFirst(slow);
        if (multi) {
            boolean slowTurn = !slow.isEmpty() && cycle++ % SLOW_EVERY == 0;
            core.addAll(extra);
            // The slow one takes the last place: if a car answers it oddly, nothing after it is lost.
            int room = slowTurn ? MAX_PER_REQUEST - 1 : MAX_PER_REQUEST;
            for (int i = 0; i < core.size() && pids.size() < room; i++) take(pids, core.get(i)[0], now);
            if (slowTurn) take(pids, slow.get(0)[0], now);
        } else {
            // A value never read goes first; after that a core one doesn't get two cycles running
            // while engine data waits, and engine data never gets two running at all.
            boolean first = !core.isEmpty() && !asked.containsKey((int) core.get(0)[0]);
            boolean waiting = !extra.isEmpty() || !slow.isEmpty();
            boolean extraTurn = waiting && !lastWasExtra;
            boolean useCore = !core.isEmpty() && (first || !lastWasCore || !extraTurn);
            boolean useExtra = !useCore && extraTurn;
            if (useCore) take(pids, core.get(0)[0], now);
            if (useExtra) {
                boolean useSlow = !slow.isEmpty() && (extra.isEmpty() || extraTurns % SLOW_TURN == SLOW_TURN - 1);
                extraTurns++;
                take(pids, (useSlow ? slow : extra).get(0)[0], now);
            }
            lastWasCore = useCore;
            lastWasExtra = useExtra;
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

    /** How many slow-tier PIDs this car is asked for. */
    private int slowCount() {
        int n = 0;
        for (VehicleField.Request r : VehicleField.requests()) if (r.slow && wants(r.pid)) n++;
        return n;
    }

    private void due(List<long[]> out, int pid, long interval, long now) {
        Long last = asked.get(pid);
        long over = last == null ? Long.MAX_VALUE : now - last - interval;
        if (over >= 0) out.add(new long[] { pid, over });
    }

    private void take(List<Integer> pids, long pid, long now) {
        int p = (int) pid;
        pids.add(p);
        Long last = asked.put(p, now);
        if (last != null) gap.put(p, now - last);
    }

    /** Most overdue first, so nothing starves when there isn't room for all; ties keep their order. */
    private static void mostOverdueFirst(List<long[]> due) {
        for (int i = 1; i < due.size(); i++) {
            for (int j = i; j > 0 && due.get(j)[1] > due.get(j - 1)[1]; j--) {
                long[] t = due.get(j);
                due.set(j, due.get(j - 1));
                due.set(j - 1, t);
            }
        }
    }
}
