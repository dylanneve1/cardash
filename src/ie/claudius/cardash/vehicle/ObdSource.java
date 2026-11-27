package ie.claudius.cardash.vehicle;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import ie.claudius.cardash.Prefs;
import ie.claudius.cardash.bt.BtDevices;
import ie.claudius.cardash.car.Vin;

/**
 * ELM327 over Bluetooth serial.
 *
 * The device is the one chosen on the Bluetooth screen, whatever it is
 * called; with none chosen, the first paired device named like a dongle.
 *
 * One polling thread. The adapter is tuned for speed (headers and spaces
 * off, aggressive adaptive timing, a short reply timeout); the car's
 * supported-PID bitmaps are read once so only supported PIDs are asked;
 * on CAN several PIDs share one request, with RPM and speed every cycle
 * and the slow values now and then ({@link ObdSchedule}). Clones are
 * riddled with firmware quirks, so nothing here treats a reply as
 * trustworthy until it parses cleanly ({@link ObdReply}): a value that
 * stops arriving goes UNKNOWN, and a clone that can't answer multi-PID
 * requests drops back to one PID at a time.
 *
 * Every raw reply is kept, verbatim, for the Diagnostics screen. That
 * is what settles "does this Yaris support fuel level" versus "is this
 * clone dongle misbehaving" — a question that otherwise costs an
 * afternoon.
 *
 * PIDs polled:
 *   0C  engine RPM          ((A*256)+B)/4
 *   0D  vehicle speed       A km/h
 *   05  coolant temp        A-40 °C
 *   2F  fuel level          A*100/255 %   (optional; many cars lack it)
 */
public final class ObdSource implements VehicleSource {

    private static final String TAG = "CarDash/OBD";
    /** Standard Bluetooth SPP UUID — every ELM327 clone uses it. */
    private static final UUID SPP =
            UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    /** Pause between cycles when the car isn't answering. */
    private static final long IDLE_MS = 500L;
    /** After this long without an answer (ignition off) poll gently, so a parked car's bus can sleep. */
    private static final long QUIET_AFTER_MS = 60_000L;
    private static final long QUIET_POLL_MS = 5000L;
    private static final long REPLY_MS = 2000L;
    /** The first request makes the adapter search every protocol. */
    private static final long SEARCH_MS = 12_000L;
    private static final int MULTI_FAILS = 3;

    /** Snapshot for the Diagnostics screen. */
    public static final class Diag {
        public final List<String> paired = new ArrayList<>();
        /** The dongle chosen on the Bluetooth screen, or null when matching by name. */
        public String chosen;
        public String matched;
        public String socket;
        public final Map<String, String> raw = new LinkedHashMap<>();
        /** ELM protocol number once found, else -1. */
        public int protocol = -1;
        /** True while several PIDs go in each request. */
        public boolean multi;
        /** Supported mode-01 PIDs, or null while not read. */
        public Set<Integer> supported;
        public float requestsPerSecond;
    }

    private volatile boolean running;
    /** Application context, for the chosen-dongle setting. */
    private volatile Context appCtx;
    private volatile Thread worker;
    private volatile BluetoothSocket socket;
    private String socketState = "not started";
    private long lastUpdate;
    private final Map<String, String> raw = new LinkedHashMap<>();

    private final VehicleState state = new VehicleState();
    private final RateMeter rate = new RateMeter();
    private volatile int protocol = -1;
    private volatile Set<Integer> supported;
    private volatile ObdSchedule schedule;
    private final Map<Integer, Long> seen = new HashMap<>();
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override
    public String name() {
        return "OBD-II (ELM327)";
    }

    @Override
    public boolean isAvailable(Context ctx) {
        appCtx = ctx.getApplicationContext();
        return findAdapter() != null;
    }

    @Override
    public String status(Context ctx) {
        appCtx = ctx.getApplicationContext();
        BluetoothAdapter bt = BluetoothAdapter.getDefaultAdapter();
        if (bt == null) return "no Bluetooth adapter on this unit";
        try {
            if (!bt.isEnabled()) return "Bluetooth is off";
            if (findAdapter() == null) {
                if (chosenAddress() != null) return "chosen dongle " + chosenLabel() + " is not paired";
                return "no chosen dongle, and no paired device named like an ELM327 ("
                        + bt.getBondedDevices().size() + " paired)";
            }
        } catch (SecurityException e) {
            return "Bluetooth permission refused";
        }
        return socketState;
    }

    @Override
    public long lastUpdateMillis() {
        return lastUpdate;
    }

    public Diag diag() {
        Diag d = new Diag();
        try {
            BluetoothAdapter bt = BluetoothAdapter.getDefaultAdapter();
            if (bt != null) {
                for (BluetoothDevice dev : bt.getBondedDevices()) {
                    d.paired.add(dev.getName() + "  " + dev.getAddress());
                }
            }
            BluetoothDevice m = findAdapter();
            d.matched = m == null ? null : m.getName() + "  " + m.getAddress();
        } catch (SecurityException e) {
            d.paired.add("(Bluetooth permission refused)");
        }
        d.chosen = chosenAddress() == null ? null : chosenLabel();
        d.socket = socketState;
        synchronized (raw) {
            d.raw.putAll(raw);
        }
        d.protocol = protocol;
        ObdSchedule sch = schedule;
        d.multi = sch != null && sch.multi();
        d.supported = supported;
        d.requestsPerSecond = rate.perSecond(System.currentTimeMillis());
        return d;
    }

    private String chosenAddress() {
        return appCtx == null ? null : Prefs.obdAddress(appCtx);
    }

    private String chosenLabel() {
        return Prefs.obdName(appCtx) + "  " + chosenAddress();
    }

    /** The paired device to talk to: the chosen one, else one named like an ELM327 clone. */
    private BluetoothDevice findAdapter() {
        try {
            BluetoothAdapter bt = BluetoothAdapter.getDefaultAdapter();
            if (bt == null || !bt.isEnabled()) return null;
            List<BluetoothDevice> devices = new ArrayList<>(bt.getBondedDevices());
            List<String> addresses = new ArrayList<>();
            List<String> names = new ArrayList<>();
            for (BluetoothDevice d : devices) {
                addresses.add(d.getAddress());
                names.add(d.getName());
            }
            int i = BtDevices.pickDongle(chosenAddress(), addresses, names);
            return i < 0 ? null : devices.get(i);
        } catch (SecurityException e) {
            // Bluetooth permission refused; treat as simply unavailable.
            Log.w(TAG, "no bluetooth permission");
        }
        return null;
    }

    @Override
    public void start(Context ctx, final VehicleState.Listener listener) {
        if (running) return;
        appCtx = ctx.getApplicationContext();
        running = true;
        socketState = "connecting";
        worker = new Thread(new Runnable() {
            @Override
            public void run() {
                loop(listener);
            }
        }, "obd-poll");
        worker.setDaemon(true);
        worker.start();
    }

    @Override
    public void stop() {
        running = false;
        Thread w = worker;
        worker = null;
        closeQuietly();
        socketState = "stopped";
        if (w != null) w.interrupt();
    }

    /** True while the calling thread is the live poller (a stopped one must not touch the new one's socket). */
    private boolean current() {
        return running && Thread.currentThread() == worker;
    }

    private void loop(VehicleState.Listener listener) {
        while (current()) {
            try {
                if (!connect()) {
                    sleep(5000);
                    continue;
                }
                session(listener);
            } catch (Exception e) {
                if (!current()) return;
                Log.w(TAG, "poll failed: " + e);
                socketState = "poll failed: " + e.getMessage();
                clearValues();
                publish(listener);
                closeQuietly();
                sleep(3000);
            }
        }
    }

    private boolean connect() {
        BluetoothDevice dev = findAdapter();
        if (dev == null) {
            socketState = "no adapter paired";
            return false;
        }
        try {
            BluetoothSocket s = dev.createRfcommSocketToServiceRecord(SPP);
            socket = s;
            stopInquiry();
            s.connect();
            OutputStream out = s.getOutputStream();
            InputStream in = s.getInputStream();
            // Reset; echo, linefeeds, spaces and headers off.
            ask(out, in, "ATZ", 4000);
            for (String init : new String[] { "ATE0", "ATL0", "ATS0", "ATH0" }) ask(out, in, init, REPLY_MS);
            // Auto protocol, starting with the one found last time (ATTP is not written to the adapter's memory).
            ask(out, in, protocol > 0 ? "ATTPA" + Integer.toHexString(protocol).toUpperCase(Locale.ROOT) : "ATSP0",
                    REPLY_MS);
            socketState = "connected to " + dev.getName();
            return true;
        } catch (Exception e) {
            Log.w(TAG, "connect failed: " + e);
            socketState = "connect failed: " + e.getMessage();
            closeQuietly();
            return false;
        }
    }

    /** A running inquiry makes RFCOMM connects slow or fail; being refused is not fatal. */
    private void stopInquiry() {
        try {
            BluetoothAdapter.getDefaultAdapter().cancelDiscovery();
        } catch (SecurityException e) {
            Log.w(TAG, "no permission to stop discovery");
        }
    }

    /** One connected stretch: find the car, then poll until the link breaks or the source stops. */
    private void session(VehicleState.Listener listener) throws Exception {
        BluetoothSocket s = socket;
        if (s == null) throw new IOException("no socket");
        OutputStream out = s.getOutputStream();
        InputStream in = s.getInputStream();
        String link = socketState;
        schedule = null;
        supported = null;
        rate.reset();
        long lastData = System.currentTimeMillis();
        int multiFails = 0;
        boolean multiWorks = false;
        while (current()) {
            if (schedule == null) {
                if (!discover(out, in)) {
                    socketState = link + "; the car is not answering (ignition off?)";
                    clearValues();
                    publish(listener);
                    sleep(quiet(lastData) ? QUIET_POLL_MS : 2000);
                    continue;
                }
                socketState = link;
                afterDiscovery(out, in);
                publish(listener);
            }
            boolean any = false;
            for (int[] req : schedule.next(System.currentTimeMillis())) {
                String cmd = ObdReply.command(req);
                String reply = ask(out, in, cmd, REPLY_MS);
                long now = System.currentTimeMillis();
                rate.record(now);
                Map<Integer, int[]> data = ObdReply.mode01(reply, req);
                for (Map.Entry<Integer, int[]> e : data.entrySet()) apply(e.getKey(), e.getValue(), now);
                if (!data.isEmpty()) any = true;
                if (req.length > 1 && !multiWorks) {
                    // Until a multi-PID request has worked once, a few empty or one-PID answers mean it can't.
                    if (data.size() > 1) multiWorks = true;
                    else if (++multiFails >= MULTI_FAILS) schedule.singleOnly();
                }
            }
            long now = System.currentTimeMillis();
            if (any) {
                lastData = now;
                lastUpdate = now;
                state.obdReplyMillis = now;
            }
            expire(now);
            publish(listener);
            if (!any) sleep(quiet(lastData) ? QUIET_POLL_MS : IDLE_MS);
        }
    }

    private static boolean quiet(long lastData) {
        return System.currentTimeMillis() - lastData > QUIET_AFTER_MS;
    }

    /** Find the protocol and read the supported-PID bitmaps. False while the car stays silent. */
    private boolean discover(OutputStream out, InputStream in) throws Exception {
        Set<Integer> all = new TreeSet<>();
        Set<Integer> first = ObdReply.supported(ask(out, in, "0100", SEARCH_MS), 0x00);
        if (first == null) {
            // Some clones fumble the bitmap yet answer real requests.
            if (ObdReply.mode01(ask(out, in, "010C", REPLY_MS), ObdSchedule.RPM).isEmpty()) return false;
            all = null;
        } else {
            all.addAll(first);
            for (int base = 0x20; base <= 0x60 && all.contains(base); base += 0x20) {
                Set<Integer> next = ObdReply.supported(ask(out, in, ObdReply.command(base), REPLY_MS), base);
                if (next == null) break;
                all.addAll(next);
            }
        }
        supported = all;
        return true;
    }

    private void afterDiscovery(OutputStream out, InputStream in) throws Exception {
        protocol = ObdReply.protocol(ask(out, in, "ATDPN", REPLY_MS));
        // Aggressive adaptive timing, and give up on a silent ECU after 0x19 * 4 ms = 100 ms.
        ask(out, in, "ATAT2", REPLY_MS);
        ask(out, in, "ATST19", REPLY_MS);
        schedule = new ObdSchedule(supported, ObdReply.isCan(protocol));
        state.fuelUnsupported = !schedule.wants(ObdSchedule.FUEL);
        String vin = ObdReply.vin(ask(out, in, "0902", 5000));
        if (vin != null) {
            state.vin = vin;
            // Diagnostics exports get posted publicly: don't keep the serial number in the raw log.
            record("0902", "VIN " + Vin.masked(vin));
        }
    }

    // ---- values -----------------------------------------------------------

    private void apply(int pid, int[] b, long now) {
        seen.put(pid, now);
        switch (pid) {
            case ObdSchedule.RPM: state.rpm = ((b[0] * 256) + b[1]) / 4; break;
            case ObdSchedule.SPEED: state.speedKph = b[0]; break;
            case ObdSchedule.COOLANT: state.coolantC = b[0] - 40; break;
            case ObdSchedule.FUEL: state.fuelPercent = (int) Math.round(b[0] * 100.0 / 255.0); break;
            default: break;
        }
    }

    /** One missed reply keeps the last value; several in a row make it unknown again. */
    private void expire(long now) {
        Iterator<Map.Entry<Integer, Long>> it = seen.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, Long> e = it.next();
            if (now - e.getValue() > ObdSchedule.staleAfter(e.getKey())) {
                clear(e.getKey());
                it.remove();
            }
        }
    }

    private void clear(int pid) {
        switch (pid) {
            case ObdSchedule.RPM: state.rpm = VehicleState.UNKNOWN_INT; break;
            case ObdSchedule.SPEED: state.speedKph = VehicleState.UNKNOWN_INT; break;
            case ObdSchedule.COOLANT: state.coolantC = VehicleState.UNKNOWN_INT; break;
            case ObdSchedule.FUEL: state.fuelPercent = VehicleState.UNKNOWN_INT; break;
            default: break;
        }
    }

    private void clearValues() {
        for (Integer pid : seen.keySet()) clear(pid);
        seen.clear();
    }

    // ---- adapter plumbing -------------------------------------------------

    /** Send one command and return the reply up to the prompt, keeping it for Diagnostics. */
    private String ask(OutputStream out, InputStream in, String cmd, long timeoutMs) throws Exception {
        while (in.available() > 0) in.read();   // a late tail of the previous reply
        out.write((cmd + "\r").getBytes("US-ASCII"));
        out.flush();
        String reply = readUntilPrompt(in, timeoutMs);
        record(cmd, reply);
        return reply;
    }

    /** Keep the reply exactly as it came, control characters made visible. */
    private void record(String cmd, String reply) {
        StringBuilder sb = new StringBuilder();
        for (char c : reply.toCharArray()) {
            if (c == '\r') sb.append("\\r");
            else if (c == '\n') sb.append("\\n");
            else if (c < 0x20) sb.append(String.format("\\x%02X", (int) c));
            else sb.append(c);
        }
        synchronized (raw) {
            raw.put(cmd, sb.toString());
        }
    }

    /** ELM327 terminates every reply with '>'. */
    private String readUntilPrompt(InputStream in, long timeoutMs) throws Exception {
        StringBuilder sb = new StringBuilder();
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (current() && System.currentTimeMillis() < deadline) {
            if (in.available() > 0) {
                int c = in.read();
                if (c == '>') return sb.toString();
                if (c < 0) throw new IOException("link closed");
                sb.append((char) c);
            } else {
                Thread.sleep(2);
            }
        }
        return sb.toString();
    }

    private void publish(final VehicleState.Listener listener) {
        final VehicleState copy = state.copy();   // the main thread must not see a half-written cycle
        main.post(new Runnable() {
            @Override
            public void run() {
                if (running) listener.onVehicleState(copy);
            }
        });
    }

    private void closeQuietly() {
        try {
            if (socket != null) socket.close();
        } catch (Exception ignored) {
        }
        socket = null;
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
