package com.globalfutservice.fulfilment;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.globalfutservice.config.AppProperties;
import com.globalfutservice.orders.OrderEntity;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Paid coin orders waiting to be sent to FUT Transfer without an admin, in {@code auto_dispatch}.
 *
 * <p>An order is queued in the transaction that marks it PAID, so the entry exists exactly
 * when the payment does: a payment that rolls back queues nothing, and one that commits
 * cannot be lost between the commit and the send. The send itself happens later, in
 * {@link AutoDispatchWorker}, never inside the payment's request.
 */
@Component
public class AutoDispatchQueue {

    public static final String QUEUED = "QUEUED";
    public static final String SENT = "SENT";
    public static final String LEFT_FOR_APPROVE = "LEFT_FOR_APPROVE";
    public static final String NEEDS_REVIEW = "NEEDS_REVIEW";

    /** One queued order, as the worker and the admin's order page read it. */
    public record Row(long orderId, String state, int attempts, Instant nextAttemptAt, String reasonCode,
                      String reason, Instant updatedAt) {
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final AppProperties props;

    public AutoDispatchQueue(NamedParameterJdbcTemplate jdbc, AppProperties props) {
        this.jdbc = jdbc;
        this.props = props;
    }

    /**
     * Queues a coin order that has just become PAID, when automatic sending is on. Called from
     * inside the payment's transaction. Anything else -- the setting off, not a coin order -- is
     * left for Approve exactly as before. Queued at most once per order.
     */
    public void paid(OrderEntity order) {
        if (!props.futTransfer().autoDispatch().enabled() || !order.getSku().isCoinTransfer()) {
            return;
        }
        jdbc.update("""
                insert into auto_dispatch (order_id) values (:orderId) on conflict (order_id) do nothing
                """, new MapSqlParameterSource("orderId", order.getId()));
    }

    /**
     * The customer's sign-in has arrived for an order that was paid and waiting for it. Queued
     * when automatic sending is on -- or queued again, when the queue had already left it for
     * Approve for want of exactly this. An order in any other state here is left as it is:
     * sent, in review, or left for a reason a sign-in does not change.
     */
    public void signInArrived(OrderEntity order) {
        if (!props.futTransfer().autoDispatch().enabled() || !order.getSku().isCoinTransfer()) {
            return;
        }
        jdbc.update("""
                insert into auto_dispatch (order_id) values (:orderId)
                on conflict (order_id) do update
                   set state = 'QUEUED', attempts = 0, next_attempt_at = now(), reason_code = null, reason = null,
                       updated_at = now()
                 where auto_dispatch.state = 'LEFT_FOR_APPROVE' and auto_dispatch.reason_code = 'NO_SIGN_IN'
                """, new MapSqlParameterSource("orderId", order.getId()));
    }

    /** Orders whose turn has come, oldest first. */
    public List<Long> due(Instant now, int limit) {
        return jdbc.queryForList("""
                select order_id from auto_dispatch
                 where state = 'QUEUED' and next_attempt_at <= :now
                 order by created_at, order_id
                 limit :limit
                """, new MapSqlParameterSource("now", Timestamp.from(now)).addValue("limit", limit), Long.class);
    }

    public Optional<Row> find(long orderId) {
        return jdbc.query("select * from auto_dispatch where order_id = :orderId",
                new MapSqlParameterSource("orderId", orderId), (rs, i) -> new Row(rs.getLong("order_id"),
                        rs.getString("state"), rs.getInt("attempts"), rs.getTimestamp("next_attempt_at").toInstant(),
                        rs.getString("reason_code"), rs.getString("reason"), rs.getTimestamp("updated_at").toInstant()))
                .stream().findFirst();
    }

    /** Settles a queued order: SENT, LEFT_FOR_APPROVE or NEEDS_REVIEW, with why. */
    public void settle(long orderId, String state, String code, String reason) {
        jdbc.update("""
                update auto_dispatch
                   set state = :state, attempts = attempts + 1, reason_code = :code, reason = :reason,
                       updated_at = now()
                 where order_id = :orderId and state = 'QUEUED'
                """, new MapSqlParameterSource("orderId", orderId).addValue("state", state).addValue("code", code)
                .addValue("reason", reason));
    }

    /** Tries it again at {@code next}: nothing was created, and the cause is passing. */
    public void retryAt(long orderId, Instant next, String code, String reason) {
        jdbc.update("""
                update auto_dispatch
                   set attempts = attempts + 1, next_attempt_at = :next, reason_code = :code, reason = :reason,
                       updated_at = now()
                 where order_id = :orderId and state = 'QUEUED'
                """, new MapSqlParameterSource("orderId", orderId).addValue("next", Timestamp.from(next))
                .addValue("code", code).addValue("reason", reason));
    }
}
