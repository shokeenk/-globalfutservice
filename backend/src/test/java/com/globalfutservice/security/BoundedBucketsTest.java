package com.globalfutservice.security;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import io.github.bucket4j.Bucket;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Rate-limit buckets in memory never grow past their ceiling, and never live past their reset. */
class BoundedBucketsTest {

    private static Bucket oneToken() {
        return Bucket.builder().addLimit(l -> l.capacity(1).refillGreedy(1, Duration.ofMinutes(1))).build();
    }

    @Test
    @DisplayName("the same caller keeps the same bucket, so its budget is spent, not renewed")
    void sameKeySameBucket() {
        BoundedBuckets buckets = new BoundedBuckets(10, Duration.ofMinutes(10));
        assertThat(buckets.get("ip:1", BoundedBucketsTest::oneToken).tryConsume(1)).isTrue();
        assertThat(buckets.get("ip:1", BoundedBucketsTest::oneToken).tryConsume(1)).isFalse();
        assertThat(buckets.size()).isOne();
    }

    @Test
    @DisplayName("a flood of distinct callers never holds more than the ceiling")
    void ceiling() {
        BoundedBuckets buckets = new BoundedBuckets(100, Duration.ofMinutes(10));
        for (int i = 0; i < 100_000; i++) {
            buckets.get("ip:" + i, BoundedBucketsTest::oneToken);
            assertThat(buckets.size()).isLessThanOrEqualTo(100);
        }
    }

    @Test
    @DisplayName("everything is dropped once the reset period has passed")
    void reset() {
        AtomicLong now = new AtomicLong(0);
        BoundedBuckets buckets = new BoundedBuckets(100, Duration.ofMinutes(10), now::get);
        for (int i = 0; i < 50; i++) buckets.get("acct:" + i, BoundedBucketsTest::oneToken);
        assertThat(buckets.size()).isEqualTo(50);

        now.set(Duration.ofMinutes(9).toMillis());
        buckets.get("acct:0", BoundedBucketsTest::oneToken);
        assertThat(buckets.size()).isEqualTo(50);

        now.set(Duration.ofMinutes(11).toMillis());
        buckets.get("acct:0", BoundedBucketsTest::oneToken);
        assertThat(buckets.size()).isOne();
    }
}
