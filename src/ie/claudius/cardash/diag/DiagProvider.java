package ie.claudius.cardash.diag;

import android.content.BroadcastReceiver;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ie.claudius.cardash.Prefs;
import ie.claudius.cardash.Receivers;
import ie.claudius.cardash.vehicle.JancarSource;
import ie.claudius.cardash.vehicle.ObdSource;
import ie.claudius.cardash.vehicle.VehicleHub;
import ie.claudius.cardash.vehicle.VehicleState;

/**
 * The diagnostics, from any adb shell — no screen, no taps.
 *
 * A ContentProvider is the one Android component the stock {@code
 * content} shell tool can talk to and print from, so every probe on the
 * Diagnostics screen is reachable as a query, and the ones that need to
 * wait for something (sniffing, OBD) as a call:
 *
 * <pre>
 *   adb shell content query --uri content://ie.claudius.cardash.diag/unit
 *   adb shell content query --uri content://ie.claudius.cardash.diag/sweep
 *   adb shell content query --uri content://ie.claudius.cardash.diag/surface/com.jancar.services
 *   adb shell content query --uri content://ie.claudius.cardash.diag/actions
 *   adb shell content query --uri content://ie.claudius.cardash.diag/aa        (wireless Android Auto checks)
 *   adb shell content query --uri content://ie.claudius.cardash.diag/serial
 *   adb shell content query --uri content://ie.claudius.cardash.diag/prefs
 *   adb shell content call  --uri content://ie.claudius.cardash.diag --method sniff --arg 15
 *   adb shell content call  --uri content://ie.claudius.cardash.diag --method sniff --arg 15 \
 *          --extra actions:s:com.foo.CAR_DATA,com.foo.DOOR
 *   adb shell content call  --uri content://ie.claudius.cardash.diag --method sources --arg 5
 *   adb shell content call  --uri content://ie.claudius.cardash.diag --method obd --arg 20
 *   adb shell content call  --uri content://ie.claudius.cardash.diag --method set --arg speed_unit --extra value:s:mph
 * </pre>
 *
 * Exported, but answers only the shell and root UIDs (and ourselves):
 * this is device identity and vendor internals, not something for
 * every installed app to read.
 */
public final class DiagProvider extends ContentProvider {

    public static final String AUTHORITY = "ie.claudius.cardash.diag";
    private static final long MAX_WAIT_MS = 60_000L;
    private static final int UID_ROOT = 0, UID_SHELL = 2000;

    @Override
    public boolean onCreate() {
        return true;
    }

    private void gate() {
        int uid = Binder.getCallingUid();
        if (uid != UID_ROOT && uid != UID_SHELL && uid != Process.myUid()) {
            throw new SecurityException("CarDash diagnostics are for adb shell / root only");
        }
    }

    // ---- query: one row per line -------------------------------------------------

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] args, String sort) {
        gate();
        List<String> seg = uri.getPathSegments();
        String cmd = seg.isEmpty() ? "help" : seg.get(0);
        String arg = seg.size() > 1 ? seg.get(1) : null;
        Context ctx = getContext();
        String text;
        switch (cmd) {
            case "unit":
                text = Probe.unit();
                break;
            case "packages":
                text = list(Probe.vendorPackages(ctx), ctx);
                break;
            case "sweep": {
                List<Probe.Hit> hits = Probe.sweep(ctx);
                Probe.rememberSweep(ctx, hits);
                StringBuilder sb = new StringBuilder();
                for (Probe.Hit h : hits) {
                    sb.append(Probe.describePackage(ctx, h.pkg)).append('\n');
                    for (String a : h.actions) sb.append("    ").append(a).append('\n');
                }
                text = sb.length() == 0 ? "no package declares car-ish actions" : sb.toString();
                break;
            }
            case "surface":
                text = arg == null ? "usage: surface/<package>" : Probe.surface(ctx, arg);
                break;
            case "actions":
                text = lines(watchList(ctx, null));
                break;
            case "serial":
                text = Probe.serialPorts();
                break;
            case "aa":
                text = Probe.wirelessAndroidAuto(ctx);
                break;
            case "prefs": {
                StringBuilder sb = new StringBuilder();
                Map<String, ?> all = ctx.getSharedPreferences("cardash", Context.MODE_PRIVATE).getAll();
                for (Map.Entry<String, ?> e : new java.util.TreeMap<String, Object>(all).entrySet()) {
                    sb.append(e.getKey()).append(" = ").append(e.getValue()).append('\n');
                }
                text = sb.toString();
                break;
            }
            default:
                text = "commands: unit | packages | sweep | surface/<pkg> | actions | aa | serial | prefs\n"
                        + "calls:    sniff <seconds> [actions:s:a,b]  |  sources <seconds>  |  "
                        + "obd <seconds>  |  set <key> value:s:<v>  |  get <key>\n"
                        + "e.g.      set canbox_protocols value:s:\"Raise, Hiworld\"  "
                        + "records the factory-settings list for the report";
        }
        MatrixCursor c = new MatrixCursor(new String[] { "line" });
        for (String l : text.split("\n")) c.addRow(new Object[] { l });
        return c;
    }

    // ---- call: things that wait ------------------------------------------------------

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        gate();
        Context ctx = getContext();
        Bundle out = new Bundle();
        long waitMs = Math.min(MAX_WAIT_MS, 1000L * parseInt(arg, 10));
        switch (method) {
            case "sniff": {
                Set<String> actions = watchList(ctx,
                        extras == null ? null : extras.getString("actions"));
                List<String> log = sniff(ctx, actions, waitMs);
                out.putInt("watched", actions.size());
                out.putInt("received", log.size());
                out.putStringArray("lines", log.toArray(new String[0]));
                break;
            }
            case "sources":
            case "obd": {
                out.putString("report", probeSources(ctx, waitMs, "obd".equals(method)));
                break;
            }
            case "set": {
                String value = extras == null ? null : extras.getString("value");
                if (arg == null || value == null) {
                    out.putString("error", "usage: --method set --arg <key> --extra value:s:<value>");
                } else {
                    Prefs.set(ctx, arg, value);
                    out.putString(arg, value);
                }
                break;
            }
            case "get":
                out.putString(String.valueOf(arg), Prefs.get(ctx, String.valueOf(arg), null));
                break;
            default:
                out.putString("error", "unknown method " + method);
        }
        return out;
    }

    /** Discovered ∪ candidate ∪ caller-supplied, deduplicated. */
    private static Set<String> watchList(Context ctx, String extra) {
        Set<String> actions = new LinkedHashSet<>();
        List<String> pkgs = Probe.merge(Probe.vendorPackages(ctx),
                hitsFor(Probe.rememberedSweep(ctx)));
        actions.addAll(Probe.declaredActions(ctx, pkgs));
        for (String a : JancarSource.CANDIDATE_ACTIONS) actions.add(a);
        if (extra != null) {
            for (String a : extra.split(",")) if (!a.trim().isEmpty()) actions.add(a.trim());
        }
        return actions;
    }

    private static List<Probe.Hit> hitsFor(List<String> pkgs) {
        List<Probe.Hit> out = new ArrayList<>();
        for (String p : pkgs) out.add(new Probe.Hit(p));
        return out;
    }

    /** Register on the main looper, sleep on this binder thread, unregister. */
    private static List<String> sniff(Context ctx, Set<String> actions, long waitMs) {
        final List<String> log = new ArrayList<>();
        BroadcastReceiver r = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                synchronized (log) {
                    log.add(JancarSource.describe(intent).replace("\n    ", "  "));
                }
            }
        };
        IntentFilter f = new IntentFilter();
        for (String a : actions) f.addAction(a);
        Context app = ctx.getApplicationContext();
        Receivers.register(app, r, f, true, new Handler(Looper.getMainLooper()));
        try {
            Thread.sleep(waitMs);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } finally {
            Receivers.unregister(app, r);
        }
        synchronized (log) {
            return new ArrayList<>(log);
        }
    }

    /**
     * Attach to the shared hub for a while and describe what happened.
     * Shared, so if the home screen already holds the dongle's socket we
     * read its data rather than fighting it for the connection.
     */
    private static String probeSources(final Context ctx, long waitMs, boolean obdDetail) {
        final VehicleHub hub = VehicleHub.shared();
        final Handler main = new Handler(Looper.getMainLooper());
        final Object lock = new Object();
        final VehicleState.Listener probe = new VehicleState.Listener() {
            @Override
            public void onVehicleState(VehicleState s) {
            }
        };
        main.post(new Runnable() {
            @Override
            public void run() {
                hub.start(ctx, probe, VehicleHub.MODE_AUTO);
                synchronized (lock) {
                    lock.notifyAll();
                }
            }
        });
        try {
            synchronized (lock) {
                lock.wait(5000);
            }
            Thread.sleep(waitMs);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        StringBuilder sb = new StringBuilder();
        long now = System.currentTimeMillis();
        for (VehicleHub.Entry e : hub.entries()) {
            sb.append(e.source.name()).append(": available=").append(e.available)
                    .append(" started=").append(e.started);
            if (e.skipped != null) sb.append(" (").append(e.skipped).append(')');
            sb.append("\n  status: ").append(e.source.status(ctx));
            sb.append("\n  last heard: ").append(e.lastUpdate == 0 ? "never"
                    : ((now - e.lastUpdate) / 1000) + " s ago");
            sb.append("\n  fields known: ").append(e.knownFields).append('/')
                    .append(VehicleState.FIELDS).append('\n');
            if (obdDetail && e.source instanceof ObdSource) {
                ObdSource.Diag d = ((ObdSource) e.source).diag();
                sb.append("  paired: ").append(d.paired).append("\n  matched: ").append(d.matched)
                        .append("\n  socket: ").append(d.socket).append('\n');
                for (Map.Entry<String, String> raw : d.raw.entrySet()) {
                    sb.append("  ").append(raw.getKey()).append(" -> ").append(raw.getValue()).append('\n');
                }
            }
        }
        main.post(new Runnable() {
            @Override
            public void run() {
                hub.stop(probe);
            }
        });
        return sb.toString();
    }

    // ---- plumbing ------------------------------------------------------------------------

    private static String list(List<String> pkgs, Context ctx) {
        if (pkgs.isEmpty()) return "(none)";
        StringBuilder sb = new StringBuilder();
        for (String p : pkgs) sb.append(Probe.describePackage(ctx, p)).append('\n');
        return sb.toString();
    }

    private static String lines(Set<String> s) {
        if (s.isEmpty()) return "(none)";
        StringBuilder sb = new StringBuilder();
        for (String l : s) sb.append(l).append('\n');
        return sb.toString();
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            return def;
        }
    }

    @Override
    public String getType(Uri uri) {
        return "vnd.android.cursor.dir/vnd.cardash.diag";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] args) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] args) {
        return 0;
    }
}
