package ie.claudius.cardash.vehicle;

/**
 * When to say "unplug the OBD dongle": a dongle left in a parked car
 * keeps drawing from the battery. Once the dongle has been reached, a
 * few minutes without the engine turning (RPM 0, or no answers at all)
 * raises the reminder, once per stop: only running the engine again arms
 * the next one. Android-free; see UnplugReminderTest.
 */
public final class UnplugReminder {

    public static final long IDLE_MS = 3 * 60 * 1000L;
    /** A longer silence between checks means the unit slept: that time says nothing about the car. */
    static final long GAP_MS = 60 * 1000L;

    private boolean armed, showing, seen;
    private long idleSince, lastCheck, lastReply;

    /**
     * Feed the latest state, or call again on a timer with the same one.
     *
     * @param linked      the adapter is connected
     * @param replyMillis when the car last answered, 0 for never
     * @param rpm         engine speed, or {@link VehicleState#UNKNOWN_INT}
     * @return true exactly when the reminder should be raised now
     */
    public boolean update(long now, boolean linked, long replyMillis, int rpm) {
        if (lastCheck != 0 && now - lastCheck > GAP_MS) idleSince = now;
        lastCheck = now;
        boolean replied = replyMillis > lastReply;
        if (replied) lastReply = replyMillis;
        if (!seen && (linked || replied)) {
            seen = true;
            armed = true;
            idleSince = now;
        }
        if (rpm != VehicleState.UNKNOWN_INT && rpm > 0) {
            seen = true;
            armed = true;
            showing = false;
            idleSince = now;
            return false;
        }
        if (armed && now - idleSince >= IDLE_MS) {
            armed = false;
            showing = true;
            return true;
        }
        return false;
    }

    /** True from the reminder until it is dismissed or the engine runs again. */
    public boolean showing() {
        return showing;
    }

    public void dismiss() {
        showing = false;
    }
}
