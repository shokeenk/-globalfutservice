package com.globalfutservice.security;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import io.github.bucket4j.Bucket;

/**
 * Rate-limit buckets kept in memory, with a ceiling on how many and how long.
 *
 * <p>Keyed by an IP, an account or a Discord user, a plain map only ever grows: every new
 * caller adds a bucket and nothing takes it away. Here the whole set is dropped every
 * {@code resetEvery}, and at once if it reaches {@code maxEntries} -- a flood of distinct
 * callers cannot hold more than that many buckets in memory.
 *
 * <p>Dropping a bucket gives that caller a fresh budget, which is the trade every in-memory
 * limiter here already makes (RateLimitFilter has always cleared its map every ten minutes).
 * The budgets refill within a minute anyway; what a reset can grant is at most one extra
 * minute's worth, once per reset.
 */
public final class BoundedBuckets {

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final int maxEntries;
    private final long resetEveryMillis;
    private final LongSupplier clock;
    private final AtomicLong lastReset;

    public BoundedBuckets(int maxEntries, Duration resetEvery) {
        this(maxEntries, resetEvery, System::currentTimeMillis);
    }

    BoundedBuckets(int maxEntries, Duration resetEvery, LongSupplier clock) {
        if (maxEntries < 1) throw new IllegalArgumentException("maxEntries must be at least 1");
        this.maxEntries = maxEntries;
        this.resetEveryMillis = resetEvery.toMillis();
        this.clock = clock;
        this.lastReset = new AtomicLong(clock.getAsLong());
    }

    /** The bucket for {@code key}, made with {@code create} if there is none. */
    public Bucket get(String key, Supplier<Bucket> create) {
        long now = clock.getAsLong();
        long previous = lastReset.get();
        if (now - previous > resetEveryMillis && lastReset.compareAndSet(previous, now)) {
            buckets.clear();
        }
        if (buckets.size() >= maxEntries && !buckets.containsKey(key)) {
            buckets.clear();
        }
        return buckets.computeIfAbsent(key, k -> create.get());
    }

    /** How many buckets are held now. */
    public int size() {
        return buckets.size();
    }
}
