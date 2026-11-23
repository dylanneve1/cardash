package ie.claudius.cardash.vehicle;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import android.provider.Settings;
import android.util.Log;

import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Car data on HCN head units (packages com.hcn.*), whose canbox app is
 * com.hcn.autocan. Facts and their sources are in docs/hcn.md.
 *
 * Three ways in, all read-only:
 *
 *  1. HCN's data service. Bind com.hcn.autocan/.service.CanService
 *     with action com.hcn.autocan.action.DATA_CTRL_SERVICE and register
 *     a callback. Done by hand with Binder.transact (no aidl tool). The
 *     callback's payloads are kept raw for Diagnostics and, when they
 *     look like Raise frames, decoded by {@link HcnParse}. Caveat: in
 *     autocan v42 nothing ever calls the callback, so on that build this
 *     stays silent; it is here for builds that do fire it.
 *  2. Door system properties persist.vendor.cem.cardoor1..5, which
 *     autocan writes when it shows its door popup (on some SoCs only).
 *  3. Settings.System "key_can_gear", which autocan writes for cars
 *     whose box reports the gear (102 = R).
 *
 * Never calls dataCtrlByte / dataCtrlString or anything else that would
 * send to the canbox or MCU: only transactions 3 and 4 (register /
 * unregister callback) are ever made.
 */
public final class HcnSource implements VehicleSource {

    private static final String TAG = "CarDash/HCN";

    public static final String VENDOR_PKG = "com.hcn.autocan";
    public static final String SERVICE_CLS = "com.hcn.autocan.service.CanService";
    public static final String ACTION_DATA = "com.hcn.autocan.action.DATA_CTRL_SERVICE";
    public static final String DESC_SERVICE = "com.hcn.autocan.aidl.ICustomDataServices";
    public static final String DESC_CALLBACK = "com.hcn.autocan.aidl.ICustomDataCallBack";

    // ICustomDataServices. 1 dataCtrlString and 2 dataCtrlByte send to the
    // box and are deliberately absent.
    private static final int TX_REGISTER = 3;
    private static final int TX_UNREGISTER = 4;
    // ICustomDataCallBack
    private static final int CB_STRING = 1;
    private static final int CB_BYTES = 2;

    static final String[] DOOR_PROPS = {
            "persist.vendor.cem.cardoor1", // front left
            "persist.vendor.cem.cardoor2", // front right
            "persist.vendor.cem.cardoor3", // rear left
            "persist.vendor.cem.cardoor4", // rear right
            "persist.vendor.cem.cardoor5", // boot
    };
    static final String SETTING_GEAR = "key_can_gear";

    private static final long POLL_MS = 1000L;
    private static final long REBIND_MS = 5000L;
    private static final int LOG_MAX = 40;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final VehicleState state = new VehicleState();
    private final HcnParse.Body body = new HcnParse.Body();
    private final List<String> log = new ArrayList<>();

    private Context ctx;
    private VehicleState.Listener listener;
    private boolean running;
    private boolean bound;
    private IBinder service;
    private boolean registered;
    private String bindState = "not started";
    private int payloads;
    private long lastUpdate;
    private String lastProps = "";
    private int lastGear = Integer.MIN_VALUE;
    private int reverse = HcnParse.UNKNOWN;

    /** Our ICustomDataCallBack, implemented on a bare Binder. */
    private final Binder callback = new Binder() {
        {
            attachInterface(null, DESC_CALLBACK);
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (code != CB_STRING && code != CB_BYTES) return super.onTransact(code, data, reply, flags);
            data.enforceInterface(DESC_CALLBACK);
            final byte[] bytes = code == CB_BYTES ? data.createByteArray() : null;
            final String text = code == CB_STRING ? data.readString() : null;
            if (reply != null) reply.writeNoException();
            main.post(new Runnable() {
                @Override
                public void run() {
                    onPayload(bytes, text);
                }
            });
            return true;
        }
    };

    private final IBinder.DeathRecipient death = new IBinder.DeathRecipient() {
        @Override
        public void binderDied() {
            main.post(new Runnable() {
                @Override
                public void run() {
                    note("service died");
                    service = null;
                    registered = false;
                    bindState = "service died, waiting for it to come back";
                    // A BIND_AUTO_CREATE binding reconnects by itself when the
                    // service restarts; rebind anyway in case it doesn't.
                    scheduleRebind();
                }
            });
        }
    };

    private final ServiceConnection conn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder b) {
            service = b;
            try {
                b.linkToDeath(death, 0);
            } catch (RemoteException e) {
                service = null;
                scheduleRebind();
                return;
            }
            registered = transact(TX_REGISTER);
            bindState = registered ? "bound, callback registered" : "bound, register failed";
            note(bindState);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
            registered = false;
            bindState = "disconnected, waiting";
            note(bindState);
        }

        @Override
        public void onBindingDied(ComponentName name) {
            service = null;
            registered = false;
            bindState = "binding died, rebinding";
            note(bindState);
            unbind();
            scheduleRebind();
        }
    };

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            pollProperties();
            main.postDelayed(this, POLL_MS);
        }
    };

    private final Runnable rebind = new Runnable() {
        @Override
        public void run() {
            if (running && !bound) bind();
        }
    };

    @Override
    public String name() {
        return "HCN canbox (com.hcn.autocan)";
    }

    @Override
    public boolean isAvailable(Context c) {
        try {
            c.getPackageManager().getPackageInfo(VENDOR_PKG, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public String status(Context c) {
        if (!isAvailable(c)) return VENDOR_PKG + " not installed";
        if (!running) return VENDOR_PKG + " present, not started";
        return bindState + "; " + payloads + " callback payloads; props " + lastProps;
    }

    @Override
    public long lastUpdateMillis() {
        return lastUpdate;
    }

    @Override
    public void start(Context c, VehicleState.Listener l) {
        if (running) return;
        ctx = c.getApplicationContext();
        listener = l;
        running = true;
        bind();
        main.post(poll);
    }

    @Override
    public void stop() {
        if (!running) return;
        running = false;
        main.removeCallbacks(poll);
        main.removeCallbacks(rebind);
        if (service != null && registered) transact(TX_UNREGISTER);
        if (service != null) {
            try {
                service.unlinkToDeath(death, 0);
            } catch (Exception ignored) {
                // already gone
            }
        }
        service = null;
        registered = false;
        unbind();
        bindState = "stopped";
        listener = null;
    }

    /** Raw payloads and decoded body, newest last, for Diagnostics. */
    public synchronized List<String> recentPayloads() {
        return new ArrayList<>(log);
    }

    /** What the frames have decoded to so far, for Diagnostics. */
    public String decoded() {
        return body.summary();
    }

    // ---- binding -------------------------------------------------------------------------

    private void bind() {
        Intent i = new Intent(ACTION_DATA);
        i.setComponent(new ComponentName(VENDOR_PKG, SERVICE_CLS));
        try {
            bound = ctx.bindService(i, conn, Context.BIND_AUTO_CREATE);
        } catch (SecurityException e) {
            bound = false;
            bindState = "bind refused: " + e.getMessage();
            note(bindState);
            return;
        }
        bindState = bound ? "binding" : "bindService returned false";
        if (!bound) {
            note(bindState);
            scheduleRebind();
        }
    }

    private void unbind() {
        if (!bound) return;
        bound = false;
        try {
            ctx.unbindService(conn);
        } catch (IllegalArgumentException ignored) {
            // not bound
        }
    }

    private void scheduleRebind() {
        if (!running) return;
        main.removeCallbacks(rebind);
        main.postDelayed(rebind, REBIND_MS);
    }

    /** ICustomDataServices.registerDataCallBack / unRegisterDataCallBack. */
    private boolean transact(int code) {
        IBinder b = service;
        if (b == null) return false;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESC_SERVICE);
            data.writeStrongBinder(callback);
            if (!b.transact(code, data, reply, 0)) return false;
            reply.readException();
            return true;
        } catch (Exception e) {
            Log.w(TAG, "transact " + code + " failed", e);
            note("transact " + code + " failed: " + e);
            return false;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    // ---- data ----------------------------------------------------------------------------

    private void onPayload(byte[] bytes, String text) {
        payloads++;
        byte[] raw = bytes != null ? bytes : HcnParse.hex(text);
        String shown = bytes != null ? "bytes " + HcnParse.hexOf(bytes) : "string \"" + text + '"';
        Log.i(TAG, shown);
        boolean understood = false;
        if (raw != null) {
            for (HcnParse.Frame f : HcnParse.frames(raw)) {
                understood |= HcnParse.apply(f, body);
            }
        }
        note(shown + (understood ? "  [decoded]" : ""));
        if (understood) publish();
    }

    private void pollProperties() {
        StringBuilder sb = new StringBuilder();
        int[] doors = new int[DOOR_PROPS.length];
        for (int i = 0; i < DOOR_PROPS.length; i++) {
            String v = sysprop(DOOR_PROPS[i]);
            sb.append(v == null ? "-" : v.isEmpty() ? "_" : v);
            doors[i] = HcnParse.doorProp(v);
        }
        int gear = Integer.MIN_VALUE;
        try {
            gear = Settings.System.getInt(ctx.getContentResolver(), SETTING_GEAR, Integer.MIN_VALUE);
        } catch (Exception ignored) {
            // not readable on this ROM
        }
        sb.append(" gear=").append(gear == Integer.MIN_VALUE ? "-" : String.valueOf(gear));
        String now = sb.toString();
        if (now.equals(lastProps) && gear == lastGear) return;
        lastProps = now;
        lastGear = gear;
        note("props doors(FL FR RL RR boot)=" + now);

        boolean any = false;
        // Doors from the properties only when they say something; a frame,
        // if one ever arrives, overwrites them on the next publish.
        int[] cur = { body.doorFrontLeft, body.doorFrontRight, body.doorRearLeft,
                body.doorRearRight, body.boot };
        for (int i = 0; i < doors.length; i++) {
            if (doors[i] != HcnParse.UNKNOWN) {
                cur[i] = doors[i];
                any = true;
            }
        }
        body.doorFrontLeft = cur[0];
        body.doorFrontRight = cur[1];
        body.doorRearLeft = cur[2];
        body.doorRearRight = cur[3];
        body.boot = cur[4];
        int rev = HcnParse.reverseFromGear(gear);
        if (rev != HcnParse.UNKNOWN) any = true;
        reverse = rev;
        if (any) publish();
    }

    private void publish() {
        state.doorFrontLeft = tri(body.doorFrontLeft);
        state.doorFrontRight = tri(body.doorFrontRight);
        state.doorRearLeft = tri(body.doorRearLeft);
        state.doorRearRight = tri(body.doorRearRight);
        state.boot = tri(body.boot);
        state.handbrake = tri(body.handbrake);
        state.reverse = tri(reverse);
        if (body.rpm != HcnParse.UNKNOWN) state.rpm = body.rpm;
        // Speed is decoded but not published: autocan polls this frame every
        // 10 s, and the speedometer prefers a vehicle speed over GPS, so a
        // 10 s old number would beat a live one. Diagnostics still shows it.
        int fuel = HcnParse.fuelPercent(body.fuelLitres, HcnParse.TANK_LITRES_POLO_6R);
        if (fuel != HcnParse.UNKNOWN) state.fuelPercent = fuel;
        lastUpdate = System.currentTimeMillis();
        VehicleState.Listener l = listener;
        if (l != null) l.onVehicleState(state);
    }

    private static VehicleState.Tri tri(int v) {
        if (v == HcnParse.UNKNOWN) return VehicleState.Tri.UNKNOWN;
        return v != 0 ? VehicleState.Tri.YES : VehicleState.Tri.NO;
    }

    private synchronized void note(String line) {
        String t = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        log.add(t + "  " + line);
        while (log.size() > LOG_MAX) log.remove(0);
    }

    /** android.os.SystemProperties.get, by reflection; null if blocked. */
    private static String sysprop(String key) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Method get = sp.getMethod("get", String.class);
            return (String) get.invoke(null, key);
        } catch (Throwable t) {
            return null;
        }
    }
}
