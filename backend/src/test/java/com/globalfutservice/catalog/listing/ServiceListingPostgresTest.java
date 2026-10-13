package com.globalfutservice.catalog.listing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.globalfutservice.affiliate.AffiliateService;
import com.globalfutservice.catalog.CatalogService;
import com.globalfutservice.catalog.CoinPricingService;
import com.globalfutservice.catalog.ListingSettings;
import com.globalfutservice.catalog.RateCardRepository;
import com.globalfutservice.catalog.web.CatalogDtos;
import com.globalfutservice.coaching.AfterCommit;
import com.globalfutservice.coaching.CoachingService;
import com.globalfutservice.coaching.CoachingSettingsService;
import com.globalfutservice.config.AppProperties;
import com.globalfutservice.credentials.CredentialVaultService;
import com.globalfutservice.domain.money.Currency;
import com.globalfutservice.domain.pricing.GatewayFeeMode;
import com.globalfutservice.domain.pricing.MarketTaxMode;
import com.globalfutservice.domain.pricing.PricingEngine;
import com.globalfutservice.domain.pricing.PricingPolicy;
import com.globalfutservice.domain.pricing.QuoteSigner;
import com.globalfutservice.fulfilment.AutoDispatchQueue;
import com.globalfutservice.identity.AccountEntity;
import com.globalfutservice.identity.AccountRepository;
import com.globalfutservice.loyalty.LoyaltyService;
import com.globalfutservice.notify.NotificationService;
import com.globalfutservice.notify.feed.CustomerFeedService;
import com.globalfutservice.orders.OrderEntity;
import com.globalfutservice.orders.OrderEventRepository;
import com.globalfutservice.orders.OrderRepository;
import com.globalfutservice.orders.OrderService;
import com.globalfutservice.orders.result.OrderResultRepository;
import com.globalfutservice.orders.result.ResultRules;
import com.globalfutservice.orders.web.OrderDtos;
import com.globalfutservice.payments.PaymentGateway;
import com.globalfutservice.payments.PaymentRepository;
import com.globalfutservice.pricing.CouponService;
import com.globalfutservice.pricing.QuoteService;
import com.globalfutservice.pricing.web.QuoteDtos;
import com.globalfutservice.storecredit.StoreCreditService;
import com.globalfutservice.web.ApiExceptions;
import jakarta.persistence.EntityManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Objectives and Tournaments listings against the real schema (V49): adding, editing,
 * duplicating as a draft, switching off and deleting (soft, kept for the orders that name
 * it); artwork kept in the database across a restart and replaced by a new address; the
 * catalogue showing only what is on sale, in display order and in the currency's price;
 * an hourly listing priced only for whole hours from one to its cap; an order frozen as it
 * was sold, refused a tampered price or hours and, by the hour, without the
 * acknowledgement; and the client's two hourly drafts created once and inactive. Runs when
 * GFS_TEST_PG_URL is set, in a throwaway schema.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ServiceListingPostgresTest {

    private static final class MovableClock extends Clock {
        private Instant now = Instant.parse("2026-10-13T05:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }

        void tick() {
            now = now.plusSeconds(1);
        }
    }

    private String schema;
    private DataSource ds;
    private JdbcTemplate jdbc;
    private LocalContainerEntityManagerFactoryBean factory;
    private TransactionTemplate tx;
    private final MovableClock clock = new MovableClock();
    private AppProperties props;

    private ServiceListingService service;
    private ListingDrafts drafts;
    private CatalogService catalog;
    private QuoteService quotes;
    private OrderService orderService;
    private OrderRepository orders;
    private AccountRepository accounts;

    private long adminId;
    private long customerId;

    @BeforeAll
    void migrate() {
        String url = System.getenv("GFS_TEST_PG_URL");
        org.junit.jupiter.api.Assumptions.assumeTrue(url != null && !url.isBlank(),
                "GFS_TEST_PG_URL not set: no database to test against");
        schema = "listings_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        ds = new DriverManagerDataSource(url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
                System.getenv("GFS_TEST_PG_USER"), System.getenv("GFS_TEST_PG_PASSWORD"));
        jdbc = new JdbcTemplate(ds);
        Flyway.configure().dataSource(ds).schemas(schema).defaultSchema(schema).locations("classpath:db/migration")
                .initSql("SET search_path TO " + schema + ", public").load().migrate();

        factory = factory();
        tx = new TransactionTemplate(new JpaTransactionManager(factory.getObject()));

        props = mock(AppProperties.class);
        when(props.season()).thenReturn("FC27");
        when(props.seasonYear()).thenReturn("27");
        AppProperties.Pricing pricing = mock(AppProperties.Pricing.class);
        when(pricing.enabledCurrencies()).thenReturn(List.of("INR", "USD", "EUR", "GBP"));
        when(props.pricing()).thenReturn(pricing);
        when(props.publicUrl()).thenReturn("https://globalfutservices.com");
        when(props.fulfilment()).thenReturn(new Binder(new MapConfigurationPropertySource(Map.of(
                "f.backup-codes-required", "3"))).bind("f", AppProperties.Fulfilment.class).get());

        service = service(factory);
        drafts = new ListingDrafts(service);

        JpaRepositoryFactory repositories = repositories(factory);
        orders = repositories.getRepository(OrderRepository.class);
        accounts = repositories.getRepository(AccountRepository.class);
        RateCardRepository rates = repositories.getRepository(RateCardRepository.class);
        ListingSettings listingSettings = mock(ListingSettings.class);
        when(listingSettings.resolveBestValue(any(), any())).thenReturn(java.util.Optional.empty());
        catalog = new CatalogService(rates, props, mock(PricingPolicy.class), mock(CoachingSettingsService.class),
                listingSettings, mock(CoinPricingService.class), service);
        // The site sells in four currencies this season: a Champs tier priced in each.
        for (String currency : List.of("INR", "USD", "EUR", "GBP")) {
            jdbc.update("""
                    insert into rate_card (season, sku, variant, currency, price_unit, unit_price_minor, label, sort_order)
                    values ('FC27', 'BOOST_CHAMPS', 'WINS_9', ?, 'FLAT', 1000, '9 wins', 1)
                    """, currency);
        }

        PricingEngine engine = new PricingEngine(new PricingPolicy(500, MarketTaxMode.INCLUDED, 250,
                GatewayFeeMode.PASS_THROUGH, 2_000, Currency.INR, 100L, 200_000L, 20L, Duration.ofMinutes(10), true),
                clock, () -> "q_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        quotes = new QuoteService(catalog, engine, new QuoteSigner("test-only-quote-secret-000000000000000000000000"),
                mock(LoyaltyService.class), mock(AffiliateService.class), mock(CouponService.class),
                mock(StoreCreditService.class), clock);

        PaymentGateway gateway = mock(PaymentGateway.class);
        when(gateway.createOrder(anyString(), any(), anyString(), anyString()))
                .thenAnswer(inv -> new PaymentGateway.GatewayOrder("stub_" + inv.getArgument(0), "STUB", 1L, "INR"));
        orderService = new OrderService(orders, repositories.getRepository(OrderEventRepository.class),
                mock(PaymentRepository.class), gateway, quotes, mock(LoyaltyService.class),
                mock(AffiliateService.class), mock(CredentialVaultService.class), mock(NotificationService.class),
                accounts, mock(CoachingService.class), mock(CouponService.class), mock(CustomerFeedService.class),
                new ObjectMapper().findAndRegisterModules(), props, clock, AfterCommit.immediate(),
                mock(AutoDispatchQueue.class), mock(StoreCreditService.class), mock(ResultRules.class),
                repositories.getRepository(OrderResultRepository.class), service);

        adminId = jdbc.queryForObject("""
                insert into account (public_id, email, email_normalised, password_hash, role, display_name)
                values ('acc_admin', 'owner@example.test', 'owner@example.test', 'x', 'ADMIN', 'Vinay') returning id
                """, Long.class);
        customerId = jdbc.queryForObject("""
                insert into account (public_id, email, email_normalised, password_hash)
                values ('acc_buyer', 'buyer@example.test', 'buyer@example.test', 'x') returning id
                """, Long.class);
    }

    private LocalContainerEntityManagerFactoryBean factory() {
        LocalContainerEntityManagerFactoryBean f = new LocalContainerEntityManagerFactoryBean();
        f.setDataSource(ds);
        f.setPackagesToScan("com.globalfutservice");
        f.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        f.setJpaPropertyMap(Map.of(
                "hibernate.hbm2ddl.auto", "validate",
                "hibernate.default_schema", schema,
                "hibernate.jdbc.time_zone", "UTC",
                "hibernate.physical_naming_strategy",
                "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy",
                "hibernate.implicit_naming_strategy",
                "org.springframework.boot.orm.jpa.hibernate.SpringImplicitNamingStrategy"));
        f.afterPropertiesSet();
        return f;
    }

    private static JpaRepositoryFactory repositories(LocalContainerEntityManagerFactoryBean f) {
        EntityManager shared = SharedEntityManagerCreator.createSharedEntityManager(f.getObject());
        return new JpaRepositoryFactory(shared);
    }

    private ServiceListingService service(LocalContainerEntityManagerFactoryBean f) {
        JpaRepositoryFactory r = repositories(f);
        return new ServiceListingService(r.getRepository(ServiceListingRepository.class),
                r.getRepository(ServiceListingImageRepository.class),
                r.getRepository(ServiceListingChangeRepository.class), r.getRepository(RateCardRepository.class),
                r.getRepository(OrderRepository.class), r.getRepository(AccountRepository.class), props, clock);
    }

    @AfterAll
    void drop() {
        if (factory != null) {
            factory.destroy();
        }
        if (jdbc != null) {
            jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    // ------------------------------------------------------------------ fixtures

    private <T> T inTx(Supplier<T> work) {
        clock.tick();
        return tx.execute(s -> work.get());
    }

    private static byte[] png(int width, int height) {
        try {
            return PngCheckTest.png(width, height);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] jpeg() {
        try {
            return PngCheckTest.jpeg();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Map<String, Long> prices(Object... pairs) {
        Map<String, Long> out = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            out.put((String) pairs[i], pairs[i + 1] == null ? null : ((Number) pairs[i + 1]).longValue());
        }
        return out;
    }

    private static ServiceListingService.Form fixed(String sku, String title, boolean active, Integer order,
                                                    Map<String, Long> prices) {
        return new ServiceListingService.Form(sku, title, "84 CDM · Special Card", null,
                "Win 7 Squad Battles matches\n\n• Score a finesse goal in 3 separate matches\n- Assist 8 times",
                7, "2–3 hours", "Usually completed within 2–3 hours after the scheduled slot begins.",
                "84 CDM Special Card", "Make sure the objective is available on your account.", "FIXED", null, true,
                active, order, prices);
    }

    private static ServiceListingService.Form hourly(String title, boolean active) {
        return new ServiceListingService.Form("BOOST_OBJECTIVES", title, "Paid per hour · 100% manual",
                "We'll work on your Club manually.", "Everything will be done 100% manually.", null, null, null,
                null, null, "PER_HOUR", 50, true, active, null, prices("INR", 65_000));
    }

    private long create(ServiceListingService.Form form) {
        return inTx(() -> service.create(form, adminId));
    }

    private ServiceListingService.AdminView view(long id) {
        return inTx(() -> service.one(id));
    }

    private List<CatalogDtos.CatalogOption> onSale(String sku, Currency currency) {
        return inTx(() -> catalog.catalogue(currency)).services().stream()
                .filter(s -> s.sku().equals(sku)).findFirst().orElseThrow().options();
    }

    private AccountEntity buyer() {
        return inTx(() -> accounts.findById(customerId).orElseThrow());
    }

    private QuoteDtos.SignedQuote quote(long listingId, String hours, String currency) {
        String sku = inTx(() -> service.one(listingId)).sku();
        return inTx(() -> quotes.quote(new QuoteDtos.QuoteRequest(sku, null, ServiceListingEntity.variantOf(listingId),
                hours == null ? null : new BigDecimal(hours), currency, null, null, 0L), buyer()));
    }

    private OrderDtos.CreateOrderResponse place(QuoteDtos.SignedQuote quote, Boolean acknowledged) {
        return inTx(() -> orderService.create(new OrderDtos.CreateOrderRequest(quote, "buyer@example.test", "Buyer",
                null, null, null, null, null, null, null, null, null, null, null, null, null, true, acknowledged),
                buyer()));
    }

    private ListingSnapshot.View snapshotOf(String ref) {
        OrderEntity order = inTx(() -> orders.findByPublicRef(ref).orElseThrow());
        return ListingSnapshot.fromJson(order.getListingSnapshot()).view();
    }

    private int liveRows(long id) {
        return jdbc.queryForObject("select count(*) from rate_card where variant = ? and valid_to is null",
                Integer.class, ServiceListingEntity.variantOf(id));
    }

    // ------------------------------------------------------------------ admin

    @Test
    @DisplayName("add: a new unique id, its prices on the rate card in this season, who added it recorded")
    void add() {
        long a = create(fixed("BOOST_OBJECTIVES", "Claire Hutton Objective", true, 1, prices("INR", 120_000, "USD", 1_450)));
        long b = create(fixed("BOOST_OBJECTIVES", "Homegrown Pride Objective", true, 2, prices("INR", 85_000)));
        assertThat(a).isNotEqualTo(b);

        ServiceListingService.AdminView v = view(a);
        assertThat(v.variant()).isEqualTo("LISTING_" + a);
        assertThat(v.title()).isEqualTo("Claire Hutton Objective");
        assertThat(v.prices()).containsOnlyKeys("INR", "USD");
        assertThat(v.prices().get("INR").formatted()).isEqualTo("₹1,200.00");
        assertThat(v.createdBy()).isEqualTo("Vinay");
        assertThat(v.changes()).extracting(ServiceListingService.Change::action).containsExactly("CREATED");
        assertThat(jdbc.queryForObject("select count(*) from rate_card where variant = ? and season = 'FC27' "
                + "and price_unit = 'FLAT' and valid_to is null", Integer.class, v.variant())).isEqualTo(2);
    }

    @Test
    @DisplayName("edit: a new price closes the old row and opens another; an emptied currency is no longer sold")
    void edit() {
        long id = create(fixed("BOOST_OBJECTIVES", "Score in 3 Objective", true, 5, prices("INR", 65_000, "GBP", 1_200)));
        inTx(() -> {
            service.update(id, fixed("BOOST_OBJECTIVES", "Score in 3", true, 5, prices("INR", 70_000, "GBP", null)), adminId);
            return null;
        });
        ServiceListingService.AdminView v = view(id);
        assertThat(v.title()).isEqualTo("Score in 3");
        assertThat(v.prices()).containsOnlyKeys("INR");
        assertThat(v.prices().get("INR").minor()).isEqualTo(70_000);
        assertThat(jdbc.queryForObject("select count(*) from rate_card where variant = ?", Integer.class, v.variant()))
                .as("the old INR and GBP rows are kept, closed").isEqualTo(3);
        assertThat(v.changes()).extracting(ServiceListingService.Change::action).containsExactly("UPDATED", "CREATED");

        CatalogDtos.CatalogOption card = onSale("BOOST_OBJECTIVES", Currency.INR).stream()
                .filter(o -> o.variant().equals(v.variant())).findFirst().orElseThrow();
        assertThat(card.label()).isEqualTo("Score in 3");
        assertThat(card.unitPriceFormatted()).isEqualTo("₹700.00");
    }

    @Test
    @DisplayName("duplicate: an inactive \"Copy of …\" with the same details, artwork and prices")
    void duplicate() throws Exception {
        long id = create(fixed("BOOST_OBJECTIVES", "Assist 8 Objective", true, 6, prices("INR", 65_000, "EUR", 1_390)));
        inTx(() -> service.setImage(id, png(400, 500), adminId));
        long copy = inTx(() -> service.duplicate(id, adminId));

        ServiceListingService.AdminView original = view(id);
        ServiceListingService.AdminView v = view(copy);
        assertThat(copy).isNotEqualTo(id);
        assertThat(v.title()).isEqualTo("Copy of Assist 8 Objective");
        assertThat(v.active()).isFalse();
        assertThat(v.requirements()).isEqualTo(original.requirements());
        assertThat(v.image().id()).isEqualTo(original.image().id());
        assertThat(v.prices().keySet()).containsExactlyInAnyOrder("INR", "EUR");
        assertThat(onSale("BOOST_OBJECTIVES", Currency.INR)).extracting(CatalogDtos.CatalogOption::variant)
                .contains(original.variant()).doesNotContain(v.variant());
        assertThat(v.changes().get(0).action()).isEqualTo("DUPLICATED");
    }

    @Test
    @DisplayName("deactivate: off the storefront and no longer priced; its prices are kept")
    void deactivate() {
        long id = create(fixed("BOOST_OBJECTIVES", "Deactivated Objective", true, 7, prices("INR", 50_000)));
        assertThat(quote(id, null, "INR").totalMinor()).isEqualTo(51_250);

        inTx(() -> {
            service.update(id, fixed("BOOST_OBJECTIVES", "Deactivated Objective", false, 7, prices("INR", 50_000)), adminId);
            return null;
        });
        assertThat(onSale("BOOST_OBJECTIVES", Currency.INR)).extracting(CatalogDtos.CatalogOption::variant)
                .doesNotContain("LISTING_" + id);
        assertThatThrownBy(() -> quote(id, null, "INR")).isInstanceOf(ApiExceptions.NotFoundException.class);
        assertThat(view(id).prices().get("INR").minor()).isEqualTo(50_000);
        assertThat(view(id).changes()).extracting(ServiceListingService.Change::action).contains("DEACTIVATED");
    }

    @Test
    @DisplayName("active needs a price; a draft may have none")
    void activeNeedsAPrice() {
        assertThatThrownBy(() -> create(fixed("BOOST_OBJECTIVES", "No price", true, 9, prices())))
                .hasMessageContaining("at least one currency");
        long draft = create(fixed("BOOST_OBJECTIVES", "No price yet", false, 9, prices()));
        assertThat(view(draft).prices()).isEmpty();
    }

    @Test
    @DisplayName("delete: an order names it, so it is kept -- hidden everywhere, its prices closed, the order untouched")
    void deleteReferenced() {
        long id = create(fixed("BOOST_OBJECTIVES", "Deleted Objective", true, 8, prices("INR", 90_000)));
        OrderDtos.CreateOrderResponse placed = place(quote(id, null, "INR"), null);
        assertThat(inTx(() -> service.ordersReferencing(id))).isEqualTo(1);

        inTx(() -> {
            service.delete(id, adminId);
            return null;
        });
        assertThat(jdbc.queryForObject("select deleted_at is not null and not active from service_listing where id = ?",
                Boolean.class, id)).isTrue();
        assertThat(inTx(() -> service.forAdmin(com.globalfutservice.domain.catalog.Sku.BOOST_OBJECTIVES)))
                .extracting(ServiceListingService.AdminView::id).doesNotContain(id);
        assertThat(onSale("BOOST_OBJECTIVES", Currency.INR)).extracting(CatalogDtos.CatalogOption::variant)
                .doesNotContain("LISTING_" + id);
        assertThat(liveRows(id)).isZero();
        assertThatThrownBy(() -> view(id)).isInstanceOf(ApiExceptions.NotFoundException.class);
        assertThat(snapshotOf(placed.publicRef()).title()).isEqualTo("Deleted Objective");
    }

    @Test
    @DisplayName("delete with no orders: nothing references it, and it is gone the same way")
    void deleteUnreferenced() {
        long id = create(fixed("BOOST_TOURNAMENTS", "Unused Tournament", true, 1, prices("INR", 30_000)));
        assertThat(inTx(() -> service.ordersReferencing(id))).isZero();
        inTx(() -> {
            service.delete(id, adminId);
            return null;
        });
        assertThat(onSale("BOOST_TOURNAMENTS", Currency.INR)).extracting(CatalogDtos.CatalogOption::variant)
                .doesNotContain("LISTING_" + id);
    }

    // ------------------------------------------------------------------ artwork

    @Test
    @DisplayName("artwork: a PNG kept in the database, read back after a restart, replaced by a new address")
    void artwork() throws Exception {
        long id = create(fixed("BOOST_OBJECTIVES", "Artwork Objective", true, 10, prices("INR", 40_000)));
        byte[] first = png(400, 500);
        ServiceListingService.Image image = inTx(() -> service.setImage(id, first, adminId));
        assertThat(image.width()).isEqualTo(400);
        assertThat(image.height()).isEqualTo(500);

        // A restart: a new persistence unit and service over the same database.
        LocalContainerEntityManagerFactoryBean restarted = factory();
        try {
            ServiceListingService after = service(restarted);
            TransactionTemplate tx2 = new TransactionTemplate(new JpaTransactionManager(restarted.getObject()));
            byte[] read = tx2.execute(s -> after.imageBytes(image.id()).orElseThrow().getBytes());
            assertThat(read).isEqualTo(first);
        } finally {
            restarted.destroy();
        }

        String before = onSale("BOOST_OBJECTIVES", Currency.INR).stream()
                .filter(o -> o.variant().equals("LISTING_" + id)).findFirst().orElseThrow().listing().imageUrl();
        ServiceListingService.Image replaced = inTx(() -> service.setImage(id, png(300, 300), adminId));
        CatalogDtos.CatalogOption card = onSale("BOOST_OBJECTIVES", Currency.INR).stream()
                .filter(o -> o.variant().equals("LISTING_" + id)).findFirst().orElseThrow();
        assertThat(card.listing().imageUrl()).isEqualTo(replaced.url()).isNotEqualTo(before);
        assertThat(card.listing().imageWidth()).isEqualTo(300);
        // The old bytes are still there for an order that was bought with them.
        assertThat(inTx(() -> service.imageBytes(image.id())).isPresent()).isTrue();
        assertThat(view(id).changes()).extracting(ServiceListingService.Change::action)
                .contains("IMAGE_UPLOADED", "IMAGE_REPLACED");

        inTx(() -> {
            service.removeImage(id, adminId);
            return null;
        });
        assertThat(view(id).image()).isNull();
    }

    @Test
    @DisplayName("artwork: not a PNG, or over 2MB, is refused and nothing changes")
    void artworkRefused() throws Exception {
        long id = create(fixed("BOOST_OBJECTIVES", "Refused Artwork", false, 11, prices("INR", 40_000)));
        assertThatThrownBy(() -> inTx(() -> service.setImage(id, jpeg(), adminId)))
                .isInstanceOfSatisfying(ApiExceptions.BadRequestException.class,
                        e -> assertThat(e.code()).isEqualTo("invalid_png"));
        byte[] big = java.util.Arrays.copyOf(png(10, 10), PngCheck.MAX_BYTES + 1);
        assertThatThrownBy(() -> inTx(() -> service.setImage(id, big, adminId)))
                .hasMessageContaining("larger than 2MB");
        assertThat(view(id).image()).isNull();
    }

    // ------------------------------------------------------------------ the storefront

    @Test
    @DisplayName("the catalogue: only active listings, in display order, each at its price in the currency")
    void catalogue() {
        long second = create(fixed("BOOST_TOURNAMENTS", "Second", true, 20, prices("INR", 20_000, "USD", 300)));
        long first = create(fixed("BOOST_TOURNAMENTS", "First", true, 10, prices("INR", 10_000)));
        long draft = create(fixed("BOOST_TOURNAMENTS", "Draft", false, 5, prices("INR", 5_000, "USD", 100)));

        List<CatalogDtos.CatalogOption> inr = onSale("BOOST_TOURNAMENTS", Currency.INR);
        assertThat(inr).extracting(CatalogDtos.CatalogOption::variant)
                .containsSubsequence("LISTING_" + first, "LISTING_" + second).doesNotContain("LISTING_" + draft);
        CatalogDtos.CatalogOption card = inr.stream().filter(o -> o.variant().equals("LISTING_" + second)).findFirst()
                .orElseThrow();
        assertThat(card.unitPriceFormatted()).isEqualTo("₹200.00");
        assertThat(card.listing().title()).isEqualTo("Second");
        assertThat(card.listing().requirements()).as("one task per line, blank lines and typed bullets gone")
                .containsExactly("Win 7 Squad Battles matches", "Score a finesse goal in 3 separate matches",
                        "Assist 8 times");

        // Not priced in dollars: not on sale in dollars.
        assertThat(onSale("BOOST_TOURNAMENTS", Currency.USD)).extracting(CatalogDtos.CatalogOption::variant)
                .contains("LISTING_" + second).doesNotContain("LISTING_" + first, "LISTING_" + draft);
        assertThat(onSale("BOOST_TOURNAMENTS", Currency.USD).stream()
                .filter(o -> o.variant().equals("LISTING_" + second)).findFirst().orElseThrow().unitPriceFormatted())
                .isEqualTo("$3.00");
    }

    // ------------------------------------------------------------------ hourly, quotes and orders

    @Test
    @DisplayName("hourly: hours are required, whole, from one to the cap, and priced as price × hours")
    void hourlyQuote() {
        long id = create(hourly("Hourly Objective", true));
        assertThatThrownBy(() -> quote(id, null, "INR")).hasMessage("Choose how many hours.");
        assertThatThrownBy(() -> quote(id, "0", "INR")).hasMessage("Choose between 1 hour and 50 hours.");
        assertThatThrownBy(() -> quote(id, "51", "INR")).hasMessage("Choose between 1 hour and 50 hours.");
        assertThatThrownBy(() -> quote(id, "2.5", "INR")).hasMessageContaining("whole number of hours");

        QuoteDtos.SignedQuote three = quote(id, "3", "INR");
        assertThat(three.subtotalMinor()).isEqualTo(195_000);
        assertThat(three.lines().get(0).label()).isEqualTo("Objectives — Hourly Objective (3 hours)");

        CatalogDtos.CatalogOption card = onSale("BOOST_OBJECTIVES", Currency.INR).stream()
                .filter(o -> o.variant().equals("LISTING_" + id)).findFirst().orElseThrow();
        assertThat(card.listing().pricingType()).isEqualTo("PER_HOUR");
        assertThat(card.listing().minHours()).isEqualTo(1);
        assertThat(card.listing().maxHours()).isEqualTo(50);
    }

    @Test
    @DisplayName("an hourly order needs the acknowledgement, and freezes the hours, the price and the sentence ticked")
    void hourlyOrder() {
        long id = create(hourly("Hourly Order Objective", true));
        assertThatThrownBy(() -> place(quote(id, "3", "INR"), null))
                .isInstanceOfSatisfying(ApiExceptions.BadRequestException.class,
                        e -> assertThat(e.code()).isEqualTo("hourly_acknowledgement_required"));
        assertThatThrownBy(() -> place(quote(id, "3", "INR"), false))
                .isInstanceOf(ApiExceptions.BadRequestException.class);

        OrderDtos.CreateOrderResponse placed = place(quote(id, "3", "INR"), true);
        ListingSnapshot.View s = snapshotOf(placed.publicRef());
        assertThat(s.pricingType()).isEqualTo("PER_HOUR");
        assertThat(s.hours()).isEqualTo(3);
        assertThat(s.unitPriceFormatted()).isEqualTo("₹650.00");
        assertThat(s.priceFormatted()).isEqualTo("₹1,950.00");
        assertThat(s.currency()).isEqualTo("INR");
        assertThat(s.hourlyAcknowledgement()).isEqualTo(
                "I understand I'm paying for the manual service time, not for a guaranteed level or specific result.");
        assertThat(s.hourlyAcknowledgedAt()).isNotNull();
        assertThat(placed.totalMinor()).isEqualTo(199_875);
    }

    @Test
    @DisplayName("a fixed-price order needs no acknowledgement and records none")
    void fixedOrder() {
        long id = create(fixed("BOOST_OBJECTIVES", "Fixed Order Objective", true, 12, prices("INR", 120_000)));
        ListingSnapshot.View s = snapshotOf(place(quote(id, null, "INR"), null).publicRef());
        assertThat(s.hours()).isNull();
        assertThat(s.hourlyAcknowledgement()).isNull();
        assertThat(s.priceFormatted()).isEqualTo("₹1,200.00");
        assertThat(s.categoryName()).isEqualTo("Objectives");
    }

    @Test
    @DisplayName("the server refuses a quote with its hours, total or price changed")
    void tampered() {
        long id = create(hourly("Tampered Objective", true));
        QuoteDtos.SignedQuote q = quote(id, "3", "INR");

        QuoteDtos.SignedQuote moreHours = new QuoteDtos.SignedQuote(q.quoteId(), q.season(), q.sku(), q.platform(),
                q.variant(), new BigDecimal("10"), q.currency(), q.lines(), q.subtotalMinor(), q.totalMinor(),
                q.totalFormatted(), q.pointsRedeemed(), q.pointsEarned(), q.referralCode(), q.couponCode(),
                q.couponMessage(), q.issuedAt(), q.expiresAt(), q.signature(), q.priceVersion(), q.storeCreditMinor(),
                q.storeCreditAvailableMinor(), q.storeCreditAvailableFormatted());
        assertThatThrownBy(() -> place(moreHours, true)).isInstanceOf(ApiExceptions.ConflictException.class);

        List<QuoteDtos.QuoteLineDto> cheaper = q.lines().stream()
                .map(l -> new QuoteDtos.QuoteLineDto(l.code(), l.label(), l.amountMinor() / 10, l.amountFormatted()))
                .toList();
        long cheaperTotal = cheaper.stream().mapToLong(QuoteDtos.QuoteLineDto::amountMinor).sum();
        QuoteDtos.SignedQuote lowerTotal = new QuoteDtos.SignedQuote(q.quoteId(), q.season(), q.sku(), q.platform(),
                q.variant(), q.quantity(), q.currency(), cheaper, q.subtotalMinor() / 10, cheaperTotal,
                q.totalFormatted(), q.pointsRedeemed(), q.pointsEarned(), q.referralCode(), q.couponCode(),
                q.couponMessage(), q.issuedAt(), q.expiresAt(), q.signature(), q.priceVersion(), q.storeCreditMinor(),
                q.storeCreditAvailableMinor(), q.storeCreditAvailableFormatted());
        assertThatThrownBy(() -> place(lowerTotal, true)).isInstanceOf(ApiExceptions.ConflictException.class);

        // The total is signed; the subtotal is not -- and is held to the rate card all the same.
        QuoteDtos.SignedQuote otherSubtotal = new QuoteDtos.SignedQuote(q.quoteId(), q.season(), q.sku(),
                q.platform(), q.variant(), q.quantity(), q.currency(), q.lines(), 1L, q.totalMinor(),
                q.totalFormatted(), q.pointsRedeemed(), q.pointsEarned(), q.referralCode(), q.couponCode(),
                q.couponMessage(), q.issuedAt(), q.expiresAt(), q.signature(), q.priceVersion(), q.storeCreditMinor(),
                q.storeCreditAvailableMinor(), q.storeCreditAvailableFormatted());
        assertThatThrownBy(() -> place(otherSubtotal, true))
                .isInstanceOfSatisfying(ApiExceptions.ConflictException.class,
                        e -> assertThat(e.code()).isEqualTo("quote_expired"));

        assertThat(place(q, true).totalMinor()).isEqualTo(199_875);
    }

    @Test
    @DisplayName("editing or deleting the listing changes nothing an existing order shows")
    void snapshotFrozen() throws Exception {
        long id = create(fixed("BOOST_OBJECTIVES", "Frozen Objective", true, 13, prices("INR", 120_000)));
        inTx(() -> service.setImage(id, png(400, 500), adminId));
        String ref = place(quote(id, null, "INR"), null).publicRef();
        ListingSnapshot.View before = snapshotOf(ref);
        assertThat(before.requirements()).hasSize(3);
        assertThat(before.imageUrl()).isNotNull();

        ServiceListingService.Form edited = new ServiceListingService.Form("BOOST_OBJECTIVES", "Renamed Objective",
                "New subtitle", "New overview", "One new task", 2, "1 hour", null, null, null, "FIXED", null, true,
                true, 13, prices("INR", 99_000));
        inTx(() -> {
            service.update(id, edited, adminId);
            service.setImage(id, png(100, 100), adminId);
            return null;
        });
        assertThat(snapshotOf(ref)).isEqualTo(before);

        inTx(() -> {
            service.delete(id, adminId);
            return null;
        });
        assertThat(snapshotOf(ref)).isEqualTo(before);
        assertThat(before.title()).isEqualTo("Frozen Objective");
        assertThat(before.priceFormatted()).isEqualTo("₹1,200.00");
    }

    @Test
    @DisplayName("a Tournaments listing is sold exactly as an Objectives one is")
    void tournaments() {
        long id = create(fixed("BOOST_TOURNAMENTS", "Weekend Cup", true, 30, prices("INR", 150_000)));
        assertThat(onSale("BOOST_TOURNAMENTS", Currency.INR)).extracting(CatalogDtos.CatalogOption::variant)
                .contains("LISTING_" + id);
        assertThat(onSale("BOOST_OBJECTIVES", Currency.INR)).extracting(CatalogDtos.CatalogOption::variant)
                .doesNotContain("LISTING_" + id);
        OrderDtos.CreateOrderResponse placed = place(quote(id, null, "INR"), null);
        OrderEntity order = inTx(() -> orders.findByPublicRef(placed.publicRef()).orElseThrow());
        assertThat(order.getSku().name()).isEqualTo("BOOST_TOURNAMENTS");
        assertThat(order.getPlatform()).as("no platform is asked for").isNull();
        assertThat(order.requiresCredentials()).as("the account is asked for in Discord").isFalse();
        assertThat(snapshotOf(placed.publicRef()).categoryName()).isEqualTo("Tournaments");
    }

    // ------------------------------------------------------------------ the client's drafts

    @Test
    @DisplayName("Gallery Tokens and Season XP Grind: created once, inactive, ₹650/hour, cap 50, no image")
    void drafts() {
        jdbc.update("delete from service_listing_change where listing_id in "
                + "(select id from service_listing where seed_key is not null)");
        jdbc.update("delete from service_listing where seed_key is not null");
        assertThat(inTx(() -> drafts.ensure())).isEqualTo(2);
        assertThat(inTx(() -> drafts.ensure())).as("the second start creates nothing").isZero();

        List<ServiceListingService.AdminView> created = inTx(() -> service.forAdmin(
                com.globalfutservice.domain.catalog.Sku.BOOST_OBJECTIVES)).stream()
                .filter(v -> v.title().equals("Gallery Tokens") || v.title().equals("Season XP Grind")).toList();
        assertThat(created).hasSize(2);
        for (ServiceListingService.AdminView v : created) {
            assertThat(v.active()).isFalse();
            assertThat(v.image()).isNull();
            assertThat(v.pricingType()).isEqualTo("PER_HOUR");
            assertThat(v.maxHours()).isEqualTo(50);
            assertThat(v.prices()).containsOnlyKeys("INR");
            assertThat(v.prices().get("INR").formatted()).isEqualTo("₹650.00");
            assertThat(v.subtitle()).isEqualTo("Paid per hour · 100% manual");
            assertThat(v.overview()).isEqualTo("We'll work on your Club manually for the hours you book, focusing on "
                    + "what's still needed to progress toward your target.");
            assertThat(ServiceListingService.lines(v.requirements())).hasSize(5);
            assertThat(v.customerInstructions()).startsWith("We recommend having at least 400,000 Coins");
            assertThat(v.requiredGames()).isNull();
            assertThat(v.estimatedTime()).isNull();
            assertThat(v.createdBy()).isEqualTo("System");
        }
        // Not on sale until the client switches them on.
        assertThat(onSale("BOOST_OBJECTIVES", Currency.INR)).extracting(CatalogDtos.CatalogOption::label)
                .doesNotContain("Gallery Tokens", "Season XP Grind");

        // Renamed by the client: still not made again.
        long gallery = created.stream().filter(v -> v.title().equals("Gallery Tokens")).findFirst().orElseThrow().id();
        jdbc.update("update service_listing set title = 'Gallery Tokens (hourly)' where id = ?", gallery);
        assertThat(inTx(() -> drafts.ensure())).isZero();
    }
}
