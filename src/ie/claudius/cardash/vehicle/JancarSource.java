package ie.claudius.cardash.vehicle;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.util.Log;

/**
 * Body-CAN data by way of the head unit's own vendor car service.
 *
 * IMPORTANT, and stated plainly: this is wired but unproven. The unit
 * it was written for has no canbox fitted, so `com.jancar.services` has
 * never had anything to report and the exact broadcast actions and
 * extra keys could not be observed. Jancar does not publish an SDK.
 *
 * Rather than invent a protocol, this listens on the plausible action
 * names AND logs every extra of anything it does receive, tagged
 * "CarDash/CAN". Fit a canbox, watch logcat, and the real keys fall
 * out in a couple of minutes — then they go in KEY_* below and this
 * becomes a real source. Until then it reports nothing, and the UI
 * hides the vehicle strip entirely rather than showing invented zeros.
 */
public final class JancarSource implements VehicleSource {

    private static final String TAG = "CarDash/CAN";
    private static final String VENDOR_PKG = "com.jancar.services";

    /** Candidate broadcasts seen referenced across Jancar-based ROMs. */
    private static final String[] ACTIONS = {
            "com.jancar.service.CAR_INFO",
            "com.jancar.service.CAR_DATA",
            "com.jancar.services.CAR_STATE",
            "android.intent.action.CAR_DOOR",
            "com.roadrover.CAR_STATE",
    };

    // Filled in once real traffic has been observed.
    private static final String KEY_DOOR_FL = "door_fl";
    private static final String KEY_DOOR_FR = "door_fr";
    private static final String KEY_DOOR_RL = "door_rl";
    private static final String KEY_DOOR_RR = "door_rr";
    private static final String KEY_BOOT = "door_trunk";
    private static final String KEY_HANDBRAKE = "handbrake";
    private static final String KEY_REVERSE = "reverse";
    private static final String KEY_FUEL = "fuel_level";

    private final VehicleState state = new VehicleState();
    private BroadcastReceiver receiver;

    @Override
    public String name() {
        return "Head unit CAN";
    }

    @Override
    public boolean isAvailable(Context ctx) {
        try {
            ctx.getPackageManager().getPackageInfo(VENDOR_PKG, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void start(Context ctx, final VehicleState.Listener listener) {
        if (receiver != null) return;
        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                dumpForDiscovery(intent);
                if (apply(intent)) listener.onVehicleState(state);
            }
        };
        IntentFilter f = new IntentFilter();
        for (String a : ACTIONS) f.addAction(a);
        ctx.registerReceiver(receiver, f);
    }

    @Override
    public void stop() {
        // Caller owns the context; unregister happens there.
        receiver = null;
    }

    public BroadcastReceiver receiver() {
        return receiver;
    }

    /** Log everything so the real key names can be read off logcat. */
    private void dumpForDiscovery(Intent intent) {
        Bundle b = intent.getExtras();
        if (b == null) {
            Log.i(TAG, intent.getAction() + " (no extras)");
            return;
        }
        StringBuilder sb = new StringBuilder(intent.getAction()).append(' ');
        for (String k : b.keySet()) {
            sb.append(k).append('=').append(b.get(k)).append(' ');
        }
        Log.i(TAG, sb.toString());
    }

    private boolean apply(Intent i) {
        boolean changed = false;
        changed |= setTri(i, KEY_DOOR_FL, 0);
        changed |= setTri(i, KEY_DOOR_FR, 1);
        changed |= setTri(i, KEY_DOOR_RL, 2);
        changed |= setTri(i, KEY_DOOR_RR, 3);
        changed |= setTri(i, KEY_BOOT, 4);
        changed |= setTri(i, KEY_HANDBRAKE, 5);
        changed |= setTri(i, KEY_REVERSE, 6);
        if (i.hasExtra(KEY_FUEL)) {
            state.fuelPercent = i.getIntExtra(KEY_FUEL, VehicleState.UNKNOWN_INT);
            changed = true;
        }
        return changed;
    }

    private boolean setTri(Intent i, String key, int which) {
        if (!i.hasExtra(key)) return false;
        VehicleState.Tri v = i.getBooleanExtra(key, false)
                ? VehicleState.Tri.YES : VehicleState.Tri.NO;
        switch (which) {
            case 0: state.doorFrontLeft = v; break;
            case 1: state.doorFrontRight = v; break;
            case 2: state.doorRearLeft = v; break;
            case 3: state.doorRearRight = v; break;
            case 4: state.boot = v; break;
            case 5: state.handbrake = v; break;
            default: state.reverse = v; break;
        }
        return true;
    }
}
