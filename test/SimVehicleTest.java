import ie.claudius.cardash.vehicle.SimVehicle;
import ie.claudius.cardash.vehicle.VehicleField;
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

        // Engine readings: plausible, following the engine, settable and removable.
        s.apply(SimVehicle.pairs("reset=1"), 0);
        v = s.state(0);
        for (VehicleField f : VehicleField.all()) {
            Float x = f.value(v);
            // A petrol car: nothing from an exhaust temperature or particulate filter sensor.
            if (f.id.startsWith("egt") || f.id.startsWith("dpf")) check(x == null, "diesel only: " + f.id);
            else check(x != null && x >= f.min && x <= f.max, "idle " + f.id + " = " + x);
        }
        float idleMaf = v.engine.get(0x10);
        check(v.engine.get(0x42) > 13.5f && idleMaf > 1 && idleMaf < 10, "idle volts and air " + idleMaf);
        s.apply(SimVehicle.pairs("rpm=0"), 0);
        v = s.state(0);
        check(v.engine.get(0x10) == 0 && v.engine.get(0x04) == 0 && v.engine.get(0x42) < 13f, "engine off");
        check(s.apply(SimVehicle.pairs("voltage=11.2,oiltemp=na,intake=33,mildist=12"), 0).isEmpty(), "engine settable");
        v = s.state(0);
        check(Math.abs(v.engine.get(0x42) - 11.2f) < 0.001f && !v.engine.containsKey(0x5C) && v.intakeC == 33
                && v.engine.get(0x21) == 12, "engine overrides");
        check(s.apply(SimVehicle.pairs("voltage=lots,load=NaN"), 0).contains("voltage=lots"), "bad engine value");
        s.apply(SimVehicle.pairs("cycle=1"), 0);
        float low = 100, high = 0;
        for (long t = 0; t < SimVehicle.CYCLE_MS; t += 500) {
            v = s.state(t);
            check(!v.engine.containsKey(0x5C), "na stays na in the cycle");
            for (VehicleField f : VehicleField.all()) {
                Float x = f.value(v);
                if (x != null) check(x >= f.min && x <= Math.max(f.max, 65535), "cycle " + f.id + " = " + x);
            }
            low = Math.min(low, v.engine.get(0x04));
            high = Math.max(high, v.engine.get(0x04));
        }
        check(low == 0 && high > 60 && s.state(20_000).engine.get(0x42) > 13, "the drive moves the engine data " + high);
        s.apply(SimVehicle.pairs("reset=1"), 0);
        check(s.state(0).engine.containsKey(0x5C), "reset brings them back");

        // Monitor status and fault codes follow the check-engine switch.
        v = s.state(0);
        check(!v.readiness.mil && v.readiness.codes == 0 && v.readiness.incomplete() == 1 && !v.readiness.diesel
                && s.codes().isEmpty(), "no light by default");
        check(s.apply(SimVehicle.pairs("mil=1"), 0).isEmpty(), "light switched on");
        v = s.state(0);
        check(v.readiness.mil && v.readiness.codes == s.codes().size() && s.codes().size() == 2
                && v.engine.get(0x21) > 0, "light, codes and distance");
        check(!s.apply(SimVehicle.pairs("readiness=1"), 0).isEmpty(), "readiness is not set by hand");
        check(s.apply(SimVehicle.pairs("egt11=640,dpfin1=300,o2v1=0.2,mil=na"), 0).isEmpty(), "diesel values settable");
        v = s.state(0);
        check(VehicleField.of("egt11").value(v) == 640f && VehicleField.of("dpfin1").value(v) == 300f
                && VehicleField.of("egt12").value(v) == null && VehicleField.of("o2v1").value(v) == 0.2f
                && v.readiness == null, "set values, and no monitor status");

        if (failures > 0) {
            System.out.println(failures + " failures");
            System.exit(1);
        }
        System.out.println("SimVehicleTest: all passed");
    }
}
