package com.globalfutservice.loyalty;

import com.globalfutservice.domain.loyalty.PointsEntryType;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Where a customer's points came from and went: the breakdown, the history with the
 * balance after each line, and the points still on the way. The same answer for the
 * customer's own pages and for staff.
 *
 * <p>Everything is read from the ledger, which stays the only record. The breakdown is
 * split so that it always adds up:
 * <pre>
 *   earned + credited − spent + returned − removed + corrected = available
 * </pre>
 * A line an admin reversed and the correction reversing it cancel out, so the pair is left
 * out of every figure -- as if the mistake had never been made. Of the rest:
 * <ul>
 *   <li><b>earned</b>: EARNED, DAILY_BONUS and positive MANUAL_ADJUSTMENT not tied to an order,
 *       less CLAWBACK and SETTLEMENT_DEDUCTION (earnings taken back with their order).
 *       Never REFUND_REVERSAL: that is spent points coming back, not new ones.
 *   <li><b>credited</b>: an old "settled as store credit" order's points, a MANUAL_ADJUSTMENT
 *       on that order. Credit, not earnings; zero for nearly everyone.
 *   <li><b>spent</b>: REDEEMED at checkout.
 *   <li><b>returned</b>: REFUND_REVERSAL, spent points given back after a refund.
 *   <li><b>removed</b>: negative MANUAL_ADJUSTMENT, points staff took off.
 *   <li><b>corrected</b>: an admin's "set the balance to N" corrections, either sign.
 * </ul>
 */
@Service
public class PointsStatement {

    /** Orders still heading for COMPLETED, whose points land when the guarantee window closes. */
    private static final List<String> ON_THE_WAY = List.of("PAID", "CREDENTIALS_PENDING",
            "READY_FOR_DELIVERY", "IN_PROGRESS", "ON_HOLD", "DELIVERED");

    /** The lines every figure counts: not reversed, and not a reversal. */
    private static final String STANDING =
            " (l.reverses_id is null and not exists (select 1 from points_ledger r where r.reverses_id = l.id)) ";

    private final NamedParameterJdbcTemplate jdbc;

    public PointsStatement(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Breakdown(long available, long earned, long spent, long returned, long removed,
                            long onTheWay, List<Pending> pending, long credited, long corrected) {
    }

    /** Points from one order that have not landed yet. {@code landsAt} is null until it is delivered. */
    public record Pending(String orderRef, long points, Instant landsAt) {
    }

    /** What a history line was, in words the pages translate. */
    public enum Kind { EARNED, SPENT, RETURNED, DAILY_BONUS, ADJUSTMENT, REMOVED, STORE_CREDIT_POINTS, CORRECTION }

    /**
     * One line. {@code note} is a correction's reason, which the customer sees; {@code reversed}
     * says an admin has taken this line back; {@code by} names the admin, for staff only.
     */
    public record Line(long id, Instant at, String orderRef, Kind kind, long amount, long balanceAfter,
                       String note, boolean reversed, Long reversesId, String by) {
    }

    public record History(List<Line> entries, int page, int size, long total) {
    }

    public static Kind kindOf(PointsEntryType type, boolean onAnOrder) {
        return switch (type) {
            case EARNED -> Kind.EARNED;
            case REDEEMED -> Kind.SPENT;
            case REFUND_REVERSAL -> Kind.RETURNED;
            case DAILY_BONUS -> Kind.DAILY_BONUS;
            // On an order, it can only be an old "settled as store credit".
            case MANUAL_ADJUSTMENT -> onAnOrder ? Kind.STORE_CREDIT_POINTS : Kind.ADJUSTMENT;
            case CLAWBACK, SETTLEMENT_DEDUCTION -> Kind.REMOVED;
            case CORRECTION -> Kind.CORRECTION;
        };
    }

    @Transactional(readOnly = true)
    public Breakdown breakdown(long accountId) {
        MapSqlParameterSource params = new MapSqlParameterSource("account", accountId);
        long[] sums = jdbc.queryForObject("""
                select coalesce(sum(l.amount), 0) as available,
                       coalesce(sum(l.amount) filter (where (l.entry_type in ('EARNED', 'DAILY_BONUS', 'CLAWBACK',
                                'SETTLEMENT_DEDUCTION')
                             or (l.entry_type = 'MANUAL_ADJUSTMENT' and l.amount > 0 and l.order_id is null))
                             and """ + STANDING + "), 0) as earned," + """
                       coalesce(sum(l.amount) filter (where l.entry_type = 'MANUAL_ADJUSTMENT'
                             and l.order_id is not null and l.amount > 0 and """ + STANDING + "), 0) as credited," + """
                       coalesce(-sum(l.amount) filter (where l.entry_type = 'REDEEMED' and """ + STANDING
                + "), 0) as spent," + """
                       coalesce(sum(l.amount) filter (where l.entry_type = 'REFUND_REVERSAL' and """ + STANDING
                + "), 0) as returned," + """
                       coalesce(-sum(l.amount) filter (where l.entry_type = 'MANUAL_ADJUSTMENT' and l.amount < 0
                             and """ + STANDING + "), 0) as removed," + """
                       coalesce(sum(l.amount) filter (where l.entry_type = 'CORRECTION' and """ + STANDING
                + "), 0) as corrected" + """
                  from points_ledger l where l.account_id = :account
                """, params, (rs, i) -> new long[]{rs.getLong("available"), rs.getLong("earned"),
                rs.getLong("spent"), rs.getLong("returned"), rs.getLong("removed"), rs.getLong("credited"),
                rs.getLong("corrected")});
        List<Pending> pending = jdbc.query("""
                select public_ref, points_earned, guarantee_expires_at from orders
                 where account_id = :account and points_settled_at is null and points_earned > 0
                   and status in (:statuses)
                 order by guarantee_expires_at nulls last, created_at, id
                """, params.addValue("statuses", ON_THE_WAY),
                (rs, i) -> new Pending(rs.getString(1), rs.getLong(2),
                        Optional.ofNullable(rs.getTimestamp(3)).map(Timestamp::toInstant).orElse(null)));
        long onTheWay = pending.stream().mapToLong(Pending::points).sum();
        return new Breakdown(sums[0], sums[1], sums[2], sums[3], sums[4], onTheWay, pending, sums[5], sums[6]);
    }

    /** Newest first, with the balance as it stood just after each line. No admin names. */
    @Transactional(readOnly = true)
    public History history(long accountId, int page, int size) {
        return history(accountId, page, size, false);
    }

    /** As {@link #history(long, int, int)}; {@code withStaff} adds who made each correction. */
    @Transactional(readOnly = true)
    public History history(long accountId, int page, int size, boolean withStaff) {
        int p = Math.max(0, page);
        int s = Math.max(1, Math.min(size, 100));
        MapSqlParameterSource params = new MapSqlParameterSource("account", accountId)
                .addValue("limit", s).addValue("offset", (long) p * s);
        long total = Optional.ofNullable(jdbc.queryForObject(
                "select count(*) from points_ledger where account_id = :account", params, Long.class)).orElse(0L);
        List<Line> lines = jdbc.query("""
                select l.id, l.created_at, o.public_ref, l.order_id, l.entry_type, l.amount, l.description,
                       l.reverses_id, coalesce(nullif(a.display_name, ''), a.email) as actor,
                       exists (select 1 from points_ledger r where r.reverses_id = l.id) as reversed,
                       sum(l.amount) over (order by l.created_at, l.id rows unbounded preceding) as balance_after
                  from points_ledger l
                  left join orders o on o.id = l.order_id
                  left join account a on a.id = l.actor_id
                 where l.account_id = :account
                 order by l.created_at desc, l.id desc
                 limit :limit offset :offset
                """, params, (rs, i) -> {
                    PointsEntryType type = PointsEntryType.valueOf(rs.getString("entry_type"));
                    boolean correction = type == PointsEntryType.CORRECTION;
                    return new Line(rs.getLong("id"), rs.getTimestamp("created_at").toInstant(),
                            rs.getString("public_ref"), kindOf(type, rs.getObject("order_id") != null),
                            rs.getLong("amount"), rs.getLong("balance_after"),
                            correction ? rs.getString("description") : null,
                            rs.getBoolean("reversed"), (Long) rs.getObject("reverses_id", Long.class),
                            withStaff && correction ? rs.getString("actor") : null);
                });
        return new History(lines, p, s, total);
    }
}
