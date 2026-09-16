package ie.claudius.cardash;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;

/**
 * One place to register a dynamic receiver.
 *
 * With targetSdk 30 the two-argument {@code registerReceiver} is fine.
 * From targetSdk 34 the framework refuses it unless you say whether
 * other apps may send to the receiver. Every register site goes through
 * here so raising the target is a one-line change rather than a hunt —
 * and so the vendor-broadcast listeners are explicitly marked as
 * needing to be reachable from other packages, which they do.
 */
public final class Receivers {

    private Receivers() {}

    /**
     * @param exported true when the broadcasts come from other apps
     *                 (the vendor car service); false for system-only
     *                 broadcasts like TIME_TICK.
     */
    public static void register(Context ctx, BroadcastReceiver r, IntentFilter f,
                                boolean exported, Handler handler) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            int flags = exported ? Context.RECEIVER_EXPORTED : Context.RECEIVER_NOT_EXPORTED;
            ctx.registerReceiver(r, f, null, handler, flags);
        } else {
            ctx.registerReceiver(r, f, null, handler);
        }
    }

    public static void register(Context ctx, BroadcastReceiver r, IntentFilter f, boolean exported) {
        register(ctx, r, f, exported, null);
    }

    public static void unregister(Context ctx, BroadcastReceiver r) {
        if (r == null) return;
        try {
            ctx.unregisterReceiver(r);
        } catch (IllegalArgumentException ignored) {
            // never registered, or already gone
        }
    }
}
