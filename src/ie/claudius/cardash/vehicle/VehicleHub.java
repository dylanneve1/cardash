package ie.claudius.cardash.vehicle;

import android.content.Context;

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
 */
public final class VehicleHub implements VehicleState.Listener {

    private final List<VehicleSource> active = new ArrayList<>(2);
    private final VehicleState merged = new VehicleState();
    private VehicleState.Listener out;
    private Context appCtx;

    public void start(Context ctx, VehicleState.Listener listener) {
        appCtx = ctx.getApplicationContext();
        out = listener;

        VehicleSource[] all = { new JancarSource(), new ObdSource() };
        for (VehicleSource s : all) {
            if (s.isAvailable(appCtx)) {
                active.add(s);
                s.start(appCtx, this);
            }
        }
    }

    public void stop() {
        for (VehicleSource s : active) s.stop();
        active.clear();
        out = null;
    }

    /** True when at least one source is plugged in and talking. */
    public boolean hasSource() {
        return !active.isEmpty();
    }

    @Override
    public void onVehicleState(VehicleState s) {
        if (s.fuelPercent != VehicleState.UNKNOWN_INT) merged.fuelPercent = s.fuelPercent;
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

        if (out != null) out.onVehicleState(merged);
    }
}
