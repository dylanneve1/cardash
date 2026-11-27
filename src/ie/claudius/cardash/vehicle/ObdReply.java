package ie.claudius.cardash.vehicle;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Whole ELM327 replies, with headers off (ATH0) and in either spacing:
 * single frames, the CAN multi-frame layout ("00E" then "0:..", "1:.."),
 * several ECUs answering, multi-PID mode-01 answers and the
 * supported-PID bitmaps. Android-free; see ObdReplyTest.
 */
public final class ObdReply {

    private ObdReply() {}

    /** Data bytes a mode-01 PID returns, for the PIDs CarDash asks; -1 for any other. */
    public static int length(int pid) {
        if (pid == 0x00 || pid == 0x20 || pid == 0x40 || pid == 0x60) return 4;
        VehicleField.Request r = VehicleField.request(pid);
        return r == null ? -1 : r.bytes;
    }

    /** The request for up to six mode-01 PIDs, e.g. "010C0D05". */
    public static String command(int... pids) {
        StringBuilder sb = new StringBuilder("01");
        for (int p : pids) sb.append(hex2(p));
        return sb.toString();
    }

    public static String hex2(int v) {
        String s = Integer.toHexString(v & 0xFF).toUpperCase(java.util.Locale.ROOT);
        return s.length() < 2 ? "0" + s : s;
    }

    /** The messages in a reply, each starting with its mode byte. Chatter lines are skipped. */
    public static List<int[]> messages(String reply) {
        List<int[]> out = new ArrayList<>();
        if (reply == null) return out;
        ByteArrayOutputStream multi = null;
        int want = 0;
        for (String raw : reply.split("[\\r\\n>]+")) {
            String line = raw.replace(" ", "").trim().toUpperCase(java.util.Locale.ROOT);
            if (line.length() == 3 && isHex(line)) {          // length header of a multi-frame message
                flush(out, multi, want);
                multi = new ByteArrayOutputStream();
                want = Integer.parseInt(line, 16);
            } else if (line.length() > 2 && line.charAt(1) == ':' && isHex(line.substring(0, 1))
                    && isHex(line.substring(2))) {             // one of its frames
                if (multi == null) continue;
                for (int b : bytes(line.substring(2))) multi.write(b);
            } else if (line.length() >= 4 && (line.length() & 1) == 0 && isHex(line)) {
                // A single-frame message; another ECU's may arrive between the frames of a long one.
                out.add(bytes(line));
            }
        }
        flush(out, multi, want);
        return out;
    }

    private static void flush(List<int[]> out, ByteArrayOutputStream multi, int want) {
        if (multi == null || multi.size() == 0) return;
        byte[] b = multi.toByteArray();
        int n = Math.min(want, b.length);   // the last frame is padded past the declared length
        int[] m = new int[n];
        for (int i = 0; i < n; i++) m[i] = b[i] & 0xFF;
        out.add(m);
    }

    /**
     * PID to data bytes for the {@code asked} PIDs found in a mode-01 reply.
     * A PID the car left out is simply absent; the first ECU to answer wins;
     * a cut-off or unexpected tail ends that message rather than guessing.
     */
    public static Map<Integer, int[]> mode01(String reply, int... asked) {
        Map<Integer, int[]> out = new LinkedHashMap<>();
        for (int[] m : messages(reply)) {
            if (m.length < 2 || m[0] != 0x41) continue;
            int i = 1;
            while (i < m.length) {
                int pid = m[i++];
                int n = length(pid);
                if (n < 0 || !contains(asked, pid) || i + n > m.length) break;
                if (!out.containsKey(pid)) {
                    int[] data = new int[n];
                    System.arraycopy(m, i, data, 0, n);
                    out.put(pid, data);
                }
                i += n;
            }
        }
        return out;
    }

    /**
     * The PIDs a bitmap reply (0100, 0120, 0140, 0160) says are supported,
     * joined over every ECU that answered; null if none did.
     */
    public static Set<Integer> supported(String reply, int base) {
        Set<Integer> out = null;
        for (int[] m : messages(reply)) {
            if (m.length < 6 || m[0] != 0x41 || m[1] != base) continue;
            if (out == null) out = new TreeSet<>();
            for (int bit = 0; bit < 32; bit++) {
                if ((m[2 + bit / 8] & (0x80 >> (bit % 8))) != 0) out.add(base + bit + 1);
            }
        }
        return out;
    }

    /** The VIN from a mode-09 PID 02 reply (one CAN message, or five short ones), or null. */
    public static String vin(String reply) {
        StringBuilder sb = new StringBuilder();
        for (int[] m : messages(reply)) {
            if (m.length < 3 || m[0] != 0x49 || m[1] != 0x02) continue;
            // m[2] counts the items (CAN) or numbers the message; padding bytes are zeros.
            for (int i = 3; i < m.length; i++) {
                if (Character.isLetterOrDigit(m[i]) && m[i] < 128) sb.append((char) m[i]);
            }
        }
        return sb.length() < 17 ? null : sb.substring(sb.length() - 17);
    }

    /** The protocol number from an ATDPN reply ("6", or "A6" when found automatically); -1 if unreadable. */
    public static int protocol(String reply) {
        if (reply == null) return -1;
        for (String line : reply.split("[\\r\\n>]+")) {
            String s = line.replace(" ", "").trim().toUpperCase(java.util.Locale.ROOT);
            if (s.length() == 2 && s.charAt(0) == 'A') s = s.substring(1);
            if (s.length() == 1 && isHex(s)) return Integer.parseInt(s, 16);
        }
        return -1;
    }

    /** ELM protocols 6 to 9 are ISO 15765-4 CAN, where one request may carry several PIDs. */
    public static boolean isCan(int protocol) {
        return protocol >= 6 && protocol <= 9;
    }

    private static boolean contains(int[] a, int v) {
        for (int x : a) if (x == v) return true;
        return false;
    }

    private static boolean isHex(String s) {
        if (s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) if (Character.digit(s.charAt(i), 16) < 0) return false;
        return true;
    }

    private static int[] bytes(String hex) {
        int[] b = new int[hex.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        return b;
    }
}
