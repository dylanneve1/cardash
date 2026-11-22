package ie.claudius.cardash.remote;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.Map;

import ie.claudius.cardash.Prefs;

/**
 * Serves {@link RemoteForm} on the local network while "Phone settings"
 * is on. One thread, one connection at a time, short timeouts: this
 * answers a person tapping Save, nothing more.
 *
 * Listens on every interface (so it survives the hotspot coming up after
 * the app) but drops any peer that is not on a private LAN address.
 */
public final class RemoteServer {

    private static final String TAG = "CarDash/Remote";
    public static final int PORT = 8466;

    private static RemoteServer running;
    private static Runnable listener;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private final Context app;
    private final ServerSocket socket;
    private final RemoteForm.Session session = new RemoteForm.Session(new SecureRandom());
    private volatile boolean stopped;

    private RemoteServer(Context app, ServerSocket socket) {
        this.app = app;
        this.socket = socket;
    }

    /** Start or stop to match the setting. Cheap; call from onResume. */
    public static synchronized void sync(Context ctx) {
        boolean want = Prefs.remoteOn(ctx);
        if (want && running == null) {
            try {
                ServerSocket s = new ServerSocket();
                s.setReuseAddress(true);
                try {
                    s.bind(new InetSocketAddress(PORT));
                } catch (IOException busy) {
                    s.bind(new InetSocketAddress(0)); // someone has 8466; any port will do
                }
                running = new RemoteServer(ctx.getApplicationContext(), s);
                running.start();
            } catch (IOException e) {
                Log.w(TAG, "could not listen", e);
            }
        } else if (!want && running != null) {
            running.stop();
            running = null;
        }
    }

    /** "http://192.168.43.1:8466", or null when off or not on any LAN. */
    public static synchronized String address() {
        if (running == null) return null;
        String ip = lanAddress();
        if (ip == null) return null;
        int port = running.socket.getLocalPort();
        return "http://" + ip + (port == 80 ? "" : ":" + port);
    }

    public static synchronized String code() {
        return running == null ? null : running.session.code();
    }

    public static synchronized boolean locked() {
        return running != null && running.session.locked();
    }

    /** Called on the main thread after the phone changed something. */
    public static void setListener(Runnable r) {
        listener = r;
    }

    private void start() {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                loop();
            }
        }, "CarDash-remote");
        t.setDaemon(true);
        t.start();
    }

    private void stop() {
        stopped = true;
        try {
            socket.close();
        } catch (IOException ignored) {
            // closing anyway
        }
    }

    private void loop() {
        while (!stopped) {
            try (Socket c = socket.accept()) {
                if (!lan(c.getInetAddress())) continue;
                c.setSoTimeout(5000);
                RemoteForm.Request r = RemoteForm.read(c.getInputStream());
                String out = r == null
                        ? RemoteForm.response(400, "Bad Request", "", "")
                        : session.handle(r, store);
                OutputStream o = c.getOutputStream();
                o.write(out.getBytes(StandardCharsets.UTF_8));
                o.flush();
            } catch (IOException e) {
                if (!stopped) Log.d(TAG, "connection: " + e);
            }
        }
    }

    private final RemoteForm.Store store = new RemoteForm.Store() {
        @Override
        public String get(String key, String def) {
            return Prefs.get(app, key, def);
        }

        @Override
        public void apply(Map<String, String> changes) {
            for (Map.Entry<String, String> e : changes.entrySet()) {
                Prefs.set(app, e.getKey(), e.getValue());
            }
            MAIN.post(new Runnable() {
                @Override
                public void run() {
                    Runnable l = listener;
                    if (l != null) l.run();
                }
            });
        }
    };

    private static boolean lan(InetAddress a) {
        return a != null && (a.isSiteLocalAddress() || a.isLinkLocalAddress());
    }

    /** The unit's private IPv4: hotspot or Wi-Fi client, whichever is up. */
    static String lanAddress() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) {
                        return a.getHostAddress();
                    }
                }
            }
        } catch (Exception e) {
            Log.d(TAG, "interfaces: " + e);
        }
        return null;
    }
}
