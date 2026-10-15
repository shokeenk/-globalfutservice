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
                 where order_id = :orderId and state = 'FAILED'
                   and (attempts < :max or last_error_code = 'RATE_LIMITED')
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

    /**
     * How far the partner has got, in thousands: what we asked for, and delivered so far; and
     * whether an admin is looking at the order (the customer is told only that we are checking).
     */
    public record Progress(long orderedK, Long deliveredK, boolean underReview) {
        public Progress(long orderedK, Long deliveredK) {
            this(orderedK, deliveredK, false);
        }
    }

    /** The customer's progress bar: the partner's own count of ordered, ours if it has not said. */
    public Optional<Progress> progress(long orderId) {
        return jdbc.query("""
                select coalesce(vendor_amount_ordered_k, amount_ordered_k) as ordered_k, amount_delivered_k,
                       state = 'NEEDS_REVIEW' as under_review
                  from vendor_order where order_id = :orderId and submitted_at is not null
                """, new MapSqlParameterSource("orderId", orderId), (rs, i) -> new Progress(
                rs.getLong("ordered_k"), rs.getObject("amount_delivered_k", Long.class),
                rs.getBoolean("under_review"))).stream().findFirst();
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

    /**
     * The partner refused because the customer's EA account has had too many orders recently
     * (HTTP 429). Nothing was created, and the cause passes by itself. The row is FAILED, as for
     * any definite refusal, and the next send claims it again -- once; {@code attempts} still
     * counts the call, but a rate-limited row is claimable past the attempt limit, so the limit
     * never runs out on a refusal that was only "not yet".
     *
     * @param vendorCode the partner's own code, kept in the review reason; the row's code is
     *                   {@code RATE_LIMITED}, which is what the automatic queue looks for
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markRateLimited(long orderId, String vendorCode) {
        return jdbc.update("""
                update vendor_order
                   set state = 'FAILED', last_error_code = 'RATE_LIMITED', review_reason = :reason,
                       updated_at = now()
                 where order_id = :orderId and state = 'SUBMITTING'
                """, new MapSqlParameterSource("orderId", orderId).addValue("reason",
                "HTTP 429 (" + vendorCode + "): too many orders for this EA account recently. Nothing was created."))
                == 1;
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
                          Instant resubmittedAt, String orderMode, String lastErrorCode) {
    }

    private static final String POLL_COLUMNS = """
            order_id, external_ref, vendor_order_id, state, amount_ordered_k, amount_delivered_k,
            vendor_status, missing_polls, customer_action, vendor_account_check, vendor_economy_state,
            resubmitted_at, order_mode, last_error_code
            """;

    private static PollRow pollRow(ResultSet rs, int i) throws SQLException {
        return new PollRow(rs.getLong("order_id"), rs.getString("external_ref"), rs.getString("vendor_order_id"),
                rs.getString("state"), rs.getLong("amount_ordered_k"), rs.getObject("amount_delivered_k", Long.class),
                rs.getString("vendor_status"), rs.getInt("missing_polls"), rs.getString("customer_action"),
                rs.getString("vendor_account_check"), rs.getString("vendor_economy_state"),
                rs.getTimestamp("resubmitted_at") == null ? null : rs.getTimestamp("resubmitted_at").toInstant(),
                rs.getString("order_mode"), rs.getString("last_error_code"));
    }

    /** How often an order waiting for an admin is read again, while the partner still has it open. */
    public static final java.time.Duration REVIEW_REREAD = java.time.Duration.ofMinutes(5);

    /**
     * Orders the vendor is working on or waiting for the customer on; and, every
     * {@link #REVIEW_REREAD}, orders waiting for an admin that the partner still has open --
     * under its id, not reported finished or aborted -- so their numbers stay current and a
     * word we did not know can be understood later. Least recently asked about first.
     */
    public List<PollRow> openForPolling() {
        return jdbc.query("select " + POLL_COLUMNS + """
                  from vendor_order
                 where state in ('SUBMITTED', 'IN_DELIVERY', 'AWAITING_CUSTOMER')
                    or (state = 'NEEDS_REVIEW' and vendor_order_id is not null
                        and lower(coalesce(vendor_status, '')) not in ('finished', 'cancelled', 'canceled', 'archived')
                        and not coalesce(vendor_was_aborted, false)
                        and (last_polled_at is null
                             or last_polled_at <= now() - :rereadSeconds * interval '1 second'))
                 order by last_polled_at asc nulls first, id
                """, new MapSqlParameterSource("rereadSeconds", REVIEW_REREAD.toSeconds()), VendorOrderLedger::pollRow);
    }

    /**
     * Claims the admin alert about one word we did not know, for one order: true once per
     * word per order, however often the partner sends it.
     */
    public boolean claimUnknownWordAlert(long orderId, String word) {
        return jdbc.update("""
                update vendor_order set unknown_words_alerted = array_append(unknown_words_alerted, :word)
                 where order_id = :orderId and not (:word = any(unknown_words_alerted))
                """, new MapSqlParameterSource("orderId", orderId).addValue("word", word)) == 1;
    }

    /** Orders waiting for an admin because of a word we did not know: what a re-read can recover. */
    public List<String> unknownWordReviews() {
        return jdbc.queryForList("""
                select external_ref from vendor_order
                 where state = 'NEEDS_REVIEW' and last_error_code in (:codes)
                 order by id
                """, new MapSqlParameterSource("codes", VendorStatusMap.UNKNOWN_WORD_REASONS), String.class);
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
                       vendor_simplified_status = :simplified, vendor_account_check_long = :accountCheckLong,
                       vendor_economy_state_long = :economyStateLong,
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
                .addValue("simplified", s.simplifiedStatus())
                .addValue("accountCheckLong", s.accountCheckLong())
                .addValue("economyStateLong", s.economyStateLong())
                .addValue("progressed", progressed));
    }

    // --------------------------------------------------------- tracking link ---

    /** A vendor order whose customer tracking link is still to be had. */
    public record LinkRow(long orderId, String externalRef, String vendorOrderId, int attempts) {
    }

    /**
     * The vendor states in which the partner may still make a link: it has the order and has
     * not finished it. A finished order cannot have one generated.
     */
    private static final String LINKABLE_STATES = "('SUBMITTED', 'IN_DELIVERY', 'AWAITING_CUSTOMER', 'NEEDS_REVIEW')";
    private static final String FINISHED_ORDERS =
            "('DELIVERED', 'COMPLETED', 'REFUNDED', 'CREDITED', 'ABANDONED', 'DISPUTED')";

    /**
     * Links still to be had and due to be asked for, the longest-waiting first: the partner
     * has the order under its id, it is not finished, there is no link yet, nor a refusal.
     * Includes orders sent before links existed, which is how they are backfilled.
     */
    public List<LinkRow> linksDue(int limit) {
        return jdbc.query("""
                select v.order_id, v.external_ref, v.vendor_order_id, v.tracking_link_attempts
                  from vendor_order v join orders o on o.id = v.order_id
                 where v.tracking_link_state is null and v.vendor_order_id is not null
                   and v.state in %s
                   and o.status not in %s
                   and (v.tracking_link_next_at is null or v.tracking_link_next_at <= now())
                 order by v.tracking_link_next_at nulls first, v.id
                 limit :limit
                """.formatted(LINKABLE_STATES, FINISHED_ORDERS), new MapSqlParameterSource("limit", limit), (rs, i) -> new LinkRow(rs.getLong("order_id"),
                rs.getString("external_ref"), rs.getString("vendor_order_id"), rs.getInt("tracking_link_attempts")));
    }

    /** The same, for one order, due or not: what an admin's "Generate link" asks for. */
    public Optional<LinkRow> linkable(long orderId) {
        return jdbc.query("""
                select v.order_id, v.external_ref, v.vendor_order_id, v.tracking_link_attempts
                  from vendor_order v join orders o on o.id = v.order_id
                 where v.order_id = :orderId and v.tracking_link_state is null and v.vendor_order_id is not null
                   and v.state in %s
                   and o.status not in %s
                """.formatted(LINKABLE_STATES, FINISHED_ORDERS), new MapSqlParameterSource("orderId", orderId), (rs, i) -> new LinkRow(rs.getLong("order_id"),
                rs.getString("external_ref"), rs.getString("vendor_order_id"), rs.getInt("tracking_link_attempts")))
                .stream().findFirst();
    }

    /** What storing a link came to. */
    public record LinkStored(boolean stored, boolean startedEmailNotNeeded) {
    }

    /**
     * The link, kept exactly as the partner returned it. Once: a link already stored, or a
     * refusal already recorded, is never replaced.
     *
     * <p>In the same statement: a link stored within {@link TransferStartedNotices#LINK_GRACE}
     * of the transfer starting settles the "transfer started" email as not needed -- "your
     * coins are on the way" says it, with the link -- unless that email is already settled.
     * Both decisions are conditional updates of the order's own row, so whichever commits
     * first wins and the other finds it settled.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public LinkStored storeTrackingLink(long orderId, String fullUrl) {
        return storeTrackingLink(orderId, new FutTransferClient.LinkReady(fullUrl));
    }

    /** As {@link #storeTrackingLink(long, String)}, with the code, shop and specialParams kept beside the link. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public LinkStored storeTrackingLink(long orderId, FutTransferClient.LinkReady link) {
        return jdbc.queryForObject("""
                with stored as (
                    update vendor_order
                       set tracking_url = :url, tracking_url_at = now(), tracking_link_state = 'STORED',
                           tracking_short_code = :code, tracking_shop = :shop, tracking_special_params = :special,
                           tracking_link_error = null, tracking_link_next_at = null,
                           tracking_link_attempts = tracking_link_attempts + 1, updated_at = now()
                     where order_id = :orderId and tracking_link_state is null
                    returning order_id, tracking_url_at
                ), quiet as (
                    update orders o set transfer_notice_at = s.tracking_url_at
                      from stored s
                     where o.id = s.order_id and o.transfer_notice_at is null and o.transfer_started_at is not null
                       and s.tracking_url_at <= o.transfer_started_at + :graceSeconds * interval '1 second'
                    returning o.id
                )
                select (select count(*) from stored) as stored, (select count(*) from quiet) as quiet
                """, new MapSqlParameterSource("orderId", orderId).addValue("url", link.fullUrl())
                        .addValue("code", link.shortCode())
                        .addValue("shop", blankToNull(link.shop()))
                        .addValue("special", blankToNull(link.specialParams()))
                        .addValue("graceSeconds", TransferStartedNotices.LINK_GRACE.toSeconds()),
                (rs, i) -> new LinkStored(rs.getLong("stored") == 1, rs.getLong("quiet") == 1));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    /** A stored tracking link and its parts, as FUT Transfer returned them. */
    public record PartnerLink(String fullUrl, String shortCode, String shop, String specialParams) {
        @Override
        public String toString() {
            return "PartnerLink[***]";
        }
    }

    /** The order's stored tracking link, with its parts; empty until one is stored. */
    public Optional<PartnerLink> partnerLink(long orderId) {
        return jdbc.query("""
                select tracking_url, tracking_short_code, tracking_shop, tracking_special_params
                  from vendor_order where order_id = :orderId and tracking_link_state = 'STORED'
                """, new MapSqlParameterSource("orderId", orderId), (rs, i) -> new PartnerLink(
                rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4))).stream().findFirst();
    }

    /** The partner will not make a link -- the order is finished. Nothing more is asked. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markTrackingUnavailable(long orderId, String code) {
        return jdbc.update("""
                update vendor_order
                   set tracking_link_state = 'UNAVAILABLE', tracking_link_error = :code, tracking_link_next_at = null,
                       tracking_link_attempts = tracking_link_attempts + 1, updated_at = now()
                 where order_id = :orderId and tracking_link_state is null
                """, new MapSqlParameterSource("orderId", orderId).addValue("code", code)) == 1;
    }

    /** No link this time: asked for again at {@code next}. The order itself is not touched. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void trackingRetryLater(long orderId, String code, Instant next) {
        jdbc.update("""
                update vendor_order
                   set tracking_link_error = :code, tracking_link_next_at = :next,
                       tracking_link_attempts = tracking_link_attempts + 1, updated_at = now()
                 where order_id = :orderId and tracking_link_state is null
                """, new MapSqlParameterSource("orderId", orderId).addValue("code", code)
                .addValue("next", Timestamp.from(next)));
    }

    /**
     * When "your coins are on the way" may go: once the "transfer started" email is settled --
     * not needed because the link came in time, or sent -- so it never arrives first. An order
     * the partner took before transfers were timed has nothing to wait for.
     */
    private static final String ON_THE_WAY_DUE = """
            v.tracking_url is not null and v.tracking_email_at is null
            and (o.transfer_notice_at is not null or o.transfer_started_at is null)
            """;

    /**
     * Claims "your coins are on the way" for sending, before anything is sent: at most once
     * per order, whoever asks and however often, and only once the "transfer started" email
     * is settled.
     *
     * @return the link to put in it, when this caller claimed it
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<String> claimTrackingEmail(long orderId) {
        return jdbc.query("""
                update vendor_order v set tracking_email_at = now()
                  from orders o
                 where v.order_id = :orderId and o.id = v.order_id and %s
                returning v.tracking_url
                """.formatted(ON_THE_WAY_DUE), new MapSqlParameterSource("orderId", orderId),
                (rs, i) -> rs.getString(1)).stream().findFirst();
    }

    /** Orders whose "on the way" email may be claimed now. */
    public List<Long> trackingEmailsDue(int limit) {
        return jdbc.queryForList("""
                select v.order_id from vendor_order v join orders o on o.id = v.order_id
                 where %s
                 order by v.tracking_url_at, v.id
                 limit :limit
                """.formatted(ON_THE_WAY_DUE), new MapSqlParameterSource("limit", limit), Long.class);
    }

    /** The order's tracking link, for its owner's order page; empty until it is stored. */
    public Optional<String> trackingUrl(long orderId) {
        return jdbc.queryForList("""
                select tracking_url from vendor_order where order_id = :orderId and tracking_url is not null
                """, new MapSqlParameterSource("orderId", orderId), String.class).stream().findFirst();
    }

    /** The link as staff see it: the link, when it was stored, and how asking for it is going. */
    public record TrackingLinkStatus(String url, Instant storedAt, String state, int attempts, String lastError,
                                     Instant nextAttemptAt) {
    }

    public Optional<TrackingLinkStatus> trackingStatus(long orderId) {
        return jdbc.query("""
                select tracking_url, tracking_url_at, tracking_link_state, tracking_link_attempts, tracking_link_error,
                       tracking_link_next_at
                  from vendor_order where order_id = :orderId
                """, new MapSqlParameterSource("orderId", orderId), (rs, i) -> new TrackingLinkStatus(
                rs.getString("tracking_url"), instant(rs, "tracking_url_at"), rs.getString("tracking_link_state"),
                rs.getInt("tracking_link_attempts"), rs.getString("tracking_link_error"),
                instant(rs, "tracking_link_next_at"))).stream().findFirst();
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
                         java.math.BigDecimal balanceAtSend,
                         /** The partner's pending / completed / error, and its two details: staff only. */
                         String vendorSimplifiedStatus, String vendorAccountCheckLong,
                         String vendorEconomyStateLong) {

        public Detail(String state, String externalRef, String vendorOrderId, long amountOrderedK,
                      Long vendorAmountOrderedK, Long deliveredK, String vendorStatus, String vendorAccountCheck,
                      String vendorEconomyState, Boolean aborted, Long coinsUsed, java.math.BigDecimal toPay,
                      int attempts, String lastErrorCode, String reviewReason, String customerAction,
                      int missingPolls, Instant submittedAt, Instant lastPolledAt, Instant lastProgressAt,
                      Instant resubmittedAt, Instant updatedAt, String orderMode, String transferMethod,
                      java.math.BigDecimal buyNowThresholdSent, java.math.BigDecimal maxPriceSent,
                      java.math.BigDecimal balanceAtSend) {
            this(state, externalRef, vendorOrderId, amountOrderedK, vendorAmountOrderedK, deliveredK, vendorStatus,
                    vendorAccountCheck, vendorEconomyState, aborted, coinsUsed, toPay, attempts, lastErrorCode,
                    reviewReason, customerAction, missingPolls, submittedAt, lastPolledAt, lastProgressAt,
                    resubmittedAt, updatedAt, orderMode, transferMethod, buyNowThresholdSent, maxPriceSent,
                    balanceAtSend, null, null, null);
        }
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
                rs.getBigDecimal("max_price_sent"), rs.getBigDecimal("balance_at_send"),
                rs.getString("vendor_simplified_status"), rs.getString("vendor_account_check_long"),
                rs.getString("vendor_economy_state_long"))).stream().findFirst();
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
    // ------------------------------------------------- the customer's new sign-in ---

    /** At most one automatic corrected sign-in per order in this long. */
    public static final java.time.Duration SIGN_IN_RESEND_GAP = java.time.Duration.ofMinutes(2);

    private static final String SIGN_IN_RESEND_DUE = """
            state = 'AWAITING_CUSTOMER' and vendor_order_id is not null and signin_resubmitted_at is not null
            and (signin_sent_for is null or signin_sent_for < signin_resubmitted_at)
            and (signin_sent_at is null or signin_sent_at <= now() - :gapSeconds * interval '1 second')
            """;

    /** Orders whose customer re-entered their sign-in, not yet sent, and whose turn has come. */
    public List<Long> signInResendsDue(int limit) {
        return jdbc.queryForList("select order_id from vendor_order where " + SIGN_IN_RESEND_DUE
                        + " order by signin_resubmitted_at limit :limit",
                new MapSqlParameterSource("limit", limit).addValue("gapSeconds", SIGN_IN_RESEND_GAP.toSeconds()),
                Long.class);
    }

    /**
     * Claims the customer's latest submission for sending: true once per submission, and at
     * most once per {@link #SIGN_IN_RESEND_GAP}, whoever asks.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean claimSignInResend(long orderId) {
        return jdbc.update("update vendor_order set signin_sent_for = signin_resubmitted_at, signin_sent_at = now(), "
                        + "updated_at = now() where order_id = :orderId and " + SIGN_IN_RESEND_DUE,
                new MapSqlParameterSource("orderId", orderId).addValue("gapSeconds", SIGN_IN_RESEND_GAP.toSeconds())) == 1;
    }

    /** Not sent after all, for a reason that passes (the partner asked us to wait): due again after the gap. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void unclaimSignInResend(long orderId) {
        jdbc.update("update vendor_order set signin_sent_for = null, updated_at = now() where order_id = :orderId",
                new MapSqlParameterSource("orderId", orderId));
    }

    /**
     * What we asked the customer to fix in their sign-in, when a send was refused for it:
     * RESUBMIT_SIGN_IN or NEW_BACKUP_CODES. Shown on their order page with the form.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void setSignInProblem(long orderId, com.globalfutservice.domain.orders.CustomerAction action) {
        jdbc.update("update orders set signin_problem = :action where id = :orderId",
                new MapSqlParameterSource("orderId", orderId).addValue("action", action.name()));
    }

    public Optional<String> signInProblem(long orderId) {
        return jdbc.query("select signin_problem from orders where id = :orderId",
                        new MapSqlParameterSource("orderId", orderId), (rs, i) -> rs.getString(1))
                .stream().filter(java.util.Objects::nonNull).findFirst();
    }

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
