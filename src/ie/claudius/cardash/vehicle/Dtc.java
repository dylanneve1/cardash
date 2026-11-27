package ie.claudius.cardash.vehicle;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Stored diagnostic trouble codes (OBD mode 03): reading them from the
 * ELM327 reply and saying roughly what the common ones mean. Reading
 * only: CarDash never clears codes (mode 04). Android-free; see DtcTest.
 */
public final class Dtc {

    /** The request. */
    public static final String COMMAND = "03";

    private Dtc() {}

    /** The code two bytes stand for, e.g. 0x04 0x20 is "P0420". */
    public static String code(int a, int b) {
        return "PCBU".charAt((a >> 6) & 3) + Integer.toString((a >> 4) & 3)
                + hex(a & 0x0F) + hex((b >> 4) & 0x0F) + hex(b & 0x0F);
    }

    private static String hex(int v) {
        return String.valueOf(Character.toUpperCase(Character.forDigit(v, 16)));
    }

    /**
     * The codes in a mode-03 reply, from every ECU that answered, each
     * once. Empty when the car has none; null when the reply was not an
     * answer. Some cars on the older protocols say "NO DATA" when they
     * have none; a CAN car always answers, so there it is no answer.
     *
     * @param can on CAN a count byte comes before the codes; older
     *            protocols send three code slots per message, zero-padded
     */
    public static List<String> parse(String reply, boolean can) {
        Set<String> out = new LinkedHashSet<>();
        boolean answered = false;
        for (int[] m : ObdReply.messages(reply)) {
            if (m.length < 2 || m[0] != 0x43) continue;
            answered = true;
            // An odd number of bytes after the 43 can only be the CAN form, whatever the protocol is thought to be.
            boolean counted = can || (m.length - 1) % 2 == 1;
            int from = counted ? 2 : 1;
            int pairs = (m.length - from) / 2;
            if (counted) pairs = Math.min(pairs, m[1]);
            for (int i = 0; i < pairs; i++) {
                int a = m[from + 2 * i], b = m[from + 2 * i + 1];
                if (a != 0 || b != 0) out.add(code(a, b));
            }
        }
        if (!answered && (can || reply == null || !reply.toUpperCase(java.util.Locale.ROOT).contains("NO DATA"))) {
            return null;
        }
        return new ArrayList<>(out);
    }

    /** {first code, last code, description}; ranges of the same letter and digit count. */
    private static final String[][] KNOWN = {
            { "P0010", "P0015", "Camshaft position actuator or timing" },
            { "P0016", "P0019", "Crankshaft / camshaft correlation" },
            { "P0030", "P0064", "Oxygen sensor heater circuit" },
            { "P0087", "P0087", "Fuel rail pressure too low" },
            { "P0088", "P0088", "Fuel rail pressure too high" },
            { "P0100", "P0104", "Mass air flow sensor" },
            { "P0105", "P0109", "Manifold pressure sensor" },
            { "P0110", "P0114", "Intake air temperature sensor" },
            { "P0115", "P0119", "Coolant temperature sensor" },
            { "P0120", "P0124", "Throttle / pedal position sensor A" },
            { "P0125", "P0125", "Coolant too cold for closed-loop fuel control" },
            { "P0128", "P0128", "Coolant below thermostat temperature (thermostat)" },
            { "P0130", "P0135", "Oxygen sensor, bank 1 sensor 1" },
            { "P0136", "P0141", "Oxygen sensor, bank 1 sensor 2" },
            { "P0150", "P0155", "Oxygen sensor, bank 2 sensor 1" },
            { "P0156", "P0161", "Oxygen sensor, bank 2 sensor 2" },
            { "P0170", "P0170", "Fuel trim, bank 1" },
            { "P0171", "P0171", "Mixture too lean, bank 1" },
            { "P0172", "P0172", "Mixture too rich, bank 1" },
            { "P0174", "P0174", "Mixture too lean, bank 2" },
            { "P0175", "P0175", "Mixture too rich, bank 2" },
            { "P0200", "P0200", "Injector circuit" },
            { "P0201", "P0201", "Injector circuit, cylinder 1" },
            { "P0202", "P0202", "Injector circuit, cylinder 2" },
            { "P0203", "P0203", "Injector circuit, cylinder 3" },
            { "P0204", "P0204", "Injector circuit, cylinder 4" },
            { "P0217", "P0217", "Engine over temperature" },
            { "P0219", "P0219", "Engine over speed" },
            { "P0220", "P0229", "Throttle / pedal position sensor B or C" },
            { "P0230", "P0233", "Fuel pump circuit" },
            { "P0234", "P0234", "Turbo / supercharger overboost" },
            { "P0299", "P0299", "Turbo / supercharger underboost" },
            { "P0300", "P0300", "Random or multiple cylinder misfire" },
            { "P0301", "P0301", "Misfire, cylinder 1" },
            { "P0302", "P0302", "Misfire, cylinder 2" },
            { "P0303", "P0303", "Misfire, cylinder 3" },
            { "P0304", "P0304", "Misfire, cylinder 4" },
            { "P0305", "P0305", "Misfire, cylinder 5" },
            { "P0306", "P0306", "Misfire, cylinder 6" },
            { "P0325", "P0334", "Knock sensor" },
            { "P0335", "P0339", "Crankshaft position sensor" },
            { "P0340", "P0349", "Camshaft position sensor" },
            { "P0350", "P0362", "Ignition coil circuit" },
            { "P0380", "P0384", "Glow plug / heater circuit" },
            { "P0400", "P0409", "Exhaust gas recirculation (EGR)" },
            { "P0410", "P0419", "Secondary air injection" },
            { "P0420", "P0420", "Catalyst efficiency below threshold, bank 1" },
            { "P0430", "P0430", "Catalyst efficiency below threshold, bank 2" },
            { "P0440", "P0440", "Evaporative emission system" },
            { "P0441", "P0441", "Evaporative system: wrong purge flow" },
            { "P0442", "P0442", "Evaporative system: small leak" },
            { "P0443", "P0449", "Evaporative system: purge or vent valve circuit" },
            { "P0450", "P0454", "Evaporative system pressure sensor" },
            { "P0455", "P0455", "Evaporative system: large leak (check the fuel cap)" },
            { "P0456", "P0456", "Evaporative system: very small leak" },
            { "P0457", "P0457", "Evaporative system leak: fuel cap loose or off" },
            { "P0460", "P0464", "Fuel level sensor" },
            { "P0480", "P0485", "Cooling fan control" },
            { "P0500", "P0503", "Vehicle speed sensor" },
            { "P0505", "P0507", "Idle speed control" },
            { "P0520", "P0524", "Engine oil pressure" },
            { "P0560", "P0560", "System voltage" },
            { "P0562", "P0562", "System voltage low" },
            { "P0563", "P0563", "System voltage high" },
            { "P0571", "P0571", "Brake switch circuit" },
            { "P0600", "P0610", "Engine computer or its communication" },
            { "P0700", "P0700", "Transmission control system (see its own codes)" },
            { "P2002", "P2003", "Particulate filter efficiency below threshold" },
            { "P2096", "P2099", "Post-catalyst fuel trim out of range" },
            { "P2187", "P2188", "Mixture wrong at idle, bank 1" },
            { "P2195", "P2198", "Oxygen sensor signal stuck lean or rich" },
            { "P2279", "P2279", "Intake air leak" },
    };

    /** A short generic description: the SAE meaning of common codes, else the family the code is in. */
    public static String describe(String code) {
        if (code == null || code.length() != 5) return "Unknown code";
        for (String[] k : KNOWN) {
            if (code.compareTo(k[0]) >= 0 && code.compareTo(k[1]) <= 0) return k[2];
        }
        char letter = code.charAt(0), digit = code.charAt(1);
        // P1xxx and P30-P33xx belong to the maker; the same digit means that for the other letters too.
        if (digit == '1' || letter == 'P' && digit == '3' && code.charAt(2) < '4') {
            return "Manufacturer-specific code";
        }
        if (letter == 'C') return "Chassis code";
        if (letter == 'B') return "Body code";
        if (letter == 'U') return "Network communication code";
        if (digit != '0') return "Powertrain code";
        switch (code.charAt(2)) {
            case '0': case '1': return "Fuel and air metering";
            case '2': return "Fuel and air metering (injection)";
            case '3': return "Ignition system or misfire";
            case '4': return "Emission controls";
            case '5': return "Speed, idle control and inputs";
            case '6': return "Engine computer and outputs";
            case '7': case '8': case '9': return "Transmission";
            default: return "Powertrain code";
        }
    }
}
