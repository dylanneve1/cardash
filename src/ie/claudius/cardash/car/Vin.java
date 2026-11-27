package ie.claudius.cardash.car;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What a VIN says about the car: the maker from its first three
 * characters, and for the VW group's European VINs ("WVWZZZ6RZAY..."),
 * the model from the type code in positions 7 and 8 and the model year
 * from position 10. Anything not recognised stays null. The VIN does not
 * encode the number of doors. Android-free; see CarProfileTest.
 */
public final class Vin {

    public static final String VOLKSWAGEN = "Volkswagen", SKODA = "Škoda", SEAT = "SEAT", AUDI = "Audi";

    public final String vin;
    /** Maker, or null. */
    public final String make;
    /** Model name ("Polo"), or null. */
    public final String model;
    /** The maker's type code ("6R"), or null. */
    public final String code;
    /** Model year, or 0. */
    public final int year;

    private Vin(String vin, String make, String model, String code, int year) {
        this.vin = vin; this.make = make; this.model = model; this.code = code; this.year = year;
    }

    private static final Map<String, String> WMI = new LinkedHashMap<>();
    /** make -> type code -> model */
    private static final Map<String, Map<String, String>> MODELS = new LinkedHashMap<>();

    private static void wmi(String make, String... codes) {
        for (String c : codes) WMI.put(c, make);
    }

    private static void models(String make, String... pairs) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) m.put(pairs[i], pairs[i + 1]);
        MODELS.put(make, m);
    }

    static {
        wmi(VOLKSWAGEN, "WVW", "WVG", "WV1", "WV2", "1VW", "3VW", "9BW", "AAV", "VWV");
        wmi(SKODA, "TMB", "TMP");
        wmi(SEAT, "VSS");
        wmi(AUDI, "WAU", "WUA", "TRU");
        wmi("BMW", "WBA", "WBS", "WBY");
        wmi("Mercedes-Benz", "WDB", "WDD", "W1K", "W1N");
        wmi("Ford", "WF0");
        wmi("Opel", "W0L", "W0V");
        wmi("Renault", "VF1");
        wmi("Peugeot", "VF3", "VR3");
        wmi("Citroën", "VF7", "VR7");
        wmi("Toyota", "SB1", "JTD", "JTE", "JTM", "JTN", "VNK");
        wmi("Hyundai", "TMA", "KMH", "NLH");
        wmi("Kia", "KNA", "KNE", "U5Y", "U6Y");
        wmi("Fiat", "ZFA");
        wmi("Nissan", "SJN", "JN1", "VSK");
        wmi("Honda", "SHH", "JHM");
        wmi("Mazda", "JMZ");
        models(VOLKSWAGEN,
                "6R", "Polo", "6C", "Polo", "9N", "Polo", "6N", "Polo", "AW", "Polo",
                "1J", "Golf", "1K", "Golf", "5K", "Golf", "AU", "Golf", "CD", "Golf",
                "3B", "Passat", "3C", "Passat", "16", "Jetta", "AA", "up!", "12", "up!",
                "13", "Scirocco", "1T", "Touran", "5T", "Touran", "5N", "Tiguan", "AD", "Tiguan",
                "7N", "Sharan", "A1", "T-Roc", "C1", "T-Cross", "2K", "Caddy");
        models(SKODA, "5J", "Fabia", "NJ", "Fabia", "6Y", "Fabia", "1U", "Octavia", "1Z", "Octavia",
                "5E", "Octavia", "3T", "Superb", "NH", "Rapid");
        models(SEAT, "6J", "Ibiza", "6L", "Ibiza", "KJ", "Ibiza", "1P", "Leon", "5F", "Leon", "1M", "Leon");
        models(AUDI, "8X", "A1", "8P", "A3", "8V", "A3", "8K", "A4", "8E", "A4", "8R", "Q5", "8U", "Q3");
    }

    private static final String YEARS = "ABCDEFGHJKLMNPRSTVWXY";   // 2010..2030

    /** Null unless {@code text} is a 17-character VIN (no I, O or Q). */
    public static Vin decode(String text) {
        if (text == null) return null;
        String v = text.trim().toUpperCase(Locale.ROOT);
        if (!v.matches("[A-HJ-NPR-Z0-9]{17}")) return null;
        String make = WMI.get(v.substring(0, 3));
        String code = null, model = null;
        int year = 0;
        Map<String, String> known = make == null ? null : MODELS.get(make);
        if (known != null && v.startsWith("ZZZ", 3)) {
            code = v.substring(6, 8);
            model = known.get(code);
            char y = v.charAt(9);
            int i = YEARS.indexOf(y);
            if (i >= 0) year = 2010 + i;
            else if (y >= '1' && y <= '9') year = 2000 + (y - '0');
        }
        return new Vin(v, make, model, code, year);
    }

    /** The makes a user can pick from. */
    public static List<String> makes() {
        List<String> out = new ArrayList<>();
        for (String m : WMI.values()) if (!out.contains(m)) out.add(m);
        return out;
    }

    /** The models known for a make; may be empty. */
    public static List<String> modelsOf(String make) {
        List<String> out = new ArrayList<>();
        Map<String, String> m = MODELS.get(make);
        if (m != null) for (String n : m.values()) if (!out.contains(n)) out.add(n);
        return out;
    }

    /** "Volkswagen Polo (6R), 2010", with whatever is known. */
    public String describe() {
        StringBuilder sb = new StringBuilder(make == null ? "Unknown maker" : make);
        if (model != null) sb.append(' ').append(model);
        if (code != null) sb.append(" (").append(code).append(')');
        if (year > 0) sb.append(", ").append(year);
        return sb.toString();
    }

    /** The VIN with the serial number hidden, for reports that get posted publicly. */
    public static String masked(String vin) {
        if (vin == null) return null;
        return vin.length() <= 11 ? vin : vin.substring(0, 11) + "******";
    }
}
