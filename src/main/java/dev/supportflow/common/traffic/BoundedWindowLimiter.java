package dev.supportflow.common.traffic;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** Fixed windows expire from first admission. Full maps reject new identities instead of evicting active quotas. */
public final class BoundedWindowLimiter {
    public enum Outcome { ALLOWED, LIMIT_REACHED, CAPACITY_REACHED }
    public record Admission(Outcome outcome, long retryAfterSeconds) {
        public boolean allowed() { return outcome == Outcome.ALLOWED; }
    }

    private final int limit;
    private final int capacity;
    private final long windowNanos;
    private final LongSupplier nanoTime;
    // Entries are ordered by window start, not last access. Cleanup only visits expired head entries.
    private final LinkedHashMap<String, Window> windows = new LinkedHashMap<>();

    public BoundedWindowLimiter(int limit, Duration window, int capacity, LongSupplier nanoTime) {
        if (limit < 1 || capacity < 1 || window == null || window.isNegative() || window.isZero())
            throw new IllegalArgumentException("Positive window limits and capacity are required");
        this.limit = limit;
        this.capacity = capacity;
        this.windowNanos = window.toNanos();
        this.nanoTime = Objects.requireNonNull(nanoTime);
    }

    public synchronized Admission acquire(String identity) {
        Objects.requireNonNull(identity);
        long now = nanoTime.getAsLong();
        expire(now);
        Window current = windows.get(identity);
        if (current != null) {
            if (current.used >= limit) return new Admission(Outcome.LIMIT_REACHED, retryAfter(current, now));
            current.used++;
            return new Admission(Outcome.ALLOWED, 0);
        }
        if (windows.size() >= capacity)
            return new Admission(Outcome.CAPACITY_REACHED, retryAfter(windows.firstEntry().getValue(), now));
        windows.put(identity, new Window(now));
        return new Admission(Outcome.ALLOWED, 0);
    }

    public synchronized int retainedIdentities() {
        expire(nanoTime.getAsLong());
        return windows.size();
    }

    private void expire(long now) {
        while (!windows.isEmpty() && elapsed(windows.firstEntry().getValue(), now) >= windowNanos)
            windows.pollFirstEntry();
    }

    private long retryAfter(Window current, long now) {
        long remaining = Math.max(1, windowNanos - elapsed(current, now));
        return Math.max(1, TimeUnit.NANOSECONDS.toSeconds(remaining - 1) + 1);
    }

    private static long elapsed(Window window, long now) { return Math.max(0, now - window.started); }

    private static final class Window {
        private final long started;
        private int used = 1;
        private Window(long started) { this.started = started; }
    }
}
