package com.globalfutservice.scheduling;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.zip.CRC32;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * One runner at a time for a scheduled job, across every instance.
 *
 * <p>Two instances each run their own copy of every {@code @Scheduled} method, and a
 * redeploy briefly runs two side by side. For jobs that call FUT Transfer or delete
 * sign-ins, two copies at once means duplicated vendor calls and racing writes. So each
 * run takes a PostgreSQL advisory lock named for the job, and a run that cannot take it
 * is skipped: the other instance is already doing the work.
 *
 * <p>The lock lives on one connection held for the length of the run and is released in
 * {@code finally}, even when the job throws. If releasing it fails, the connection is
 * aborted rather than returned to the pool, because a session lock stays held for as long
 * as its session lives -- and a crashed instance's session ends with it, which is what
 * makes a dead runner release the lock by itself.
 */
@Component
public class SchedulerLock {

    private static final Logger log = LoggerFactory.getLogger(SchedulerLock.class);

    /** The high half of every key, so these never collide with another use of advisory locks. */
    private static final long NAMESPACE = 0x4746_5300L << 32;

    private final DataSource dataSource;

    public SchedulerLock(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Runs {@code job} if no other runner holds {@code name}.
     *
     * @return true if the job ran; false if another runner had it, or the lock could not be asked for
     * @throws RuntimeException whatever the job threw, after the lock is released
     */
    public boolean runExclusively(String name, Runnable job) {
        long key = keyFor(name);
        Connection connection;
        try {
            connection = dataSource.getConnection();
        } catch (SQLException e) {
            log.warn("Scheduled job {} skipped: no database connection for its lock", name);
            return false;
        }
        boolean acquired = false;
        try {
            acquired = tryLock(connection, key);
            if (!acquired) {
                log.debug("Scheduled job {} skipped: another runner has it", name);
                return false;
            }
            job.run();
            return true;
        } catch (SQLException e) {
            log.warn("Scheduled job {} skipped: could not ask for its lock", name);
            return false;
        } finally {
            release(connection, key, acquired, name);
        }
    }

    static long keyFor(String name) {
        CRC32 crc = new CRC32();
        crc.update(name.getBytes(StandardCharsets.UTF_8));
        return NAMESPACE | crc.getValue();
    }

    private static boolean tryLock(Connection connection, long key) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("select pg_try_advisory_lock(?)")) {
            ps.setLong(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        }
    }

    private static void release(Connection connection, long key, boolean acquired, String name) {
        boolean clean = true;
        if (acquired) {
            try (PreparedStatement ps = connection.prepareStatement("select pg_advisory_unlock(?)")) {
                ps.setLong(1, key);
                ps.executeQuery().close();
            } catch (SQLException e) {
                clean = false;
                log.error("Could not release the lock for scheduled job {}; discarding its connection", name);
            }
        }
        try {
            if (clean) {
                connection.close();
            } else {
                // Ends the session, and with it the lock, instead of pooling a locked connection.
                connection.abort(Runnable::run);
            }
        } catch (SQLException e) {
            log.warn("Could not close the lock connection for scheduled job {}", name);
        }
    }
}
