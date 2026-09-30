package com.globalfutservice.fulfilment;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes {@code vendor_order_action}: what an admin did to an order at FUT Transfer, who
 * did it, and what came of it.
 *
 * <p>Committed on its own and never thrown from, like {@link VendorCallLog}: by the time
 * this is written the vendor has already answered, and failing to record it must not hide
 * that answer from the admin who asked. The failure is logged loudly instead.
 */
@Component
public class VendorOrderActionLog {

    private static final Logger log = LoggerFactory.getLogger(VendorOrderActionLog.class);

    public enum Action { SEND_SIGN_IN, RESUME, STOP, MARK_FINISHED, RETRY, LINK, RESOLVE }

    public enum Outcome { DONE, REFUSED, UNCERTAIN }

    /** One action, as the admin timeline shows it. */
    public record Entry(Instant at, String action, String actorLabel, String outcome, String code, String detail) {
    }

    private final NamedParameterJdbcTemplate jdbc;

    public VendorOrderActionLog(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param detail written by us, for the admin reading the timeline; never a sign-in
     * @param code   a bare identifier (TIMEOUT, HTTP_429), or null
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(long orderId, Action action, Long actorId, String actorLabel, Outcome outcome, String code,
                       String detail) {
        try {
            jdbc.update("""
                    insert into vendor_order_action (order_id, action, actor_id, actor_label, outcome, code, detail)
                    values (:orderId, :action, :actorId, :actorLabel, :outcome, :code, :detail)
                    """, new MapSqlParameterSource("orderId", orderId)
                    .addValue("action", action.name())
                    .addValue("actorId", actorId)
                    .addValue("actorLabel", actorLabel)
                    .addValue("outcome", outcome.name())
                    .addValue("code", code)
                    .addValue("detail", detail));
        } catch (RuntimeException e) {
            log.error("Could not record admin action {} ({}) on order id {} by {}: {}", action, outcome, orderId,
                    actorLabel, e.getMessage());
        }
    }

    /** Every admin action on one order, oldest first. */
    public List<Entry> forOrder(long orderId) {
        return jdbc.query("""
                select created_at, action, actor_label, outcome, code, detail
                  from vendor_order_action where order_id = :orderId order by created_at, id
                """, new MapSqlParameterSource("orderId", orderId), (rs, i) -> {
            Timestamp at = rs.getTimestamp("created_at");
            return new Entry(at == null ? null : at.toInstant(), rs.getString("action"), rs.getString("actor_label"),
                    rs.getString("outcome"), rs.getString("code"), rs.getString("detail"));
        });
    }
}
