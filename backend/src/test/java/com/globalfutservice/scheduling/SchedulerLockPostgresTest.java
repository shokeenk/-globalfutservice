package com.globalfutservice.scheduling;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * One runner at a time, proven on a real PostgreSQL: advisory locks are the database's,
 * so nothing here can stand in for it. Runs when {@code GFS_TEST_PG_URL} is set. Each
 * test uses its own lock name, so it cannot meet the application's jobs or another run.
 */
class SchedulerLockPostgresTest {

    private DriverManagerDataSource ds;
    private String job;

    @BeforeEach
    void setUp() {
        String url = System.getenv("GFS_TEST_PG_URL");
        assumeTrue(url != null && !url.isBlank(), "GFS_TEST_PG_URL not set: no database to lock against");
        ds = new DriverManagerDataSource(url, System.getenv("GFS_TEST_PG_USER"), System.getenv("GFS_TEST_PG_PASSWORD"));
        job = "test-" + UUID.randomUUID();
    }

    @Test
    @DisplayName("while one instance runs the job, the other skips it; afterwards it runs")
    void secondRunnerSkips() throws Exception {
        SchedulerLock first = new SchedulerLock(ds);
        SchedulerLock second = new SchedulerLock(ds);
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        AtomicInteger runs = new AtomicInteger();

        ExecutorService pool = Executors.newSingleThreadExecutor();
        Future<Boolean> held = pool.submit(() -> first.runExclusively(job, () -> {
            runs.incrementAndGet();
            running.countDown();
            try {
                finish.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        assertThat(running.await(10, TimeUnit.SECONDS)).isTrue();

        assertThat(second.runExclusively(job, runs::incrementAndGet)).isFalse();

        finish.countDown();
        assertThat(held.get(10, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        assertThat(second.runExclusively(job, runs::incrementAndGet)).isTrue();
        assertThat(runs.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("a job that throws still releases the lock, and the error reaches the caller")
    void releasedOnFailure() {
        SchedulerLock lock = new SchedulerLock(ds);

        assertThatThrownBy(() -> lock.runExclusively(job, () -> {
            throw new IllegalStateException("vendor down");
        })).hasMessage("vendor down");

        assertThat(new SchedulerLock(ds).runExclusively(job, () -> { })).isTrue();
    }

    @Test
    @DisplayName("different jobs do not block each other")
    void separateJobs() throws Exception {
        SchedulerLock lock = new SchedulerLock(ds);
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        Future<Boolean> held = pool.submit(() -> lock.runExclusively(job, () -> {
            running.countDown();
            try {
                finish.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        assertThat(running.await(10, TimeUnit.SECONDS)).isTrue();

        assertThat(lock.runExclusively(job + "-other", () -> { })).isTrue();

        finish.countDown();
        assertThat(held.get(10, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();
    }
}
