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
