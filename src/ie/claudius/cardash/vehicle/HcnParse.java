package ie.claudius.cardash.vehicle;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Raise (RZC) canbox frames as seen on HCN head units, decoded for the
 * VW PQ platform (Polo 6R and friends). No Android imports, so it runs
 * under test.sh on a plain JVM.
 *
 * A frame on the wire is
 *
 *     2E  type  len  data[len]  chk      chk = 0xFF - ((type + len + sum(data)) & 0xFF)
 *
 * and the head unit answers each good one with a single FF byte. HCN's
 * decoders see the frame with the 2E and the checksum already stripped
 * (type, len, data...), so both shapes are accepted here.
 *
 * The bit layout comes from reading what HCN's own decoder for this
 * protocol does with each byte (see docs/hcn.md); none of it has been
 * checked against a moving car yet.
 */
public final class HcnParse {

    private HcnParse() {}

    public static final int SOF = 0x2E;
    public static final int UNKNOWN = Integer.MIN_VALUE;

    /** Body / engine info. data[0] is a sub-type: 0x01 doors, 0x02 driving data. */
    public static final int TYPE_BODY = 0x41;
    public static final int SUB_DOORS = 0x01;
    public static final int SUB_DRIVING = 0x02;
    public static final int SUB_WARNINGS = 0x03;

    /** Polo 6R fuel tank. The box reports litres; the dash wants a percentage. */
    public static final int TANK_LITRES_POLO_6R = 45;

    /** One Raise frame. {@code data} is everything after the length byte (sub-type first). */
    public static final class Frame {
        public final int type;
        public final int[] data;
        /** True when it came with 2E and a checksum that matched. */
        public final boolean checked;

        Frame(int type, int[] data, boolean checked) {
            this.type = type;
            this.data = data;
            this.checked = checked;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder(String.format(Locale.US, "%02X:", type));
            for (int b : data) sb.append(String.format(Locale.US, " %02X", b));
            return sb.toString();
        }
    }

    /** What the frames have told us so far. Every field starts UNKNOWN. */
    public static final class Body {
        // 1 = open / on / warning, 0 = shut / off / fine.
        public int doorFrontLeft = UNKNOWN, doorFrontRight = UNKNOWN;
        public int doorRearLeft = UNKNOWN, doorRearRight = UNKNOWN, boot = UNKNOWN;
        public int handbrake = UNKNOWN, washerLow = UNKNOWN, beltUnfastened = UNKNOWN;
        public int fuelLow = UNKNOWN, batteryLow = UNKNOWN;

        public int rpm = UNKNOWN;
        /** Hundredths of a km/h, as sent. */
        public int speedCentiKph = UNKNOWN;
        /** Hundredths of a volt. */
        public int batteryCentiV = UNKNOWN;
        /** Tenths of a degree C, signed. */
        public int outsideDeciC = UNKNOWN;
        public int odometerKm = UNKNOWN;
        public int fuelLitres = UNKNOWN;

        public String summary() {
            StringBuilder sb = new StringBuilder();
            sb.append("doors FL=").append(b(doorFrontLeft)).append(" FR=").append(b(doorFrontRight))
                    .append(" RL=").append(b(doorRearLeft)).append(" RR=").append(b(doorRearRight))
                    .append(" boot=").append(b(boot)).append(" handbrake-bit=").append(b(handbrake))
                    .append(" washerLow=").append(b(washerLow)).append(" belt=").append(b(beltUnfastened));
            sb.append("\nrpm=").append(n(rpm))
                    .append(" speed=").append(speedCentiKph == UNKNOWN ? "?" : fmt(speedCentiKph, 100)).append("km/h")
                    .append(" battery=").append(batteryCentiV == UNKNOWN ? "?" : fmt(batteryCentiV, 100)).append("V")
                    .append(" outside=").append(outsideDeciC == UNKNOWN ? "?" : fmt(outsideDeciC, 10)).append("C")
                    .append(" odo=").append(n(odometerKm)).append("km")
                    .append(" fuel=").append(n(fuelLitres)).append("L")
                    .append(" fuelLow=").append(b(fuelLow)).append(" batteryLow=").append(b(batteryLow));
            return sb.toString();
        }

        private static String b(int v) {
            return v == UNKNOWN ? "?" : String.valueOf(v);
        }

        private static String n(int v) {
            return v == UNKNOWN ? "?" : String.valueOf(v);
        }

        private static String fmt(int v, int div) {
            return String.format(Locale.US, div == 10 ? "%.1f" : "%.2f", v / (double) div);
        }
    }

    /** 0xFF minus the low byte of type + len + data. */
    public static int checksum(int type, int len, int[] data, int from, int count) {
        int sum = type + len;
        for (int i = 0; i < count; i++) sum += data[from + i];
        return 0xFF - (sum & 0xFF);
    }

    /**
     * Pull frames out of whatever the vendor handed over.
     *
     *  - Starts with 2E: a wire capture. Walk it frame by frame; a frame
     *    whose checksum fails is skipped one byte at a time, ACK bytes
     *    (FF) between frames are ignored.
     *  - Otherwise: one already-stripped frame, type then len then data,
     *    optionally followed by its checksum.
     *
     * Anything that fits neither shape gives an empty list.
     */
    public static List<Frame> frames(byte[] raw) {
        List<Frame> out = new ArrayList<>();
        if (raw == null || raw.length < 2) return out;
        int[] u = new int[raw.length];
        for (int i = 0; i < raw.length; i++) u[i] = raw[i] & 0xFF;

        if (u[0] == SOF) {
            int i = 0;
            while (i + 3 < u.length) {
                if (u[i] != SOF) {
                    i++;
                    continue;
                }
                int type = u[i + 1], len = u[i + 2];
                int end = i + 3 + len; // index of the checksum
                if (end >= u.length) break;
                if (checksum(type, len, u, i + 3, len) == u[end]) {
                    int[] data = new int[len];
                    System.arraycopy(u, i + 3, data, 0, len);
                    out.add(new Frame(type, data, true));
                    i = end + 1;
                } else {
                    i++;
                }
            }
            return out;
        }

        int type = u[0], len = u[1];
        if (len > u.length - 2) return out;
        int[] data = new int[len];
        System.arraycopy(u, 2, data, 0, len);
        boolean checked = u.length == len + 3 && checksum(type, len, u, 2, len) == u[len + 2];
        out.add(new Frame(type, data, checked));
        return out;
    }

    /**
     * Fold one frame into {@code b}. Returns true if it was a frame we
     * understand (even if nothing changed), false for anything else.
     */
    public static boolean apply(Frame f, Body b) {
        if (f.type != TYPE_BODY || f.data.length < 2) return false;
        int[] d = f.data;
        switch (d[0]) {
            case SUB_DOORS: {
                int bits = d[1];
                b.doorFrontLeft = bit(bits, 0);
                b.doorFrontRight = bit(bits, 1);
                b.doorRearLeft = bit(bits, 2);
                b.doorRearRight = bit(bits, 3);
                b.boot = bit(bits, 4);
                b.handbrake = bit(bits, 5);
                b.washerLow = bit(bits, 6);
                b.beltUnfastened = bit(bits, 7);
                return true;
            }
            case SUB_DRIVING: {
                // sub + 12 bytes: rpm(2) speed(2) battery(2) outside(2, signed) odo(3) fuel(1)
                if (d.length < 13) return false;
                b.rpm = be16(d, 1);
                b.speedCentiKph = be16(d, 3);
                b.batteryCentiV = be16(d, 5);
                int t = (short) be16(d, 7);
                // HCN shows "--" outside this window; the box sends junk when the sensor is absent.
                b.outsideDeciC = (t >= -500 && t <= 775) ? t : UNKNOWN;
                b.odometerKm = (d[9] << 16) | (d[10] << 8) | d[11];
                b.fuelLitres = d[12];
                return true;
            }
            case SUB_WARNINGS:
                b.batteryLow = bit(d[1], 6);
                b.fuelLow = bit(d[1], 7);
                return true;
            default:
                return false;
        }
    }

    /**
     * HCN's outdoor temperature broadcast ("12.5℃", "-3°C", "--") as whole
     * degrees C, or UNKNOWN. A Fahrenheit reading is converted.
     */
    public static int outdoorTempC(String s) {
        if (s == null) return UNKNOWN;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^\\s*([+-]?\\d{1,3}(?:[.,]\\d+)?)\\s*(℃|°\\s*C|C|℉|°\\s*F|F)?\\s*$").matcher(s);
        if (!m.matches()) return UNKNOWN;
        float v = Float.parseFloat(m.group(1).replace(',', '.'));
        String u = m.group(2);
        if (u != null && (u.endsWith("F") || u.equals("℉"))) v = (v - 32f) * 5f / 9f;
        return v < -50f || v > 77.5f ? UNKNOWN : Math.round(v);
    }

    /** Litres to a 0-100 percentage of the tank, or UNKNOWN. */
    public static int fuelPercent(int litres, int tankLitres) {
        if (litres == UNKNOWN || litres < 0 || tankLitres <= 0) return UNKNOWN;
        int p = Math.round(litres * 100f / tankLitres);
        return Math.max(0, Math.min(100, p));
    }

    /**
     * HCN's door system properties (persist.vendor.cem.cardoorN): "1"
     * means shut, "2" open, "0" or empty means it has never been told.
     */
    public static int doorProp(String v) {
        if (v == null) return UNKNOWN;
        switch (v.trim()) {
            case "1": return 0;
            case "2": return 1;
            default: return UNKNOWN;
        }
    }

    /**
     * HCN's gear setting (Settings.System "key_can_gear"): 102 is R,
     * -1 means "no gear data from this car". Returns 1 for reverse,
     * 0 for any other real gear, UNKNOWN otherwise.
     */
    public static int reverseFromGear(int gear) {
        if (gear == 102) return 1;
        if ((gear >= 0 && gear <= 10) || (gear >= 100 && gear <= 104)) return 0;
        return UNKNOWN;
    }

    /**
     * A string payload that is just hex ("2E 41 06 ...", "2e4106...",
     * "0x2E,0x41,...") as bytes; null for anything else.
     */
    public static byte[] hex(String s) {
        if (s == null) return null;
        String c = s.replaceAll("(?i)0x", "").replaceAll("[\\s,:;\\-\\[\\]]", "");
        if (c.isEmpty() || (c.length() & 1) != 0 || !c.matches("[0-9A-Fa-f]+")) return null;
        byte[] out = new byte[c.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(c.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    public static String hexOf(byte[] b) {
        if (b == null) return "null";
        StringBuilder sb = new StringBuilder(b.length * 3);
        for (int i = 0; i < b.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(String.format(Locale.US, "%02X", b[i] & 0xFF));
        }
        return sb.toString();
    }

    private static int bit(int v, int n) {
        return (v >> n) & 1;
    }

    private static int be16(int[] d, int at) {
        return (d[at] << 8) | d[at + 1];
    }
}
