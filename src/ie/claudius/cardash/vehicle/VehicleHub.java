package ie.claudius.cardash.vehicle;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * Runs whichever sources this car actually has and merges them.
 *
 * The two sources overlap on almost nothing — OBD brings engine data,
 * the canbox brings body data — so merging is mostly a union. Where
 * both can report a field (fuel), the canbox wins: it reads the
 * cluster's own value rather than an optional OBD PID that Toyota may
 * not implement.
 *
 * There is exactly one of these per process. An ELM327 serves one
 * RFCOMM socket, so two hubs — say the home screen's and the
 * diagnostics screen's — would race for it and the loser would sit in
 * "connect failed" for no reason anyone could see. Screens attach a
 * listener and detach it; sources run while anyone is attached.
 *
 * Fuel is the one field worth remembering across restarts: it barely
 * changes while the unit is off, and the strip flashing empty for the
 * first seconds of every drive is the kind of thing you notice daily.
 * Doors and speed are not remembered — a stale "door open" is a lie.
 */
public final class VehicleHub {

    private static final String TAG = "CarDash/Vehicle";
    private static final long FUEL_MEMORY_MS = 24 * 60 * 60 * 1000L;

    public static final String MODE_AUTO = "auto", MODE_OBD = "obd",
            MODE_CAN = "can", MODE_OFF = "off";

    private static VehicleHub shared;

    public static synchronized VehicleHub shared() {
        if (shared == null) shared = new VehicleHub();
        return shared;
    }

    /** One row for the Diagnostics screen. */
    public static final class Entry {
        public final VehicleSource source;
        public boolean available;
        public String skipped;      // why it was not started, or null
        public boolean started;
        public int knownFields;
        /** When this source last delivered anything; 0 for never. */
        public long lastUpdate;

        Entry(VehicleSource s) {
            source = s;
        }
    }

    private final List<Entry> entries = new ArrayList<>(2);
    private final List<VehicleState.Listener> listeners = new ArrayList<>(2);
    private final VehicleState merged = new VehicleState();
    private Context appCtx;
    private String runningMode;
    private long lastUpdate;

    private VehicleHub() {}

    /**
     * Attach a listener; start the sources if this is the first one, or
     * restart them if the requested mode differs from what's running.
     * The listener gets the current merged state straight away if
     * there is anything in it.
     */
    public synchronized void start(Context ctx, VehicleState.Listener listener, String mode) {
        appCtx = ctx.getApplicationContext();
        if (!listeners.contains(listener)) listeners.add(listener);
        if (runningMode == null) {
            startSources(mode);
        } else if (!runningMode.equals(mode)) {
            stopSources();
            startSources(mode);
        }
        if (merged.hasAnything()) listener.onVehicleState(merged);
    }

    /** Detach; the sources stop when the last listener leaves. */
    public synchronized void stop(VehicleState.Listener listener) {
        listeners.remove(listener);
        if (listeners.isEmpty()) stopSources();
    }

    private void startSources(String mode) {
        runningMode = mode;
        entries.clear();
        restoreFuel();

        VehicleSource[] all = { new JancarSource(), new ObdSource() };
        for (VehicleSource s : all) {
            final Entry e = new Entry(s);
            entries.add(e);
            e.available = s.isAvailable(appCtx);
            boolean wanted = MODE_AUTO.equals(mode)
                    || (MODE_OBD.equals(mode) && s instanceof ObdSource)
                    || (MODE_CAN.equals(mode) && s instanceof JancarSource);
            if (!e.available) {
                e.skipped = s.status(appCtx);
            } else if (!wanted) {
                e.skipped = "disabled in settings";
            } else {
                e.started = true;
                s.start(appCtx, new VehicleState.Listener() {
                    @Override
                    public void onVehicleState(VehicleState state) {
                        e.knownFields = state.knownCount();
                        e.lastUpdate = System.currentTimeMillis();
                        merge(state);
                    }
                });
            }
        }
    }

    private void stopSources() {
        for (Entry e : entries) if (e.started) e.source.stop();
        for (Entry e : entries) e.started = false;
        runningMode = null;
    }

    /** True when at least one source is plugged in and talking. */
    public boolean hasSource() {
        for (Entry e : entries) if (e.started) return true;
        return false;
    }

    public List<Entry> entries() {
        return entries;
    }

    public VehicleState merged() {
        return merged;
    }

    public long lastUpdateMillis() {
        return lastUpdate;
    }

    private void merge(VehicleState s) {
        if (s.fuelPercent != VehicleState.UNKNOWN_INT) {
            // A canbox may hand over raw litres or a 0-255 byte; until its
            // scale is known, a value outside 0-100 is dropped rather than
            // shown. A gauge reading 255% is worse than none.
            if (s.fuelPercent >= 0 && s.fuelPercent <= 100) {
                merged.fuelPercent = s.fuelPercent;
                rememberFuel(s.fuelPercent);
            } else {
                Log.w(TAG, "fuel out of range, ignored: " + s.fuelPercent);
            }
        }
        if (s.speedKph != VehicleState.UNKNOWN_INT) merged.speedKph = s.speedKph;
        if (s.rpm != VehicleState.UNKNOWN_INT) merged.rpm = s.rpm;
        if (s.coolantC != VehicleState.UNKNOWN_INT) merged.coolantC = s.coolantC;

        if (s.doorFrontLeft != VehicleState.Tri.UNKNOWN) merged.doorFrontLeft = s.doorFrontLeft;
        if (s.doorFrontRight != VehicleState.Tri.UNKNOWN) merged.doorFrontRight = s.doorFrontRight;
        if (s.doorRearLeft != VehicleState.Tri.UNKNOWN) merged.doorRearLeft = s.doorRearLeft;
        if (s.doorRearRight != VehicleState.Tri.UNKNOWN) merged.doorRearRight = s.doorRearRight;
        if (s.boot != VehicleState.Tri.UNKNOWN) merged.boot = s.boot;
        if (s.handbrake != VehicleState.Tri.UNKNOWN) merged.handbrake = s.handbrake;
        if (s.reverse != VehicleState.Tri.UNKNOWN) merged.reverse = s.reverse;

        lastUpdate = System.currentTimeMillis();
        List<VehicleState.Listener> snapshot;
        synchronized (this) {
            snapshot = new ArrayList<>(listeners);
        }
        for (VehicleState.Listener l : snapshot) l.onVehicleState(merged);
    }

    private SharedPreferences prefs() {
        return appCtx.getSharedPreferences("cardash", Context.MODE_PRIVATE);
    }

    private void rememberFuel(int percent) {
        prefs().edit().putInt("veh_fuel", percent)
                .putLong("veh_fuel_at", System.currentTimeMillis()).apply();
    }

    private void restoreFuel() {
        SharedPreferences p = prefs();
        long at = p.getLong("veh_fuel_at", 0L);
        if (at > 0 && System.currentTimeMillis() - at < FUEL_MEMORY_MS) {
            merged.fuelPercent = p.getInt("veh_fuel", VehicleState.UNKNOWN_INT);
        }
    }
}
