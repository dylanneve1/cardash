package ie.claudius.cardash.vehicle;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.util.Log;

import ie.claudius.cardash.Receivers;

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
 * "CarDash/CAN". The Diagnostics screen goes further: it reads the
 * actions the vendor packages declare in their own manifests and sniffs
 * all of them live, so the real names can be read off the screen
 * without a canbox ever being fitted. Then they go in KEY_* below and
 * this becomes a real source. Until then it reports nothing, and the UI
 * hides the vehicle strip entirely rather than showing invented zeros.
 */
public final class JancarSource implements VehicleSource {

    private static final String TAG = "CarDash/CAN";
    public static final String VENDOR_PKG = "com.jancar.services";

    /** Candidate broadcasts seen referenced across Jancar-based ROMs. */
    public static final String[] CANDIDATE_ACTIONS = {
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
    private Context registeredOn;
    private BroadcastReceiver receiver;
    private long lastUpdate;
    private int received;
    private String lastAction;

    @Override
    public String name() {
        return "Head unit CAN";
    }

    /**
     * The known vendor service, or anything the Diagnostics sweep found
     * declaring car-ish broadcasts. Vendors rename the service between
     * ROM builds; the sweep result is what actually exists on this unit.
     */
    @Override
    public boolean isAvailable(Context ctx) {
        return vendorPackage(ctx) != null;
    }

    private static String vendorPackage(Context ctx) {
        java.util.List<String> candidates = new java.util.ArrayList<>();
        candidates.add(VENDOR_PKG);
        candidates.addAll(ie.claudius.cardash.diag.Probe.rememberedSweep(ctx));
        for (String pkg : candidates) {
            try {
                ctx.getPackageManager().getPackageInfo(pkg, 0);
                return pkg;
            } catch (Exception ignored) {
                // not this one
            }
        }
        return null;
    }

    @Override
    public String status(Context ctx) {
        String pkg = vendorPackage(ctx);
        if (pkg == null) return "no vendor car service found (run Diagnostics to sweep)";
        if (receiver == null) return pkg + " present, not listening";
        if (received == 0) return "listening on " + CANDIDATE_ACTIONS.length
                + " candidate actions, nothing received";
        return received + " broadcasts, last " + lastAction;
    }

    @Override
    public long lastUpdateMillis() {
        return lastUpdate;
    }

    @Override
    public void start(Context ctx, final VehicleState.Listener listener) {
        if (receiver != null) return;
        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                received++;
                lastAction = intent.getAction();
                dumpForDiscovery(intent);
                if (apply(intent)) {
                    lastUpdate = System.currentTimeMillis();
                    listener.onVehicleState(state);
                }
            }
        };
        IntentFilter f = new IntentFilter();
        for (String a : CANDIDATE_ACTIONS) f.addAction(a);
        registeredOn = ctx.getApplicationContext();
        // Exported: the broadcasts come from the vendor's own package.
        Receivers.register(registeredOn, receiver, f, true);
    }

    @Override
    public void stop() {
        if (registeredOn != null) Receivers.unregister(registeredOn, receiver);
        receiver = null;
        registeredOn = null;
    }

    /** Log everything so the real key names can be read off logcat. */
    private void dumpForDiscovery(Intent intent) {
        Log.i(TAG, describe(intent));
    }

    /** "ACTION key=value (Type) ..." — the type is half the battle. */
    public static String describe(Intent intent) {
        Bundle b = intent.getExtras();
        StringBuilder sb = new StringBuilder(String.valueOf(intent.getAction()));
        if (b == null) return sb.append(" (no extras)").toString();
        for (String k : b.keySet()) {
            Object v = b.get(k);
            sb.append("\n    ").append(k).append('=').append(v)
                    .append(" (").append(v == null ? "null" : v.getClass().getSimpleName()).append(')');
        }
        return sb.toString();
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
