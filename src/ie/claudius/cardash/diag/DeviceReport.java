package ie.claudius.cardash.diag;

import android.app.ActivityManager;
import android.bluetooth.BluetoothAdapter;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.FeatureInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.WindowManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Everything an unprivileged app can read about the unit's hardware
 * and ROM, as plain-text export sections (issue #15). Read-only; each
 * section is isolated so one blocked API never loses the others, and
 * anything gated by a permission says which one it needs.
 */
public final class DeviceReport {

    private DeviceReport() {}

    public static Map<String, String> sections(Context ctx) {
        Map<String, String> out = new LinkedHashMap<>();
        put(out, "SYSTEM FEATURES (PackageManager)", features(ctx));
        put(out, "BLUETOOTH CAPABILITIES", bluetooth(ctx));
        put(out, "DISPLAY", display(ctx));
        put(out, "CPU, RAM, STORAGE", cpuMemory(ctx));
        put(out, "SENSORS", sensors(ctx));
        put(out, "CAMERAS", cameras(ctx));
        put(out, "AUDIO OUTPUTS", audio(ctx));
        put(out, "VEHICLE/VENDOR SYSTEM PROPERTIES", properties());
        put(out, "VEHICLE-RELATED SETTINGS (Settings.System / Global)", settings(ctx));
        return out;
    }

    private static void put(Map<String, String> out, String title, Object body) {
        out.put(title, String.valueOf(body));
    }

    private interface Part {
        String run() throws Throwable;
    }

    /** Run one section; a throwing API becomes a one-line note. */
    private static String safe(Part p) {
        try {
            return p.run();
        } catch (Throwable t) {
            return "(failed: " + t + ")\n";
        }
    }

    private static void line(StringBuilder sb, String k, Object v) {
        sb.append(String.format(Locale.US, "%-26s %s%n", k, v));
    }

    // ---- features ------------------------------------------------------------------

    private static String features(final Context ctx) {
        return safe(new Part() {
            public String run() {
                FeatureInfo[] all = ctx.getPackageManager().getSystemAvailableFeatures();
                if (all == null || all.length == 0) return "(none reported)\n";
                TreeSet<String> names = new TreeSet<>();
                String gles = null;
                for (FeatureInfo f : all) {
                    if (f.name != null) {
                        names.add(f.version != 0 ? f.name + " (version " + f.version + ")" : f.name);
                    } else {
                        gles = "OpenGL ES " + f.getGlEsVersion();
                    }
                }
                StringBuilder sb = new StringBuilder();
                sb.append(names.size()).append(" features");
                if (gles != null) sb.append("; ").append(gles);
                sb.append('\n');
                for (String n : names) sb.append("  ").append(n).append('\n');
                return sb.toString();
            }
        });
    }

    // ---- bluetooth -----------------------------------------------------------------

    private static boolean granted(Context ctx, String perm) {
        if (Build.VERSION.SDK_INT < 23) return true; // install-time permissions
        return ctx.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED;
    }

    private static String bluetooth(final Context ctx) {
        return safe(new Part() {
            public String run() {
                PackageManager pm = ctx.getPackageManager();
                StringBuilder sb = new StringBuilder();
                line(sb, "feature bluetooth", pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH));
                line(sb, "feature bluetooth_le", pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE));
                BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
                if (a == null) {
                    sb.append("no Bluetooth adapter\n");
                    return sb.toString();
                }
                boolean s31 = Build.VERSION.SDK_INT >= 31;
                boolean connect = !s31 || granted(ctx, "android.permission.BLUETOOTH_CONNECT");
                String need = "needs permission android.permission.BLUETOOTH_CONNECT";
                line(sb, "enabled", a.isEnabled());
                line(sb, "adapter name", connect ? safeName(a) : need);
                line(sb, "LE scanner available", a.isEnabled()
                        ? String.valueOf(a.getBluetoothLeScanner() != null)
                        : "unknown (Bluetooth is off)");
                line(sb, "LE advertiser available", a.isEnabled()
                        ? String.valueOf(a.getBluetoothLeAdvertiser() != null)
                        : "unknown (Bluetooth is off)");
                if (Build.VERSION.SDK_INT >= 21) {
                    line(sb, "multiple advertisement", a.isMultipleAdvertisementSupported());
                    line(sb, "offloaded filtering", a.isOffloadedFilteringSupported());
                    line(sb, "offloaded scan batching", a.isOffloadedScanBatchingSupported());
                }
                if (Build.VERSION.SDK_INT >= 26) {
                    line(sb, "LE 2M PHY", a.isLe2MPhySupported());
                    line(sb, "LE coded PHY", a.isLeCodedPhySupported());
                    line(sb, "LE extended advertising", a.isLeExtendedAdvertisingSupported());
                    line(sb, "LE periodic advertising", a.isLePeriodicAdvertisingSupported());
                    line(sb, "LE max advert data length", a.getLeMaximumAdvertisingDataLength());
                }
                if (Build.VERSION.SDK_INT >= 33) {
                    line(sb, "LE audio", a.isLeAudioSupported());
                }
                sb.append("profile connection state (HEADSET/A2DP/GATT are what the adapter reports):\n");
                int[] ids = { 1, 2, 7, 8 };
                String[] names = { "HEADSET", "A2DP", "GATT", "GATT_SERVER" };
                for (int i = 0; i < ids.length; i++) {
                    String v;
                    try {
                        v = state(a.getProfileConnectionState(ids[i]));
                    } catch (SecurityException e) {
                        v = need;
                    } catch (Throwable t) {
                        v = "not queryable (" + t.getClass().getSimpleName() + ")";
                    }
                    line(sb, "  " + names[i], v);
                }
                return sb.toString();
            }
        });
    }

    private static String safeName(BluetoothAdapter a) {
        try {
            return a.getName();
        } catch (SecurityException e) {
            return "needs permission android.permission.BLUETOOTH_CONNECT";
        }
    }

    private static String state(int s) {
        switch (s) {
            case 0: return "disconnected";
            case 1: return "connecting";
            case 2: return "connected";
            case 3: return "disconnecting";
            default: return String.valueOf(s);
        }
    }

    // ---- display -------------------------------------------------------------------

    private static String display(final Context ctx) {
        return safe(new Part() {
            public String run() {
                WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
                Display d = wm.getDefaultDisplay();
                DisplayMetrics m = new DisplayMetrics();
                d.getRealMetrics(m);
                StringBuilder sb = new StringBuilder();
                line(sb, "real size", m.widthPixels + " x " + m.heightPixels + " px");
                line(sb, "density", m.densityDpi + " dpi (scale " + m.density + ")");
                line(sb, "physical dpi", String.format(Locale.US, "%.1f x %.1f", m.xdpi, m.ydpi));
                line(sb, "current refresh", String.format(Locale.US, "%.2f Hz", d.getRefreshRate()));
                if (Build.VERSION.SDK_INT >= 23) {
                    TreeSet<String> modes = new TreeSet<>();
                    for (Display.Mode mode : d.getSupportedModes()) {
                        modes.add(mode.getPhysicalWidth() + "x" + mode.getPhysicalHeight() + " @ "
                                + String.format(Locale.US, "%.2f", mode.getRefreshRate()) + " Hz");
                    }
                    line(sb, "supported modes", modes.size() + ": " + modes);
                } else {
                    float[] rates = d.getSupportedRefreshRates();
                    line(sb, "supported refresh rates", java.util.Arrays.toString(rates));
                }
                if (Build.VERSION.SDK_INT >= 26) line(sb, "wide colour gamut", d.isWideColorGamut());
                if (Build.VERSION.SDK_INT >= 24) {
                    Display.HdrCapabilities h = d.getHdrCapabilities();
                    line(sb, "HDR types", h == null || h.getSupportedHdrTypes().length == 0
                            ? "none" : java.util.Arrays.toString(h.getSupportedHdrTypes()));
                } else {
                    line(sb, "HDR types", "n/a below API 24");
                }
                return sb.toString();
            }
        });
    }

    // ---- cpu / ram / storage ----------------------------------------------------------

    private static String cpuMemory(final Context ctx) {
        return safe(new Part() {
            public String run() throws Exception {
                StringBuilder sb = new StringBuilder();
                line(sb, "cores (available)", Runtime.getRuntime().availableProcessors());
                line(sb, "ABIs", java.util.Arrays.toString(Build.SUPPORTED_ABIS));
                Map<String, String> cpu = cpuinfo();
                for (String k : new String[] { "Hardware", "Processor", "model name", "CPU implementer",
                        "CPU architecture", "CPU part", "Features" }) {
                    if (cpu.containsKey(k)) line(sb, "cpuinfo " + k, cpu.get(k));
                }
                if (!cpu.containsKey("Hardware")) line(sb, "cpuinfo Hardware", "(no Hardware line)");
                TreeMap<Integer, String> freq = new TreeMap<>();
                File[] cpus = new File("/sys/devices/system/cpu").listFiles();
                if (cpus != null) {
                    for (File c : cpus) {
                        if (!c.getName().matches("cpu\\d+")) continue;
                        String v = readLine(new File(c, "cpufreq/cpuinfo_max_freq"));
                        freq.put(Integer.parseInt(c.getName().substring(3)),
                                v == null ? "unreadable" : (Long.parseLong(v.trim()) / 1000) + " MHz");
                    }
                }
                line(sb, "max freq per core", freq.isEmpty() ? "(/sys not readable)" : freq.toString());
                ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
                ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
                am.getMemoryInfo(mi);
                line(sb, "RAM total", mb(mi.totalMem) + " (available now " + mb(mi.availMem) + ")");
                line(sb, "low-RAM device", am.isLowRamDevice());
                line(sb, "memory class", am.getMemoryClass() + " MB (large " + am.getLargeMemoryClass() + " MB)");
                line(sb, "storage /data", stat(Environment.getDataDirectory()));
                line(sb, "storage external", stat(Environment.getExternalStorageDirectory()));
                return sb.toString();
            }
        });
    }

    private static String stat(File f) {
        try {
            StatFs s = new StatFs(f.getPath());
            return mb(s.getTotalBytes()) + " total, " + mb(s.getAvailableBytes()) + " free";
        } catch (Throwable t) {
            return "unreadable (" + t.getClass().getSimpleName() + ")";
        }
    }

    private static String mb(long bytes) {
        return (bytes / (1024 * 1024)) + " MB";
    }

    private static Map<String, String> cpuinfo() {
        Map<String, String> m = new LinkedHashMap<>();
        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader("/proc/cpuinfo"));
            String l;
            while ((l = r.readLine()) != null) {
                int i = l.indexOf(':');
                if (i < 0) continue;
                String k = l.substring(0, i).trim();
                if (!m.containsKey(k)) m.put(k, l.substring(i + 1).trim()); // first core only
            }
        } catch (Exception e) {
            m.put("error", String.valueOf(e));
        } finally {
            closeQuietly(r);
        }
        return m;
    }

    private static String readLine(File f) {
        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader(f));
            return r.readLine();
        } catch (Exception e) {
            return null;
        } finally {
            closeQuietly(r);
        }
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c != null) try { c.close(); } catch (Exception ignored) { }
    }

    // ---- sensors / cameras / audio ---------------------------------------------------

    private static String sensors(final Context ctx) {
        return safe(new Part() {
            public String run() {
                SensorManager sm = (SensorManager) ctx.getSystemService(Context.SENSOR_SERVICE);
                List<Sensor> list = sm.getSensorList(Sensor.TYPE_ALL);
                if (list.isEmpty()) return "(no sensors)\n";
                StringBuilder sb = new StringBuilder();
                sb.append(list.size()).append(" sensors\n");
                for (Sensor s : list) {
                    sb.append("  ").append(s.getName()).append("  [")
                            .append(Build.VERSION.SDK_INT >= 20 ? s.getStringType() : "type " + s.getType())
                            .append("]  vendor=").append(s.getVendor()).append('\n');
                }
                return sb.toString();
            }
        });
    }

    private static String cameras(final Context ctx) {
        return safe(new Part() {
            public String run() throws Exception {
                PackageManager pm = ctx.getPackageManager();
                StringBuilder sb = new StringBuilder();
                line(sb, "feature camera", pm.hasSystemFeature(PackageManager.FEATURE_CAMERA));
                line(sb, "feature camera.front", pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_FRONT));
                line(sb, "feature camera.external", pm.hasSystemFeature("android.hardware.camera.external"));
                CameraManager cm = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
                String[] ids = cm.getCameraIdList();
                line(sb, "camera2 ids", ids.length);
                for (String id : ids) {
                    String facing = "unknown";
                    try {
                        Integer f = cm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
                        if (f != null) facing = f == 0 ? "front" : f == 1 ? "back" : f == 2 ? "external" : "facing " + f;
                    } catch (Exception e) {
                        facing = "unreadable (" + e.getClass().getSimpleName() + ")";
                    }
                    sb.append("  id ").append(id).append(": ").append(facing).append('\n');
                }
                return sb.toString();
            }
        });
    }

    private static String audio(final Context ctx) {
        return safe(new Part() {
            public String run() {
                if (Build.VERSION.SDK_INT < 23) return "(AudioManager.getDevices needs API 23)\n";
                AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
                AudioDeviceInfo[] outs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
                if (outs.length == 0) return "(no output devices)\n";
                StringBuilder sb = new StringBuilder();
                for (AudioDeviceInfo d : outs) {
                    sb.append("  ").append(audioType(d.getType())).append("  \"")
                            .append(d.getProductName()).append("\"  id=").append(d.getId()).append('\n');
                }
                return sb.toString();
            }
        });
    }

    private static String audioType(int t) {
        switch (t) {
            case 1: return "builtin earpiece";
            case 2: return "builtin speaker";
            case 3: return "wired headset";
            case 4: return "wired headphones";
            case 5: return "line analog";
            case 6: return "line digital";
            case 7: return "bluetooth SCO";
            case 8: return "bluetooth A2DP";
            case 9: return "HDMI";
            case 10: return "HDMI ARC";
            case 11: return "USB device";
            case 12: return "USB accessory";
            case 13: return "dock";
            case 14: return "FM";
            case 15: return "builtin mic";
            case 18: return "telephony";
            case 19: return "aux line";
            case 20: return "IP";
            case 21: return "bus";
            case 22: return "USB headset";
            case 23: return "hearing aid";
            case 24: return "builtin speaker safe";
            case 26: return "BLE headset";
            case 27: return "BLE speaker";
            default: return "type " + t;
        }
    }

    // ---- system properties -----------------------------------------------------------

    /** Keys worth asking about even when getprop output isn't readable. */
    private static final String[] CURATED_PROPS = {
            "ro.product.manufacturer", "ro.product.brand", "ro.product.board", "ro.board.platform",
            "ro.hardware", "ro.build.version.sdk", "ro.build.version.release", "ro.build.display.id",
            "ro.build.description", "ro.mediatek.platform", "ro.vendor.product.name",
            "ro.vendor.build.fingerprint", "ro.boot.hardware", "ro.boot.bootloader",
            "persist.vendor.canbox.type", "persist.vendor.canbox.protocol", "persist.sys.canbox",
            "persist.sys.can.type", "persist.sys.can.protocol", "ro.canbus.type", "ro.can.protocol",
            "ro.vendor.can.type", "ro.mcu.version", "ro.vendor.mcu.version", "persist.sys.mcu.version",
            "ro.sys.mcu", "ro.hcn.version", "ro.jancar.version", "ro.fyt.version", "ro.syu.version",
            "ro.car.model", "ro.vehicle.type", "persist.sys.car.model", "persist.vendor.car.model",
            "ro.config.car_mode", "ro.build.characteristics", "ro.hmi.version",
            "sys.acc.state", "persist.sys.acc", "sys.reverse.state", "persist.sys.reverse",
    };

    private static String properties() {
        return safe(new Part() {
            public String run() {
                TreeMap<String, String> found = new TreeMap<>();
                int total = 0;
                String getpropNote;
                try {
                    Process p = Runtime.getRuntime().exec("getprop");
                    BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
                    String l;
                    while ((l = r.readLine()) != null) {
                        int sep = l.indexOf("]: [");
                        if (!l.startsWith("[") || sep < 0 || !l.endsWith("]")) continue;
                        total++;
                        String k = l.substring(1, sep), v = l.substring(sep + 4, l.length() - 1);
                        if (ExportText.looksVehicleKey(k) || isVendorPrefixed(k)) found.put(k, v);
                    }
                    p.waitFor();
                    getpropNote = total == 0
                            ? "getprop ran but printed nothing readable (blocked)"
                            : "getprop: " + total + " properties readable, " + found.size() + " look vehicle/vendor-related";
                } catch (Throwable t) {
                    getpropNote = "getprop blocked: " + t;
                }
                StringBuilder sb = new StringBuilder();
                sb.append(getpropNote).append('\n');
                Method get = null;
                String reflectNote = "";
                try {
                    get = Class.forName("android.os.SystemProperties").getMethod("get", String.class);
                } catch (Throwable t) {
                    reflectNote = "SystemProperties reflection blocked: " + t + "\n";
                }
                sb.append(reflectNote);
                for (String k : CURATED_PROPS) {
                    if (found.containsKey(k)) continue;
                    if (get == null) continue;
                    try {
                        String v = (String) get.invoke(null, k);
                        found.put(k, v == null || v.isEmpty() ? "" : v);
                    } catch (Throwable t) {
                        found.put(k, "<blocked>");
                    }
                }
                for (Map.Entry<String, String> e : found.entrySet()) {
                    String v = e.getValue();
                    sb.append("  ").append(e.getKey()).append(" = ")
                            .append(v.isEmpty() ? "<empty>" : v).append('\n');
                }
                return sb.toString();
            }
        });
    }

    /** vendor/OEM namespaces that the sweep cares about, regardless of the key's words. */
    private static boolean isVendorPrefixed(String k) {
        return k.startsWith("persist.vendor.") && ExportText.looksVehicleKey(k)
                || k.startsWith("ro.hcn.") || k.startsWith("ro.jancar.") || k.startsWith("persist.hcn.")
                || k.startsWith("persist.jancar.") || k.startsWith("ro.fyt.") || k.startsWith("ro.syu.");
    }

    // ---- settings --------------------------------------------------------------------

    private static final String[] CURATED_SETTINGS = {
            "car_mode", "car_mode_enabled", "canbox_protocol", "can_protocol", "can_type", "mcu_version",
            "gear", "gear_state", "speed", "speed_unit", "door_state", "brake_state", "handbrake",
            "reverse_state", "acc_state", "driving_mode",
    };

    private static String settings(final Context ctx) {
        return safe(new Part() {
            public String run() {
                ContentResolver cr = ctx.getContentResolver();
                StringBuilder sb = new StringBuilder();
                String[] tables = { "system", "global" };
                for (String table : tables) {
                    TreeMap<String, String> hits = new TreeMap<>();
                    String note = "enumerated";
                    Cursor c = null;
                    try {
                        c = cr.query(Uri.parse("content://settings/" + table), null, null, null, null);
                        if (c == null) {
                            note = "enumeration returned nothing (blocked)";
                        } else {
                            int ni = c.getColumnIndex("name"), vi = c.getColumnIndex("value");
                            int rows = 0;
                            while (c.moveToNext() && ni >= 0) {
                                rows++;
                                String k = c.getString(ni);
                                if (ExportText.looksVehicleKey(k)) hits.put(k, vi >= 0 ? c.getString(vi) : "");
                            }
                            note = "enumerated " + rows + " keys";
                        }
                    } catch (Throwable t) {
                        note = "enumeration blocked (" + t.getClass().getSimpleName() + "), curated keys only";
                    } finally {
                        if (c != null) c.close();
                    }
                    for (String k : CURATED_SETTINGS) {
                        if (hits.containsKey(k)) continue;
                        try {
                            String v = "system".equals(table)
                                    ? Settings.System.getString(cr, k) : Settings.Global.getString(cr, k);
                            if (v != null) hits.put(k, v);
                        } catch (Throwable ignored) { }
                    }
                    sb.append(table).append(": ").append(note).append(", ").append(hits.size())
                            .append(" matching\n");
                    for (Map.Entry<String, String> e : hits.entrySet()) {
                        sb.append("  ").append(e.getKey()).append(" = ").append(e.getValue()).append('\n');
                    }
                }
                return sb.toString();
            }
        });
    }
}
