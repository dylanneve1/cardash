package ie.claudius.cardash.diag;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The read-only probes behind the Diagnostics screen. Each returns
 * plain text so the same output goes on screen and into the export.
 */
public final class Probe {

    /**
     * Packages that tend to be the car-integration layer on these ROMs.
     * Kept tight on purpose: every false positive costs a manifest
     * parse, and the sweep catches what a name doesn't.
     */
    private static final Pattern VENDORISH = Pattern.compile(
            "jancar|canbus|canbox|carservice|carinfo|(^|\\.)car(service|\\.|$)|(^|\\.)mcu(\\.|$)|"
                    + "roadrover|txznet|(^|\\.)syu(\\.|$)|(^|\\.)fyt(\\.|$)|(^|\\.)mtc(\\.|$)|"
                    + "autochips|zlink|carbit|steering|(^|\\.)swc(\\.|$)|klipad|glocal|joying|dasaita",
            Pattern.CASE_INSENSITIVE);

    private Probe() {}

    // ---- 4.1 unit identity ----------------------------------------------------

    public static String unit() {
        StringBuilder sb = new StringBuilder();
        line(sb, "fingerprint", Build.FINGERPRINT);
        line(sb, "display", Build.DISPLAY);
        line(sb, "board / hardware", Build.BOARD + " / " + Build.HARDWARE);
        line(sb, "manufacturer / model", Build.MANUFACTURER + " / " + Build.MODEL);
        line(sb, "device / product", Build.DEVICE + " / " + Build.PRODUCT);
        // Side by side on purpose: vendors overstate RELEASE in the build
        // props; SDK_INT is what the framework actually is.
        line(sb, "claims Android", Build.VERSION.RELEASE);
        line(sb, "actual API level", String.valueOf(Build.VERSION.SDK_INT)
                + " (" + androidName(Build.VERSION.SDK_INT) + ")");
        line(sb, "security patch", Build.VERSION.SECURITY_PATCH);
        line(sb, "ABIs", join(Build.SUPPORTED_ABIS));
        line(sb, "64-bit ABIs", Build.SUPPORTED_64_BIT_ABIS.length == 0
                ? "none — 32-bit only" : join(Build.SUPPORTED_64_BIT_ABIS));
        return sb.toString();
    }

    private static String androidName(int sdk) {
        switch (sdk) {
            case 30: return "Android 11";
            case 31: case 32: return "Android 12";
            case 33: return "Android 13";
            case 34: return "Android 14";
            case 35: return "Android 15";
            case 36: return "Android 16";
            default: return sdk < 30 ? "Android 10 or older" : "Android 16+";
        }
    }

    /**
     * Installed packages whose <em>name</em> looks like car / MCU / radio
     * integration. Fast, and wrong whenever a vendor renames things —
     * which they do constantly — so {@link #sweep} is the real answer
     * and this is the first-paint approximation.
     */
    public static List<String> vendorPackages(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        List<String> out = new ArrayList<>();
        for (PackageInfo pi : pm.getInstalledPackages(0)) {
            if (pi.packageName.equals(ctx.getPackageName())) continue;
            if (VENDORISH.matcher(pi.packageName).find()) out.add(pi.packageName);
        }
        Collections.sort(out);
        return out;
    }

    /** Intent actions that smell like vehicle integration, wherever they live. */
    static final Pattern CARISH_ACTION = Pattern.compile(
            "car|canbus|canbox|(^|[._])can([._]|$)|door|(^|[._])acc([._]|$)|mcu|reverse|"
                    + "backcar|steer|swc|wheel|illum|handbrake|(^|[._])park|radar|vehicle|"
                    + "(^|[._])key(event|_event)?([._]|$)",
            Pattern.CASE_INSENSITIVE);

    /** One package that declares car-ish actions, and which ones. */
    public static final class Hit {
        public final String pkg;
        public final Set<String> actions = new LinkedHashSet<>();

        Hit(String pkg) {
            this.pkg = pkg;
        }
    }

    /**
     * Read every installed package's manifest and keep the ones whose
     * receivers or services filter on car-ish actions. Slow enough to
     * want a background thread (a second or two for ~150 packages), and
     * the only way to find the car service on a ROM that has renamed it.
     * Framework packages are skipped: they declare everything.
     */
    public static List<Hit> sweep(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        List<Hit> out = new ArrayList<>();
        for (PackageInfo pi : pm.getInstalledPackages(0)) {
            String pkg = pi.packageName;
            if (pkg.equals(ctx.getPackageName()) || "android".equals(pkg)
                    || pkg.startsWith("com.android.") || pkg.startsWith("com.google.")) {
                continue;
            }
            Hit hit = null;
            try {
                for (ManifestReader.Component c : ManifestReader.read(ctx, pkg)) {
                    if (!"receiver".equals(c.kind) && !"service".equals(c.kind)) continue;
                    for (String a : c.actions) {
                        if (a == null || !CARISH_ACTION.matcher(a).find()) continue;
                        if (hit == null) hit = new Hit(pkg);
                        hit.actions.add(a);
                    }
                }
            } catch (Exception ignored) {
                // unreadable APK (split, or an odd install); nothing to learn
            }
            if (hit != null) out.add(hit);
        }
        Collections.sort(out, new java.util.Comparator<Hit>() {
            @Override
            public int compare(Hit a, Hit b) {
                return a.pkg.compareTo(b.pkg);
            }
        });
        return out;
    }

    /** Remember what the sweep found, so the vehicle source can use it without sweeping. */
    public static void rememberSweep(Context ctx, List<Hit> hits) {
        StringBuilder sb = new StringBuilder();
        for (Hit h : hits) sb.append(h.pkg).append(',');
        ctx.getSharedPreferences("cardash", Context.MODE_PRIVATE).edit()
                .putString("diag_vendor_pkgs", sb.toString()).apply();
    }

    public static List<String> rememberedSweep(Context ctx) {
        String raw = ctx.getSharedPreferences("cardash", Context.MODE_PRIVATE)
                .getString("diag_vendor_pkgs", "");
        List<String> out = new ArrayList<>();
        for (String p : raw.split(",")) if (!p.isEmpty()) out.add(p);
        return out;
    }

    /** Name heuristic ∪ sweep, deduplicated and sorted. */
    public static List<String> merge(List<String> byName, List<Hit> swept) {
        Set<String> all = new java.util.TreeSet<>(byName);
        for (Hit h : swept) all.add(h.pkg);
        return new ArrayList<>(all);
    }

    public static String describePackage(Context ctx, String pkg) {
        PackageManager pm = ctx.getPackageManager();
        try {
            PackageInfo pi = pm.getPackageInfo(pkg, 0);
            ApplicationInfo ai = pi.applicationInfo;
            boolean system = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            return String.format(Locale.US, "%s  v%s (%d)%s%s", pkg, pi.versionName,
                    pi.versionCode, system ? "  [system]" : "",
                    ai.enabled ? "" : "  [disabled]");
        } catch (Exception e) {
            return pkg + "  (" + e.getMessage() + ")";
        }
    }

    // ---- 4.2 vendor surface ---------------------------------------------------

    /** Manifest components of a package, flattened to copyable text. */
    public static String surface(Context ctx, String pkg) {
        StringBuilder sb = new StringBuilder();
        try {
            List<ManifestReader.Component> comps = ManifestReader.read(ctx, pkg);
            int shown = 0;
            for (ManifestReader.Component c : comps) {
                // Activities are noise for this purpose unless they filter
                // on something other than launching.
                if ("activity".equals(c.kind) || "activity-alias".equals(c.kind)) {
                    boolean interesting = false;
                    for (String a : c.actions) {
                        if (!"android.intent.action.MAIN".equals(a)) interesting = true;
                    }
                    if (!interesting) continue;
                }
                shown++;
                sb.append(c.kind).append("  ").append(c.name)
                        .append("  exported=").append(c.exported);
                if (c.permission != null) sb.append("  permission=").append(c.permission);
                if (c.authorities != null) sb.append("\n    authorities=").append(c.authorities);
                for (String a : c.actions) sb.append("\n    action   ").append(a);
                for (String a : c.categories) sb.append("\n    category ").append(a);
                sb.append('\n');
            }
            if (shown == 0) sb.append("(no receivers, services or providers declared)\n");
        } catch (Exception e) {
            sb.append("manifest unreadable: ").append(e).append('\n');
        }
        return sb.toString();
    }

    /**
     * Every action any vendor-ish package declares a receiver or service
     * for. Receivers are what a package listens to — so across the whole
     * vendor suite, this is the set of broadcasts that fly between them,
     * which is exactly what to sniff.
     */
    public static Set<String> declaredActions(Context ctx, List<String> pkgs) {
        Set<String> out = new LinkedHashSet<>();
        for (String pkg : pkgs) {
            try {
                for (ManifestReader.Component c : ManifestReader.read(ctx, pkg)) {
                    if ("receiver".equals(c.kind) || "service".equals(c.kind)) {
                        for (String a : c.actions) if (a != null) out.add(a);
                    }
                }
            } catch (Exception ignored) {
                // unreadable APK; the surface panel reports it
            }
        }
        // The framework's own noise would drown a live log.
        out.remove("android.intent.action.BOOT_COMPLETED");
        out.remove("android.intent.action.TIME_TICK");
        out.remove("android.intent.action.SCREEN_ON");
        out.remove("android.intent.action.SCREEN_OFF");
        return out;
    }

    // ---- wireless Android Auto ---------------------------------------------------

    /** Projection apps worth knowing the version of. */
    private static final String[][] PROJECTION = {
            { "com.google.android.projection.gearhead", "Android Auto" },
            { "com.google.android.gms", "Google Play services" },
            { "ca.yyx.hu", "Headunit Reloaded / Revived" },
            { "info.anodsplace.headunit", "Headunit Reloaded (alt id)" },
            { "com.zjinnova.zlink", "ZLink" },
            { "com.zjinnova.zlink5", "ZLink 5" },
            { "com.autokit.aauto", "AutoKit" },
            { "com.jancar.aauto", "Jancar Android Auto" },
            { "com.jancar.carplay", "Jancar CarPlay" },
            { "com.carlinkit.autokit", "Carlinkit AutoKit" },
    };

    /**
     * Wireless Android Auto has a hard hardware list, and cheap RK3326
     * units fail it more often than not. Every item is queryable from an
     * app on API 30, so this answers "settings problem, app problem, or
     * a wall" in one panel — before anyone spends an evening on it.
     */
    public static String wirelessAndroidAuto(Context ctx) {
        StringBuilder sb = new StringBuilder();
        PackageManager pm = ctx.getPackageManager();
        android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager)
                ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);

        sb.append("requirements\n");
        if (wm == null) {
            sb.append("  no Wi-Fi service — that is the wall\n");
        } else {
            try {
                check(sb, "5 GHz Wi-Fi", wm.is5GHzBandSupported(),
                        "the projection link is Wi-Fi Direct on 5 GHz; 2.4-only is a hard no");
                check(sb, "Wi-Fi Direct (P2P) feature", pm.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT),
                        "no P2P, no wireless AA");
                check(sb, "P2P reported by Wi-Fi service", wm.isP2pSupported(), null);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    check(sb, "STA + AP concurrency", wm.isStaApConcurrencySupported(),
                            "must stay on the phone's hotspot/home Wi-Fi while running the Direct group");
                    line(sb, "  6 GHz", String.valueOf(wm.is6GHzBandSupported()));
                } else {
                    line(sb, "  STA + AP concurrency", "not queryable below API 30");
                }
                line(sb, "  Wi-Fi state", wifiState(wm.getWifiState()));
                android.net.wifi.WifiInfo info = wm.getConnectionInfo();
                if (info != null && info.getNetworkId() != -1) {
                    int f = info.getFrequency();
                    line(sb, "  connected to", info.getSSID() + "  " + f + " MHz ("
                            + (f > 5000 ? "5 GHz" : f > 2000 ? "2.4 GHz" : "?") + ")  rssi " + info.getRssi());
                } else {
                    line(sb, "  connected to", "nothing");
                }
            } catch (SecurityException e) {
                sb.append("  Wi-Fi state not readable: ").append(e.getMessage()).append('\n');
            }
        }
        android.bluetooth.BluetoothAdapter bt = android.bluetooth.BluetoothAdapter.getDefaultAdapter();
        if (bt == null) {
            check(sb, "Bluetooth for the handshake", false, "the phone gets SSID and credentials over RFCOMM first");
        } else {
            try {
                check(sb, "Bluetooth for the handshake", true, null);
                line(sb, "  Bluetooth", (bt.isEnabled() ? "on" : "OFF") + ", name \"" + bt.getName()
                        + "\", " + bt.getBondedDevices().size() + " paired");
            } catch (SecurityException e) {
                line(sb, "  Bluetooth", "permission refused");
            }
        }

        sb.append("\nnetwork interfaces\n");
        File net = new File("/sys/class/net");
        File[] ifs = net.listFiles();
        if (ifs == null) {
            sb.append("  /sys/class/net not listable\n");
        } else {
            List<String> names = new ArrayList<>();
            for (File f : ifs) names.add(f.getName());
            Collections.sort(names);
            line(sb, "  present", join(names.toArray(new String[0])));
            boolean p2p = false;
            for (String n : names) if (n.startsWith("p2p")) p2p = true;
            line(sb, "  p2p interface", p2p ? "yes" : "none visible (may appear only while a group is up)");
        }

        sb.append("\nprojection apps\n");
        int found = 0;
        for (String[] p : PROJECTION) {
            try {
                PackageInfo pi = pm.getPackageInfo(p[0], 0);
                found++;
                line(sb, "  " + p[1], p[0] + "  v" + pi.versionName + " (" + pi.versionCode + ")"
                        + (pi.applicationInfo.enabled ? "" : "  [disabled]"));
            } catch (PackageManager.NameNotFoundException ignored) {
                // not installed
            }
        }
        if (found == 0) sb.append("  none of the known ones installed\n");
        sb.append("\nBuilt-in (Zlink/AutoKit/Jancar) and Headunit Reloaded are different exercises: "
                + "HUR's wireless mode has its own handshake and logs and is usually configuration.\n");
        return sb.toString();
    }

    private static void check(StringBuilder sb, String what, boolean ok, String why) {
        sb.append(ok ? "  [ok]   " : "  [FAIL] ").append(what);
        if (!ok && why != null) sb.append(" — ").append(why);
        sb.append('\n');
    }

    private static String wifiState(int s) {
        switch (s) {
            case android.net.wifi.WifiManager.WIFI_STATE_ENABLED: return "enabled";
            case android.net.wifi.WifiManager.WIFI_STATE_DISABLED: return "disabled";
            case android.net.wifi.WifiManager.WIFI_STATE_ENABLING: return "enabling";
            case android.net.wifi.WifiManager.WIFI_STATE_DISABLING: return "disabling";
            default: return "unknown";
        }
    }

    // ---- 4.7 serial ports -----------------------------------------------------

    public static String serialPorts() {
        StringBuilder sb = new StringBuilder();
        // `ls -l` gives owner and mode bits, which is what a seller asks
        // for; fall back to probing names if /dev can't be listed.
        try {
            Process p = Runtime.getRuntime().exec(new String[] {
                    "sh", "-c", "ls -l /dev 2>/dev/null | grep -iE 'tty(S|ACM|USB|HS|XRUSB|GS|MT)' " });
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String l;
            while ((l = r.readLine()) != null) sb.append(l).append('\n');
            p.waitFor();
        } catch (Exception e) {
            sb.append("ls failed: ").append(e).append('\n');
        }
        if (sb.length() == 0) {
            String[] names = { "ttyS0", "ttyS1", "ttyS2", "ttyS3", "ttyS4", "ttyS5",
                    "ttyACM0", "ttyUSB0", "ttyHS0", "ttyHS1", "ttyGS0", "ttyMT0", "ttyMT1" };
            for (String n : names) {
                File f = new File("/dev/" + n);
                if (f.exists()) {
                    sb.append(n).append(f.canRead() ? "  r" : "  -")
                            .append(f.canWrite() ? "w" : "-").append('\n');
                }
            }
            if (sb.length() == 0) sb.append("(none visible from this app)\n");
        }
        sb.append("\nAn unprivileged app cannot open these on a stock ROM; the point is knowing which UART the MCU uses.\n");
        return sb.toString();
    }

    // ---- helpers ----------------------------------------------------------------

    private static void line(StringBuilder sb, String k, String v) {
        sb.append(String.format(Locale.US, "%-22s %s%n", k, v));
    }

    private static String join(String[] a) {
        StringBuilder sb = new StringBuilder();
        for (String s : a) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(s);
        }
        return sb.toString();
    }
}
