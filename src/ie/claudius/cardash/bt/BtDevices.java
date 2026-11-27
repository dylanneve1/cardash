package ie.claudius.cardash.bt;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Android-free half of the Bluetooth screen: which names look like an OBD dongle, which paired
 * device {@code ObdSource} should talk to, and the merged, sorted device list built from the paired
 * set, classic discovery and the BLE scan. No framework imports, so test.sh runs it on a plain JVM.
 */
public final class BtDevices {

    /** Same values as BluetoothDevice.DEVICE_TYPE_*; they OR together (classic | LE = dual). */
    public static final int UNKNOWN = 0, CLASSIC = 1, LE = 2, DUAL = 3;
    public static final int NO_RSSI = Integer.MIN_VALUE;

    /** OBD / ELM327 / V-LINK / Vgate / iCar / Konnwei / Veepeak. "ELM" and "iCar" must start a word. */
    private static final Pattern OBD_NAME = Pattern.compile(
            "OBD|ELM[ _-]?327|(?<![A-Z])ELM(?![A-Z])|(?<![A-Z])V[ _-]?LINK|VGATE"
                    + "|(?<![A-Z])ICAR(?:PRO)?(?![A-Z])|KONNWEI|VEEPEAK");

    public static final class Device {
        public final String address;
        public String name;
        public int type = UNKNOWN;
        /** Latest signal strength, for display. */
        public int rssi = NO_RSSI;
        /** Strongest seen this scan, for sorting: it only rises, so rows don't keep swapping. */
        public int bestRssi = NO_RSSI;
        public boolean bonded;
        /** Heard by the current scan. */
        public boolean seen;

        Device(String address) {
            this.address = address;
        }

        public boolean looksLikeObd() {
            return BtDevices.looksLikeObd(name);
        }
    }

    private final Map<String, Device> devices = new LinkedHashMap<>();

    public static boolean looksLikeObd(String name) {
        return name != null && OBD_NAME.matcher(name.toUpperCase(Locale.US)).find();
    }

    /**
     * Which paired device is the dongle. With a chosen address only that device will do, whatever it
     * is called; with none, the first one whose name looks like an OBD dongle. -1 when neither.
     */
    public static int pickDongle(String chosen, List<String> addresses, List<String> names) {
        boolean choice = chosen != null && !chosen.isEmpty();
        for (int i = 0; i < addresses.size(); i++) {
            if (choice ? chosen.equalsIgnoreCase(addresses.get(i)) : looksLikeObd(names.get(i))) return i;
        }
        return -1;
    }

    private Device entry(String address) {
        String key = address.toUpperCase(Locale.US);
        Device d = devices.get(key);
        if (d == null) {
            d = new Device(key);
            devices.put(key, d);
        }
        return d;
    }

    private static void describe(Device d, String name, int type) {
        // A later report without a name (common in BLE advertisements) must not erase one.
        if (name != null && !name.trim().isEmpty()) d.name = name.trim();
        d.type |= type & DUAL;
    }

    /** One discovery or BLE scan result. */
    public Device found(String address, String name, int type, int rssi) {
        Device d = entry(address);
        describe(d, name, type);
        d.seen = true;
        if (rssi != NO_RSSI) {
            d.rssi = rssi;
            d.bestRssi = Math.max(d.bestRssi, rssi);
        }
        return d;
    }

    /** Mark everything unpaired before re-reading the system's paired set. */
    public void beginBonded() {
        for (Device d : devices.values()) d.bonded = false;
    }

    public void bonded(String address, String name, int type) {
        Device d = entry(address);
        describe(d, name, type);
        d.bonded = true;
    }

    /** Drop rows that are neither paired nor heard by a scan (e.g. just unpaired). */
    public void endBonded() {
        for (Iterator<Device> it = devices.values().iterator(); it.hasNext();) {
            Device d = it.next();
            if (!d.bonded && !d.seen) it.remove();
        }
    }

    /** A new scan: forget what the last one heard; paired devices stay. */
    public void clearScan() {
        for (Device d : devices.values()) {
            d.seen = false;
            d.rssi = NO_RSSI;
            d.bestRssi = NO_RSSI;
        }
        endBonded();
    }

    public Device get(String address) {
        return address == null ? null : devices.get(address.toUpperCase(Locale.US));
    }

    /** Paired devices: the chosen dongle, then likely dongles, then by name. */
    public List<Device> paired(final String chosen) {
        List<Device> out = new ArrayList<>();
        for (Device d : devices.values()) if (d.bonded) out.add(d);
        Collections.sort(out, new Comparator<Device>() {
            @Override
            public int compare(Device a, Device b) {
                int c = first(a.address.equalsIgnoreCase(chosen), b.address.equalsIgnoreCase(chosen));
                if (c == 0) c = first(a.looksLikeObd(), b.looksLikeObd());
                if (c == 0) c = first(a.name != null, b.name != null);
                if (c == 0 && a.name != null) c = a.name.compareToIgnoreCase(b.name);
                return c != 0 ? c : a.address.compareTo(b.address);
            }
        });
        return out;
    }

    /** Unpaired scan results: likely dongles, then named devices, then the strongest signal. */
    public List<Device> nearby() {
        List<Device> out = new ArrayList<>();
        for (Device d : devices.values()) if (d.seen && !d.bonded) out.add(d);
        Collections.sort(out, new Comparator<Device>() {
            @Override
            public int compare(Device a, Device b) {
                int c = first(a.looksLikeObd(), b.looksLikeObd());
                if (c == 0) c = first(a.name != null, b.name != null);
                if (c == 0) c = a.bestRssi < b.bestRssi ? 1 : a.bestRssi > b.bestRssi ? -1 : 0;
                return c != 0 ? c : a.address.compareTo(b.address);
            }
        });
        return out;
    }

    private static int first(boolean a, boolean b) {
        return a == b ? 0 : a ? -1 : 1;
    }

    /**
     * BluetoothDevice's hidden "why did pairing end" extra, as far as it helps someone in a car.
     * Null for codes not worth explaining.
     */
    public static String bondFailure(int reason) {
        switch (reason) {
            case 1: return "wrong PIN or authentication failed";
            case 2: return "the device rejected it";
            case 3: return "cancelled";
            case 4: return "the device did not answer (powered and in range?)";
            case 5: return "a search was still running";
            case 6: return "timed out";
            case 7: return "too many attempts, wait a moment";
            case 8: return "the device cancelled it";
            default: return null;
        }
    }
}
