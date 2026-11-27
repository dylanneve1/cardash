package ie.claudius.cardash.vehicle;

/**
 * Several sources' latest states folded into one. For every field the
 * first source (in priority order) that knows it wins, so a value one
 * source stops reporting falls back to the next one's. Android-free;
 * see VehicleMergeTest.
 */
public final class VehicleMerge {

    private VehicleMerge() {}

    /** @param byPriority best first; nulls are skipped */
    public static VehicleState merge(VehicleState... byPriority) {
        VehicleState m = new VehicleState();
        boolean noFuel = false;
        for (VehicleState s : byPriority) {
            if (s == null) continue;
            // A canbox may hand over raw litres or a 0-255 byte; until its scale is known,
            // a value outside 0-100 is dropped: a gauge reading 255% is worse than none.
            if (m.fuelPercent == VehicleState.UNKNOWN_INT && s.fuelPercent >= 0 && s.fuelPercent <= 100) {
                m.fuelPercent = s.fuelPercent;
            }
            m.speedKph = first(m.speedKph, s.speedKph);
            m.rpm = first(m.rpm, s.rpm);
            m.coolantC = first(m.coolantC, s.coolantC);
            m.doorFrontLeft = first(m.doorFrontLeft, s.doorFrontLeft);
            m.doorFrontRight = first(m.doorFrontRight, s.doorFrontRight);
            m.doorRearLeft = first(m.doorRearLeft, s.doorRearLeft);
            m.doorRearRight = first(m.doorRearRight, s.doorRearRight);
            m.boot = first(m.boot, s.boot);
            m.handbrake = first(m.handbrake, s.handbrake);
            m.reverse = first(m.reverse, s.reverse);
            m.obdReplyMillis = Math.max(m.obdReplyMillis, s.obdReplyMillis);
            if (m.vin == null) m.vin = s.vin;
            noFuel |= s.fuelUnsupported;
        }
        m.fuelUnsupported = noFuel && m.fuelPercent == VehicleState.UNKNOWN_INT;
        return m;
    }

    private static int first(int a, int b) {
        return a != VehicleState.UNKNOWN_INT ? a : b;
    }

    private static VehicleState.Tri first(VehicleState.Tri a, VehicleState.Tri b) {
        return a != VehicleState.Tri.UNKNOWN ? a : b;
    }
}
