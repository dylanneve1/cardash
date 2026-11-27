import ie.claudius.cardash.bt.BtDevices;

import java.util.Arrays;
import java.util.List;

/** Plain-JVM checks for the Bluetooth screen's name heuristics and list logic — run with test.sh. */
public class BtDevicesTest {

    private static int failures = 0;

    public static void main(String[] args) {
        for (String yes : new String[] { "OBDII", "obd2", "OBD", "ELM327", "elm 327 v1.5", "MyELM327",
                "ELM-BT", "V-LINK", "Android-Vlink", "IOS-Vlink", "VLINKER MC", "vgate iCar", "iCar Pro",
                "iCarPro BLE", "iCar2", "KONNWEI KW902", "Veepeak OBDCheck", "VEEPEAK" }) {
            check("obd: " + yes, BtDevices.looksLikeObd(yes));
        }
        for (String no : new String[] { null, "", "Jey's Phone", "Helmet H7", "Anselm", "Elmo speaker",
                "MusiCar", "Icarus", "TV Link", "Car Kit", "JBL Flip" }) {
            check("not obd: " + no, !BtDevices.looksLikeObd(no));
        }

        // Dongle choice: a chosen address wins over any name; without one, names; never guess.
        List<String> addrs = Arrays.asList("AA:00:00:00:00:01", "AA:00:00:00:00:02", "AA:00:00:00:00:03");
        List<String> names = Arrays.asList("Phone", "OBDII", null);
        eq("no choice: by name", BtDevices.pickDongle(null, addrs, names), 1);
        eq("empty choice: by name", BtDevices.pickDongle("", addrs, names), 1);
        eq("chosen, unnamed", BtDevices.pickDongle("aa:00:00:00:00:03", addrs, names), 2);
        eq("chosen beats name", BtDevices.pickDongle("AA:00:00:00:00:01", addrs, names), 0);
        eq("chosen not paired: no fallback", BtDevices.pickDongle("AA:00:00:00:00:09", addrs, names), -1);
        eq("nothing matches", BtDevices.pickDongle(null, addrs, Arrays.asList("a", "b", null)), -1);

        // Merge by address: types OR, a name survives a nameless report, RSSI latest and best.
        BtDevices d = new BtDevices();
        d.found("aa:bb:cc:dd:ee:01", "OBDII", BtDevices.CLASSIC, -70);
        d.found("AA:BB:CC:DD:EE:01", null, BtDevices.LE, -60);
        d.found("AA:BB:CC:DD:EE:01", "  ", BtDevices.UNKNOWN, -80);
        eq("merged to one", d.nearby().size(), 1);
        BtDevices.Device m = d.get("aa:bb:cc:dd:ee:01");
        eq("dual", m.type, BtDevices.DUAL);
        eq("latest rssi", m.rssi, -80);
        eq("best rssi", m.bestRssi, -60);
        check("name kept", "OBDII".equals(m.name));

        // Nearby order: likely dongles, named, then strongest; ties by address.
        d.found("00:00:00:00:00:0A", null, BtDevices.LE, -30);
        d.found("00:00:00:00:00:0B", "Speaker", BtDevices.CLASSIC, -50);
        d.found("00:00:00:00:00:0C", "Watch", BtDevices.LE, -40);
        d.found("00:00:00:00:00:0D", "Mystery", BtDevices.LE, BtDevices.NO_RSSI);
        order("nearby", d.nearby(), "AA:BB:CC:DD:EE:01", "00:00:00:00:00:0C", "00:00:00:00:00:0B",
                "00:00:00:00:00:0D", "00:00:00:00:00:0A");

        // Paired devices leave "nearby", keep their signal, and the chosen one comes first.
        d.beginBonded();
        d.bonded("00:00:00:00:00:0B", "Speaker", BtDevices.CLASSIC);
        d.bonded("AA:BB:CC:DD:EE:01", null, BtDevices.UNKNOWN);
        d.bonded("11:11:11:11:11:11", "Alpha", BtDevices.CLASSIC);
        d.endBonded();
        eq("nearby without paired", d.nearby().size(), 3);
        order("paired", d.paired(null), "AA:BB:CC:DD:EE:01", "11:11:11:11:11:11", "00:00:00:00:00:0B");
        order("chosen first", d.paired("00:00:00:00:00:0b"), "00:00:00:00:00:0B", "AA:BB:CC:DD:EE:01",
                "11:11:11:11:11:11");
        eq("paired keeps rssi", d.get("00:00:00:00:00:0B").rssi, -50);

        // Unpaired: a device the scan heard goes back to nearby, one it didn't disappears.
        d.beginBonded();
        d.bonded("AA:BB:CC:DD:EE:01", null, BtDevices.UNKNOWN);
        d.endBonded();
        eq("heard goes back", d.nearby().size(), 4);
        check("unheard dropped", d.get("11:11:11:11:11:11") == null);

        d.clearScan();
        eq("new scan: nearby empty", d.nearby().size(), 0);
        eq("new scan: paired stays", d.paired(null).size(), 1);
        eq("new scan: rssi forgotten", d.get("AA:BB:CC:DD:EE:01").rssi, BtDevices.NO_RSSI);

        check("wrong pin text", BtDevices.bondFailure(1).contains("PIN"));
        check("unknown reason", BtDevices.bondFailure(0) == null);

        if (failures > 0) throw new AssertionError(failures + " failures");
        System.out.println("BtDevicesTest: OK");
    }

    private static void order(String what, List<BtDevices.Device> got, String... want) {
        StringBuilder g = new StringBuilder();
        for (BtDevices.Device x : got) g.append(x.address).append(' ');
        StringBuilder w = new StringBuilder();
        for (String s : want) w.append(s).append(' ');
        if (!g.toString().equals(w.toString())) {
            failures++;
            System.out.println("FAIL " + what + ": " + g + "!= " + w);
        }
    }

    private static void eq(String what, int got, int want) {
        if (got != want) {
            failures++;
            System.out.println("FAIL " + what + ": " + got + " != " + want);
        }
    }

    private static void check(String what, boolean ok) {
        if (!ok) {
            failures++;
            System.out.println("FAIL " + what);
        }
    }
}
