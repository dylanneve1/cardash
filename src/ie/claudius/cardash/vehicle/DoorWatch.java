package ie.claudius.cardash.vehicle;

/**
 * Decides when the door pop-up shows. It appears as soon as a door, the
 * boot or the bonnet is reported open, stays while anything is open, and
 * leaves {@link #LINGER_MS} after the last one stops being. A dismissal
 * holds for the parts that were open at the time: another part opening,
 * or the same one after it has been reported shut, brings it back. A part
 * nobody reports is not an open one, and its silence doesn't count as
 * shut either. Android-free; see DoorWatchTest.
 */
public final class DoorWatch {

    public static final long LINGER_MS = 3000L;

    /** Bits of a part mask, in the order of {@code CarView}'s parts. */
    public static final int FRONT_LEFT = 1, FRONT_RIGHT = 2, REAR_LEFT = 4, REAR_RIGHT = 8,
            BOOT = 16, BONNET = 32;
    public static final int PARTS = 6;

    private int open, dismissed;
    private boolean showing;
    /** When the last open part closed, or -1 while something is open. */
    private long closedAt = -1;

    /** The parts of {@code s} that read {@code want}. */
    public static int mask(VehicleState s, VehicleState.Tri want) {
        if (s == null) return 0;
        VehicleState.Tri[] t = { s.doorFrontLeft, s.doorFrontRight, s.doorRearLeft,
                s.doorRearRight, s.boot, s.bonnet };
        int m = 0;
        for (int i = 0; i < PARTS; i++) if (t[i] == want) m |= 1 << i;
        return m;
    }

    /** @param threeDoor the car has no rear doors, whatever the bus says about them */
    public void update(VehicleState s, boolean threeDoor, long now) {
        int has = threeDoor ? ~(REAR_LEFT | REAR_RIGHT) : ~0;
        open = mask(s, VehicleState.Tri.YES) & has;
        dismissed &= ~mask(s, VehicleState.Tri.NO);
        if ((open & ~dismissed) != 0) {
            showing = true;
            closedAt = -1;
        } else if (showing && open == 0 && closedAt < 0) {
            closedAt = now;
        }
    }

    public boolean visible(long now) {
        if (showing && closedAt >= 0 && now - closedAt >= LINGER_MS) {
            showing = false;
            closedAt = -1;
        }
        return showing;
    }

    /** When the pop-up will leave by itself, or -1 while it has no reason to. */
    public long hidesAt() {
        return showing && closedAt >= 0 ? closedAt + LINGER_MS : -1;
    }

    /** The parts open at the last update. */
    public int open() {
        return open;
    }

    /** Tapped away. */
    public void dismiss() {
        dismissed |= open;
        showing = false;
        closedAt = -1;
    }

    /** Nobody is watching the car for now: show nothing until it is heard from again, keep dismissals. */
    public void suspend() {
        open = 0;
        showing = false;
        closedAt = -1;
    }

    /** Another data source: forget everything. */
    public void reset() {
        suspend();
        dismissed = 0;
    }
}
