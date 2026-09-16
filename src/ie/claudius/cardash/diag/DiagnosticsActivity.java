package ie.claudius.cardash.diag;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.SparseArray;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import ie.claudius.cardash.Fonts;
import ie.claudius.cardash.M3;
import ie.claudius.cardash.Motion;
import ie.claudius.cardash.Prefs;
import ie.claudius.cardash.R;
import ie.claudius.cardash.Receivers;
import ie.claudius.cardash.Shapes;
import ie.claudius.cardash.vehicle.JancarSource;
import ie.claudius.cardash.vehicle.ObdSource;
import ie.claudius.cardash.vehicle.VehicleHub;
import ie.claudius.cardash.vehicle.VehicleState;

/**
 * Diagnostics: the instrument that makes the car features possible.
 *
 * Nothing here is for the driver. It exists so the head unit can be
 * asked what it is and what its vendor car service actually says,
 * instead of guessing and buying hardware to find out. Its export is
 * the artefact to hand to a canbox seller, an installer or a forum.
 *
 * The shell renders immediately; every probe that touches the package
 * manager, parses a manifest or forks a shell runs on a worker and
 * fills its panel when it lands. A live CAN feed is tens of messages a
 * second, so the log panels coalesce their redraws — the instrument
 * must not fall over the moment a canbox finally starts talking.
 */
public class DiagnosticsActivity extends Activity {

    private static final int MAX_LOG_LINES = 300;   // kept for the report
    private static final int SHOWN_LOG_LINES = 80;  // drawn on screen
    private static final long RENDER_COALESCE_MS = 200L;

    private float density;
    private M3 m3;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    private final VehicleHub hub = VehicleHub.shared();
    private final VehicleState.Listener hubListener = new VehicleState.Listener() {
        @Override
        public void onVehicleState(VehicleState state) {
            renderSources();
        }
    };
    private List<String> vendorPkgs = new ArrayList<>();
    private Set<String> sniffActions = new LinkedHashSet<>();

    // Panel text is produced once and cached, so the on-screen panel and
    // the export are the same bytes — never a placeholder scraped back
    // out of a TextView that hadn't rendered yet.
    private String unitText = "", surfaceText = "", serialText = "", aaText = "";
    private TextView unitBody, surfaceBody, sniffBody, sourcesBody, obdBody, keysBody, serialBody, aaBody;
    private EditText protocolField;

    private final Log sniff = new Log();
    private final Log keys = new Log();
    private BroadcastReceiver sniffer;
    private final SparseArray<String> deviceNames = new SparseArray<>();

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            renderSources();
            renderObd();
            main.postDelayed(this, 1000);
        }
    };

    /** A capped log whose on-screen render is coalesced. */
    private final class Log {
        final List<String> lines = new ArrayList<>();
        TextView body;
        String idle;
        boolean paused;
        private boolean renderQueued;
        private final Runnable render = new Runnable() {
            @Override
            public void run() {
                renderQueued = false;
                if (body == null) return;
                if (lines.isEmpty()) {
                    body.setText(idle);
                    return;
                }
                StringBuilder sb = new StringBuilder();
                int from = lines.size() - 1, to = Math.max(0, lines.size() - SHOWN_LOG_LINES);
                for (int i = from; i >= to; i--) sb.append(lines.get(i)).append('\n');
                if (to > 0) sb.append("… ").append(to).append(" older in the export\n");
                body.setText(sb.toString());
            }
        };

        void add(String text) {
            if (paused) return;
            lines.add(clock.format(new Date()) + "  " + text);
            while (lines.size() > MAX_LOG_LINES) lines.remove(0);
            if (!renderQueued) {
                renderQueued = true;
                main.postDelayed(render, RENDER_COALESCE_MS);
            }
        }

        void clear() {
            lines.clear();
            main.removeCallbacks(render);
            renderQueued = false;
            render.run();
        }

        String joined() {
            if (lines.isEmpty()) return "(none)";
            StringBuilder sb = new StringBuilder();
            for (String l : lines) sb.append(l).append('\n');
            return sb.toString();
        }
    }

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        density = getResources().getDisplayMetrics().density;
        Motion.setCalm(true); // an instrument, not a showpiece
        m3 = M3.fromSeed(Prefs.themeSeed(this));

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(m3.surface());
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        column.setPadding(pad, pad, pad, pad);
        scroll.addView(column);

        // ---- header ----
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(8), 0, dp(8), dp(16));
        TextView title = new TextView(this);
        title.setText(R.string.diagnostics);
        title.setTypeface(Fonts.display(this));
        title.setTextColor(m3.onSurface());
        title.setTextSize(26);
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(pill(getString(R.string.diag_copy), false, new Runnable() {
            @Override
            public void run() {
                copyReport();
            }
        }));
        header.addView(pill(getString(R.string.diag_export), false, new Runnable() {
            @Override
            public void run() {
                exportReport();
            }
        }));
        header.addView(pill(getString(R.string.done), true, new Runnable() {
            @Override
            public void run() {
                finish();
            }
        }));
        column.addView(header);

        // ---- panels, empty until the worker fills them ----
        String working = getString(R.string.diag_working);
        unitBody = panel(column, R.string.diag_unit, R.string.diag_unit_caption, null);
        unitBody.setText(working);
        surfaceBody = panel(column, R.string.diag_surface, R.string.diag_surface_caption, null);
        surfaceBody.setText(working);

        sniffBody = panel(column, R.string.diag_sniffer, R.string.diag_sniffer_caption, sniff);
        sniff.body = sniffBody;
        sniff.idle = working;
        sniffBody.setText(working);

        sourcesBody = panel(column, R.string.diag_sources, R.string.diag_sources_caption, null);
        obdBody = panel(column, R.string.diag_obd, R.string.diag_obd_caption, null);

        keysBody = panel(column, R.string.diag_keys, R.string.diag_keys_caption, keys);
        keys.body = keysBody;
        keys.idle = getString(R.string.diag_keys_idle);
        keysBody.setText(keys.idle);

        protocolPanel(column);

        aaBody = panel(column, R.string.diag_aa, R.string.diag_aa_caption, null);
        aaBody.setText(working);

        serialBody = panel(column, R.string.diag_serial, R.string.diag_serial_caption, null);
        serialBody.setText(working);

        setContentView(scroll);
        for (int i = 1; i < column.getChildCount(); i++) {
            Motion.enter(column.getChildAt(i), 30L * i, 0f, dp(16));
        }
        probeInBackground();
    }

    /**
     * Everything slow, off the main thread, in the order the answers
     * are useful: identity first (instant), then the name heuristic so
     * the sniffer can arm on something, then the full manifest sweep,
     * which re-arms it on whatever the unit actually declares.
     */
    private void probeInBackground() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final Context ctx = DiagnosticsActivity.this;
                final String unit = Probe.unit();
                post(new Runnable() {
                    public void run() {
                        unitText = unit;
                        unitBody.setText(unitText);
                    }
                });

                final List<String> quick = Probe.merge(Probe.vendorPackages(ctx),
                        hitsFor(Probe.rememberedSweep(ctx)));
                final Set<String> quickActions = watchList(ctx, quick);
                post(new Runnable() {
                    public void run() {
                        vendorPkgs = quick;
                        resniff(quickActions);
                    }
                });

                final String aa = Probe.wirelessAndroidAuto(ctx);
                post(new Runnable() {
                    public void run() {
                        aaText = aa;
                        aaBody.setText(aaText);
                    }
                });

                final String serial = Probe.serialPorts();
                post(new Runnable() {
                    public void run() {
                        serialText = serial;
                        serialBody.setText(serialText);
                    }
                });

                final List<Probe.Hit> hits = Probe.sweep(ctx);
                Probe.rememberSweep(ctx, hits);
                final List<String> all = Probe.merge(Probe.vendorPackages(ctx), hits);
                final String unitFull = unitText(ctx, all, hits.size());
                final String surface = surfaceText(ctx, all);
                final Set<String> actions = watchList(ctx, all);
                post(new Runnable() {
                    public void run() {
                        vendorPkgs = all;
                        unitText = unitFull;
                        unitBody.setText(unitText);
                        surfaceText = surface;
                        surfaceBody.setText(surfaceText);
                        resniff(actions);
                    }
                });
            }
        }, "diag-probe").start();
    }

    private void post(final Runnable r) {
        main.post(new Runnable() {
            @Override
            public void run() {
                if (!isFinishing()) r.run();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        hub.start(this, hubListener, VehicleHub.MODE_AUTO);
        if (sniffer == null && !sniffActions.isEmpty()) resniff(sniffActions);
        main.post(refresh);
    }

    @Override
    protected void onPause() {
        super.onPause();
        main.removeCallbacks(refresh);
        unsniff();
        hub.stop(hubListener);
        saveProtocols();
    }

    /** 4.6: every key that reaches this window, typed and sourced. */
    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (!keys.paused) {
            keys.add(String.format(Locale.US,
                    "%s  scan=%d  %s  repeat=%d\n    device %d \"%s\"  source=0x%X",
                    KeyEvent.keyCodeToString(e.getKeyCode()), e.getScanCode(),
                    e.getAction() == KeyEvent.ACTION_DOWN ? "DOWN" : "UP", e.getRepeatCount(),
                    e.getDeviceId(), deviceName(e.getDeviceId()), e.getSource()));
        }
        return super.dispatchKeyEvent(e);
    }

    /** InputDevice.getDevice is a binder call; auto-repeat would hammer it. */
    private String deviceName(int id) {
        String n = deviceNames.get(id);
        if (n == null) {
            InputDevice dev = InputDevice.getDevice(id);
            n = dev == null ? "?" : dev.getName();
            deviceNames.put(id, n);
        }
        return n;
    }

    // ---- sniffer -----------------------------------------------------------------

    /** Discovered ∪ candidate, deduplicated. Pure; safe off the main thread. */
    private static Set<String> watchList(Context ctx, List<String> pkgs) {
        Set<String> actions = new LinkedHashSet<>(Probe.declaredActions(ctx, pkgs));
        for (String a : JancarSource.CANDIDATE_ACTIONS) actions.add(a);
        return actions;
    }

    private static List<Probe.Hit> hitsFor(List<String> pkgs) {
        List<Probe.Hit> out = new ArrayList<>();
        for (String p : pkgs) out.add(new Probe.Hit(p));
        return out;
    }

    /**
     * Tear the receiver down and re-register on a new watch list.
     * Called when the quick list is ready, again after the sweep, and
     * whenever discovery hands us a new action — no restart needed.
     */
    private void resniff(Set<String> actions) {
        unsniff();
        sniffActions = actions;
        sniffer = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                sniff.add(JancarSource.describe(intent));
            }
        };
        IntentFilter f = new IntentFilter();
        for (String a : sniffActions) f.addAction(a);
        // Exported: these broadcasts come from the vendor's packages.
        Receivers.register(this, sniffer, f, true);
        sniff.idle = getString(R.string.diag_sniffer_idle, sniffActions.size());
        if (sniff.lines.isEmpty()) sniffBody.setText(sniff.idle);
    }

    private void unsniff() {
        Receivers.unregister(this, sniffer);
        sniffer = null;
    }

    // ---- panel text -----------------------------------------------------------------

    private static String unitText(Context ctx, List<String> pkgs, int swept) {
        StringBuilder sb = new StringBuilder(Probe.unit());
        sb.append("\nvendor-ish packages (").append(pkgs.size()).append(", ")
                .append(swept).append(" from the manifest sweep):\n");
        for (String p : pkgs) sb.append("  ").append(Probe.describePackage(ctx, p)).append('\n');
        if (pkgs.isEmpty()) sb.append("  none — car integration may be a private binder or UART\n");
        sb.append("\nfrom adb:  content query --uri content://").append(DiagProvider.AUTHORITY)
                .append("/sweep\n");
        return sb.toString();
    }

    private static String surfaceText(Context ctx, List<String> pkgs) {
        StringBuilder sb = new StringBuilder();
        for (String p : pkgs) {
            sb.append("== ").append(p).append(" ==\n").append(Probe.surface(ctx, p)).append('\n');
        }
        if (pkgs.isEmpty()) sb.append("nothing to read\n");
        return sb.toString();
    }

    private String sourcesText() {
        StringBuilder sb = new StringBuilder();
        long now = System.currentTimeMillis();
        for (VehicleHub.Entry e : hub.entries()) {
            sb.append(e.source.name()).append('\n');
            sb.append("    available  ").append(e.available ? "yes" : "no").append('\n');
            sb.append("    started    ").append(e.started ? "yes" : "no — " + e.skipped).append('\n');
            sb.append("    status     ").append(e.source.status(this)).append('\n');
            // "never" and "40 s ago" are the two answers that matter: a
            // source that died looks like one that never started otherwise.
            sb.append("    last heard ").append(e.lastUpdate == 0 ? "never"
                    : ((now - e.lastUpdate) / 1000) + " s ago").append('\n');
            sb.append("    fields     ").append(e.knownFields).append(" of ")
                    .append(VehicleState.FIELDS).append(" known\n");
        }
        if (hub.entries().isEmpty()) sb.append("(hub not running)\n");
        VehicleState m = hub.merged();
        sb.append("\nmerged: fuel=").append(v(m.fuelPercent)).append("  speed=").append(v(m.speedKph))
                .append("  rpm=").append(v(m.rpm)).append("  coolant=").append(v(m.coolantC))
                .append("\n        doors FL=").append(m.doorFrontLeft).append(" FR=").append(m.doorFrontRight)
                .append(" RL=").append(m.doorRearLeft).append(" RR=").append(m.doorRearRight)
                .append(" boot=").append(m.boot).append("\n        handbrake=").append(m.handbrake)
                .append(" reverse=").append(m.reverse).append('\n');
        return sb.toString();
    }

    private String obdText() {
        ObdSource obd = null;
        for (VehicleHub.Entry e : hub.entries()) {
            if (e.source instanceof ObdSource) obd = (ObdSource) e.source;
        }
        if (obd == null) return getString(R.string.diag_obd_none);
        ObdSource.Diag d = obd.diag();
        StringBuilder sb = new StringBuilder();
        sb.append("paired devices (").append(d.paired.size()).append("):\n");
        for (String p : d.paired) sb.append("  ").append(p).append('\n');
        sb.append("matched as dongle: ").append(d.matched == null ? "none" : d.matched).append('\n');
        sb.append("socket: ").append(d.socket).append('\n');
        if (!d.raw.isEmpty()) {
            sb.append("\nlast raw reply per command:\n");
            for (Map.Entry<String, String> e : d.raw.entrySet()) {
                sb.append(String.format(Locale.US, "  %-6s %s%n", e.getKey(), e.getValue()));
            }
        }
        return sb.toString();
    }

    private void renderSources() {
        if (sourcesBody != null) sourcesBody.setText(sourcesText());
    }

    private void renderObd() {
        if (obdBody != null) obdBody.setText(obdText());
    }

    // ---- the one fact that can't be probed ------------------------------------------

    /**
     * The head unit's factory-settings canbox protocol list decides
     * which box can be bought, and no API exposes it. Typed once here,
     * it goes into every export from then on.
     */
    private void protocolPanel(LinearLayout column) {
        LinearLayout card = cardShell(column, R.string.diag_protocols, R.string.diag_protocols_caption, null);
        protocolField = new EditText(this);
        protocolField.setTypeface(Typeface.MONOSPACE);
        protocolField.setTextSize(13);
        protocolField.setTextColor(m3.onSurface());
        protocolField.setHintTextColor(M3.withAlpha(m3.onSurfaceVariant(), 0x99));
        protocolField.setHint(R.string.diag_protocols_hint);
        protocolField.setBackground(Shapes.round(density, m3.surfaceContainerHigh(), 16));
        protocolField.setPadding(dp(14), dp(12), dp(14), dp(12));
        protocolField.setMinLines(2);
        protocolField.setText(Prefs.get(this, Prefs.CANBOX_PROTOCOLS, ""));
        protocolField.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {}
            public void afterTextChanged(Editable s) {
                main.removeCallbacks(saveProtocols);
                main.postDelayed(saveProtocols, 600);
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        card.addView(protocolField, lp);
    }

    private final Runnable saveProtocols = new Runnable() {
        @Override
        public void run() {
            saveProtocols();
        }
    };

    private void saveProtocols() {
        if (protocolField != null) {
            Prefs.set(this, Prefs.CANBOX_PROTOCOLS, protocolField.getText().toString().trim());
        }
    }

    // ---- export ---------------------------------------------------------------------

    /** One route for everything: the cached probe text, the live generators, the logs. */
    private String report() {
        StringBuilder sb = new StringBuilder();
        sb.append("CarDash diagnostics  ").append(new Date()).append("\n\n");
        section(sb, "UNIT", unitText);
        String protocols = Prefs.get(this, Prefs.CANBOX_PROTOCOLS, "");
        section(sb, "FACTORY-SETTINGS CANBOX PROTOCOL LIST (typed in by hand)",
                protocols.isEmpty() ? "(not recorded yet)" : protocols);
        section(sb, "VENDOR SERVICE SURFACE", surfaceText);
        section(sb, "SNIFFED BROADCASTS (" + sniffActions.size() + " actions watched)",
                sniff.joined() + "\nwatching:\n  " + joinLines(sniffActions).replace("\n", "\n  "));
        section(sb, "VEHICLE SOURCES", sourcesText());
        section(sb, "OBD", obdText());
        section(sb, "KEY EVENTS", keys.joined());
        section(sb, "WIRELESS ANDROID AUTO", aaText);
        section(sb, "SERIAL PORTS", serialText);
        return sb.toString();
    }

    private void copyReport() {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("CarDash diagnostics", report()));
    }

    /** Write to app-external storage (no permission needed) and offer to share. */
    private void exportReport() {
        String text = report();
        String name = "cardash-diagnostics-"
                + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ".txt";
        File dir = getExternalFilesDir(null);
        String where = "";
        if (dir != null) {
            try {
                File f = new File(dir, name);
                FileOutputStream out = new FileOutputStream(f);
                out.write(text.getBytes("UTF-8"));
                out.close();
                where = f.getAbsolutePath();
            } catch (Exception e) {
                where = "write failed: " + e;
            }
        }
        // Plain text in the intent: no FileProvider (that is AndroidX) and
        // file:// URIs are refused on API 24+. Every mail/chat app takes it.
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_SUBJECT, name);
        send.putExtra(Intent.EXTRA_TEXT, text + "\n\n(saved at " + where + ")");
        try {
            startActivity(Intent.createChooser(send, getString(R.string.diag_export)));
        } catch (Exception e) {
            copyReport();
        }
    }

    // ---- pieces ---------------------------------------------------------------------

    /** A card with a title, a caption, an optional Pause/Clear pair for a log, and a monospace body. */
    private TextView panel(LinearLayout column, int titleRes, int captionRes, final Log log) {
        LinearLayout card = cardShell(column, titleRes, captionRes, log);
        TextView body = new TextView(this);
        body.setTypeface(Typeface.MONOSPACE);
        body.setTextColor(m3.onSurfaceVariant());
        body.setTextSize(12);
        body.setTextIsSelectable(true);
        body.setPadding(0, dp(12), 0, 0);
        body.setHorizontallyScrolling(false);
        card.addView(body);
        return body;
    }

    private LinearLayout cardShell(LinearLayout column, int titleRes, int captionRes, final Log log) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Shapes.round(density, m3.surfaceContainer(), 28));
        card.setPadding(dp(24), dp(16), dp(24), dp(20));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(this);
        title.setText(titleRes);
        title.setTypeface(Fonts.display(this));
        title.setTextColor(m3.onSurface());
        title.setTextSize(18);
        text.addView(title);
        TextView caption = new TextView(this);
        caption.setText(captionRes);
        caption.setTypeface(Fonts.body(this));
        caption.setTextColor(m3.onSurfaceVariant());
        caption.setTextSize(13);
        text.addView(caption);
        head.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (log != null) {
            final TextView pause = pill(getString(R.string.diag_pause), false, null);
            pause.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    log.paused = !log.paused;
                    pause.setText(log.paused ? R.string.diag_resume : R.string.diag_pause);
                    style(pause, log.paused);
                    Motion.pop(v);
                }
            });
            head.addView(pause);
            head.addView(pill(getString(R.string.diag_clear), false, new Runnable() {
                @Override
                public void run() {
                    log.clear();
                }
            }));
        }
        card.addView(head);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(16);
        column.addView(card, lp);
        return card;
    }

    private TextView pill(String text, boolean primary, final Runnable action) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTypeface(Fonts.display(this));
        t.setTextSize(15);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(18), dp(10), dp(18), dp(10));
        t.setClickable(true);
        Shapes.springy(t);
        style(t, primary);
        if (action != null) {
            t.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    action.run();
                    Motion.pop(v);
                }
            });
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(3);
        lp.rightMargin = dp(3);
        t.setLayoutParams(lp);
        return t;
    }

    private void style(TextView t, boolean selected) {
        int fill = selected ? m3.primaryContainer() : m3.surfaceContainerHigh();
        int on = selected ? m3.onPrimaryContainer() : m3.onSurfaceVariant();
        t.setTextColor(on);
        t.setBackground(Shapes.pill(density, fill, M3.withAlpha(on, 0x40)));
    }

    private static void section(StringBuilder sb, String title, String body) {
        sb.append("---- ").append(title).append(" ----\n").append(body).append("\n\n");
    }

    private static String joinLines(Set<String> lines) {
        if (lines.isEmpty()) return "(none)";
        StringBuilder sb = new StringBuilder();
        for (String l : lines) sb.append(l).append('\n');
        return sb.toString();
    }

    private static String v(int i) {
        return i == VehicleState.UNKNOWN_INT ? "?" : String.valueOf(i);
    }

    private int dp(int v) {
        return Math.round(v * density);
    }
}
