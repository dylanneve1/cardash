package ie.claudius.cardash.remote;

import java.nio.charset.StandardCharsets;

/**
 * A minimal QR code encoder for the phone settings address (issue #1).
 *
 * Byte mode, error correction level M, versions 1 to 6 (21 to 41 modules,
 * up to 106 bytes). That covers any "http://ip:port" with room to spare,
 * and those versions need neither version-information blocks nor more than
 * one alignment pattern. Android-free so test.sh can check it on the JVM.
 *
 * Follows ISO/IEC 18004; the structure mirrors Project Nayuki's reference
 * encoder (MIT), cut down to what CarDash needs.
 */
public final class QrCode {

    private QrCode() {}

    /** Data codewords per version (index = version), level M. */
    private static final int[] DATA = { 0, 16, 28, 44, 64, 86, 108 };
    /** Error-correction codewords per block, level M. */
    private static final int[] ECC = { 0, 10, 16, 26, 18, 24, 16 };
    /** Blocks per version, level M. All blocks are the same size here. */
    private static final int[] BLOCKS = { 0, 1, 1, 1, 2, 2, 4 };

    public static final int MAX_VERSION = 6;

    /** Longest input that fits, in bytes. */
    public static int capacity() {
        return DATA[MAX_VERSION] - 2; // mode + 8-bit length take 12 bits, rounded up
    }

    /** Modules (true = dark) of the smallest code holding text, or null if it doesn't fit. */
    public static boolean[][] encode(String text) {
        return encode(text.getBytes(StandardCharsets.UTF_8), -1);
    }

    /** As {@link #encode(String)}; mask 0-7 forces a mask pattern, -1 picks the best. */
    static boolean[][] encode(byte[] data, int mask) {
        int version = 0;
        for (int v = 1; v <= MAX_VERSION; v++) {
            if (4 + 8 + data.length * 8 <= DATA[v] * 8) { version = v; break; }
        }
        if (version == 0) return null;
        byte[] codewords = interleave(version, dataCodewords(version, data));

        Grid g = new Grid(version);
        g.drawFunctionPatterns();
        g.drawCodewords(codewords);
        if (mask < 0) {
            int best = Integer.MAX_VALUE;
            for (int m = 0; m < 8; m++) {
                g.applyMask(m);
                g.drawFormat(m);
                int p = g.penalty();
                if (p < best) { best = p; mask = m; }
                g.applyMask(m); // XOR undoes it
            }
        }
        g.applyMask(mask);
        g.drawFormat(mask);
        return g.modules;
    }

    // ---- data -------------------------------------------------------------------

    static byte[] dataCodewords(int version, byte[] data) {
        int capBits = DATA[version] * 8;
        BitBuffer bb = new BitBuffer(capBits);
        bb.append(0x4, 4);            // byte mode
        bb.append(data.length, 8);    // character count, 8 bits for versions 1-9
        for (byte b : data) bb.append(b & 0xFF, 8);
        bb.append(0, Math.min(4, capBits - bb.length));       // terminator
        bb.append(0, (8 - bb.length % 8) % 8);                 // to a byte boundary
        for (int pad = 0xEC; bb.length < capBits; pad ^= 0xEC ^ 0x11) bb.append(pad, 8);
        return bb.bytes;
    }

    /** Splits into blocks, adds Reed-Solomon codewords, interleaves. */
    static byte[] interleave(int version, byte[] data) {
        int blocks = BLOCKS[version], ecc = ECC[version];
        int per = data.length / blocks;
        int[] divisor = rsDivisor(ecc);
        byte[][] eccBlocks = new byte[blocks][];
        for (int b = 0; b < blocks; b++) {
            byte[] block = new byte[per];
            System.arraycopy(data, b * per, block, 0, per);
            eccBlocks[b] = rsRemainder(block, divisor);
        }
        byte[] out = new byte[data.length + blocks * ecc];
        int k = 0;
        for (int i = 0; i < per; i++)
            for (int b = 0; b < blocks; b++) out[k++] = data[b * per + i];
        for (int i = 0; i < ecc; i++)
            for (int b = 0; b < blocks; b++) out[k++] = eccBlocks[b][i];
        return out;
    }

    // ---- Reed-Solomon over GF(2^8), polynomial 0x11D ------------------------------

    static int[] rsDivisor(int degree) {
        int[] result = new int[degree];
        result[degree - 1] = 1;
        int root = 1;
        for (int i = 0; i < degree; i++) {
            for (int j = 0; j < degree; j++) {
                result[j] = gfMul(result[j], root);
                if (j + 1 < degree) result[j] ^= result[j + 1];
            }
            root = gfMul(root, 0x02);
        }
        return result;
    }

    static byte[] rsRemainder(byte[] data, int[] divisor) {
        int[] r = new int[divisor.length];
        for (byte b : data) {
            int factor = (b & 0xFF) ^ r[0];
            System.arraycopy(r, 1, r, 0, r.length - 1);
            r[r.length - 1] = 0;
            for (int i = 0; i < r.length; i++) r[i] ^= gfMul(divisor[i], factor);
        }
        byte[] out = new byte[r.length];
        for (int i = 0; i < r.length; i++) out[i] = (byte) r[i];
        return out;
    }

    static int gfMul(int x, int y) {
        int z = 0;
        for (int i = 7; i >= 0; i--) {
            z = (z << 1) ^ ((z >>> 7) * 0x11D);
            z ^= ((y >>> i) & 1) * x;
        }
        return z;
    }

    // ---- format information ------------------------------------------------------

    /** 15-bit format word for level M (indicator 00) and a mask. */
    static int formatBits(int mask) {
        int data = mask; // (00 << 3) | mask
        int rem = data;
        for (int i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
        return ((data << 10) | rem) ^ 0x5412;
    }

    // ---- the module grid ---------------------------------------------------------

    private static final class Grid {
        final int version, size;
        final boolean[][] modules;   // [y][x]
        final boolean[][] function;

        Grid(int version) {
            this.version = version;
            this.size = version * 4 + 17;
            modules = new boolean[size][size];
            function = new boolean[size][size];
        }

        void set(int x, int y, boolean dark) {
            modules[y][x] = dark;
            function[y][x] = true;
        }

        void drawFunctionPatterns() {
            for (int i = 0; i < size; i++) {
                set(6, i, i % 2 == 0);
                set(i, 6, i % 2 == 0);
            }
            finder(3, 3);
            finder(size - 4, 3);
            finder(3, size - 4);
            if (version >= 2) {
                int c = version * 4 + 10; // the one centre that isn't under a finder
                for (int dy = -2; dy <= 2; dy++)
                    for (int dx = -2; dx <= 2; dx++)
                        set(c + dx, c + dy, Math.max(Math.abs(dx), Math.abs(dy)) != 1);
            }
            drawFormat(0); // reserves the format areas; redrawn once the mask is chosen
        }

        void finder(int cx, int cy) {
            for (int dy = -4; dy <= 4; dy++) {
                for (int dx = -4; dx <= 4; dx++) {
                    int x = cx + dx, y = cy + dy;
                    if (x < 0 || x >= size || y < 0 || y >= size) continue;
                    int d = Math.max(Math.abs(dx), Math.abs(dy));
                    set(x, y, d != 2 && d != 4);
                }
            }
        }

        void drawFormat(int mask) {
            int bits = formatBits(mask);
            for (int i = 0; i <= 5; i++) set(8, i, bit(bits, i));
            set(8, 7, bit(bits, 6));
            set(8, 8, bit(bits, 7));
            set(7, 8, bit(bits, 8));
            for (int i = 9; i < 15; i++) set(14 - i, 8, bit(bits, i));
            for (int i = 0; i < 8; i++) set(size - 1 - i, 8, bit(bits, i));
            for (int i = 8; i < 15; i++) set(8, size - 15 + i, bit(bits, i));
            set(8, size - 8, true); // the dark module
        }

        void drawCodewords(byte[] data) {
            int i = 0, total = data.length * 8;
            for (int right = size - 1; right >= 1; right -= 2) {
                if (right == 6) right = 5; // skip the vertical timing column
                for (int vert = 0; vert < size; vert++) {
                    for (int j = 0; j < 2; j++) {
                        int x = right - j;
                        boolean upward = ((right + 1) & 2) == 0;
                        int y = upward ? size - 1 - vert : vert;
                        if (!function[y][x] && i < total) {
                            modules[y][x] = bit(data[i >>> 3], 7 - (i & 7));
                            i++;
                        }
                        // remainder bits stay light
                    }
                }
            }
        }

        void applyMask(int mask) {
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    if (function[y][x]) continue;
                    boolean invert;
                    switch (mask) {
                        case 0: invert = (x + y) % 2 == 0; break;
                        case 1: invert = y % 2 == 0; break;
                        case 2: invert = x % 3 == 0; break;
                        case 3: invert = (x + y) % 3 == 0; break;
                        case 4: invert = (x / 3 + y / 2) % 2 == 0; break;
                        case 5: invert = x * y % 2 + x * y % 3 == 0; break;
                        case 6: invert = (x * y % 2 + x * y % 3) % 2 == 0; break;
                        default: invert = ((x + y) % 2 + x * y % 3) % 2 == 0; break;
                    }
                    modules[y][x] ^= invert;
                }
            }
        }

        /** ISO 18004 penalty score; lower scans better. */
        int penalty() {
            int p = 0, dark = 0;
            for (int pass = 0; pass < 2; pass++) {
                for (int a = 0; a < size; a++) {
                    int run = 0;
                    boolean last = false;
                    for (int b = 0; b < size; b++) {
                        boolean m = pass == 0 ? modules[a][b] : modules[b][a];
                        if (b > 0 && m == last) {
                            run++;
                        } else {
                            if (run >= 5) p += run - 2;
                            run = 1;
                            last = m;
                        }
                        if (pass == 0 && m) dark++;
                        if (finderLike(pass, a, b)) p += 40;
                    }
                    if (run >= 5) p += run - 2;
                }
            }
            for (int y = 0; y + 1 < size; y++)
                for (int x = 0; x + 1 < size; x++) {
                    boolean m = modules[y][x];
                    if (m == modules[y][x + 1] && m == modules[y + 1][x] && m == modules[y + 1][x + 1]) p += 3;
                }
            int total = size * size;
            int k = (Math.abs(dark * 20 - total * 10) + total - 1) / total - 1;
            return p + Math.max(0, k) * 10;
        }

        /** 1:1:3:1:1 dark-light pattern starting at b, with 4 light modules on one side. */
        private boolean finderLike(int pass, int a, int b) {
            final boolean[] pat = { true, false, true, true, true, false, true };
            if (b + 7 > size) return false;
            for (int i = 0; i < 7; i++) if (at(pass, a, b + i) != pat[i]) return false;
            boolean before = true, after = true;
            for (int i = 1; i <= 4; i++) {
                if (at(pass, a, b - i)) before = false;
                if (at(pass, a, b + 6 + i)) after = false;
            }
            return before || after;
        }

        /** Out of bounds reads as light (the quiet zone). */
        private boolean at(int pass, int a, int b) {
            if (b < 0 || b >= size) return false;
            return pass == 0 ? modules[a][b] : modules[b][a];
        }
    }

    private static boolean bit(int x, int i) {
        return ((x >>> i) & 1) != 0;
    }

    private static final class BitBuffer {
        final byte[] bytes;
        int length;

        BitBuffer(int capBits) { bytes = new byte[capBits / 8]; }

        void append(int value, int bits) {
            for (int i = bits - 1; i >= 0; i--, length++) {
                if (((value >>> i) & 1) != 0) bytes[length >>> 3] |= (byte) (0x80 >>> (length & 7));
            }
        }
    }
}
