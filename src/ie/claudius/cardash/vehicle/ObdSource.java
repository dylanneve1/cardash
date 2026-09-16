package ie.claudius.cardash.vehicle;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * ELM327 over Bluetooth serial.
 *
 * Deliberately conservative: one polling thread, a short list of PIDs,
 * and any parse failure leaves the field UNKNOWN rather than guessing.
 * Clones are riddled with firmware quirks and half of them answer
 * "NO DATA" or "?" to perfectly legal requests, so nothing here treats
 * a reply as trustworthy until it parses cleanly.
 *
 * Every raw reply is kept, verbatim, for the Diagnostics screen. That
 * is what settles "does this Yaris support fuel level" versus "is this
 * clone dongle misbehaving" — a question that otherwise costs an
 * afternoon.
 *
 * PIDs polled:
 *   010C  engine RPM          ((A*256)+B)/4
 *   010D  vehicle speed       A km/h
 *   0105  coolant temp        A-40 °C
 *   012F  fuel level          A*100/255 %   (optional on Toyota)
 */
public final class ObdSource implements VehicleSource {

    private static final String TAG = "CarDash/OBD";
    /** Standard Bluetooth SPP UUID — every ELM327 clone uses it. */
    private static final UUID SPP =
            UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final long POLL_MS = 1000L;
    private static final String[] NAME_HINTS = { "OBD", "ELM", "VLINK", "VGATE", "KONNWEI" };

    /** Snapshot for the Diagnostics screen. */
    public static final class Diag {
        public final List<String> paired = new ArrayList<>();
        public String matched;
        public String socket;
        public final Map<String, String> raw = new LinkedHashMap<>();
    }

    private volatile boolean running;
    private Thread worker;
    private BluetoothSocket socket;
    private String socketState = "not started";
    private long lastUpdate;
    private final Map<String, String> raw = new LinkedHashMap<>();

    private final VehicleState state = new VehicleState();
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override
    public String name() {
        return "OBD-II (ELM327)";
    }

    @Override
    public boolean isAvailable(Context ctx) {
        return findAdapter() != null;
    }

    @Override
    public String status(Context ctx) {
        BluetoothAdapter bt = BluetoothAdapter.getDefaultAdapter();
        if (bt == null) return "no Bluetooth adapter on this unit";
        try {
            if (!bt.isEnabled()) return "Bluetooth is off";
            if (findAdapter() == null) {
                return "no paired device named like an ELM327 ("
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
        d.socket = socketState;
        synchronized (raw) {
            d.raw.putAll(raw);
        }
        return d;
    }

    /** A paired device whose name looks like an ELM327 clone. */
    private BluetoothDevice findAdapter() {
        try {
            BluetoothAdapter bt = BluetoothAdapter.getDefaultAdapter();
            if (bt == null || !bt.isEnabled()) return null;
            for (BluetoothDevice d : bt.getBondedDevices()) {
                String n = d.getName();
                if (n == null) continue;
                String u = n.toUpperCase();
                for (String hint : NAME_HINTS) if (u.contains(hint)) return d;
            }
        } catch (SecurityException e) {
            // Bluetooth permission refused; treat as simply unavailable.
            Log.w(TAG, "no bluetooth permission");
        }
        return null;
    }

    @Override
    public void start(Context ctx, final VehicleState.Listener listener) {
        if (running) return;
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
        closeQuietly();
        socketState = "stopped";
        if (worker != null) worker.interrupt();
        worker = null;
    }

    private void loop(VehicleState.Listener listener) {
        while (running) {
            try {
                if (socket == null || !socket.isConnected()) {
                    if (!connect()) {
                        sleep(5000);
                        continue;
                    }
                }
                OutputStream out = socket.getOutputStream();
                InputStream in = socket.getInputStream();

                state.rpm = intPid(out, in, "010C", 2, RPM);
                state.speedKph = intPid(out, in, "010D", 1, RAW);
                state.coolantC = intPid(out, in, "0105", 1, COOLANT);
                state.fuelPercent = intPid(out, in, "012F", 1, PERCENT);

                lastUpdate = System.currentTimeMillis();
                publish(listener);
                sleep(POLL_MS);
            } catch (Exception e) {
                Log.w(TAG, "poll failed: " + e);
                socketState = "poll failed: " + e.getMessage();
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
            socket = dev.createRfcommSocketToServiceRecord(SPP);
            socket.connect();
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();
            // Reset, echo off, linefeeds off, spaces off, auto protocol.
            for (String init : new String[] { "ATZ", "ATE0", "ATL0", "ATS0", "ATSP0" }) {
                send(out, init);
                record(init, readUntilPrompt(in));
            }
            socketState = "connected to " + dev.getName();
            return true;
        } catch (Exception e) {
            Log.w(TAG, "connect failed: " + e);
            socketState = "connect failed: " + e.getMessage();
            closeQuietly();
            return false;
        }
    }

    // ---- PID plumbing ---------------------------------------------------

    private interface Decode { int apply(int[] bytes); }

    private static final Decode RPM = new Decode() {
        public int apply(int[] b) { return ((b[0] * 256) + b[1]) / 4; }
    };
    private static final Decode RAW = new Decode() {
        public int apply(int[] b) { return b[0]; }
    };
    private static final Decode COOLANT = new Decode() {
        public int apply(int[] b) { return b[0] - 40; }
    };
    private static final Decode PERCENT = new Decode() {
        public int apply(int[] b) { return (int) Math.round(b[0] * 100.0 / 255.0); }
    };

    private int intPid(OutputStream out, InputStream in,
                       String pid, int expected, Decode decode) {
        try {
            send(out, pid);
            String reply = readUntilPrompt(in);
            record(pid, reply);
            int[] bytes = ObdParse.payload(reply, pid, expected);
            if (bytes == null) return VehicleState.UNKNOWN_INT;
            return decode.apply(bytes);
        } catch (Exception e) {
            record(pid, "EXCEPTION " + e);
            return VehicleState.UNKNOWN_INT;
        }
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

    private void send(OutputStream out, String cmd) throws Exception {
        out.write((cmd + "\r").getBytes("US-ASCII"));
        out.flush();
    }

    /** ELM327 terminates every reply with '>'. */
    private String readUntilPrompt(InputStream in) throws Exception {
        StringBuilder sb = new StringBuilder();
        long deadline = System.currentTimeMillis() + 2000;
        while (System.currentTimeMillis() < deadline) {
            if (in.available() > 0) {
                int c = in.read();
                if (c == '>') return sb.toString();
                if (c >= 0) sb.append((char) c);
            } else {
                Thread.sleep(10);
            }
        }
        return sb.toString();
    }

    private void publish(final VehicleState.Listener listener) {
        main.post(new Runnable() {
            @Override
            public void run() {
                listener.onVehicleState(state);
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
