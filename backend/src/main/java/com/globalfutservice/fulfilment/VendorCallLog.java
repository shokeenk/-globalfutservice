package com.globalfutservice.fulfilment;

import java.sql.Array;
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
 * Writes {@code vendor_call}: one row for every HTTP attempt to FUT Transfer.
 *
 * <p>Committed on its own, so the record of a call survives whatever the caller's
 * transaction does next. A failure to write it is logged and swallowed: the audit trail
 * must never be the reason a vendor call's outcome is lost.
 */
@Component
public class VendorCallLog {

    private static final Logger log = LoggerFactory.getLogger(VendorCallLog.class);

    private final NamedParameterJdbcTemplate jdbc;

    public VendorCallLog(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** One call, as the admin timeline shows it. */
    public record Call(Instant at, String endpoint, String domain, Integer httpStatus, String result,
                       String errorCode, String vendorOrderId, int durationMs) {
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String endpoint, boolean backup, Integer httpStatus, String result, String errorCode,
                       List<String> orderRefs, String vendorOrderId, long durationMs) {
        try {
            jdbc.getJdbcTemplate().update(con -> {
                var ps = con.prepareStatement("""
                        insert into vendor_call (endpoint, domain, http_status, result, error_code, order_refs,
                                                 vendor_order_id, duration_ms)
                        values (?, ?, ?, ?, ?, ?, ?, ?)
                        """);
                ps.setString(1, endpoint);
                ps.setString(2, backup ? "BACKUP" : "PRIMARY");
                if (httpStatus == null) ps.setNull(3, java.sql.Types.INTEGER); else ps.setInt(3, httpStatus);
                ps.setString(4, result);
                ps.setString(5, errorCode);
                Array refs = con.createArrayOf("text", (orderRefs == null ? List.<String>of() : orderRefs).toArray());
                ps.setArray(6, refs);
                ps.setString(7, vendorOrderId);
                ps.setInt(8, (int) Math.min(Integer.MAX_VALUE, Math.max(0, durationMs)));
                return ps;
            });
        } catch (RuntimeException e) {
            log.error("Could not record a FUT Transfer call to {}: {}", endpoint, e.getMessage());
        }
    }

    /** Every call about one order, oldest first. */
    public List<Call> forOrder(String publicRef) {
        return jdbc.query("""
                select created_at, endpoint, domain, http_status, result, error_code, vendor_order_id, duration_ms
                  from vendor_call where :ref = any(order_refs) order by created_at, id
                """, new MapSqlParameterSource("ref", publicRef), (rs, i) -> {
            Timestamp at = rs.getTimestamp("created_at");
            return new Call(at == null ? null : at.toInstant(), rs.getString("endpoint"), rs.getString("domain"),
                    rs.getObject("http_status", Integer.class), rs.getString("result"), rs.getString("error_code"),
                    rs.getString("vendor_order_id"), rs.getInt("duration_ms"));
        });
    }
}
