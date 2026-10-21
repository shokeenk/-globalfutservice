package com.globalfutservice.security;

import java.time.Duration;
import java.time.Instant;

import io.github.bucket4j.Bucket;

/**
 * A budget of attempts per key -- an email address, an IP -- that refills evenly over a
 * window.
 *
 * <p>Unlike {@link RateLimitFilter}, which counts every request to a path, this counts only
 * what the caller records: failed sign-ins, reset links asked for. A customer who signs in
 * first time never touches it; somebody working through a password list runs out within
 * the first few guesses at one address, and within a few dozen from one IP however many
 * addresses they spread them over.
 *
 * <p>Keys are counted whether or not an account exists behind them, so being slowed down
 * tells the caller nothing about the address. In memory, with the same bound and the same
 * single-instance caveat as {@link RateLimitFilter}.
 */
public final class AttemptThrottle {

    static final int MAX_KEYS = 20_000;

    private final int capacity;
    private final Duration window;
    private final BoundedBuckets buckets;

    public AttemptThrottle(int capacity, Duration window) {
        if (capacity < 1) throw new IllegalArgumentException("capacity must be at least 1");
        this.capacity = capacity;
        this.window = window;
        // Dropped no sooner than a full window, so a reset cannot hand out a fresh budget
        // faster than the refill would have.
        this.buckets = new BoundedBuckets(MAX_KEYS, window.compareTo(Duration.ofMinutes(10)) > 0
                ? window : Duration.ofMinutes(10));
    }

    /** True when {@code key} has no attempts left. Spends nothing. */
    public boolean exhausted(String key) {
        return key != null && bucket(key).getAvailableTokens() < 1;
    }

    /** Spends one attempt. False when there was none left to spend. */
    public boolean record(String key) {
        return key == null || bucket(key).tryConsume(1);
    }

    /** Attempts spent and not yet refilled: 0 for a key never recorded. */
    public long used(String key) {
        return key == null ? 0 : Math.max(0, capacity - bucket(key).getAvailableTokens());
    }

    /** When {@code key} will next have an attempt, at the earliest: one refill step from now. */
    public Instant retryAt(Instant now) {
        return now.plus(window.dividedBy(capacity));
    }

    private Bucket bucket(String key) {
        return buckets.get(key, () -> Bucket.builder()
                .addLimit(limit -> limit.capacity(capacity).refillGreedy(capacity, window))
                .build());
    }
}
