package com.atompay.cardpaycore.security;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

/**
 * Fixed-window request counter keyed by caller identity. In-memory and
 * per-instance by design -- fine for a single-instance deployment, but a
 * multi-instance deployment would need a shared store (e.g. Redis) instead.
 */
public class RateLimiter {

    private final int limit;
    private final long windowMillis;
    private final LongSupplier clock;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    public RateLimiter(int limit, long windowMillis) {
        this(limit, windowMillis, System::currentTimeMillis);
    }

    RateLimiter(int limit, long windowMillis, LongSupplier clock) {
        this.limit = limit;
        this.windowMillis = windowMillis;
        this.clock = clock;
    }

    /** Returns true if the request is allowed, false if the caller is over the limit. */
    public boolean tryAcquire(String key) {
        long now = clock.getAsLong();
        Window window = windows.compute(key, (k, existing) -> {
            if (existing == null || now - existing.windowStart >= windowMillis) {
                return new Window(now);
            }
            existing.count.incrementAndGet();
            return existing;
        });
        return window.count.get() <= limit;
    }

    /** Milliseconds until the caller's current window resets. */
    public long millisUntilReset(String key) {
        Window window = windows.get(key);
        if (window == null) {
            return 0;
        }
        long elapsed = clock.getAsLong() - window.windowStart;
        return Math.max(0, windowMillis - elapsed);
    }

    private static final class Window {
        final long windowStart;
        final AtomicInteger count = new AtomicInteger(1);

        Window(long windowStart) {
            this.windowStart = windowStart;
        }
    }
}
