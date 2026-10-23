package com.globalfutservice.orders;

import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.globalfutservice.affiliate.AffiliateService;
import com.globalfutservice.coaching.AfterCommit;
import com.globalfutservice.coaching.CoachingService;
import com.globalfutservice.config.AppProperties;
import com.globalfutservice.credentials.CredentialVaultService;
import com.globalfutservice.domain.catalog.Platform;
import com.globalfutservice.domain.catalog.Sku;
import com.globalfutservice.domain.money.Currency;
import com.globalfutservice.domain.money.Money;
import com.globalfutservice.domain.orders.OrderStatus;
import com.globalfutservice.domain.pricing.LineCode;
import com.globalfutservice.domain.pricing.Quote;
import com.globalfutservice.domain.pricing.QuoteLine;
import com.globalfutservice.fulfilment.AutoDispatchQueue;
import com.globalfutservice.identity.AccountEntity;
import com.globalfutservice.identity.AccountRepository;
import com.globalfutservice.loyalty.LoyaltyService;
import com.globalfutservice.loyalty.PointsLedgerRepository;
import com.globalfutservice.notify.NotificationService;
import com.globalfutservice.notify.Notifier;
import com.globalfutservice.notify.OrderNotification;
import com.globalfutservice.notify.feed.CustomerFeedService;
import com.globalfutservice.notify.feed.CustomerNotificationRepository;
import com.globalfutservice.orders.result.OrderResultRepository;
import com.globalfutservice.orders.result.ResultRules;
import com.globalfutservice.orders.web.OrderDtos;
import com.globalfutservice.payments.PaymentGateway;
import com.globalfutservice.payments.PaymentRepository;
import com.globalfutservice.pricing.CouponRedemptionRepository;
import com.globalfutservice.pricing.CouponRepository;
import com.globalfutservice.pricing.CouponService;
import com.globalfutservice.pricing.QuoteService;
import com.globalfutservice.pricing.web.QuoteDtos;
import com.globalfutservice.storecredit.StoreCreditRepository;
import com.globalfutservice.storecredit.StoreCreditService;
import com.globalfutservice.testsupport.SmallPool;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Checkout against the real schema, with the real coupon, points, store credit, bell feed
 * and notification code: a coin order with a coupon, store credit and the payment fee, by a
 * customer with notifications on (the 2026-10-23 checkout failure).
 *
 * <p>Every connection carries a 2 s lock timeout, so a checkout that waits on a lock its
 * own transaction holds fails here in 2 s rather than hanging -- which is what spending
 * store credit (account row locked) and then writing the bell notification in a second
 * transaction did. The pool has three connections, and each test checks they all come back.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CheckoutLocksPostgresTest {

    private static final Instant NOW = Instant.parse("2026-10-23T08:30:00Z");

    private String schema;
    private String url;
    private HikariDataSource pool;
    private JdbcTemplate jdbc;
    private LocalContainerEntityManagerFactoryBean factory;
    private TransactionTemplate tx;
    private OrderRepository orders;
    private AccountRepository accounts;
    private final QuoteService quotes = mock(QuoteService.class);
    private final List<String> sent = new CopyOnWriteArrayList<>();
    private OrderService checkout;
    private OrderService checkoutWithBrokenNotifications;
    private long priorOrder;

    @BeforeAll
    void migrate() {
        String base = System.getenv("GFS_TEST_PG_URL");
        assumeTrue(base != null && !base.isBlank(), "GFS_TEST_PG_URL not set: no database to test against");
        schema = "checkout_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        url = base + (base.contains("?") ? "&" : "?") + "currentSchema=" + schema;
        pool = SmallPool.of(url, System.getenv("GFS_TEST_PG_USER"), System.getenv("GFS_TEST_PG_PASSWORD"), 3);
        pool.addDataSourceProperty("options", "-c lock_timeout=2000 -c statement_timeout=10000");
        jdbc = new JdbcTemplate(pool);
        Flyway.configure().dataSource(pool).schemas(schema).defaultSchema(schema).locations("classpath:db/migration")
                .initSql("SET search_path TO " + schema + ", public").load().migrate();

        factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(pool);
        factory.setPackagesToScan("com.globalfutservice");
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setJpaPropertyMap(Map.of(
                "hibernate.hbm2ddl.auto", "none",
                "hibernate.default_schema", schema,
                "hibernate.jdbc.time_zone", "UTC",
                "hibernate.physical_naming_strategy",
                "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy",
                "hibernate.implicit_naming_strategy",
                "org.springframework.boot.orm.jpa.hibernate.SpringImplicitNamingStrategy"));
        factory.afterPropertiesSet();
        JpaTransactionManager transactions = new JpaTransactionManager(factory.getObject());
        tx = new TransactionTemplate(transactions);
        EntityManager shared = SharedEntityManagerCreator.createSharedEntityManager(factory.getObject());
        JpaRepositoryFactory repos = new JpaRepositoryFactory(shared);
        orders = repos.getRepository(OrderRepository.class);
        accounts = repos.getRepository(AccountRepository.class);

        AppProperties props = mock(AppProperties.class, RETURNS_DEEP_STUBS);
        when(props.seasonYear()).thenReturn("26");
        when(props.publicUrl()).thenReturn("https://globalfutservices.com");
        when(props.fulfilment()).thenReturn(new Binder(new MapConfigurationPropertySource(Map.of(
                "f.backup-codes-required", "3"))).bind("f", AppProperties.Fulfilment.class).get());
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        AfterCommit afterCommit = new AfterCommit(transactions);

        PaymentGateway gateway = mock(PaymentGateway.class);
        when(gateway.createOrder(anyString(), any(), anyString(), anyString()))
                .thenAnswer(inv -> new PaymentGateway.GatewayOrder("stub_" + inv.getArgument(0), "STUB", 1L, "INR"));

        Notifier recorder = new Notifier() {
            @Override public void orderPlaced(OrderNotification n) { sent.add("placed " + n.publicRef()); }
            @Override public void orderDelivered(OrderNotification n) { }
            @Override public void credentialsNeeded(OrderNotification n) { }
            @Override public boolean isEnabled() { return true; }
            @Override public String channelName() { return "test"; }
        };
        Notifier broken = new Notifier() {
            @Override public void orderPlaced(OrderNotification n) { throw new IllegalStateException("mail down"); }
            @Override public void orderDelivered(OrderNotification n) { }
            @Override public void credentialsNeeded(OrderNotification n) { }
            @Override public boolean isEnabled() { return true; }
            @Override public String channelName() { return "broken"; }
        };
        CustomerNotificationRepository feedRows = repos.getRepository(CustomerNotificationRepository.class);
        CustomerNotificationRepository failingFeedRows = mock(CustomerNotificationRepository.class);
        when(failingFeedRows.save(any())).thenThrow(new IllegalStateException("bell down"));

        StoreCreditService storeCredit = new StoreCreditService(repos.getRepository(StoreCreditRepository.class), clock);
        LoyaltyService loyalty = new LoyaltyService(repos.getRepository(PointsLedgerRepository.class), props, clock);
        CouponService coupons = new CouponService(repos.getRepository(CouponRepository.class),
                repos.getRepository(CouponRedemptionRepository.class), clock);
        checkout = orderService(repos, gateway, loyalty, coupons, storeCredit, props, clock, afterCommit,
                new NotificationService(List.of(recorder), Runnable::run),
                new CustomerFeedService(feedRows, accounts, afterCommit));
        checkoutWithBrokenNotifications = orderService(repos, gateway, loyalty, coupons, storeCredit, props, clock,
                afterCommit, new NotificationService(List.of(broken), Runnable::run),
                new CustomerFeedService(failingFeedRows, accounts, afterCommit));

        long someone = customer();
        priorOrder = jdbc.queryForObject("""
                insert into orders (public_ref, account_id, season, sku, quantity, delivery_method, currency,
                                    subtotal_minor, total_minor, price_breakdown, status, quote_id, guest_email)
                values ('GFS-26-PRIOR001', ?, 'FC26', 'TRADING_SERVICE', 1, 'PLAYER_AUCTION', 'INR', 1000, 1000,
                        '{"lines":[]}'::jsonb, 'COMPLETED', 'q_prior001', null)
                returning id
                """, Long.class, someone);
    }

    private OrderService orderService(JpaRepositoryFactory repos, PaymentGateway gateway, LoyaltyService loyalty,
                                      CouponService coupons, StoreCreditService storeCredit, AppProperties props,
                                      Clock clock, AfterCommit afterCommit, NotificationService notifications,
                                      CustomerFeedService feed) {
        return new OrderService(orders, repos.getRepository(OrderEventRepository.class),
                mock(PaymentRepository.class), gateway, quotes, loyalty, mock(AffiliateService.class),
                mock(CredentialVaultService.class), notifications, accounts, mock(CoachingService.class), coupons,
                feed, new ObjectMapper().findAndRegisterModules(), props, clock, afterCommit,
                mock(AutoDispatchQueue.class), storeCredit, mock(ResultRules.class),
                repos.getRepository(OrderResultRepository.class));
    }

    @AfterAll
    void drop() {
        if (factory != null) {
            factory.destroy();
        }
        if (jdbc != null) {
            jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
        if (pool != null) {
            pool.close();
        }
    }

    @AfterEach
    void everyConnectionBack() {
        SmallPool.assertAllReturned(pool);
        sent.clear();
    }

    // ------------------------------------------------------------------ fixtures

    private long customer() {
        String id = UUID.randomUUID().toString().substring(0, 8);
        return jdbc.queryForObject("""
                insert into account (public_id, email, email_normalised, password_hash)
                values (?, ?, ?, 'x') returning id
                """, Long.class, "acc_" + id, id + "@example.test", id + "@example.test");
    }

    /** Store credit the customer holds, from an earlier settlement. */
    private void giveCredit(long accountId, long minor) {
        long source = jdbc.queryForObject("""
                insert into orders (public_ref, account_id, season, sku, quantity, delivery_method, currency,
                                    subtotal_minor, total_minor, price_breakdown, status, quote_id, guest_email)
                values (?, ?, 'FC26', 'TRADING_SERVICE', 1, 'PLAYER_AUCTION', 'INR', 1000, 1000,
                        '{"lines":[]}'::jsonb, 'COMPLETED', ?, null)
                returning id
                """, Long.class, "GFS-26-SRC" + accountId, accountId, "q_src" + accountId);
        jdbc.update("""
                insert into store_credit_ledger (account_id, currency, amount_minor, entry_type, order_id, description)
                values (?, 'INR', ?, 'ISSUED', ?, 'Settlement')
                """, accountId, minor, source);
    }

    private void givePoints(long accountId, long points) {
        jdbc.update("""
                insert into points_ledger (account_id, order_id, entry_type, amount, description)
                values (?, ?, 'EARNED', ?, 'Earned')
                """, accountId, priorOrder, points);
    }

    private String coupon() {
        String code = "TEN" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        jdbc.update("insert into coupon (code, discount_bps) values (?, 1000)", code);
        return code;
    }

    /**
     * A coin order's verified quote: 800K on PlayStation, ₹10,000, 10% coupon, the 2.5% fee
     * on what is left, then points and store credit off the total.
     */
    private Quote coinQuote(String couponCode, long points, long credit) {
        long base = 1_000_000;
        long discount = couponCode == null ? 0 : 100_000;
        long fee = (base - discount) * 250 / 10_000;
        long total = base - discount + fee - points * 100 - credit;
        List<QuoteLine> lines = new ArrayList<>();
        lines.add(new QuoteLine(LineCode.BASE, "800K coins", Money.ofMinor(base, Currency.INR)));
        if (couponCode != null) {
            lines.add(new QuoteLine(LineCode.COUPON_DISCOUNT, "Coupon", Money.ofMinor(-discount, Currency.INR)));
        }
        lines.add(new QuoteLine(LineCode.MARKET_TAX, "Payment processing (2.5%)", Money.ofMinor(fee, Currency.INR)));
        if (points > 0) {
            lines.add(new QuoteLine(LineCode.WALLET_REDEMPTION, "Points", Money.ofMinor(-points * 100, Currency.INR)));
        }
        if (credit > 0) {
            lines.add(new QuoteLine(LineCode.STORE_CREDIT, "GFS Store Credit", Money.ofMinor(-credit, Currency.INR)));
        }
        return new Quote("q_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16), "FC26",
                Sku.TRADING_SERVICE, Platform.PLAYSTATION, null, new BigDecimal("0.8"), Currency.INR, lines,
                Money.ofMinor(base, Currency.INR), Money.ofMinor(total, Currency.INR), points, 0L, null, couponCode,
                NOW, NOW.plusSeconds(600), null, credit);
    }

    /** Any well-formed signed quote: the service is handed the verified one above. */
    private static QuoteDtos.SignedQuote anySignedQuote() throws Exception {
        RecordComponent[] parts = QuoteDtos.SignedQuote.class.getRecordComponents();
        Object[] values = new Object[parts.length];
        Class<?>[] types = new Class<?>[parts.length];
        for (int i = 0; i < parts.length; i++) {
            Class<?> t = parts[i].getType();
            types[i] = t;
            values[i] = t == String.class ? "x" : t == BigDecimal.class ? BigDecimal.ONE : t == List.class ? List.of()
                    : t == long.class ? 0L : t == int.class ? 0 : t == boolean.class ? false
                    : t == Instant.class ? NOW : null;
        }
        return QuoteDtos.SignedQuote.class.getDeclaredConstructor(types).newInstance(values);
    }

    private OrderDtos.CreateOrderResponse place(OrderService service, long accountId, Quote quote,
                                                Runnable beforeCommit) throws Exception {
        when(quotes.verifyOrThrow(any(), any())).thenReturn(quote);
        QuoteDtos.SignedQuote signed = anySignedQuote();
        AccountEntity buyer = tx.execute(s -> accounts.findById(accountId).orElseThrow());
        long started = System.nanoTime();
        OrderDtos.CreateOrderResponse response = tx.execute(s -> {
            OrderDtos.CreateOrderResponse r = service.create(new OrderDtos.CreateOrderRequest(signed,
                    "buyer@example.test", "Buyer", null, null, null, "ea-handle", null, null, null, null, null, null,
                    null, null, null, true), buyer);
            beforeCommit.run();
            return r;
        });
        // Nothing waited on a lock: well inside the 2 s a self-wait would take to fail.
        assertThat((System.nanoTime() - started) / 1_000_000).as("checkout took (ms)").isLessThan(1_500);
        return response;
    }

    /** Counted from a connection of its own: what everyone else can see, committed only. */
    private int bellRows(long accountId) throws Exception {
        try (Connection c = DriverManager.getConnection(url, System.getenv("GFS_TEST_PG_USER"),
                System.getenv("GFS_TEST_PG_PASSWORD"));
             ResultSet rs = c.createStatement().executeQuery(
                     "select count(*) from customer_notification where account_id = " + accountId)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private long creditBalance(long accountId) {
        return jdbc.queryForObject("select coalesce(sum(amount_minor), 0) from store_credit_ledger where account_id = ?",
                Long.class, accountId);
    }

    // ------------------------------------------------------------------ tests

    @Test
    @DisplayName("coin order, coupon, store credit and the 2.5% fee, notifications on: placed, without waiting on "
            + "its own lock; the bell notification and the email only after commit")
    void couponStoreCreditAndFee() throws Exception {
        long buyer = customer();
        giveCredit(buyer, 120_000);
        String code = coupon();
        int[] bellBeforeCommit = {-1};
        int[] sentBeforeCommit = {-1};
        OrderDtos.CreateOrderResponse placed = place(checkout, buyer, coinQuote(code, 0, 120_000), () -> {
            try {
                bellBeforeCommit[0] = bellRows(buyer);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            sentBeforeCommit[0] = sent.size();
        });

        assertThat(placed.status()).isEqualTo(OrderStatus.AWAITING_PAYMENT.name());
        OrderEntity order = tx.execute(s -> orders.findByPublicRef(placed.publicRef()).orElseThrow());
        assertThat(order.getStoreCreditMinor()).isEqualTo(120_000);
        assertThat(order.getCouponCode()).isEqualTo(code);
        assertThat(creditBalance(buyer)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from coupon_redemption where order_id = ?", Integer.class,
                order.getId())).isEqualTo(1);

        assertThat(bellBeforeCommit[0]).as("no bell row before the order commits").isZero();
        assertThat(sentBeforeCommit[0]).as("no email before the order commits").isZero();
        assertThat(bellRows(buyer)).as("the bell row once it has").isEqualTo(1);
        assertThat(sent).containsExactly("placed " + placed.publicRef());
    }

    @Test
    @DisplayName("the bell and every notification channel failing does not touch the order")
    void notificationsFailing() throws Exception {
        long buyer = customer();
        giveCredit(buyer, 50_000);
        OrderDtos.CreateOrderResponse placed = place(checkoutWithBrokenNotifications, buyer,
                coinQuote(coupon(), 0, 50_000), () -> { });
        assertThat(placed.status()).isEqualTo(OrderStatus.AWAITING_PAYMENT.name());
        assertThat(tx.execute(s -> orders.findByPublicRef(placed.publicRef())).isPresent()).isTrue();
        assertThat(creditBalance(buyer)).isZero();
        assertThat(bellRows(buyer)).isZero();
    }

    @Test
    @DisplayName("paid in full by store credit: paid at once, no wait on its own lock, notified after commit")
    void wholeOrderOnStoreCredit() throws Exception {
        long buyer = customer();
        Quote quote = coinQuote(coupon(), 0, 0);
        long whole = quote.total().minor();
        giveCredit(buyer, whole);
        OrderDtos.CreateOrderResponse placed = place(checkout, buyer, coinQuote(quote.couponCode(), 0, whole), () -> { });
        assertThat(placed.totalMinor()).isZero();
        assertThat(placed.status()).isNotEqualTo(OrderStatus.AWAITING_PAYMENT.name());
        assertThat(creditBalance(buyer)).isZero();
        assertThat(bellRows(buyer)).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("points and store credit together: no wait on its own lock")
    void pointsAndStoreCredit() throws Exception {
        long buyer = customer();
        givePoints(buyer, 500);
        giveCredit(buyer, 20_000);
        OrderDtos.CreateOrderResponse placed = place(checkout, buyer, coinQuote(null, 300, 20_000), () -> { });
        assertThat(placed.status()).isEqualTo(OrderStatus.AWAITING_PAYMENT.name());
        assertThat(jdbc.queryForObject("select coalesce(sum(amount), 0) from points_ledger where account_id = ?",
                Long.class, buyer)).isEqualTo(200);
        assertThat(creditBalance(buyer)).isZero();
        assertThat(bellRows(buyer)).isEqualTo(1);
    }
}
