package com.globalfutservice.fulfilment;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

/**
 * The record of what we sent to FUT Transfer, one row per order, in {@code vendor_order}.
 *
 * <p><b>The database decides who sends.</b> {@link #claim} writes the row as SUBMITTING
 * before the vendor is called, and the unique constraint on the order id means exactly
 * one caller can do that. A double click, a second admin, a second instance and a
 * redeploy mid-request all end at the same row and stop there. Re-sending after a
 * definite refusal reuses the row through a conditional update, so that is decided by the
 * database too.
 *
 * <p><b>Every write commits on its own.</b> {@code REQUIRES_NEW} throughout: the claim has
 * to be visible to other callers before the vendor is called, and the vendor's answer has
 * to be durable the moment we have it, whatever the surrounding transaction does next.
 * Each state change is conditional on the row still being SUBMITTING, so no path can
 * overwrite an outcome another path has already written.
 *
 * <p>Plain SQL rather than an entity: every statement here is a guarded update whose row
 * count is the answer, and a managed entity would only get in the way of reading it.
 */
@Component
public class VendorOrderLedger {

    public static final String SUBMITTING = "SUBMITTING";
    public static final String SUBMITTED = "SUBMITTED";
    public static final String FAILED = "FAILED";
    public static final String NEEDS_REVIEW = "NEEDS_REVIEW";

    private final NamedParameterJdbcTemplate jdbc;

    public VendorOrderLedger(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** What the row says now. */
    public record Row(long orderId, String externalRef, String vendorOrderId, String state,
                      long amountOrderedK, int attempts, String lastErrorCode, String reviewReason,
                      Instant updatedAt) {
    }

    /** Whether this caller may call the vendor, and if not, what stopped it. */
    public sealed interface Claim permits Claimed, NotClaimed {
    }

    public record Claimed(int attempt) implements Claim {
    }

    public record NotClaimed(Row current) implements Claim {
    }

    /**
     * Takes the right to send this order to the vendor.
     *
     * <p>A first send inserts the row; the unique constraint turns every other insert into
     * a no-op. A send after a definite refusal ({@code FAILED}) moves the row back to
     * SUBMITTING, while fewer than {@code maxAttempts} sends have been made. Every other
     * state -- in flight, sent, or waiting for an admin -- is not claimable.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Claim claim(long orderId, String externalRef, long amountK, int maxAttempts) {
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("orderId", orderId)
                .addValue("ref", externalRef)
                .addValue("k", amountK)
                .addValue("max", maxAttempts);

        List<Integer> inserted = jdbc.queryForList("""
                insert into vendor_order (order_id, external_ref, state, amount_ordered_k, attempts)
                values (:orderId, :ref, 'SUBMITTING', :k, 1)
                on conflict (order_id) do nothing
                returning attempts
                """, p, Integer.class);
        if (!inserted.isEmpty()) {
            return new Claimed(inserted.get(0));
        }

        List<Integer> reclaimed = jdbc.queryForList("""
                update vendor_order
                   set state = 'SUBMITTING', attempts = attempts + 1, amount_ordered_k = :k,
                       last_error_code = null, review_reason = null, updated_at = now()
                 where order_id = :orderId and state = 'FAILED' and attempts < :max
                returning attempts
                """, p, Integer.class);
        if (!reclaimed.isEmpty()) {
            return new Claimed(reclaimed.get(0));
        }
        return new NotClaimed(find(orderId).orElseThrow());
    }

    /**
     * The vendor accepted and gave its id.
     *
     * <p>Also written to {@code orders.supplier_order_id}, which the status poller reads.
     * An id the vendor has already given another of our orders is not accepted silently: it
     * is exactly the kind of surprise an admin has to look at.
     *
     * @return false if the row was no longer SUBMITTING, or the id is already linked
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markSubmitted(long orderId, String vendorOrderId) {
        try {
            int n = jdbc.update("""
                    update vendor_order
                       set state = 'SUBMITTED', vendor_order_id = :vid, submitted_at = now(),
                           last_error_code = null, updated_at = now()
                     where order_id = :orderId and state = 'SUBMITTING'
                    """, new MapSqlParameterSource("orderId", orderId).addValue("vid", vendorOrderId));
            if (n == 1) {
                // The version moves too, so a stale copy of the order held elsewhere fails
                // its optimistic lock instead of writing a null id back over this one.
                jdbc.update("""
                        update orders set supplier_order_id = :vid, version = version + 1
                         where id = :orderId and supplier_order_id is null
                        """, new MapSqlParameterSource("orderId", orderId).addValue("vid", vendorOrderId));
            }
            return n == 1;
        } catch (DuplicateKeyException e) {
            // The id is already linked to another order. Postgres has aborted this
            // transaction, so it is rolled back rather than committed, and the caller sends
            // the order to review.
            try {
                TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            } catch (org.springframework.transaction.NoTransactionException outsideSpring) {
                // Called directly, as the database tests do: each statement committed itself.
            }
            return false;
        }
    }

    /**
     * The answer to the placement was lost, and a lookup by our reference found the order.
     * The status response carries no vendor order id, so none is recorded; what the lookup
     * saw is, and {@code lastErrorCode} keeps what went wrong first.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markConfirmedByLookup(long orderId, String firstError, FutTransferClient.Found found) {
        int n = jdbc.update("""
                update vendor_order
                   set state = 'SUBMITTED', submitted_at = now(), last_error_code = :err,
                       vendor_status = :status, vendor_account_check = :accountCheck,
                       vendor_economy_state = :economyState, vendor_was_aborted = :aborted,
                       vendor_amount_ordered_k = :ordered, amount_delivered_k = :delivered,
                       coins_used = :coinsUsed, to_pay = :toPay, updated_at = now()
                 where order_id = :orderId and state = 'SUBMITTING'
                """, new MapSqlParameterSource("orderId", orderId)
                .addValue("err", firstError)
                .addValue("status", found.status())
                .addValue("accountCheck", found.accountCheck())
                .addValue("economyState", found.economyState())
                .addValue("aborted", found.aborted())
                .addValue("ordered", found.amountOrderedK())
                .addValue("delivered", found.amountDeliveredK())
                .addValue("coinsUsed", found.coinsUsed())
                .addValue("toPay", found.toPay()));
        return n == 1;
    }

    /** The vendor definitely created nothing. An admin may approve the order again. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markFailed(long orderId, String code, String reason) {
        return finish(orderId, FAILED, code, reason);
    }

    /** We cannot prove what happened. Nothing is sent again until an admin decides. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markNeedsReview(long orderId, String code, String reason) {
        return finish(orderId, NEEDS_REVIEW, code, reason);
    }

    private boolean finish(long orderId, String state, String code, String reason) {
        return jdbc.update("""
                update vendor_order
                   set state = :state, last_error_code = :code, review_reason = :reason, updated_at = now()
                 where order_id = :orderId and state = 'SUBMITTING'
                """, new MapSqlParameterSource("orderId", orderId)
                .addValue("state", state).addValue("code", code).addValue("reason", reason)) == 1;
    }

    public Optional<Row> find(long orderId) {
        return jdbc.query("""
                select order_id, external_ref, vendor_order_id, state, amount_ordered_k, attempts,
                       last_error_code, review_reason, updated_at
                  from vendor_order where order_id = :orderId
                """, new MapSqlParameterSource("orderId", orderId), VendorOrderLedger::row).stream().findFirst();
    }

    private static Row row(ResultSet rs, int i) throws SQLException {
        Timestamp updated = rs.getTimestamp("updated_at");
        return new Row(rs.getLong("order_id"), rs.getString("external_ref"), rs.getString("vendor_order_id"),
                rs.getString("state"), rs.getLong("amount_ordered_k"), rs.getInt("attempts"),
                rs.getString("last_error_code"), rs.getString("review_reason"),
                updated == null ? null : updated.toInstant());
    }
}
