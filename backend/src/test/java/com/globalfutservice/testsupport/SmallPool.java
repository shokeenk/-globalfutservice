package com.globalfutservice.testsupport;

import com.zaxxer.hikari.HikariDataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A deliberately small connection pool for the Postgres tests, so a connection that is
 * not given back shows up at once: the next borrower times out instead of quietly using
 * one of many. And a check that every connection is back.
 */
public final class SmallPool {

    private SmallPool() {
    }

    public static HikariDataSource of(String url, String user, String password, int size) {
        HikariDataSource pool = new HikariDataSource();
        pool.setJdbcUrl(url);
        pool.setUsername(user);
        pool.setPassword(password);
        pool.setMaximumPoolSize(size);
        pool.setMinimumIdle(0);
        pool.setConnectionTimeout(3_000);
        pool.setLeakDetectionThreshold(2_000);
        pool.setPoolName("test-" + size);
        return pool;
    }

    /** Every connection is back in the pool. Waits a moment for one being returned right now. */
    public static void assertAllReturned(HikariDataSource pool) {
        long until = System.nanoTime() + 2_000_000_000L;
        while (active(pool) > 0 && System.nanoTime() < until) {
            Thread.onSpinWait();
        }
        assertThat(active(pool)).as("connections not returned to the pool").isZero();
    }

    public static int active(HikariDataSource pool) {
        return pool.getHikariPoolMXBean() == null ? 0 : pool.getHikariPoolMXBean().getActiveConnections();
    }
}
