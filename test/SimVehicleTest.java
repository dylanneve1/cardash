import ie.claudius.cardash.vehicle.SimVehicle;
import ie.claudius.cardash.vehicle.VehicleState;

/** The simulated vehicle: settings by name, bad input, and the drive cycle. */
public class SimVehicleTest {

    private static int failures = 0;

    private static void check(boolean ok, String what) {
        if (!ok) {
            failures++;
            System.out.println("FAIL: " + what);
        }
    }

    public static void main(String[] args) {
        SimVehicle s = new SimVehicle();
        VehicleState v = s.state(1000);
        check(v.rpm == 850 && v.speedKph == 0 && v.doorFrontLeft == VehicleState.Tri.NO && v.obdReplyMillis == 1000,
                "defaults");
        check(s.apply(SimVehicle.pairs("rpm=3000,door_fl=1"), 0).isEmpty(), "adb spec accepted");
        v = s.state(0);
        check(v.rpm == 3000 && v.doorFrontLeft == VehicleState.Tri.YES, "applied");
        s.apply(SimVehicle.pairs("rpm=99999;coolant=-99, fuel=na ,boot=on"), 0);
        v = s.state(0);
        check(v.rpm == 8000 && v.coolantC == -30 && v.boot == VehicleState.Tri.YES, "clamped");
        check(v.fuelPercent == VehicleState.UNKNOWN_INT && v.fuelUnsupported, "na means unavailable");
        String bad = s.apply(SimVehicle.pairs("warp=9,rpm=fast,door_fr=2,cycle=x"), 0);
        check(bad.contains("warp") && bad.contains("rpm=fast") && bad.contains("door_fr=2")
                && bad.contains("cycle=x"), "rejects: " + bad);
        check(s.state(0).rpm == 8000, "bad values change nothing");
        check(SimVehicle.pairs(null).isEmpty() && SimVehicle.pairs("").isEmpty(), "empty spec");

        s.apply(SimVehicle.pairs("reset=1"), 0);
        s.apply(SimVehicle.pairs("cycle=1"), 10_000);
        check(s.cycling(), "cycle on");
        boolean redRpm = false, hot = false, off = false, reverse = false, door = false;
        int top = 0;
        for (long t = 10_000; t < 10_000 + SimVehicle.CYCLE_MS; t += 500) {
            v = s.state(t);
            redRpm |= v.rpm >= 5500;
            hot |= v.coolantC >= 110;
            off |= v.rpm == 0;
            reverse |= v.reverse == VehicleState.Tri.YES;
            door |= v.doorFrontLeft == VehicleState.Tri.YES;
            top = Math.max(top, v.speedKph);
        }
        check(redRpm && hot && off && reverse && door && top >= 100,
                "the cycle shows the warnings, reverse, a stop and a door");
        check(s.state(10_000 + SimVehicle.CYCLE_MS).rpm == s.state(10_000).rpm, "repeats");
        s.apply(SimVehicle.pairs("cycle=0"), 34_000);   // 24 s into the cycle
        check(!s.cycling() && s.state(99_000).rpm == 6000, "stops where it was");
        s.apply(SimVehicle.pairs("cycle=1"), 0);
        s.apply(SimVehicle.pairs("speed=30"), 1000);
        check(!s.cycling() && s.state(1000).speedKph == 30, "a manual value stops the cycle");

        if (failures > 0) {
            System.out.println(failures + " failures");
            System.exit(1);
        }
        System.out.println("SimVehicleTest: all passed");
    }
}
