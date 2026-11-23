package ie.claudius.cardash.diag;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Android-free text rules for the diagnostics export: masking of
 * identifiers (exports get posted publicly) and the "looks
 * vehicle-related" key filter for system properties and settings.
 * No framework imports, so test.sh can run it on a plain JVM.
 */
public final class ExportText {

    private ExportText() {}

    private static final Pattern MAC = Pattern.compile(
            "(?<![0-9A-Fa-f:\\-])([0-9A-Fa-f]{2})([:\\-])([0-9A-Fa-f]{2})\\2([0-9A-Fa-f]{2})"
                    + "(?:\\2[0-9A-Fa-f]{2}){3}(?![0-9A-Fa-f])");

    /** key (or key=value / [key]: [value]) lines whose key names a serial or IMEI. */
    private static final Pattern SERIAL_LINE = Pattern.compile(
            "(?im)(\\b[\\w.\\-]*(?:serial|imei|meid)[\\w.\\-]*\\]?\\s*[:=]\\s*\\[?\\s*)([^\\]\\s,;]+)");

    /** Keep the vendor prefix (first three octets), hide the device part. */
    public static String maskMac(String mac) {
        if (mac == null) return null;
        Matcher m = MAC.matcher(mac);
        if (!m.matches()) return mac;
        return mask(m);
    }

    private static String mask(Matcher m) {
        String s = m.group(2);
        return m.group(1).toUpperCase(Locale.US) + s + m.group(3).toUpperCase(Locale.US) + s
                + m.group(4).toUpperCase(Locale.US) + s + "xx" + s + "xx" + s + "xx";
    }

    /** First two characters and a length hint; short values are fully hidden. */
    public static String maskSerial(String serial) {
        if (serial == null) return null;
        String t = serial.trim();
        if (t.isEmpty() || t.equals("unknown") || t.equals("?")) return serial;
        if (t.length() <= 4) return "****";
        return t.substring(0, 2) + "******(" + t.length() + " chars)";
    }

    /** Mask every MAC address and every serial/IMEI value found in free text. */
    public static String maskText(String text) {
        if (text == null || text.isEmpty()) return text;
        Matcher m = MAC.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m.find()) m.appendReplacement(sb, Matcher.quoteReplacement(mask(m)));
        m.appendTail(sb);
        Matcher s = SERIAL_LINE.matcher(sb.toString());
        StringBuffer out = new StringBuffer();
        while (s.find()) {
            String v = s.group(2);
            if (v.contains("******") || v.equals("****") || v.equals("unknown")) {
                s.appendReplacement(out, Matcher.quoteReplacement(s.group(0)));
            } else {
                s.appendReplacement(out, Matcher.quoteReplacement(s.group(1) + maskSerial(v)));
            }
        }
        s.appendTail(out);
        return out.toString();
    }

    // ---- vehicle-related key filter ---------------------------------------------

    private static final String[] VEHICLE_SEGMENTS = {
            "can", "canbox", "canbus", "car", "mcu", "gear", "speed", "door", "brake",
            "hcn", "jancar", "fyt", "syu", "txz", "mtc", "vehicle", "reverse", "steering",
            "swc", "obd", "tpms", "radar", "avm", "acc", "handbrake", "parking",
    };

    /**
     * Does this property/settings key look vehicle-related? Matches whole
     * dot/underscore/dash/camel segments, so "ro.car.mode" and
     * "canbox_protocol" hit but "ro.carrier" and "scan_mode" don't.
     */
    public static boolean looksVehicleKey(String key) {
        if (key == null) return false;
        String k = key.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase(Locale.US);
        for (String seg : k.split("[._\\-\\s]+")) {
            if (seg.isEmpty()) continue;
            for (String w : VEHICLE_SEGMENTS) {
                if (seg.equals(w)) return true;
            }
            if (seg.startsWith("canbo") || seg.startsWith("canbu")) return true;
        }
        return false;
    }
}
