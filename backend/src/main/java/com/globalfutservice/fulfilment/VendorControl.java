package com.globalfutservice.fulfilment;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

import com.globalfutservice.config.AppProperties;
import com.globalfutservice.notify.FulfilmentAlert;
import com.globalfutservice.notify.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The switch that stops every call to FUT Transfer, held in {@code vendor_control}.
 *
 * <p>Tripped by the first 403 -- our API credentials refused -- and cleared only by an
 * admin. In the database, so it holds across restarts and across instances. Staff are
 * alerted when it trips, once: the update that trips it is conditional on it being off,
 * so a burst of 403s alerts one person one time.
 */
@Component
public class VendorControl {

    private static final Logger log = LoggerFactory.getLogger(VendorControl.class);

    private final NamedParameterJdbcTemplate jdbc;
    private final NotificationService notifications;
    private final AppProperties props;

    public VendorControl(NamedParameterJdbcTemplate jdbc, NotificationService notifications, AppProperties props) {
        this.jdbc = jdbc;
        this.notifications = notifications;
        this.props = props;
    }

    public record State(boolean paused, Instant pausedAt, String reason, Instant resumedAt) {
    }

    public boolean isPaused() {
        return state().map(State::paused).orElse(false);
    }

    public Optional<State> state() {
        return jdbc.query("select paused, paused_at, paused_reason, resumed_at from vendor_control where id = 1",
                new MapSqlParameterSource(), (rs, i) -> new State(rs.getBoolean("paused"),
                        instant(rs.getTimestamp("paused_at")), rs.getString("paused_reason"),
                        instant(rs.getTimestamp("resumed_at")))).stream().findFirst();
    }

    /**
     * Stops every call. Alerts staff only if this call is the one that stopped them.
     *
     * @param reason a short code, never a body
     * @param orderRef the order the refused call was about, if any
     */
    public void pause(String reason, String orderRef) {
        int n = jdbc.update("""
                update vendor_control set paused = true, paused_at = now(), paused_reason = :reason
                 where id = 1 and not paused
                """, new MapSqlParameterSource("reason", reason));
        if (n == 1) {
            log.error("FUT TRANSFER PAUSED: our API credentials were refused ({}). Every call is stopped "
                    + "until an admin resumes them.", reason);
            try {
                notifications.fulfilmentAlert(new FulfilmentAlert(orderRef == null ? "FUT Transfer" : orderRef,
                        "All FUT Transfer calls paused",
                        "FUT Transfer refused our API credentials (" + reason + "). Every call to it -- new "
                                + "orders and status checks -- is stopped until an admin resumes them. Check "
                                + "GFS_FUTTRANSFER_API_USER and GFS_FUTTRANSFER_API_KEY first.",
                        "HTTP_403", props.publicUrl() + "/admin/orders"));
            } catch (RuntimeException e) {
                log.warn("Could not queue the pause alert: {}", e.getMessage());
            }
        }
    }

    // ------------------------------------------------------------ poll schedule ---

    /** Whether the next poll is due. Held in the database, so every instance agrees. */
    public boolean pollDue() {
        Boolean due = jdbc.queryForObject("select next_poll_at is null or next_poll_at <= now() from vendor_control where id = 1",
                new MapSqlParameterSource(), Boolean.class);
        return Boolean.TRUE.equals(due);
    }

    /**
     * Sets the next poll: after the interval if this one went well, or after a back-off that
     * doubles with each troubled poll, up to {@code maxBackoff}. Either way, plus up to
     * {@code jitter} at random.
     */
    public java.time.Duration scheduleNextPoll(boolean troubled, java.time.Duration interval,
                                               java.time.Duration jitter, java.time.Duration maxBackoff) {
        Integer level = jdbc.queryForObject("""
                update vendor_control set backoff_level = case when :troubled then least(backoff_level + 1, 20) else 0 end
                 where id = 1 returning backoff_level
                """, new MapSqlParameterSource("troubled", troubled), Integer.class);
        java.time.Duration delay = nextDelay(level == null ? 0 : level, interval, jitter, maxBackoff);
        jdbc.update("update vendor_control set next_poll_at = now() + make_interval(secs => :secs) where id = 1",
                new MapSqlParameterSource("secs", delay.toMillis() / 1000.0));
        return delay;
    }

    static java.time.Duration nextDelay(int level, java.time.Duration interval, java.time.Duration jitter,
                                        java.time.Duration maxBackoff) {
        long base = interval.toMillis();
        for (int i = 0; i < level && base < maxBackoff.toMillis(); i++) {
            base *= 2;
        }
        base = Math.min(base, Math.max(interval.toMillis(), maxBackoff.toMillis()));
        long extra = jitter.isZero() ? 0 : java.util.concurrent.ThreadLocalRandom.current().nextLong(jitter.toMillis() + 1);
        return java.time.Duration.ofMillis(base + extra);
    }

    /** @return whether calls were paused and now are not */
    public boolean resume(long adminAccountId) {
        int n = jdbc.update("""
                update vendor_control set paused = false, resumed_at = now(), resumed_by = :admin
                 where id = 1 and paused
                """, new MapSqlParameterSource("admin", adminAccountId));
        if (n == 1) {
            log.warn("FUT Transfer calls resumed by admin {}", adminAccountId);
        }
        return n == 1;
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
