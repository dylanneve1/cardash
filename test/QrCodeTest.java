import ie.claudius.cardash.remote.QrCode;

import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

import javax.imageio.ImageIO;

/**
 * Plain-JVM checks for the QR encoder behind the phone settings address (#1).
 *
 * Structure checks always run. If zbarimg (zbar-tools) is on PATH, every
 * version 1-6 and every mask is also rendered and decoded back.
 */
public class QrCodeTest {

    private static int failures = 0;

    public static void main(String[] args) throws Exception {
        reedSolomonKnownAnswer();
        formatBits();
        sizesAndCapacity();
        finderPatterns();
        decodeWithZbar();
        if (failures > 0) {
            System.out.println(failures + " QR check(s) failed");
            System.exit(1);
        }
        System.out.println("QrCodeTest: all checks passed");
    }

    /** ISO 18004 / thonky.com worked example: "HELLO WORLD", 1-M. */
    static void reedSolomonKnownAnswer() throws Exception {
        int[] data = { 32, 91, 11, 120, 209, 114, 220, 77, 67, 64, 236, 17, 236, 17, 236, 17 };
        int[] want = { 196, 35, 39, 119, 235, 215, 231, 226, 93, 23 };
        byte[] in = new byte[data.length];
        for (int i = 0; i < data.length; i++) in[i] = (byte) data[i];
        Method div = QrCode.class.getDeclaredMethod("rsDivisor", int.class);
        Method rem = QrCode.class.getDeclaredMethod("rsRemainder", byte[].class, int[].class);
        div.setAccessible(true);
        rem.setAccessible(true);
        byte[] got = (byte[]) rem.invoke(null, in, div.invoke(null, 10));
        boolean ok = got.length == want.length;
        for (int i = 0; ok && i < want.length; i++) ok = (got[i] & 0xFF) == want[i];
        check(ok, "Reed-Solomon matches the HELLO WORLD 1-M example");
    }

    /** Level M format words from the ISO table. */
    static void formatBits() throws Exception {
        int[] want = { 0x5412, 0x5125, 0x5E7C, 0x5B4B, 0x45F9, 0x40CE, 0x4F97, 0x4AA0 };
        Method f = QrCode.class.getDeclaredMethod("formatBits", int.class);
        f.setAccessible(true);
        for (int m = 0; m < 8; m++) {
            int got = (Integer) f.invoke(null, m);
            check(got == want[m], "format bits M/mask " + m + " = " + Integer.toHexString(got));
        }
    }

    static void sizesAndCapacity() {
        check(QrCode.encode("http://192.168.43.1:8466").length == 25, "typical hotspot address is version 2");
        check(QrCode.encode("").length == 21, "empty string is version 1");
        check(QrCode.encode(repeat('a', QrCode.capacity())).length == 41, "capacity fits version 6");
        check(QrCode.encode(repeat('a', QrCode.capacity() + 1)) == null, "one byte over capacity is refused");
        check(QrCode.capacity() == 106, "capacity is 106 bytes");
    }

    static void finderPatterns() {
        boolean[][] m = QrCode.encode("http://10.0.0.5:8466");
        int n = m.length;
        boolean ok = true;
        int[][] corners = { { 0, 0 }, { n - 7, 0 }, { 0, n - 7 } };
        for (int[] c : corners) {
            for (int y = 0; y < 7; y++)
                for (int x = 0; x < 7; x++) {
                    int d = Math.max(Math.abs(x - 3), Math.abs(y - 3));
                    ok &= m[c[1] + y][c[0] + x] == (d != 2);
                }
        }
        check(ok, "three finder patterns in place");
        check(m[n - 8][8], "dark module set");
    }

    static void decodeWithZbar() throws Exception {
        if (!onPath("zbarimg")) {
            System.out.println("  (zbarimg not on PATH: skipping decode round-trip)");
            return;
        }
        String[] samples = {
            "http://192.168.43.1:8466",
            "http://10.0.0.5",
            "http://172.20.10.2:8466",
            "http://[fe80::1]:8466/" + repeat('x', 28),
            "http://192.168.1.100:8466/" + repeat('y', 40),
            repeat('z', QrCode.capacity()),
        };
        Method enc = QrCode.class.getDeclaredMethod("encode", byte[].class, int.class);
        enc.setAccessible(true);
        File dir = new File(System.getProperty("java.io.tmpdir"), "cardash-qr-test");
        dir.mkdirs();
        for (String s : samples) {
            for (int mask = -1; mask < 8; mask++) {
                boolean[][] m = (boolean[][]) enc.invoke(null, s.getBytes(StandardCharsets.UTF_8), mask);
                File png = new File(dir, "qr.png");
                ImageIO.write(render(m, 6), "png", png);
                String got = zbar(png);
                check(s.equals(got), "zbar decodes v" + ((m.length - 17) / 4) + " mask " + mask
                        + (s.equals(got) ? "" : " (got " + got + ")"));
            }
        }
        new File(dir, "qr.png").delete();
        dir.delete();
    }

    static BufferedImage render(boolean[][] m, int scale) {
        int quiet = 4, n = m.length, px = (n + quiet * 2) * scale;
        BufferedImage img = new BufferedImage(px, px, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < px; y++)
            for (int x = 0; x < px; x++) {
                int mx = x / scale - quiet, my = y / scale - quiet;
                boolean dark = mx >= 0 && my >= 0 && mx < n && my < n && m[my][mx];
                img.setRGB(x, y, dark ? 0x000000 : 0xFFFFFF);
            }
        return img;
    }

    static String zbar(File png) throws Exception {
        Process p = new ProcessBuilder("zbarimg", "--quiet", "--raw", "-Sdisable", "-Sqrcode.enable",
                png.getPath()).redirectErrorStream(true).start();
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(sb.length() > 0 ? "\n" : "").append(line);
        }
        p.waitFor();
        return sb.toString();
    }

    static boolean onPath(String exe) {
        String path = System.getenv("PATH");
        if (path == null) return false;
        for (String d : path.split(File.pathSeparator)) if (new File(d, exe).canExecute()) return true;
        return false;
    }

    static String repeat(char c, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(c);
        return sb.toString();
    }

    static void check(boolean ok, String what) {
        if (!ok) {
            failures++;
            System.out.println("  FAIL " + what);
        }
    }
}
