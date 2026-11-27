import ie.claudius.cardash.vehicle.VehicleField;
import ie.claudius.cardash.vehicle.VehicleMerge;
import ie.claudius.cardash.vehicle.VehicleState;

import java.util.HashSet;
import java.util.Set;

/** The vehicle value table: PID decoding (SAE J1979), reading a state, units and text. */
public class VehicleFieldTest {

    private static int failures = 0;

    private static void check(boolean ok, String what) {
        if (!ok) {
            failures++;
            System.out.println("FAIL: " + what);
        }
    }

    private static void decodes(int pid, float want, int... bytes) {
        float got = VehicleField.decode(pid, bytes);
        check(Math.abs(got - want) < 0.01f, String.format("PID %02X: want %s, got %s", pid, want, got));
        check(VehicleField.byPid(pid).bytes == bytes.length, String.format("PID %02X byte count", pid));
    }

    public static void main(String[] args) {
        decodes(0x04, 50.196f, 0x80);
        decodes(0x05, 83, 123);
        decodes(0x06, 0, 128);
        decodes(0x06, -100, 0);
        decodes(0x07, 99.219f, 255);
        decodes(0x0A, 300, 100);
        decodes(0x0B, 98, 98);
        decodes(0x0C, 1726, 0x1A, 0xF8);
        decodes(0x0D, 50, 50);
        decodes(0x0E, 10, 148);
        decodes(0x0E, -64, 0);
        decodes(0x0F, 25, 65);
        decodes(0x10, 3.0f, 0x01, 0x2C);
        decodes(0x11, 100, 255);
        decodes(0x1F, 600, 0x02, 0x58);
        decodes(0x21, 258, 1, 2);
        decodes(0x23, 50, 0x01, 0xF4);          // 500 * 10 kPa = 5000 kPa = 50 bar
        decodes(0x2F, 100, 255);
        decodes(0x31, 1000, 0x03, 0xE8);
        decodes(0x33, 101, 101);
        decodes(0x3C, 450, 0x13, 0x24);         // 4900 / 10 - 40
        decodes(0x42, 13.8f, 0x35, 0xE8);
        decodes(0x43, 100, 0, 255);
        decodes(0x46, -40, 0);
        decodes(0x49, 20, 51);
        decodes(0x5C, 90, 130);
        decodes(0x5E, 6.4f, 0, 128);

        Set<String> ids = new HashSet<>();
        Set<Integer> pids = new HashSet<>();
        for (VehicleField f : VehicleField.all()) {
            check(ids.add(f.id) && pids.add(f.pid), "unique " + f.id);
            check(VehicleField.of(f.id) == f && VehicleField.byPid(f.pid) == f, "lookup " + f.id);
            check(f.bytes >= 1 && f.bytes <= 2 && f.min < f.max && !f.label.isEmpty(), "sane " + f.id);
            check(f.interval > 0 || "rpm".equals(f.id) || "speed".equals(f.id), "interval " + f.id);
            check(f.value(new VehicleState()) == null && f.value(null) == null, "unknown stays unknown: " + f.id);
        }
        check(VehicleField.of("warp") == null && VehicleField.byPid(0x00) == null, "no such field");

        VehicleState s = new VehicleState();
        s.coolantC = 90;
        s.engine.put(0x42, 13.8f);
        check(VehicleField.of("coolant").value(s) == 90f && VehicleField.of("voltage").value(s) == 13.8f, "read");
        check(s.hasAnything() && s.copy().engine.get(0x42) == 13.8f, "copied");
        VehicleState copy = s.copy();
        s.engine.clear();
        check(copy.engine.size() == 1, "a copy is its own");
        VehicleState other = new VehicleState();
        other.engine.put(0x42, 12f);
        other.engine.put(0x04, 30f);
        VehicleState merged = VehicleMerge.merge(copy, null, other);
        check(merged.engine.get(0x42) == 13.8f && merged.engine.get(0x04) == 30f, "merged by priority");

        VehicleField temp = VehicleField.of("coolant"), speed = VehicleField.of("speed"),
                mil = VehicleField.of("mildist"), volts = VehicleField.of("voltage"),
                run = VehicleField.of("runtime"), trim = VehicleField.of("stft");
        check(temp.convert(100, true, false) == 212 && "°F".equals(temp.unit(true, false))
                && temp.convert(100, false, true) == 100 && "°C".equals(temp.unit(false, true)), "fahrenheit");
        check(Math.round(speed.convert(100, false, true)) == 62 && "mph".equals(speed.unit(false, true))
                && "km/h".equals(speed.unit(true, false)), "miles");
        check("mi".equals(mil.unit(false, true)) && mil.label(true).endsWith("mi"), "distance");
        check("13.8".equals(volts.format(13.84f)) && "0.0".equals(trim.format(-0.01f))
                && "-2.3".equals(trim.format(-2.34f)) && "91".equals(temp.format(90.6f)), "decimals");
        check("0:07".equals(run.format(7)) && "10:00".equals(run.format(600)) && "1 h 05".equals(run.format(3900)),
                "run time");

        if (failures > 0) {
            System.out.println(failures + " failures");
            System.exit(1);
        }
        System.out.println("VehicleFieldTest: all passed");
    }
}
