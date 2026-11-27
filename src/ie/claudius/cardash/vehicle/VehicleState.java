package ie.claudius.cardash.vehicle;

/**
 * Everything the launcher might know about the car.
 *
 * Every field is explicitly "unknown" until something tells us
 * otherwise, because the two data sources expose completely different
 * subsets — an OBD dongle can read fuel and RPM but has no idea whether
 * a door is open, and a canbox knows about doors but may never report
 * coolant. The UI hides whatever is unknown rather than rendering a
 * confident zero.
 */
public final class VehicleState {

    public static final int UNKNOWN_INT = Integer.MIN_VALUE;

    /** Tri-state: a door we've had no word about is not the same as shut. */
    public enum Tri { UNKNOWN, YES, NO }

    public int fuelPercent = UNKNOWN_INT;
    public int speedKph = UNKNOWN_INT;
    public int rpm = UNKNOWN_INT;
    public int coolantC = UNKNOWN_INT;

    public Tri doorFrontLeft = Tri.UNKNOWN;
    public Tri doorFrontRight = Tri.UNKNOWN;
    public Tri doorRearLeft = Tri.UNKNOWN;
    public Tri doorRearRight = Tri.UNKNOWN;
    public Tri boot = Tri.UNKNOWN;
    public Tri handbrake = Tri.UNKNOWN;
    public Tri reverse = Tri.UNKNOWN;

    /** Wall-clock millis of the last valid OBD data reply, 0 for never. */
    public long obdReplyMillis;

    /** The car's VIN, or null. */
    public String vin;
    /** The car says it has no fuel level on OBD and nothing else reports one: show "unavailable". */
    public boolean fuelUnsupported;

    /** A snapshot that later writes to this one don't touch. */
    public VehicleState copy() {
        VehicleState c = new VehicleState();
        c.fuelPercent = fuelPercent;
        c.speedKph = speedKph;
        c.rpm = rpm;
        c.coolantC = coolantC;
        c.doorFrontLeft = doorFrontLeft;
        c.doorFrontRight = doorFrontRight;
        c.doorRearLeft = doorRearLeft;
        c.doorRearRight = doorRearRight;
        c.boot = boot;
        c.handbrake = handbrake;
        c.reverse = reverse;
        c.obdReplyMillis = obdReplyMillis;
        c.vin = vin;
        c.fuelUnsupported = fuelUnsupported;
        return c;
    }

    public boolean hasAnything() {
        return fuelPercent != UNKNOWN_INT
                || speedKph != UNKNOWN_INT
                || rpm != UNKNOWN_INT
                || coolantC != UNKNOWN_INT
                || anyDoorKnown();
    }

    public boolean anyDoorKnown() {
        return doorFrontLeft != Tri.UNKNOWN
                || doorFrontRight != Tri.UNKNOWN
                || doorRearLeft != Tri.UNKNOWN
                || doorRearRight != Tri.UNKNOWN
                || boot != Tri.UNKNOWN;
    }

    /** Fields with a value, out of the eleven — a health number for diagnostics. */
    public int knownCount() {
        int n = 0;
        if (fuelPercent != UNKNOWN_INT) n++;
        if (speedKph != UNKNOWN_INT) n++;
        if (rpm != UNKNOWN_INT) n++;
        if (coolantC != UNKNOWN_INT) n++;
        for (Tri t : new Tri[] { doorFrontLeft, doorFrontRight, doorRearLeft,
                doorRearRight, boot, handbrake, reverse }) {
            if (t != Tri.UNKNOWN) n++;
        }
        return n;
    }

    public static final int FIELDS = 11;

    /** How many doors are actually standing open right now. */
    public int openDoors() {
        int n = 0;
        if (doorFrontLeft == Tri.YES) n++;
        if (doorFrontRight == Tri.YES) n++;
        if (doorRearLeft == Tri.YES) n++;
        if (doorRearRight == Tri.YES) n++;
        if (boot == Tri.YES) n++;
        return n;
    }

    public interface Listener {
        void onVehicleState(VehicleState state);
    }
}
