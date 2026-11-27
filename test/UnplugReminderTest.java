import ie.claudius.cardash.vehicle.UnplugReminder;
import ie.claudius.cardash.vehicle.VehicleState;

/** The unplug-the-dongle reminder: when it fires, and that it fires once per stop. */
public class UnplugReminderTest {

    private static int failures = 0;
    private static final int U = VehicleState.UNKNOWN_INT;
    private static long now = 1_000_000L;

    private static void check(boolean ok, String what) {
        if (!ok) {
            failures++;
            System.out.println("FAIL: " + what);
        }
    }

    /** Ticks every 10 s for {@code seconds}; returns how often it fired. */
    private static int run(UnplugReminder r, int seconds, boolean linked, boolean replying, int rpm) {
        int fired = 0;
        for (int s = 0; s < seconds; s += 10) {
            now += 10_000;
            if (r.update(now, linked, replying ? now : 0, rpm)) fired++;
        }
        return fired;
    }

    public static void main(String[] args) {
        UnplugReminder r = new UnplugReminder();
        check(run(r, 600, false, false, U) == 0, "no dongle reached: never");
        check(run(r, 600, true, true, 800) == 0, "engine running: never");
        check(run(r, 170, true, true, 0) == 0, "not before three minutes");
        check(run(r, 20, true, true, 0) == 1 && r.showing(), "fires at three minutes of RPM 0");
        check(run(r, 900, true, true, 0) == 0, "once per stop");
        r.dismiss();
        check(!r.showing() && run(r, 900, false, false, U) == 0, "dismissed stays dismissed");
        run(r, 60, true, true, 900);
        check(run(r, 200, false, false, U) == 1, "silence after a drive also counts");
        run(r, 10, true, true, 900);
        check(!r.showing(), "starting the engine clears it");

        r = new UnplugReminder();
        check(run(r, 200, true, false, U) == 1, "connected but the car never answers");

        r = new UnplugReminder();
        run(r, 60, true, true, 900);
        run(r, 60, true, true, 0);
        now += 3_600_000;   // the unit slept for an hour
        check(!r.update(now, false, 0, U), "no reminder straight after waking");
        check(run(r, 200, false, false, U) == 1, "but still after three quiet minutes");

        if (failures > 0) {
            System.out.println(failures + " failures");
            System.exit(1);
        }
        System.out.println("UnplugReminderTest: all passed");
    }
}
