package com.globalfutservice.catalog;

import com.globalfutservice.config.AppProperties;
import com.globalfutservice.domain.catalog.Sku;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What an admin has set about listings beyond their price: success rates, the Best Value
 * tag, and which listings are hidden. Read by the public catalogue and the Listings page.
 *
 * <p><b>Nothing changes until an admin decides.</b> A success rate nobody has set here
 * comes from configuration, as before; with no Best Value choice for a service, its last
 * tier carries the tag, as before.
 */
@Component
public class ListingSettings {

    private final NamedParameterJdbcTemplate jdbc;
    private final AppProperties props;

    public ListingSettings(NamedParameterJdbcTemplate jdbc, AppProperties props) {
        this.jdbc = jdbc;
        this.props = props;
    }

    /** One listing's settings as stored; absent fields mean "never set here". */
    public record Stored(Integer successRateBps, boolean successRateSet, Instant hiddenSince) {
    }

    public Map<String, Stored> forSku(Sku sku) {
        Map<String, Stored> out = new HashMap<>();
        jdbc.query("select variant, success_rate_bps, success_rate_set, hidden_since from listing_setting where sku = :sku",
                new MapSqlParameterSource("sku", sku.name()), (RowCallbackHandler) rs -> {
                    Timestamp hidden = rs.getTimestamp("hidden_since");
                    out.put(rs.getString("variant"), new Stored(rs.getObject("success_rate_bps", Integer.class),
                            rs.getBoolean("success_rate_set"), hidden == null ? null : hidden.toInstant()));
                });
        return out;
    }

    /** The success rate a listing shows: the admin's if they set one, else configuration's. */
    public Integer successRate(String variant, Stored stored) {
        if (stored != null && stored.successRateSet()) {
            return stored.successRateBps();
        }
        return props.boosting().successRateBpsFor(variant);
    }

    /**
     * The admin's Best Value choice for a service: empty when they never chose (the last
     * tier carries it), a present-but-empty inner value when they chose "none".
     */
    public Optional<Optional<String>> bestValue(Sku sku) {
        List<Optional<String>> rows = jdbc.query("select variant from listing_best_value where sku = :sku",
                new MapSqlParameterSource("sku", sku.name()), (rs, i) -> Optional.ofNullable(rs.getString(1)));
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** The listing carrying the tag, from the choice or, without one, the last of {@code variants}. */
    public Optional<String> resolveBestValue(Sku sku, List<String> variantsInOrder) {
        return bestValue(sku).orElseGet(() -> variantsInOrder.isEmpty()
                ? Optional.empty()
                : Optional.of(variantsInOrder.get(variantsInOrder.size() - 1)));
    }

    void setSuccessRate(Sku sku, String variant, Integer bps, Long adminId) {
        jdbc.update("""
                insert into listing_setting (sku, variant, success_rate_bps, success_rate_set, updated_by, updated_at)
                values (:sku, :variant, :bps, true, :by, now())
                on conflict (sku, variant) do update
                   set success_rate_bps = :bps, success_rate_set = true, updated_by = :by, updated_at = now()
                """, params(sku, variant, adminId).addValue("bps", bps));
    }

    void setHidden(Sku sku, String variant, boolean hidden, Long adminId) {
        jdbc.update("""
                insert into listing_setting (sku, variant, hidden_since, updated_by, updated_at)
                values (:sku, :variant, case when :hidden then now() end, :by, now())
                on conflict (sku, variant) do update
                   set hidden_since = case when :hidden then coalesce(listing_setting.hidden_since, now()) end,
                       updated_by = :by, updated_at = now()
                """, params(sku, variant, adminId).addValue("hidden", hidden));
    }

    /** {@code variant} null: no listing of this service carries the tag. */
    void setBestValue(Sku sku, String variant, Long adminId) {
        jdbc.update("""
                insert into listing_best_value (sku, variant, updated_by, updated_at)
                values (:sku, :variant, :by, now())
                on conflict (sku) do update set variant = :variant, updated_by = :by, updated_at = now()
                """, params(sku, variant, adminId));
    }

    private static MapSqlParameterSource params(Sku sku, String variant, Long adminId) {
        return new MapSqlParameterSource("sku", sku.name()).addValue("variant", variant).addValue("by", adminId);
    }
}
