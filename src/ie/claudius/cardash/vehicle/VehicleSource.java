package ie.claudius.cardash.vehicle;

import android.content.Context;

/**
 * A place car data comes from.
 *
 * Two implementations, deliberately kept behind one interface so the
 * cheap route today doesn't have to be thrown away when a canbox goes
 * in later:
 *
 *   ObdSource     — ELM327 over Bluetooth. Engine data, maybe fuel.
 *                   Cannot see doors; OBD-II has no PID for them.
 *   JancarSource  — the head unit's own vendor car service, which is
 *                   fed by a canbox on the harness. Doors, lights,
 *                   handbrake, reverse. Does nothing if no box fitted.
 *
 * The status methods exist for the Diagnostics screen: "not detected",
 * "detected but silent" and "talking but unparsed" look identical on the
 * home screen and are three different problems.
 */
public interface VehicleSource {

    /** Cheap check — is this source even worth starting on this device? */
    boolean isAvailable(Context ctx);

    /** Human label for the settings/status line. */
    String name();

    /** One line on the current state, including why it's unavailable. */
    String status(Context ctx);

    /** Wall-clock millis of the last update delivered, or 0 for never. */
    long lastUpdateMillis();

    void start(Context ctx, VehicleState.Listener listener);

    void stop();
}
