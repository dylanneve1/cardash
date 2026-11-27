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

    /** A field's reading of its PID's bytes; null for none. */
    private static void reads(String id, Float want, int... bytes) {
        VehicleField f = VehicleField.of(id);
        Float got = f.read(bytes);
        check(bytes.length == f.bytes, id + " byte count");
        check(want == null ? got == null : got != null && Math.abs(got - want) < 0.01f,
                id + ": want " + want + ", got " + got);
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

        decodes(0x08, 50, 192);
        decodes(0x09, -50, 64);
        decodes(0x22, 79, 0x03, 0xE8);          // 1000 * 0.079
        decodes(0x2C, 100, 255);
        decodes(0x2D, -100, 0);
        decodes(0x2D, 0, 128);
        decodes(0x2E, 20, 51);
        decodes(0x32, -1, 0xFF, 0xFC);          // two's complement, quarter pascals
        decodes(0x32, 1000, 0x0F, 0xA0);
        decodes(0x45, 20, 51);
        decodes(0x4A, 100, 255);
        decodes(0x4B, 0, 0);
        decodes(0x4D, 258, 1, 2);
        decodes(0x4E, 1000, 0x03, 0xE8);
        // Narrowband O2: volts and trim; FF means the sensor takes no part in the trim.
        reads("o2v1", 0.45f, 90, 128);
        reads("o2t1", 0f, 90, 128);
        reads("o2t2", -50f, 90, 64);
        reads("o2t2", null, 90, 0xFF);
        reads("o2v8", 1.275f, 255, 0xFF);
        // Wideband: lambda and volts, or lambda and current.
        reads("wbr1", 1.0f, 0x80, 0x00, 0x20, 0x00);
        reads("wbv1", 1.0f, 0x80, 0x00, 0x20, 0x00);
        reads("wcr3", 1.0f, 0x80, 0x00, 0x80, 0x00);
        reads("wci3", 0f, 0x80, 0x00, 0x80, 0x00);
        reads("wci3", -128f, 0x80, 0x00, 0x00, 0x00);
        reads("wci3", 1.5f, 0x80, 0x00, 0x81, 0x80);
        // Exhaust temperatures: byte A says which sensors are there.
        int[] egt = { 0x05, 0x10, 0x00, 0, 0, 0x17, 0x70, 0, 0 };
        reads("egt11", 369.6f, egt);
        reads("egt12", null, egt);
        reads("egt13", 560f, egt);
        reads("egt14", null, egt);
        reads("egt21", 369.6f, egt);
        int[] dpf = { 0x02, 0, 0, 0x0F, 0xA0, 0, 0, 0, 0 };
        reads("dpfin1", null, dpf);
        reads("dpfout1", 360f, dpf);
        reads("readiness", 1f, 0x81, 0x07, 0, 0);
        reads("readiness", 0f, 0, 0x07, 0, 0);
        check(VehicleField.of("egt11").pid == 0x78 && VehicleField.of("egt24").pid == 0x79
                && VehicleField.of("wbr1").pid == 0x24 && VehicleField.of("wcr8").pid == 0x3B
                && VehicleField.of("o2v5").pid == 0x18, "PIDs of the many");
        check(VehicleField.of("egt11").read(new int[] { 1, 2 }) == null, "a short reply is nothing");
        for (int pid : new int[] { 1, 8, 9, 0x13, 0x1D, 0x14, 0x1B, 0x22, 0x24, 0x2B, 0x2C, 0x2D, 0x2E, 0x32, 0x34,
                0x3B, 0x45, 0x4A, 0x4B, 0x4D, 0x4E, 0x78, 0x79, 0x7C }) {
            check(VehicleField.request(pid) != null, String.format("PID %02X is asked", pid));
        }

        Set<String> ids = new HashSet<>();
        Set<Integer> pids = new HashSet<>();
        for (VehicleField f : VehicleField.all()) {
            check(ids.add(f.id) && pids.add(f.key()), "unique " + f.id);
            check(VehicleField.of(f.id) == f && VehicleField.byPid(f.pid).pid == f.pid
                    && VehicleField.request(f.pid).bytes == f.bytes, "lookup " + f.id);
            check(f.bytes >= 1 && f.bytes <= 9 && f.min < f.max && !f.label.isEmpty(), "sane " + f.id);
            check(f.interval > 0 || "rpm".equals(f.id) || "speed".equals(f.id), "interval " + f.id);
            check(f.value(new VehicleState()) == null && f.value(null) == null, "unknown stays unknown: " + f.id);
        }
        check(VehicleField.of("warp") == null && VehicleField.byPid(0x00) == null, "no such field");
        // The list runs section by section, as the engine data page shows it.
        int section = 0;
        for (VehicleField f : VehicleField.all()) {
            check(f.section.ordinal() >= section, "in section order: " + f.id);
            section = f.section.ordinal();
        }
        // Sensor names follow the bitmap the car answers.
        VehicleState banks = new VehicleState();
        VehicleField third = VehicleField.of("o2v3"), fifth = VehicleField.of("wbr5");
        check("O2 B1S3".equals(third.label(banks, false)) && "Lambda B2S1".equals(fifth.label(banks, false)),
                "two banks unless told otherwise");
        banks.o2Sensors4Banks = 0x0F;
        check("O2 B2S1".equals(third.label(banks, false)) && "Lambda B3S1".equals(fifth.label(banks, false))
                && "O2 B2S1 trim".equals(VehicleField.of("o2t3").label(banks, false))
                && "Lambda B4S2 current".equals(VehicleField.of("wci8").label(banks, false))
                && "Battery".equals(VehicleField.of("voltage").label(banks, false)), "four banks");
        banks.o2Sensors2Banks = 0x03;
        check("O2 B1S3".equals(third.label(banks, false)), "PID 13 wins");
        Set<String> picks = new HashSet<>();
        for (VehicleField f : VehicleField.all()) check(picks.add(f.pickName()), "pick names differ: " + f.pickName());
        VehicleField mins = VehicleField.of("miltime");
        check("45 min".equals(mins.format(45)) && "2 h 05".equals(mins.format(125)), "minutes");

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
        other.readiness = ie.claudius.cardash.vehicle.Readiness.parse(new int[] { 0x80, 0, 0, 0 });
        other.o2Sensors2Banks = 3;
        merged = VehicleMerge.merge(copy, other);
        check(merged.readiness != null && merged.copy().readiness.mil && merged.o2Sensors2Banks == 3
                && VehicleField.of("readiness").value(merged) == 1f && merged.hasAnything(), "readiness merged");

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
