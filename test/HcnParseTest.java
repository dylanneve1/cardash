import ie.claudius.cardash.vehicle.HcnParse;

import java.util.List;

/**
 * Plain-JVM checks for the HCN / Raise frame decoder — run with test.sh.
 *
 * The first fixtures are real bytes captured on a 2010 Polo 6R's Raise
 * box; the rest are built from the layout in docs/hcn.md so each field
 * is pinned to a byte and a bit.
 */
public class HcnParseTest {

    private static int failures = 0;
    private static final int U = HcnParse.UNKNOWN;

    public static void main(String[] args) {
        // Real: box -> head unit, 0x41 sub 0x01 (doors), everything shut.
        byte[] doors = bytes(0x2E, 0x41, 0x06, 0x01, 0x00, 0x00, 0x00, 0x20, 0x40, 0x57);
        List<HcnParse.Frame> fs = HcnParse.frames(doors);
        eq("real 41 one frame", fs.size(), 1);
        eq("real 41 type", fs.get(0).type, 0x41);
        eq("real 41 len", fs.get(0).data.length, 6);
        eq("real 41 checked", fs.get(0).checked ? 1 : 0, 1);
        HcnParse.Body b = new HcnParse.Body();
        eq("real 41 understood", HcnParse.apply(fs.get(0), b) ? 1 : 0, 1);
        eq("real FL shut", b.doorFrontLeft, 0);
        eq("real FR shut", b.doorFrontRight, 0);
        eq("real RL shut", b.doorRearLeft, 0);
        eq("real RR shut", b.doorRearRight, 0);
        eq("real boot shut", b.boot, 0);
        eq("real handbrake bit", b.handbrake, 0);
        eq("real rpm untouched", b.rpm, U);

        // Real: head unit -> box request for 0x41 sub 0x02. Valid frame, not data.
        byte[] req = bytes(0x2E, 0x90, 0x02, 0x41, 0x02, 0x2A);
        fs = HcnParse.frames(req);
        eq("request parses", fs.size(), 1);
        eq("request type", fs.get(0).type, 0x90);
        eq("request ignored", HcnParse.apply(fs.get(0), new HcnParse.Body()) ? 1 : 0, 0);

        // Checksum arithmetic on both real frames.
        eq("chk 41", HcnParse.checksum(0x41, 6, new int[] { 1, 0, 0, 0, 0x20, 0x40 }, 0, 6), 0x57);
        eq("chk 90", HcnParse.checksum(0x90, 2, new int[] { 0x41, 0x02 }, 0, 2), 0x2A);

        // Bad checksum: dropped, not trusted.
        eq("bad chk", HcnParse.frames(bytes(0x2E, 0x41, 0x06, 0x01, 0x00, 0x00, 0x00, 0x20, 0x40, 0x58)).size(), 0);

        // A stream: two frames with the head unit's FF ACKs in between, then a truncated one.
        byte[] stream = cat(doors, bytes(0xFF), req, bytes(0xFF, 0x2E, 0x41, 0x06, 0x01));
        eq("stream frames", HcnParse.frames(stream).size(), 2);

        // Constructed: every door bit on its own.
        String[] names = { "FL", "FR", "RL", "RR", "boot", "handbrake", "washer", "belt" };
        for (int bit = 0; bit < 8; bit++) {
            HcnParse.Body x = new HcnParse.Body();
            HcnParse.apply(HcnParse.frames(frame(0x41, 0x01, 1 << bit, 0, 0, 0, 0)).get(0), x);
            int[] got = { x.doorFrontLeft, x.doorFrontRight, x.doorRearLeft, x.doorRearRight,
                    x.boot, x.handbrake, x.washerLow, x.beltUnfastened };
            for (int i = 0; i < 8; i++) eq("bit " + bit + " -> " + names[i], got[i], i == bit ? 1 : 0);
        }

        // Constructed 0x41 sub 0x02: 2350 rpm, 87.65 km/h, 14.12 V, -3.5 C,
        // 123456 km, 31 L.
        byte[] drive = frame(0x41, 0x02,
                0x09, 0x2E,        // rpm 2350
                0x22, 0x3D,        // 8765 -> 87.65 km/h
                0x05, 0x84,        // 1412 -> 14.12 V
                0xFF, 0xDD,        // -35 -> -3.5 C
                0x01, 0xE2, 0x40,  // 123456 km
                0x1F);             // 31 L
        b = new HcnParse.Body();
        fs = HcnParse.frames(drive);
        eq("drive checked", fs.get(0).checked ? 1 : 0, 1);
        eq("drive understood", HcnParse.apply(fs.get(0), b) ? 1 : 0, 1);
        eq("rpm", b.rpm, 2350);
        eq("speed", b.speedCentiKph, 8765);
        eq("battery", b.batteryCentiV, 1412);
        eq("outside", b.outsideDeciC, -35);
        eq("odo", b.odometerKm, 123456);
        eq("fuel L", b.fuelLitres, 31);
        eq("doors untouched", b.doorFrontLeft, U);
        eq("fuel %", HcnParse.fuelPercent(b.fuelLitres, HcnParse.TANK_LITRES_POLO_6R), 69);
        eq("fuel % clamps", HcnParse.fuelPercent(60, 45), 100);
        eq("fuel % unknown", HcnParse.fuelPercent(U, 45), U);

        // Outside temp out of the sensor's range stays unknown.
        b = new HcnParse.Body();
        HcnParse.apply(HcnParse.frames(frame(0x41, 0x02, 0, 0, 0, 0, 0, 0, 0x7F, 0xFF, 0, 0, 0, 0)).get(0), b);
        eq("outside junk", b.outsideDeciC, U);

        // Short driving frame: refused rather than read past the end.
        eq("short drive", HcnParse.apply(HcnParse.frames(frame(0x41, 0x02, 0x09, 0x2E)).get(0),
                new HcnParse.Body()) ? 1 : 0, 0);

        // Warnings sub 0x03.
        b = new HcnParse.Body();
        HcnParse.apply(HcnParse.frames(frame(0x41, 0x03, 0x80)).get(0), b);
        eq("fuel low", b.fuelLow, 1);
        eq("battery ok", b.batteryLow, 0);

        // The shape HCN's own decoders see: no 2E, no checksum.
        fs = HcnParse.frames(bytes(0x41, 0x02, 0x01, 0x01));
        eq("stripped one", fs.size(), 1);
        eq("stripped unchecked", fs.get(0).checked ? 1 : 0, 0);
        b = new HcnParse.Body();
        HcnParse.apply(fs.get(0), b);
        eq("stripped FL open", b.doorFrontLeft, 1);
        eq("stripped too short", HcnParse.frames(bytes(0x41, 0x06, 0x01)).size(), 0);

        // Other real types seen on the wire (backlight 0x14, 0x37): framed fine, not decoded.
        eq("0x14 ignored", HcnParse.apply(HcnParse.frames(frame(0x14, 0x80)).get(0), new HcnParse.Body()) ? 1 : 0, 0);
        eq("0x37 ignored", HcnParse.apply(HcnParse.frames(frame(0x37, 0x01, 0x02)).get(0), new HcnParse.Body()) ? 1 : 0, 0);

        // String payloads.
        eq("hex spaced", HcnParse.hex("2E 41 06 01 00 00 00 20 40 57").length, 10);
        eq("hex 0x", HcnParse.hex("0x2E,0x90,0x02,0x41,0x02,0x2A")[1] & 0xFF, 0x90);
        eq("hex junk", HcnParse.hex("{\"door\":1}") == null ? 1 : 0, 1);
        eq("hex odd", HcnParse.hex("2E4") == null ? 1 : 0, 1);
        eq("hexOf", HcnParse.hexOf(req).equals("2E 90 02 41 02 2A") ? 1 : 0, 1);

        // Door properties and gear setting.
        eq("prop shut", HcnParse.doorProp("1"), 0);
        eq("prop open", HcnParse.doorProp("2"), 1);
        eq("prop never", HcnParse.doorProp("0"), U);
        eq("prop blocked", HcnParse.doorProp(""), U);
        eq("prop null", HcnParse.doorProp(null), U);
        eq("gear R", HcnParse.reverseFromGear(102), 1);
        eq("gear P", HcnParse.reverseFromGear(101), 0);
        eq("gear 3", HcnParse.reverseFromGear(3), 0);
        eq("gear none", HcnParse.reverseFromGear(-1), U);

        if (failures > 0) {
            System.out.println(failures + " HcnParse test(s) FAILED");
            System.exit(1);
        }
        System.out.println("HcnParse: all tests passed");
    }

    /** A framed, checksummed Raise frame: 2E type len data chk. */
    private static byte[] frame(int type, int... data) {
        byte[] out = new byte[data.length + 4];
        out[0] = 0x2E;
        out[1] = (byte) type;
        out[2] = (byte) data.length;
        for (int i = 0; i < data.length; i++) out[3 + i] = (byte) data[i];
        out[out.length - 1] = (byte) HcnParse.checksum(type, data.length, data, 0, data.length);
        return out;
    }

    private static byte[] bytes(int... v) {
        byte[] out = new byte[v.length];
        for (int i = 0; i < v.length; i++) out[i] = (byte) v[i];
        return out;
    }

    private static byte[] cat(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) n += p.length;
        byte[] out = new byte[n];
        int at = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, at, p.length);
            at += p.length;
        }
        return out;
    }

    private static void eq(String name, int got, int want) {
        if (got != want) {
            failures++;
            System.out.println("FAIL " + name + ": got " + got + " want " + want);
        }
    }
}
