package ie.claudius.cardash.vehicle;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

/**
 * The simulated vehicle as a source. It runs only while the simulator is
 * switched on in Diagnostics, and then it runs alone, so pretend and real
 * data never mix. The dash is marked while it does.
 */
public final class SimSource implements VehicleSource {

    private static final long TICK_MS = 200L;

    private final Handler main = new Handler(Looper.getMainLooper());
    private VehicleState.Listener listener;
    private long lastUpdate;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (listener == null) return;
            lastUpdate = System.currentTimeMillis();
            listener.onVehicleState(SimVehicle.SHARED.state(lastUpdate));
            main.postDelayed(this, TICK_MS);
        }
    };

    @Override
    public boolean isAvailable(Context ctx) {
        return true;
    }

    @Override
    public String name() {
        return "Simulated vehicle";
    }

    @Override
    public String status(Context ctx) {
        return SimVehicle.SHARED.cycling() ? "playing the drive cycle" : "values set by hand";
    }

    @Override
    public long lastUpdateMillis() {
        return lastUpdate;
    }

    @Override
    public void start(Context ctx, VehicleState.Listener l) {
        listener = l;
        main.removeCallbacks(tick);
        main.post(tick);
    }

    @Override
    public void stop() {
        listener = null;
        main.removeCallbacks(tick);
    }
}
