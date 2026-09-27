package com.globalfutservice.admin;

import com.globalfutservice.domain.money.Currency;
import com.globalfutservice.domain.money.Money;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The Payments page: every payment a customer reported, and what became of it.
 *
 * <p>Payments here are manual claims -- a UPI, PayPal or USDT transfer the customer says
 * they made, with its reference -- because that is how the site takes money. The
 * {@code payment} table is the old card gateway's and is not read.
 *
 * <p>Each claim has one of four statuses on this page, from what happened to it and to its
 * order: <b>Pending</b> (nobody has checked it), <b>Failed</b> (checked and rejected: the
 * money was not found), <b>Refunded</b> (verified, and the order was later refunded), and
 * <b>Success</b> (verified, not refunded).
 */
@Service
public class AdminPaymentQueries {

    private static final String PAY = """
            pay as (
              select c.id as claim_id, c.method, c.reference, c.destination, c.status as claim_status,
                     c.submitted_at, c.reviewed_at, c.review_note,
                     o.public_ref, o.status as order_status, o.total_minor, o.currency, o.guest_email as email,
                     coalesce(nullif(trim(o.guest_name), ''), a.display_name) as name,
                     exists (select 1 from manual_payment_proof p where p.claim_id = c.id) as has_proof,
                     coalesce(nullif(trim(rv.display_name), ''), rv.email) as reviewed_by,
                     r.amount_minor as refund_minor, r.currency as refund_currency, r.method as refund_method,
                     r.reference as refund_reference, r.reason as refund_reason, r.created_at as refunded_at,
                     coalesce(nullif(trim(ra.display_name), ''), ra.email) as refunded_by,
                     case when c.status = 'SUBMITTED' then 'PENDING'
                          when c.status = 'REJECTED' then 'FAILED'
                          when o.status = 'REFUNDED' then 'REFUNDED'
                          else 'SUCCESS' end as pay_status
                from manual_payment_claim c
                join orders o on o.id = c.order_id
                left join account a on a.id = o.account_id
                left join account rv on rv.id = c.reviewed_by
                left join refund r on r.order_id = o.id and c.status = 'VERIFIED'
                left join account ra on ra.id = r.created_by
            )""";

    static final Set<String> STATUSES = Set.of("SUCCESS", "PENDING", "FAILED", "REFUNDED");
    static final Set<String> METHODS = Set.of("UPI", "PAYPAL", "CRYPTO");

    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    public AdminPaymentQueries(NamedParameterJdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public record Filter(String status, String method, LocalDate from, LocalDate to, String search) {
    }

    public record Refund(long amountMinor, String amountFormatted, String method, String reference,
                         String reason, Instant at, String by) {
    }

    public record Row(long claimId, String publicRef, String customerName, String email, String method,
                      String reference, String destination, String status, String orderStatus,
                      long amountMinor, String amountFormatted, String currency, Instant submittedAt,
                      Instant reviewedAt, String reviewedBy, String reviewNote, boolean hasProof,
                      /** Present when a refund was recorded; a Refunded row without one predates the record. */
                      Refund refund) {
    }

    public record Page(List<Row> items, long total, int page, int size) {
    }

    /** One status in one currency: how many payments, and (for admins) how much. */
    public record Total(String status, String currency, long count, Long minor, String formatted) {
    }

    public record Overview(
            /** This month so far, India time. */
            List<Total> thisMonth,
            /** Last month up to the same point. */
            List<Total> lastMonthSoFar,
            /** Every payment ever, by status: the tab counts. */
            Map<String, Long> allTime) {
    }

    @Transactional(readOnly = true)
    public Page search(Filter filter, int page, int size) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        String where = where(filter, params);
        long total = Optional.ofNullable(jdbc.queryForObject(
                "with " + PAY + " select count(*) from pay " + where, params, Long.class)).orElse(0L);
        params.addValue("limit", size).addValue("offset", (long) page * size);
        List<Row> rows = jdbc.query("with " + PAY + " select * from pay " + where
                + " order by submitted_at desc, claim_id desc limit :limit offset :offset", params, AdminPaymentQueries::row);
        return new Page(rows, total, page, size);
    }

    @Transactional(readOnly = true)
    public List<Row> export(Filter filter, int cap) {
        MapSqlParameterSource params = new MapSqlParameterSource("cap", cap);
        return jdbc.query("with " + PAY + " select * from pay " + where(filter, params)
                + " order by submitted_at desc, claim_id desc limit :cap", params, AdminPaymentQueries::row);
    }

    @Transactional(readOnly = true)
    public Overview overview(boolean withMoney) {
        ZonedDateTime local = clock.instant().atZone(AdminOrderQueries.BUSINESS_ZONE);
        ZonedDateTime monthStart = local.toLocalDate().withDayOfMonth(1).atStartOfDay(AdminOrderQueries.BUSINESS_ZONE);
        Map<String, Long> allTime = new LinkedHashMap<>();
        for (String status : List.of("SUCCESS", "PENDING", "FAILED", "REFUNDED")) {
            allTime.put(status, 0L);
        }
        jdbc.query("with " + PAY + " select pay_status, count(*) as n from pay group by pay_status",
                new MapSqlParameterSource(), (RowCallbackHandler) rs -> {
                    allTime.put(rs.getString("pay_status"), rs.getLong("n"));
                });
        return new Overview(
                totals(monthStart.toInstant(), local.toInstant(), withMoney),
                totals(monthStart.minusMonths(1).toInstant(), local.minusMonths(1).toInstant(), withMoney),
                allTime);
    }

    private List<Total> totals(Instant from, Instant before, boolean withMoney) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("from", Timestamp.from(from)).addValue("before", Timestamp.from(before));
        return jdbc.query("with " + PAY + """
                 select pay_status, currency, count(*) as n, sum(total_minor) as minor from pay
                  where submitted_at >= :from and submitted_at < :before
                  group by pay_status, currency order by pay_status, currency
                """, params, (rs, i) -> {
            Currency currency = Currency.valueOf(rs.getString("currency").trim());
            long minor = rs.getLong("minor");
            return withMoney
                    ? new Total(rs.getString("pay_status"), currency.name(), rs.getLong("n"), minor,
                            Money.ofMinor(minor, currency).format())
                    : new Total(rs.getString("pay_status"), currency.name(), rs.getLong("n"), null, null);
        });
    }

    private static String where(Filter f, MapSqlParameterSource params) {
        List<String> clauses = new ArrayList<>();
        if (f.status() != null) {
            params.addValue("status", f.status());
            clauses.add("pay_status = :status");
        }
        if (f.method() != null) {
            params.addValue("method", f.method());
            clauses.add("method = :method");
        }
        if (f.from() != null) {
            params.addValue("from", Timestamp.from(f.from().atStartOfDay(AdminOrderQueries.BUSINESS_ZONE).toInstant()));
            clauses.add("submitted_at >= :from");
        }
        if (f.to() != null) {
            params.addValue("to", Timestamp.from(f.to().plusDays(1).atStartOfDay(AdminOrderQueries.BUSINESS_ZONE).toInstant()));
            clauses.add("submitted_at < :to");
        }
        if (f.search() != null) {
            params.addValue("p", "%" + AdminOrderSpecs.escapeLike(f.search().toLowerCase(Locale.ROOT)) + "%");
            clauses.add("""
                    (lower(public_ref) like :p escape '\\' or lower(email) like :p escape '\\'
                     or lower(reference) like :p escape '\\' or lower(coalesce(name, '')) like :p escape '\\'
                     or lower(coalesce(refund_reference, '')) like :p escape '\\')""");
        }
        return clauses.isEmpty() ? " " : " where " + String.join(" and ", clauses) + " ";
    }

    private static Row row(ResultSet rs, int i) throws SQLException {
        Currency currency = Currency.valueOf(rs.getString("currency").trim());
        long amount = rs.getLong("total_minor");
        Refund refund = null;
        long refundMinor = rs.getLong("refund_minor");
        if (!rs.wasNull()) {
            Currency refundCurrency = Currency.valueOf(rs.getString("refund_currency").trim());
            refund = new Refund(refundMinor, Money.ofMinor(refundMinor, refundCurrency).format(),
                    rs.getString("refund_method"), rs.getString("refund_reference"), rs.getString("refund_reason"),
                    instant(rs, "refunded_at"), rs.getString("refunded_by"));
        }
        return new Row(rs.getLong("claim_id"), rs.getString("public_ref"), rs.getString("name"), rs.getString("email"),
                rs.getString("method"), rs.getString("reference"), rs.getString("destination"),
                rs.getString("pay_status"), rs.getString("order_status"), amount,
                Money.ofMinor(amount, currency).format(), currency.name(), instant(rs, "submitted_at"),
                instant(rs, "reviewed_at"), rs.getString("reviewed_by"), rs.getString("review_note"),
                rs.getBoolean("has_proof"), refund);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }
}
