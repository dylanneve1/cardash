import ie.claudius.cardash.vehicle.Dtc;
import ie.claudius.cardash.vehicle.Readiness;

import java.util.Arrays;
import java.util.List;

/** Monitor status (PID 01) and stored fault codes (mode 03). */
public class ReadinessDtcTest {

    private static int failures = 0;

    private static void check(boolean ok, String what) {
        if (!ok) {
            failures++;
            System.out.println("FAIL: " + what);
        }
    }

    private static String monitors(Readiness r) {
        StringBuilder sb = new StringBuilder();
        for (Readiness.Monitor m : r.monitors) sb.append(m.name).append(m.complete ? "+" : "-").append(';');
        return sb.toString();
    }

    private static void codes(String reply, boolean can, String... want) {
        List<String> got = Dtc.parse(reply, can);
        check(got != null && got.equals(Arrays.asList(want)), "codes of " + reply.replace('\r', '|') + ": " + got);
    }

    public static void main(String[] args) {
        // Petrol, light on with 3 codes; continuous all complete; catalyst, evap, O2, heater, EGR; evap not run.
        Readiness r = Readiness.parse(new int[] { 0x83, 0x07, 0xE5, 0x04 });
        check(r.mil && r.codes == 3 && !r.diesel, "light, count, spark");
        check(monitors(r).equals("Misfire+;Fuel system+;Components+;Catalyst+;Evaporative system-;"
                + "Oxygen sensor+;Oxygen sensor heater+;EGR / VVT+;"), "spark monitors " + monitors(r));
        check(r.incomplete() == 1 && "1 incomplete".equals(r.headline()) && "MIL on · 3 codes".equals(r.light()),
                "summary");
        // Continuous monitors incomplete: bits 4 to 6 of byte B.
        r = Readiness.parse(new int[] { 0x01, 0x77, 0x00, 0x00 });
        check(!r.mil && r.codes == 1 && r.incomplete() == 3 && "MIL off · 1 code".equals(r.light()), "continuous");
        // Compression ignition has its own names; reserved bits are skipped.
        r = Readiness.parse(new int[] { 0x00, 0x0F, 0xFF, 0x42 });
        check(r.diesel && monitors(r).equals("Misfire+;Fuel system+;Components+;NMHC catalyst+;"
                + "NOx / SCR aftertreatment-;Boost pressure+;Exhaust gas sensor+;Particulate filter-;EGR / VVT+;"),
                "compression monitors " + monitors(r));
        r = Readiness.parse(new int[] { 0, 0, 0, 0 });
        check("No monitors".equals(r.headline()) && "MIL off".equals(r.light()), "nothing supported");
        check(Readiness.parse(new int[] { 0, 0, 0 }) == null && Readiness.parse(null) == null, "short");

        check("P0420".equals(Dtc.code(0x04, 0x20)) && "C0300".equals(Dtc.code(0x43, 0x00))
                && "B1ABC".equals(Dtc.code(0x9A, 0xBC)) && "U0100".equals(Dtc.code(0xC1, 0x00)), "code letters");
        // CAN: a count, then the codes; padding is not a code.
        codes("43 02 04 20 03 01\r\r", true, "P0420", "P0301");
        codes("43010420000000\r\r", true, "P0420");
        codes("4300\r\r", true);
        // CAN multi-frame: four codes.
        codes("00A\r0:430404200301\r1:01710300AAAAAA\r\r", true, "P0420", "P0301", "P0171", "P0300");
        // Two ECUs, the same code once.
        codes("43010420\r430204200700\r", true, "P0420", "P0700");
        // Older protocols: three slots per message, zeros where there is none.
        codes("43 04 20 03 01 00 00\r\r", false, "P0420", "P0301");
        codes("43013301710300\r43042000000000\r", false, "P0133", "P0171", "P0300", "P0420");
        codes("43000000000000\r", false);
        codes("NO DATA\r", false);
        codes("SEARCHING...\r43010420\r", true, "P0420");
        codes("43010420\r", false, "P0420");   // protocol unknown: the odd length gives CAN away
        check(Dtc.parse("NO DATA\r", true) == null, "silence on CAN is not a clean bill");
        for (String bad : new String[] { null, "", "?\r", "STOPPED\r", "CAN ERROR\r", "UNABLE TO CONNECT\r",
                "7F0311\r" }) {
            check(Dtc.parse(bad, true) == null, "not an answer: " + bad);
        }

        check(Dtc.describe("P0420").contains("Catalyst") && Dtc.describe("P0301").contains("cylinder 1")
                && Dtc.describe("P0102").contains("Mass air flow") && Dtc.describe("P2002").contains("Particulate"),
                "known codes");
        check("Manufacturer-specific code".equals(Dtc.describe("P1234"))
                && "Manufacturer-specific code".equals(Dtc.describe("P3100"))
                && "Ignition system or misfire".equals(Dtc.describe("P0399"))
                && "Transmission".equals(Dtc.describe("P0899"))
                && "Chassis code".equals(Dtc.describe("C0035"))
                && "Network communication code".equals(Dtc.describe("U0100"))
                && "Powertrain code".equals(Dtc.describe("P2A00"))
                && "Unknown code".equals(Dtc.describe(null)), "families");

        if (failures > 0) {
            System.out.println(failures + " failures");
            System.exit(1);
        }
        System.out.println("ReadinessDtcTest: all passed");
    }
}
