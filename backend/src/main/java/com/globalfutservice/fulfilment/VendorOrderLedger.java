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
     * How an order is sent, recorded on its row when it is claimed: which endpoint, which
     * vendor method, and what a public-pool order sent for {@code buyNowThreshold} and
     * {@code maxPrice} (null: not sent).
     */
    public record SendTerms(String orderMode, String transferMethod, java.math.BigDecimal buyNowThreshold,
                            java.math.BigDecimal maxPrice) {

        public boolean publicPool() {
            return "PUBLIC_POOL".equals(orderMode);
        }
    }

    /**
     * Takes the right to send this order to the vendor.
     *
     * <p>A first send inserts the row; the unique constraint turns every other insert into
     * a no-op. A send after a definite refusal ({@code FAILED}) moves the row back to
     * SUBMITTING, while fewer than {@code maxAttempts} sends have been made. Every other
     * state -- in flight, sent, or waiting for an admin -- is not claimable.
     *
     * <p>Either way the row records {@code terms}: a retry is sent as configuration says
     * now, which may not be how the first attempt went, and the row says which.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Claim claim(long orderId, String externalRef, long amountK, int maxAttempts, SendTerms terms) {
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("orderId", orderId)
                .addValue("ref", externalRef)
                .addValue("k", amountK)
                .addValue("max", maxAttempts)
                .addValue("mode", terms.orderMode())
                .addValue("method", terms.transferMethod())
                .addValue("threshold", terms.buyNowThreshold())
                .addValue("maxPrice", terms.maxPrice());

        List<Integer> inserted = jdbc.queryForList("""
                insert into vendor_order (order_id, external_ref, state, amount_ordered_k, attempts, order_mode,
                                          transfer_method, buy_now_threshold_sent, max_price_sent)
                values (:orderId, :ref, 'SUBMITTING', :k, 1, :mode, :method, :threshold, :maxPrice)
                on conflict (order_id) do nothing
                returning attempts
                """, p, Integer.class);
        if (!inserted.isEmpty()) {
            return new Claimed(inserted.get(0));
        }

        List<Integer> reclaimed = jdbc.queryForList("""
                update vendor_order
                   set state = 'SUBMITTING', attempts = attempts + 1, amount_ordered_k = :k,
                       last_error_code = null, review_reason = null, order_mode = :mode,
                       transfer_method = :method, buy_now_threshold_sent = :threshold,
                       max_price_sent = :maxPrice, balance_at_send = null, updated_at = now()
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
                markTransferStarted(orderId);
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
        if (n == 1) {
            markTransferStarted(orderId);
        }
        return n == 1;
    }

    /**
     * The partner has the order: the customer's "Track your order" button shows, and the
     * "transfer started" email becomes due. Set once, on whichever way the partner first
     * accepted it -- an id returned, a lookup confirming it, an admin linking it.
     */
    private void markTransferStarted(long orderId) {
        jdbc.update("""
                update orders set transfer_started_at = coalesce(transfer_started_at, now()) where id = :orderId
                """, new MapSqlParameterSource("orderId", orderId));
    }

    /** How far the partner has got, in thousands: what we asked for, and delivered so far. */
    public record Progress(long orderedK, Long deliveredK) {
    }

    /** The customer's progress bar: the partner's own count of ordered, ours if it has not said. */
    public Optional<Progress> progress(long orderId) {
        return jdbc.query("""
                select coalesce(vendor_amount_ordered_k, amount_ordered_k) as ordered_k, amount_delivered_k
                  from vendor_order where order_id = :orderId and submitted_at is not null
                """, new MapSqlParameterSource("orderId", orderId), (rs, i) -> new Progress(
                rs.getLong("ordered_k"), rs.getObject("amount_delivered_k", Long.class))).stream().findFirst();
    }

    /** {@link #progress} for a page of orders at once: the admin's Orders table, in one query. */
    public java.util.Map<Long, Progress> progressAmong(java.util.Collection<Long> orderIds) {
        if (orderIds.isEmpty()) return java.util.Map.of();
        java.util.Map<Long, Progress> out = new java.util.HashMap<>();
        jdbc.query("""
                select order_id, coalesce(vendor_amount_ordered_k, amount_ordered_k) as ordered_k, amount_delivered_k
                  from vendor_order where order_id in (:ids) and submitted_at is not null
                """, new MapSqlParameterSource("ids", orderIds), rs -> {
            out.put(rs.getLong("order_id"), new Progress(rs.getLong("ordered_k"),
                    rs.getObject("amount_delivered_k", Long.class)));
        });
        return out;
    }

    /**
     * The balance FUT Transfer reported just before this send, for margin tracking. Only
     * while the row is still SUBMITTING: it belongs to this send and no other.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordBalanceAtSend(long orderId, java.math.BigDecimal balance) {
        jdbc.update("""
                update vendor_order set balance_at_send = :balance, updated_at = now()
                 where order_id = :orderId and state = 'SUBMITTING'
                """, new MapSqlParameterSource("orderId", orderId).addValue("balance", balance));
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

    // ---------------------------------------------------------------- polling ---

    /** A vendor order the poller asks about, and how it was sent (PUBLIC_POOL or OWN_SENDERS). */
    public record PollRow(long orderId, String externalRef, String vendorOrderId, String state,
                          long amountOrderedK, Long deliveredK, String vendorStatus, int missingPolls,
                          String customerAction, String vendorAccountCheck, String vendorEconomyState,
                          Instant resubmittedAt, String orderMode) {
    }

    private static final String POLL_COLUMNS = """
            order_id, external_ref, vendor_order_id, state, amount_ordered_k, amount_delivered_k,
            vendor_status, missing_polls, customer_action, vendor_account_check, vendor_economy_state,
            resubmitted_at, order_mode
            """;

    private static PollRow pollRow(ResultSet rs, int i) throws SQLException {
        return new PollRow(rs.getLong("order_id"), rs.getString("external_ref"), rs.getString("vendor_order_id"),
                rs.getString("state"), rs.getLong("amount_ordered_k"), rs.getObject("amount_delivered_k", Long.class),
                rs.getString("vendor_status"), rs.getInt("missing_polls"), rs.getString("customer_action"),
                rs.getString("vendor_account_check"), rs.getString("vendor_economy_state"),
                rs.getTimestamp("resubmitted_at") == null ? null : rs.getTimestamp("resubmitted_at").toInstant(),
                rs.getString("order_mode"));
    }

    /** Orders the vendor is working on or waiting for the customer on, least recently asked about first. */
    public List<PollRow> openForPolling() {
        return jdbc.query("select " + POLL_COLUMNS + """
                  from vendor_order where state in ('SUBMITTED', 'IN_DELIVERY', 'AWAITING_CUSTOMER')
                 order by last_polled_at asc nulls first, id
                """, new MapSqlParameterSource(), VendorOrderLedger::pollRow);
    }

    /** Sends still SUBMITTING after the grace period: the process that sent them is gone. */
    public List<PollRow> staleSubmitting(java.time.Duration grace) {
        return jdbc.query("select " + POLL_COLUMNS + """
                  from vendor_order where state = 'SUBMITTING'
                   and updated_at < now() - make_interval(secs => :secs)
                """, new MapSqlParameterSource("secs", grace.toSeconds()), VendorOrderLedger::pollRow);
    }

    /** Working orders with no progress at all for longer than {@code stallAfter}. */
    public List<PollRow> stalled(java.time.Duration stallAfter) {
        return jdbc.query("select " + POLL_COLUMNS + """
                  from vendor_order where state in ('SUBMITTED', 'IN_DELIVERY')
                   and last_progress_at < now() - make_interval(secs => :secs)
                """, new MapSqlParameterSource("secs", stallAfter.toSeconds()), VendorOrderLedger::pollRow);
    }

    /**
     * What the vendor just reported, in named fields. The stall clock restarts if anything
     * moved, and the missing count clears because the order was mentioned.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordReport(long orderId, FutTransferClient.SupplierStatus s, boolean progressed) {
        jdbc.update("""
                update vendor_order
                   set vendor_status = :status, vendor_account_check = :accountCheck,
                       vendor_economy_state = :economyState, vendor_was_aborted = :aborted,
                       vendor_amount_ordered_k = :ordered, amount_delivered_k = :delivered,
                       coins_used = :coinsUsed, to_pay = :toPay, missing_polls = 0,
                       last_polled_at = now(),
                       last_progress_at = case when :progressed then now() else last_progress_at end,
                       updated_at = now()
                 where order_id = :orderId
                """, new MapSqlParameterSource("orderId", orderId)
                .addValue("status", s.status())
                .addValue("accountCheck", s.accountCheck())
                .addValue("economyState", s.economyState())
                .addValue("aborted", s.aborted())
                .addValue("ordered", s.amountOrderedK())
                .addValue("delivered", s.amountDeliveredK())
                .addValue("coinsUsed", s.coinsUsed())
                .addValue("toPay", s.toPay())
                .addValue("progressed", progressed));
    }

    /** The vendor left this order out of its answer. @return the count of misses in a row */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int recordMissing(long orderId) {
        return jdbc.queryForObject("""
                update vendor_order set missing_polls = missing_polls + 1, last_polled_at = now(), updated_at = now()
                 where order_id = :orderId
                returning missing_polls
                """, new MapSqlParameterSource("orderId", orderId), Integer.class);
    }

    /**
     * Moves the row to a new state if it is still in one of {@code from}. Conditional, so
     * the poller never overwrites an admin's decision or another path's outcome.
     *
     * @param action what the customer is asked to do; only kept while AWAITING_CUSTOMER
     * @return whether it moved
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean move(long orderId, java.util.Collection<String> from, String to, String reasonCode,
                        String reason, String action) {
        return jdbc.update("""
                update vendor_order
                   set state = :to, last_error_code = coalesce(:code, last_error_code),
                       review_reason = case when :to in ('NEEDS_REVIEW', 'PARTIALLY_DELIVERED') then :reason
                                            else review_reason end,
                       customer_action = case when :to = 'AWAITING_CUSTOMER' then :action else null end,
                       last_progress_at = case when state <> :to then now() else last_progress_at end,
                       updated_at = now()
                 where order_id = :orderId and state in (:from)
                """, new MapSqlParameterSource("orderId", orderId)
                .addValue("from", from)
                .addValue("to", to)
                .addValue("code", reasonCode)
                .addValue("reason", reason)
                .addValue("action", action)) == 1;
    }

    /**
     * Orders parked for an admin -- needing review, or delivered short -- that nobody has
     * touched for longer than {@code retention}, and still holding a sign-in. Those sign-ins
     * are deleted: a decision that slow is not going to send the order again on the same
     * details.
     */
    public List<Long> heldForReviewLongerThan(java.time.Duration retention) {
        return jdbc.queryForList("""
                select order_id from vendor_order
                 where state in ('NEEDS_REVIEW', 'PARTIALLY_DELIVERED')
                   and updated_at < now() - make_interval(secs => :secs)
                   and exists (select 1 from credential_vault c
                                where c.order_id = vendor_order.order_id and c.purged_at is null)
                """, new MapSqlParameterSource("secs", retention.toSeconds()), Long.class);
    }

    // ------------------------------------------------------------ admin actions ---

    /**
     * Takes the right to send a corrected sign-in, or a resume, for an order the vendor
     * already has. Conditional, so two admins clicking at once send it once; a claim older
     * than {@code inFlight} has been abandoned and may be taken again.
     *
     * @param from the states it may be sent from
     * @return whether this caller may send it
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean claimRestart(long orderId, java.util.Collection<String> from, java.time.Duration inFlight) {
        return jdbc.update("""
                update vendor_order set resubmitted_at = now(), updated_at = now()
                 where order_id = :orderId and state in (:from) and vendor_order_id is not null
                   and (resubmitted_at is null or resubmitted_at < now() - make_interval(secs => :secs))
                """, new MapSqlParameterSource("orderId", orderId).addValue("from", from)
                .addValue("secs", inFlight.toSeconds())) == 1;
    }

    /** The vendor did not take it: the claim is let go at once, so an admin may try again. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void releaseRestart(long orderId) {
        jdbc.update("update vendor_order set resubmitted_at = null, updated_at = now() where order_id = :orderId",
                new MapSqlParameterSource("orderId", orderId));
    }

    /**
     * The vendor restarted the order: back to SUBMITTED, with a fresh stall clock and
     * nothing asked of the customer. {@code resubmitted_at} stays, for the poll's grace.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markRestarted(long orderId, java.util.Collection<String> from) {
        return jdbc.update("""
                update vendor_order
                   set state = 'SUBMITTED', customer_action = null, missing_polls = 0, review_reason = null,
                       last_progress_at = now(), updated_at = now()
                 where order_id = :orderId and state in (:from)
                """, new MapSqlParameterSource("orderId", orderId).addValue("from", from)) == 1;
    }

    /**
     * An admin checked the vendor's dashboard, and our lookup did not find the order
     * either: nothing was created, so the order may be approved again.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean allowResend(long orderId) {
        return jdbc.update("""
                update vendor_order
                   set state = 'FAILED', last_error_code = 'ADMIN_CONFIRMED_ABSENT', review_reason = null,
                       updated_at = now()
                 where order_id = :orderId and state = 'NEEDS_REVIEW' and vendor_order_id is null
                """, new MapSqlParameterSource("orderId", orderId)) == 1;
    }

    /**
     * An order waiting for review that the vendor does have: watched again, as sent.
     * {@code vendorOrderId} is recorded if the admin gave one and none is held yet.
     *
     * @return false if it was no longer waiting for review, or the id belongs to another order
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean link(long orderId, String vendorOrderId) {
        try {
            int n = jdbc.update("""
                    update vendor_order
                       set state = 'SUBMITTED', vendor_order_id = coalesce(vendor_order_id, :vid),
                           submitted_at = coalesce(submitted_at, now()), review_reason = null, missing_polls = 0,
                           last_progress_at = now(), updated_at = now()
                     where order_id = :orderId and state = 'NEEDS_REVIEW'
                    """, new MapSqlParameterSource("orderId", orderId).addValue("vid", vendorOrderId));
            if (n == 1 && vendorOrderId != null) {
                jdbc.update("""
                        update orders set supplier_order_id = :vid, version = version + 1
                         where id = :orderId and supplier_order_id is null
                        """, new MapSqlParameterSource("orderId", orderId).addValue("vid", vendorOrderId));
            }
            if (n == 1) {
                markTransferStarted(orderId);
            }
            return n == 1;
        } catch (DuplicateKeyException e) {
            try {
                TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            } catch (org.springframework.transaction.NoTransactionException outsideSpring) {
                // Called directly, as the database tests do.
            }
            return false;
        }
    }

    /** States an admin may close by hand: nothing more will happen to them on their own. */
    public static final List<String> RESOLVABLE = List.of("NEEDS_REVIEW", "PARTIALLY_DELIVERED", "AWAITING_CUSTOMER",
            "FAILED");

    /** Closed by an admin, with their note. Never polled again, never sent again. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean resolve(long orderId, String note) {
        return jdbc.update("""
                update vendor_order
                   set state = 'RESOLVED', review_reason = :note, customer_action = null, updated_at = now()
                 where order_id = :orderId and state in (:from)
                """, new MapSqlParameterSource("orderId", orderId).addValue("note", note)
                .addValue("from", RESOLVABLE)) == 1;
    }

    // ------------------------------------------------------------- admin view ---

    /** Everything we hold about one vendor order, for the admin's order page. */
    public record Detail(String state, String externalRef, String vendorOrderId, long amountOrderedK,
                         Long vendorAmountOrderedK, Long deliveredK, String vendorStatus, String vendorAccountCheck,
                         String vendorEconomyState, Boolean aborted, Long coinsUsed, java.math.BigDecimal toPay,
                         int attempts, String lastErrorCode, String reviewReason, String customerAction,
                         int missingPolls, Instant submittedAt, Instant lastPolledAt, Instant lastProgressAt,
                         Instant resubmittedAt, Instant updatedAt, String orderMode, String transferMethod,
                         java.math.BigDecimal buyNowThresholdSent, java.math.BigDecimal maxPriceSent,
                         java.math.BigDecimal balanceAtSend) {
    }

    public Optional<Detail> detail(long orderId) {
        return jdbc.query("""
                select * from vendor_order where order_id = :orderId
                """, new MapSqlParameterSource("orderId", orderId), (rs, i) -> new Detail(
                rs.getString("state"), rs.getString("external_ref"), rs.getString("vendor_order_id"),
                rs.getLong("amount_ordered_k"), rs.getObject("vendor_amount_ordered_k", Long.class),
                rs.getObject("amount_delivered_k", Long.class), rs.getString("vendor_status"),
                rs.getString("vendor_account_check"), rs.getString("vendor_economy_state"),
                rs.getObject("vendor_was_aborted", Boolean.class), rs.getObject("coins_used", Long.class),
                rs.getBigDecimal("to_pay"), rs.getInt("attempts"), rs.getString("last_error_code"),
                rs.getString("review_reason"), rs.getString("customer_action"), rs.getInt("missing_polls"),
                instant(rs, "submitted_at"), instant(rs, "last_polled_at"), instant(rs, "last_progress_at"),
                instant(rs, "resubmitted_at"), instant(rs, "updated_at"), rs.getString("order_mode"),
                rs.getString("transfer_method"), rs.getBigDecimal("buy_now_threshold_sent"),
                rs.getBigDecimal("max_price_sent"), rs.getBigDecimal("balance_at_send"))).stream().findFirst();
    }

    /** An order waiting for an admin's decision, for the review list on the Orders page. */
    public record ReviewItem(String externalRef, String state, String lastErrorCode, String reviewReason,
                             long amountOrderedK, Long deliveredK, Instant updatedAt) {
    }

    /** Everything waiting for an admin, longest-waiting first. */
    public List<ReviewItem> needingReview() {
        return jdbc.query("""
                select external_ref, state, last_error_code, review_reason, amount_ordered_k, amount_delivered_k,
                       updated_at
                  from vendor_order where state in ('NEEDS_REVIEW', 'PARTIALLY_DELIVERED')
                 order by updated_at, id
                """, new MapSqlParameterSource(), (rs, i) -> new ReviewItem(rs.getString("external_ref"),
                rs.getString("state"), rs.getString("last_error_code"), rs.getString("review_reason"),
                rs.getLong("amount_ordered_k"), rs.getObject("amount_delivered_k", Long.class),
                instant(rs, "updated_at")));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }

    /**
     * Of these orders, the ones the partner has, or may have: a vendor order in any state
     * but FAILED, whether or not it gave us its id. Approving one of them sends nothing --
     * the release refuses it -- so the queue does not offer to.
     */
    public java.util.Set<Long> atPartner(java.util.Collection<Long> orderIds) {
        if (orderIds.isEmpty()) return java.util.Set.of();
        return new java.util.HashSet<>(jdbc.queryForList("""
                select order_id from vendor_order where order_id in (:ids) and state <> 'FAILED'
                """, new MapSqlParameterSource("ids", orderIds), Long.class));
    }

    /** What the customer is asked to do, while their order waits for them. */
    public Optional<String> customerAction(long orderId) {
        return jdbc.query("""
                select customer_action from vendor_order where order_id = :orderId and state = 'AWAITING_CUSTOMER'
                """, new MapSqlParameterSource("orderId", orderId), (rs, i) -> rs.getString(1))
                .stream().filter(java.util.Objects::nonNull).findFirst();
    }

    /** How this order was last sent, or tried: PUBLIC_POOL or OWN_SENDERS. Empty if never. */
    public Optional<String> orderMode(long orderId) {
        return jdbc.query("select order_mode from vendor_order where order_id = :orderId",
                new MapSqlParameterSource("orderId", orderId), (rs, i) -> rs.getString(1)).stream().findFirst();
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
