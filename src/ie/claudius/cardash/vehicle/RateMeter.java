package ie.claudius.cardash.vehicle;

import java.util.ArrayDeque;

/** Events per second over a short sliding window: the measured OBD request rate. Android-free. */
public final class RateMeter {

    public static final long WINDOW_MS = 5000L;
    private final ArrayDeque<Long> times = new ArrayDeque<>();

    public synchronized void record(long now) {
        times.add(now);
        trim(now);
    }

    public synchronized float perSecond(long now) {
        trim(now);
        if (times.size() < 2) return 0f;
        long span = now - times.peekFirst();
        return span <= 0 ? 0f : (times.size() - 1) * 1000f / Math.max(span, 1000L);
    }

    public synchronized void reset() {
        times.clear();
    }

    private void trim(long now) {
        while (!times.isEmpty() && now - times.peekFirst() > WINDOW_MS) times.pollFirst();
    }
}
