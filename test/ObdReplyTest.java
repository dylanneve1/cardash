import ie.claudius.cardash.vehicle.ObdReply;
import ie.claudius.cardash.vehicle.ObdSchedule;
import ie.claudius.cardash.vehicle.RateMeter;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Multi-PID replies, supported-PID bitmaps, the request schedule and the rate meter. */
public class ObdReplyTest {

    private static int failures = 0;

    private static void check(boolean ok, String what) {
        if (!ok) {
            failures++;
            System.out.println("FAIL: " + what);
        }
    }

    private static boolean eq(int[] a, int... b) {
        return Arrays.equals(a, b);
    }

    public static void main(String[] args) {
        check("010C0D05".equals(ObdReply.command(0x0C, 0x0D, 0x05)), "command");

        // Single frame, spaces off, two PIDs.
        Map<Integer, int[]> m = ObdReply.mode01("410C1AF80D32\r\r", 0x0C, 0x0D);
        check(m.size() == 2 && eq(m.get(0x0C), 0x1A, 0xF8) && eq(m.get(0x0D), 0x32), "single frame multi-PID");
        // Spaced, with SEARCHING chatter.
        m = ObdReply.mode01("SEARCHING...\r41 0C 0B B8 0D 00 \r\r", 0x0C, 0x0D);
        check(m.size() == 2 && eq(m.get(0x0C), 0x0B, 0xB8), "spaced reply after SEARCHING");

        // Multi-frame: 6 PIDs, declared length 0x0F, padded last frame.
        String multi = "00F\r0:410C1AF80D32\r1:057B2F8046370F\r2:41AAAAAAAAAAAA\r\r";
        m = ObdReply.mode01(multi, 0x0C, 0x0D, 0x05, 0x2F, 0x46, 0x0F);
        check(m.size() == 6, "multi-frame size " + m.keySet());
        check(eq(m.get(0x05), 0x7B) && eq(m.get(0x2F), 0x80) && eq(m.get(0x46), 0x37)
                && eq(m.get(0x0F), 0x41), "multi-frame values");
        // Another ECU's short answer between the frames doesn't cut the long one off.
        m = ObdReply.mode01("00F\r0:410C1AF80D32\r410D31\r1:057B2F8046370F\r2:41AAAAAAAAAAAA\r",
                0x0C, 0x0D, 0x05, 0x2F, 0x46, 0x0F);
        check(m.size() == 6 && eq(m.get(0x0F), 0x41), "interleaved ECUs " + m.keySet());
        // Padding past the declared length is never read.
        m = ObdReply.mode01("008\r0:410C1AF80D32\r1:057BAAAAAAAAAA\r", 0x0C, 0x0D, 0x05);
        check(m.size() == 3 && eq(m.get(0x05), 0x7B), "padding cut");
        // The car leaves out a PID it lacks.
        m = ObdReply.mode01("410C1AF8057B\r", 0x0C, 0x0D, 0x05);
        check(m.size() == 2 && !m.containsKey(0x0D) && m.containsKey(0x05), "omitted PID");
        // Two ECUs: first answer wins.
        m = ObdReply.mode01("410C1AF80D32\r410C00000D31\r", 0x0C, 0x0D);
        check(eq(m.get(0x0C), 0x1A, 0xF8) && eq(m.get(0x0D), 0x32), "two ECUs");
        // Garbage and truncation.
        for (String bad : new String[] { null, "", "NO DATA\r", "?\r", "STOPPED\r", "CAN ERROR\r",
                "UNABLE TO CONNECT\r", "410C1A\r", "7F0112\r", "BUS INIT: ...ERROR\r" }) {
            check(ObdReply.mode01(bad, 0x0C, 0x0D).isEmpty(), "rejects " + bad);
        }
        m = ObdReply.mode01("410D32990000\r", 0x0C, 0x0D);
        check(m.size() == 1, "unknown PID ends the message");
        check(ObdReply.mode01("410D32\r", 0x0C).isEmpty(), "an unasked PID is not taken");

        // Bitmaps: BE 3E B8 13 supports 01,03..07,0B..0F,11,13..15,1C,1F,20.
        Set<Integer> s = ObdReply.supported("4100BE3EB813\r", 0x00);
        check(s != null && s.contains(0x05) && s.contains(0x0C) && s.contains(0x0D) && s.contains(0x0F)
                && s.contains(0x20) && !s.contains(0x02) && !s.contains(0x08), "bitmap 00: " + s);
        s = ObdReply.supported("412080020000\r", 0x20);
        check(s != null && s.contains(0x21) && s.contains(0x2F) && s.size() == 2, "bitmap 20: " + s);
        s = ObdReply.supported("410080000000\r410000100000\r", 0x00);
        check(s != null && s.contains(0x01) && s.contains(0x0C), "bitmaps joined over ECUs: " + s);
        check(ObdReply.supported("NO DATA\r", 0x00) == null, "no bitmap");
        check(ObdReply.supported("4100BE3EB813\r", 0x20) == null, "wrong base");

        check(ObdReply.protocol("A6\r") == 6 && ObdReply.protocol("3\r") == 3 && ObdReply.protocol("?\r") == -1,
                "protocol number");
        check(ObdReply.protocol("AA\r") == 10, "A10 is not read as automatic");
        check(ObdReply.isCan(6) && ObdReply.isCan(9) && !ObdReply.isCan(5) && !ObdReply.isCan(10), "CAN range");

        // Schedule on CAN: one request, fast PIDs first, slow ones when due.
        ObdSchedule sch = new ObdSchedule(null, true);
        List<int[]> r = sch.next(1000);
        check(r.size() == 1 && r.get(0).length <= 6 && r.get(0)[0] == 0x0C && r.get(0)[1] == 0x0D, "CAN first");
        check(has(r, 0x05) && has(r, 0x2F), "slow ones due at the start");
        r = sch.next(1100);
        check(r.size() == 1 && eq(r.get(0), 0x0C, 0x0D), "fast only");
        r = sch.next(3100);
        check(has(r, 0x05) && !has(r, 0x2F), "coolant every 2 s");
        check(has(sch.next(11000), 0x2F), "fuel every 10 s");
        // Unsupported PIDs are never asked.
        Set<Integer> only = new TreeSet<>(Arrays.asList(0x0C, 0x05));
        sch = new ObdSchedule(only, true);
        for (long t = 0; t < 60000; t += 500) {
            for (int[] q : sch.next(t)) for (int p : q) check(only.contains(p), "unsupported asked " + p);
        }
        // Non-CAN: one PID per request, one slow per cycle.
        sch = new ObdSchedule(null, false);
        r = sch.next(0);
        check(r.size() == 3, "fast two plus one slow, got " + r.size());
        for (int[] q : r) check(q.length == 1, "single PID");
        check(has(sch.next(10), 0x2F) || has(r, 0x2F), "the other slow one follows");
        check(new ObdSchedule(new TreeSet<Integer>(), true).next(0).isEmpty(), "nothing supported");

        // Intake air is only asked when there is no ambient PID.
        sch = new ObdSchedule(new TreeSet<>(Arrays.asList(0x46, 0x0F)), true);
        check(has(sch.next(0), 0x46) && !sch.wants(0x0F), "ambient replaces intake");
        sch = new ObdSchedule(new TreeSet<>(Arrays.asList(0x0F)), true);
        check(has(sch.next(0), 0x0F), "intake as the fallback");

        RateMeter rm = new RateMeter();
        for (int i = 0; i <= 50; i++) rm.record(i * 100);
        check(Math.abs(rm.perSecond(5000) - 10f) < 0.5f, "10/s, got " + rm.perSecond(5000));
        check(rm.perSecond(60000) == 0f, "old events fall out");

        if (failures > 0) {
            System.out.println(failures + " failures");
            System.exit(1);
        }
        System.out.println("ObdReplyTest: all passed");
    }

    private static boolean has(List<int[]> reqs, int pid) {
        for (int[] q : reqs) for (int p : q) if (p == pid) return true;
        return false;
    }
}
