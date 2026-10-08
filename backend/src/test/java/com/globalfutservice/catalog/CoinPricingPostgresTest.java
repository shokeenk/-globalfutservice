package com.globalfutservice.catalog;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.globalfutservice.catalog.web.CatalogDtos;
import com.globalfutservice.config.AppProperties;
import com.globalfutservice.domain.catalog.CoinMarket;
import com.globalfutservice.domain.catalog.CoinPriceTable;
import com.globalfutservice.domain.catalog.CoinPriceTable.Bracket;
import com.globalfutservice.domain.catalog.Platform;
import com.globalfutservice.domain.money.Currency;
import com.globalfutservice.web.ApiExceptions;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Coin prices moving from the rate card into two structures, and living there: what V44
 * copies, what it refuses, and saving, reading and pricing from the stored versions.
 * Runs when {@code GFS_TEST_PG_URL} is set, each test in a throwaway schema.
 */
class CoinPricingPostgresTest {

    private String schema;
    private DriverManagerDataSource ds;
    private JdbcTemplate jdbc;

    /** Migrated to just before V44, with the FC26 coin prices as production has them now. */
    private void before44() {
        String url = System.getenv("GFS_TEST_PG_URL");
        assumeTrue(url != null && !url.isBlank(), "GFS_TEST_PG_URL not set: no database to test against");
        schema = "coin_test_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        ds = new DriverManagerDataSource(url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema + ",public",
                System.getenv("GFS_TEST_PG_USER"), System.getenv("GFS_TEST_PG_PASSWORD"));
        jdbc = new JdbcTemplate(ds);
        flyway("43").migrate();
        // Changed from the admin since V25: these are the numbers V44 must carry, not V25's.
        price("INR", 1_300_000);
        price("USD", 14_300);
        price("EUR", 12_500);
        price("GBP", 10_800);
    }

    private void price(String currency, long perMillionMinor) {
        jdbc.update("update rate_card set unit_price_minor = ? where sku = 'TRADING_SERVICE' and valid_to is null "
                + "and season = 'FC26' and currency = ?", perMillionMinor, currency);
    }

    private Flyway flyway(String target) {
        return Flyway.configure().dataSource(ds).schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration").target(target)
                .initSql("SET search_path TO " + schema + ", public").load();
    }

    @AfterEach
    void drop() {
        if (jdbc != null && schema != null) {
            jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    private CoinPricingService service(CoinPriceStore store) {
        AppProperties props = mock(AppProperties.class);
        AppProperties.Pricing pricing = mock(AppProperties.Pricing.class);
        AppProperties.FutTransfer futTransfer = mock(AppProperties.FutTransfer.class);
        when(props.season()).thenReturn("FC26");
        when(props.pricing()).thenReturn(pricing);
        when(pricing.enabledCurrencies()).thenReturn(List.of("INR", "USD", "EUR", "GBP"));
        when(props.futTransfer()).thenReturn(futTransfer);
        when(futTransfer.order()).thenReturn(new AppProperties.FutTransferOrder(300, 1, 50, 0, "1", 0, "0", "0", 0,
                "-1", "-1"));
        return new CoinPricingService(store, props);
    }

    @Test
    @DisplayName("V44: two structures from the live prices -- PC from PC, PlayStation + Xbox from PlayStation -- "
            + "at a 50K minimum, today's range and quick picks; the old coin rows closed")
    void migrates() {
        before44();
        flyway("latest").migrate();
        CoinPricingService pricing = service(new CoinPriceStore(new NamedParameterJdbcTemplate(ds)));

        Map<CoinMarket, CoinPriceStore.Version> live = pricing.live();
        assertThat(live).containsOnlyKeys(CoinMarket.PC, CoinMarket.CONSOLE);
        for (CoinPriceStore.Version v : live.values()) {
            CoinPriceTable t = v.table();
            assertThat(t.season()).isEqualTo("FC26");
            assertThat(List.of(t.minK(), t.maxK(), t.stepK())).containsExactly(50, 1000, 10);
            assertThat(t.quickPicksK()).containsExactly(50, 100, 250, 500, 1000);
            assertThat(t.base(Currency.INR)).hasValue(1_300_000);
            assertThat(t.base(Currency.USD)).hasValue(14_300);
            assertThat(t.base(Currency.EUR)).hasValue(12_500);
            assertThat(t.base(Currency.GBP)).hasValue(10_800);
            assertThat(t.brackets(Currency.INR)).isEmpty();
            assertThat(v.createdBy()).isNull();
            assertThat(pricing.check(t).errors()).isEmpty();
        }
        assertThat(jdbc.queryForObject("select count(*) from rate_card where sku = 'TRADING_SERVICE' and valid_to is null",
                Long.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from rate_card where sku = 'TRADING_SERVICE'", Long.class))
                .isPositive();
        assertThat(pricing.pricedCurrencies("FC26"))
                .containsExactlyInAnyOrder(Currency.INR, Currency.USD, Currency.EUR, Currency.GBP);
    }

    @Test
    @DisplayName("V44: PlayStation and Xbox priced differently -- stops, names both, and changes nothing")
    void refusesToPick() {
        before44();
        jdbc.update("update rate_card set unit_price_minor = 1250000 where sku = 'TRADING_SERVICE' and valid_to is null "
                + "and season = 'FC26' and currency = 'INR' and platform = 'XBOX'");

        assertThatThrownBy(() -> flyway("latest").migrate())
                .hasMessageContaining("PlayStation and Xbox coin prices differ")
                .hasMessageContaining("FC26 INR: PlayStation 1300000")
                .hasMessageContaining("Xbox 1250000");
        assertThat(jdbc.queryForObject("select to_regclass('coin_price_card')::text", String.class)).isNull();
        assertThat(jdbc.queryForObject("select count(*) from rate_card where sku = 'TRADING_SERVICE' and valid_to is null",
                Long.class)).isEqualTo(12L);
    }

    @Test
    @DisplayName("saving a structure: a new version, the old one closed and kept, the other structure untouched, "
            + "a save that changes nothing not saved, and one live version at a time")
    void saves() {
        before44();
        flyway("latest").migrate();
        CoinPriceStore store = new CoinPriceStore(new NamedParameterJdbcTemplate(ds));
        CoinPricingService pricing = service(store);
        CoinPriceTable before = pricing.live().get(CoinMarket.CONSOLE).table();
        long admin = jdbc.queryForObject("""
                insert into account (public_id, email, email_normalised, role, password_hash)
                values ('acc_pricing', 'owner@example.test', 'owner@example.test', 'ADMIN', 'x') returning id
                """, Long.class);

        Map<Currency, List<Bracket>> rates = new java.util.EnumMap<>(Currency.class);
        rates.putAll(before.rates());
        rates.put(Currency.INR, List.of(new Bracket(0, 1_250_000), new Bracket(500, 1_200_000)));
        CoinPriceTable next = new CoinPriceTable(null, "FC26", CoinMarket.CONSOLE, 50, 2000, 10, List.of(), rates);
        CoinPriceStore.Version saved = pricing.save(next, admin, "acc_pricing");

        assertThat(saved.table().version()).isNotEqualTo(before.version());
        assertThat(saved.table().maxK()).isEqualTo(2000);
        assertThat(saved.table().quickPicksK()).isEmpty();
        assertThat(saved.createdBy()).isEqualTo("owner@example.test");
        assertThat(pricing.save(next, admin, "acc_pricing").table().version()).isEqualTo(saved.table().version());
        assertThat(pricing.history(CoinMarket.CONSOLE, 20)).extracting(v -> v.table().version())
                .containsExactly(saved.table().version(), before.version());
        assertThat(pricing.history(CoinMarket.CONSOLE, 20).get(1).validTo()).isNotNull();
        assertThat(pricing.live().get(CoinMarket.PC).table().base(Currency.INR)).hasValue(1_300_000);

        // Priced from the version: PlayStation and Xbox alike, the bracket reached; PC at its own price.
        var xbox = pricing.rateCard(Platform.XBOX, Currency.INR, new BigDecimal("0.60"));
        assertThat(xbox.unitPrice().minor()).isEqualTo(1_200_000);
        assertThat(xbox.priceVersion()).isEqualTo(saved.table().version());
        assertThat(pricing.rateCard(Platform.PLAYSTATION, Currency.INR, new BigDecimal("0.40")).unitPrice().minor())
                .isEqualTo(1_250_000);
        assertThat(pricing.rateCard(Platform.PC, Currency.INR, new BigDecimal("0.60")).unitPrice().minor())
                .isEqualTo(1_300_000);

        assertThatThrownBy(() -> jdbc.update("insert into coin_price_card (season, market, min_k, max_k, step_k) "
                + "values ('FC26', 'CONSOLE', 50, 1000, 10)")).isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    @DisplayName("a save that cannot be sold is refused with every reason, and nothing is written")
    void refusesInvalid() {
        before44();
        flyway("latest").migrate();
        CoinPricingService pricing = service(new CoinPriceStore(new NamedParameterJdbcTemplate(ds)));
        CoinPriceTable bad = new CoinPriceTable(null, "FC26", CoinMarket.PC, 55, 20_000, 10, List.of(),
                Map.of(Currency.INR, List.of(new Bracket(0, 1_300_000))));

        assertThatThrownBy(() -> pricing.save(bad, null, "acc_pricing"))
                .isInstanceOfSatisfying(ApiExceptions.BadRequestException.class, e -> assertThat(e.getMessage())
                        .contains("The minimum must be a whole multiple of 10K")
                        .contains("The maximum can be at most 10M")
                        .contains("Set a base price for USD"));
        assertThat(jdbc.queryForObject("select count(*) from coin_price_card", Long.class)).isEqualTo(2L);
    }

    @Test
    @DisplayName("the catalogue: one option per platform, PlayStation and Xbox the same, each with its structure")
    void catalogue() {
        before44();
        flyway("latest").migrate();
        CoinPricingService pricing = service(new CoinPriceStore(new NamedParameterJdbcTemplate(ds)));
        CoinPriceTable pc = pricing.live().get(CoinMarket.PC).table();
        Map<Currency, List<Bracket>> rates = new java.util.EnumMap<>(Currency.class);
        rates.putAll(pc.rates());
        rates.put(Currency.INR, List.of(new Bracket(0, 1_400_000), new Bracket(500, 1_350_000)));
        pricing.save(new CoinPriceTable(null, "FC26", CoinMarket.PC, 50, 1000, 10, List.of(100, 500), rates), null, "t");

        List<CatalogDtos.CatalogOption> options = pricing.catalogOptions(Currency.INR);
        assertThat(options).extracting(CatalogDtos.CatalogOption::platform).containsExactly("PC", "PLAYSTATION", "XBOX");
        assertThat(options).extracting(CatalogDtos.CatalogOption::unitPriceFormatted)
                .containsExactly("₹14,000.00", "₹13,000.00", "₹13,000.00");
        assertThat(options.get(0).minQuantity()).isEqualByComparingTo("0.05");
        assertThat(options.get(0).coin().quickPicksK()).containsExactly(100, 500);
        assertThat(options.get(0).coin().rates()).extracting(CatalogDtos.CoinRate::fromK, CatalogDtos.CoinRate::per100kFormatted)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(0, "₹1,400.00"),
                        org.assertj.core.groups.Tuple.tuple(500, "₹1,350.00"));
        assertThat(options.get(1).coin()).isEqualTo(options.get(2).coin());
        assertThat(options.get(1).coin().market()).isEqualTo("CONSOLE");
        assertThat(options.get(1).coin().marketLabel()).isEqualTo("PlayStation & Xbox");
    }
}
