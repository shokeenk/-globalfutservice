package com.globalfutservice.catalog;

import com.globalfutservice.domain.catalog.Sku;
import com.globalfutservice.domain.money.Currency;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The season check against the prices the migrations actually ship.
 *
 * <p>A fresh database must pass for the default season -- otherwise the check that exists
 * to stop a broken deploy would itself stop every new one -- and a season the migrations
 * never priced must be refused, naming the one they did. Runs when {@code GFS_TEST_PG_URL}
 * is set, in a throwaway schema it drops afterwards.
 */
class SeasonPriceSeedPostgresTest {

    /** {@code GFS_CURRENCIES}' default in application.yml. */
    private static final List<String> DEFAULT_CURRENCIES = List.of("INR", "USD", "EUR", "GBP");

    private String schema;
    private JdbcTemplate jdbc;
    /** Coins, read the way the season check reads them: from their price structures. */
    private CoinPricingService coins;

    @BeforeEach
    void migrate() {
        String url = System.getenv("GFS_TEST_PG_URL");
        assumeTrue(url != null && !url.isBlank(), "GFS_TEST_PG_URL not set: no database to test against");
        schema = "season_test_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        DriverManagerDataSource ds = new DriverManagerDataSource(
                url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema + ",public",
                System.getenv("GFS_TEST_PG_USER"), System.getenv("GFS_TEST_PG_PASSWORD"));
        Flyway.configure().dataSource(ds).schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration")
                .initSql("SET search_path TO " + schema + ", public").load().migrate();
        jdbc = new JdbcTemplate(ds);
        coins = new CoinPricingService(new CoinPriceStore(
                new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(ds)), null);
    }

    @AfterEach
    void drop() {
        if (jdbc != null) jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }

    @Test
    @DisplayName("the shipped FC26 prices cover every sellable service in every default currency")
    void defaultSeasonPasses() {
        assertThat(SeasonPriceCheck.gaps("FC26", priced("FC26"), liveSeasons())).isEmpty();
    }

    @Test
    @DisplayName("a season the migrations never priced is refused, pointing at FC26")
    void unpricedSeasonRefused() {
        assertThatThrownBy(() -> SeasonPriceCheck.gaps("FC27", priced("FC27"), liveSeasons()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("GFS_SEASON is FC27")
                .hasMessageContaining("Seasons with live prices: FC26");
    }

    /**
     * What {@link SeasonPriceCheck#verify} counts as on sale, per currency: the rate card's
     * live rows for everything but coins, and coins where every coin structure prices them.
     */
    private Map<Currency, Set<Sku>> priced(String season) {
        Set<Currency> coinPriced = coins.pricedCurrencies(season);
        Map<Currency, Set<Sku>> out = new LinkedHashMap<>();
        for (Currency currency : SeasonPriceCheck.currencies(DEFAULT_CURRENCIES)) {
            Set<Sku> skus = EnumSet.noneOf(Sku.class);
            jdbc.queryForList("""
                    select sku from rate_card
                    where valid_to is null and season = ? and currency = ? and sku <> 'TRADING_SERVICE'
                    """, String.class, season, currency.name())
                    .forEach(sku -> skus.add(Sku.valueOf(sku)));
            if (coinPriced.contains(currency)) {
                skus.add(Sku.TRADING_SERVICE);
            }
            out.put(currency, skus);
        }
        return out;
    }

    private Supplier<List<String>> liveSeasons() {
        return () -> java.util.stream.Stream.concat(
                        jdbc.queryForList("select distinct season from rate_card where valid_to is null",
                                String.class).stream(),
                        coins.liveSeasons().stream())
                .distinct().sorted().toList();
    }
}
