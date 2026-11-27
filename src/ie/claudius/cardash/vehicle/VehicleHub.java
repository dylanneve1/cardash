package ie.claudius.cardash.vehicle;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

import ie.claudius.cardash.Prefs;

/**
 * Runs whichever sources this car actually has and merges them.
 *
 * The hub keeps each source's latest state and folds them together in a
 * fixed priority order ({@link VehicleMerge}): the OBD dongle first
 * (live engine values, and fuel where the car has the PID), then the
 * canbox sources, which are the only ones that know about doors and
 * stand in for fuel when OBD lacks it.
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
        /** This source's latest state. */
        VehicleState latest;

        Entry(VehicleSource s) {
            source = s;
        }
    }

    private final List<Entry> entries = new ArrayList<>(3);
    private final List<VehicleState.Listener> listeners = new ArrayList<>(2);
    private VehicleState merged = new VehicleState();
    /** Remembered values from last time: used only while no source knows better. */
    private final VehicleState memory = new VehicleState();
    private long rememberedAt;
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

        // Listed in merge priority order.
        VehicleSource[] all = { new ObdSource(), new HcnSource(), new JancarSource() };
        for (VehicleSource s : all) {
            final Entry e = new Entry(s);
            entries.add(e);
            e.available = s.isAvailable(appCtx);
            boolean wanted = MODE_AUTO.equals(mode)
                    || (MODE_OBD.equals(mode) && s instanceof ObdSource)
                    || (MODE_CAN.equals(mode) && (s instanceof JancarSource || s instanceof HcnSource));
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
                        e.latest = state.copy();
                        merge();
                    }
                });
            }
        }
    }

    private void stopSources() {
        for (Entry e : entries) if (e.started) e.source.stop();
        for (Entry e : entries) {
            e.started = false;
            e.latest = null;
        }
        runningMode = null;
        merged = new VehicleState();
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

    private void merge() {
        VehicleState[] states = new VehicleState[entries.size() + 1];
        for (int i = 0; i < entries.size(); i++) states[i] = entries.get(i).latest;
        VehicleState live = VehicleMerge.merge(states);
        if (live.fuelPercent != VehicleState.UNKNOWN_INT) rememberFuel(live.fuelPercent);
        states[entries.size()] = memory;
        merged = VehicleMerge.merge(states);
        if (live.vin != null && !live.vin.equals(Prefs.get(appCtx, Prefs.CAR_VIN, null))) {
            Prefs.set(appCtx, Prefs.CAR_VIN, live.vin);
        }

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
        long now = System.currentTimeMillis();
        // Live data arrives several times a second: write only on a change, or now and then to stay fresh.
        if (percent == memory.fuelPercent && now - rememberedAt < 60_000L) return;
        memory.fuelPercent = percent;
        rememberedAt = now;
        prefs().edit().putInt("veh_fuel", percent).putLong("veh_fuel_at", now).apply();
    }

    private void restoreFuel() {
        memory.fuelPercent = VehicleState.UNKNOWN_INT;
        rememberedAt = 0;
        SharedPreferences p = prefs();
        long at = p.getLong("veh_fuel_at", 0L);
        if (at > 0 && System.currentTimeMillis() - at < FUEL_MEMORY_MS) {
            memory.fuelPercent = p.getInt("veh_fuel", VehicleState.UNKNOWN_INT);
        }
        merged = VehicleMerge.merge(memory);
    }
}
