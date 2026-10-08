package com.globalfutservice.catalog;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import com.globalfutservice.domain.catalog.CoinMarket;
import com.globalfutservice.domain.catalog.CoinPriceTable;
import com.globalfutservice.domain.money.Currency;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * The coin price structures, as stored: {@code coin_price_card} for each version's slider,
 * {@code coin_price_rate} for its prices.
 *
 * <p>A version is never changed. {@link #replace} closes the live one and inserts the
 * next, in one transaction; the unique index on the live version makes a second admin
 * saving at the same moment fail rather than leave two live prices.
 */
@Repository
public class CoinPriceStore {

    private final NamedParameterJdbcTemplate jdbc;

    public CoinPriceStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** A stored version: its prices, when it was live, and who set it (null for a migration). */
    public record Version(CoinPriceTable table, Instant validFrom, Instant validTo, String createdBy) {
    }

    private static final String CARD = """
            select c.id, c.season, c.market, c.min_k, c.max_k, c.step_k,
                   array_to_string(c.quick_picks_k, ',') as picks, c.valid_from, c.valid_to, a.email as created_by
              from coin_price_card c left join account a on a.id = c.created_by
            """;

    /** Each structure's live version in {@code season}. */
    @Transactional(readOnly = true)
    public Map<CoinMarket, Version> live(String season) {
        Map<CoinMarket, Version> out = new EnumMap<>(CoinMarket.class);
        for (Version v : versions(CARD + " where c.season = :season and c.valid_to is null",
                new MapSqlParameterSource("season", season))) {
            out.put(v.table().market(), v);
        }
        return out;
    }

    @Transactional(readOnly = true)
    public Optional<Version> live(String season, CoinMarket market) {
        return Optional.ofNullable(live(season).get(market));
    }

    /** Every version of one structure in {@code season}, newest first. */
    @Transactional(readOnly = true)
    public List<Version> history(String season, CoinMarket market, int limit) {
        return versions(CARD + " where c.season = :season and c.market = :market order by c.valid_from desc, c.id desc"
                        + " limit :limit",
                new MapSqlParameterSource("season", season).addValue("market", market.name()).addValue("limit", limit));
    }

    /** Seasons with a live coin price, for telling an operator what exists. */
    @Transactional(readOnly = true)
    public List<String> liveSeasons() {
        return jdbc.queryForList("select distinct season from coin_price_card where valid_to is null order by season",
                new MapSqlParameterSource(), String.class);
    }

    /**
     * Makes {@code next} the live version of its structure: closes the current one and
     * inserts this, with its prices.
     *
     * @throws org.springframework.dao.DuplicateKeyException when another save made a new
     *         version first
     */
    @Transactional
    public Version replace(CoinPriceTable next, Long adminId) {
        MapSqlParameterSource card = new MapSqlParameterSource()
                .addValue("season", next.season())
                .addValue("market", next.market().name())
                .addValue("minK", next.minK())
                .addValue("maxK", next.maxK())
                .addValue("stepK", next.stepK())
                .addValue("picks", next.quickPicksK().stream().map(String::valueOf).collect(Collectors.joining(",")))
                .addValue("by", adminId);
        jdbc.update("""
                update coin_price_card set valid_to = now()
                 where season = :season and market = :market and valid_to is null
                """, card);
        Long id = jdbc.queryForObject("""
                insert into coin_price_card (season, market, min_k, max_k, step_k, quick_picks_k, created_by)
                values (:season, :market, :minK, :maxK, :stepK, string_to_array(:picks, ',')::int[], :by)
                returning id
                """, card, Long.class);
        List<MapSqlParameterSource> rates = new ArrayList<>();
        next.rates().forEach((currency, brackets) -> brackets.forEach(b -> rates.add(new MapSqlParameterSource()
                .addValue("card", id).addValue("currency", currency.name())
                .addValue("fromK", b.fromK()).addValue("rate", b.perMillionMinor()))));
        jdbc.batchUpdate("""
                insert into coin_price_rate (card_id, currency, from_k, per_million_minor)
                values (:card, :currency, :fromK, :rate)
                """, rates.toArray(MapSqlParameterSource[]::new));
        return live(next.season(), next.market()).orElseThrow();
    }

    private List<Version> versions(String sql, MapSqlParameterSource params) {
        List<Card> cards = jdbc.query(sql, params, (rs, i) -> card(rs));
        if (cards.isEmpty()) {
            return List.of();
        }
        Map<Long, Map<Currency, List<CoinPriceTable.Bracket>>> rates = new HashMap<>();
        jdbc.query("""
                select card_id, currency, from_k, per_million_minor from coin_price_rate where card_id in (:ids)
                """, new MapSqlParameterSource("ids", cards.stream().map(Card::id).toList()), rs -> {
            Currency currency;
            try {
                currency = Currency.valueOf(rs.getString("currency").trim());
            } catch (IllegalArgumentException e) {
                return; // a currency this build does not know cannot be priced in
            }
            rates.computeIfAbsent(rs.getLong("card_id"), k -> new EnumMap<>(Currency.class))
                    .computeIfAbsent(currency, k -> new ArrayList<>())
                    .add(new CoinPriceTable.Bracket(rs.getInt("from_k"), rs.getLong("per_million_minor")));
        });
        return cards.stream().map(c -> new Version(new CoinPriceTable(c.id(), c.season(), c.market(), c.minK(),
                        c.maxK(), c.stepK(), c.picks(), rates.getOrDefault(c.id(), Map.of())),
                c.validFrom(), c.validTo(), c.createdBy())).toList();
    }

    private record Card(long id, String season, CoinMarket market, int minK, int maxK, int stepK, List<Integer> picks,
                        Instant validFrom, Instant validTo, String createdBy) {
    }

    private static Card card(ResultSet rs) throws SQLException {
        String picks = rs.getString("picks");
        return new Card(rs.getLong("id"), rs.getString("season"), CoinMarket.valueOf(rs.getString("market")),
                rs.getInt("min_k"), rs.getInt("max_k"), rs.getInt("step_k"),
                picks == null || picks.isBlank() ? List.of()
                        : Arrays.stream(picks.split(",")).map(String::trim).map(Integer::valueOf).toList(),
                instant(rs, "valid_from"), instant(rs, "valid_to"), rs.getString("created_by"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        java.sql.Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }
}
