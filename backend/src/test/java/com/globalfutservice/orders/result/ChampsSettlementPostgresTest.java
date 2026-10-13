package com.globalfutservice.orders.result;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.globalfutservice.admin.AdminPaymentQueries;
import com.globalfutservice.affiliate.AffiliateService;
import com.globalfutservice.catalog.RateCardRepository;
import com.globalfutservice.coaching.AfterCommit;
import com.globalfutservice.coaching.CoachingService;
import com.globalfutservice.config.AppProperties;
import com.globalfutservice.credentials.CredentialVaultService;
import com.globalfutservice.domain.money.Currency;
import com.globalfutservice.domain.orders.Actor;
import com.globalfutservice.domain.orders.OrderStatus;
import com.globalfutservice.fulfilment.AutoDispatchQueue;
import com.globalfutservice.fulfilment.VendorOrderLedger;
import com.globalfutservice.identity.AccountRepository;
import com.globalfutservice.loyalty.LoyaltyService;
import com.globalfutservice.loyalty.PointsLedgerRepository;
import com.globalfutservice.notify.DiscordBotClient;
import com.globalfutservice.notify.NotificationService;
import com.globalfutservice.notify.OrderNotification;
import com.globalfutservice.notify.discord.DiscordVerificationService;
import com.globalfutservice.notify.email.ChampionsEmails;
import com.globalfutservice.notify.email.EmailTemplate;
import com.globalfutservice.notify.feed.CustomerFeedService;
import com.globalfutservice.orders.OrderEntity;
import com.globalfutservice.orders.OrderEventEntity;
import com.globalfutservice.orders.OrderEventRepository;
import com.globalfutservice.orders.OrderPaymentState;
import com.globalfutservice.orders.OrderRepository;
import com.globalfutservice.orders.OrderService;
import com.globalfutservice.orders.web.OrderDtos;
import com.globalfutservice.orders.web.OrderMapper;
import com.globalfutservice.payments.PaymentGateway;
import com.globalfutservice.payments.PaymentRepository;
import com.globalfutservice.payments.RefundEntity;
import com.globalfutservice.payments.RefundMethod;
import com.globalfutservice.payments.RefundRepository;
import com.globalfutservice.payments.RefundService;
import com.globalfutservice.pricing.CouponService;
import com.globalfutservice.pricing.QuoteService;
import com.globalfutservice.storecredit.StoreCreditEntryEntity;
import com.globalfutservice.storecredit.StoreCreditRepository;
import com.globalfutservice.storecredit.StoreCreditService;
import com.globalfutservice.web.ApiExceptions;
import jakarta.persistence.EntityManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Champions results and their settlement against the real schema (V47, V48): Wins Achieved
 * required to deliver, delivering settling nothing, the amount from the prices that applied
 * when the order was priced, below the lowest tier by hand, the customer's one choice, Paid
 * only when confirmed and recorded as a settlement refund, Issued only with the credit, the
 * lock, points taken back pending or credited, the 5-day default, a full refund after a
 * settlement, and staff-only notes never reaching the customer. Runs when GFS_TEST_PG_URL is
 * set, in a throwaway schema.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ChampsSettlementPostgresTest {

    /** When the result is recorded: five days after the order was priced. */
    private static final Instant NOW = Instant.parse("2026-10-13T05:00:00Z");
    private static final String BREAKDOWN = """
            {"issuedAt":"2026-10-08T02:54:00Z","lines":[
              {"code":"BASE","label":"Champs Boosting — 15 wins · Elite I","amountMinor":400000,"amountFormatted":"₹4,000.00"},
              {"code":"COUPON_DISCOUNT","label":"Coupon FB10 (5% off)","amountMinor":-20000,"amountFormatted":"-₹200.00"},
              {"code":"GATEWAY_FEE","label":"Payment processing (2.5%)","amountMinor":9500,"amountFormatted":"₹95.00"}
            ],"totalMinor":389500}""";

    /** A clock the 5-day default can be walked forward on. */
    private static final class MovableClock extends Clock {
        private Instant now = NOW;

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
    }

    private String schema;
    private JdbcTemplate jdbc;
    private LocalContainerEntityManagerFactoryBean factory;
    private TransactionTemplate tx;
    private final MovableClock clock = new MovableClock();

    private OrderRepository orders;
    private OrderEventRepository events;
    private OrderResultRepository results;
    private RefundRepository refunds;
    private StoreCreditRepository credit;
    private PointsLedgerRepository points;

    private OrderService orderService;
    private OrderResultService service;
    private RefundService refundService;
    private SettlementDefaultJob job;
    private OrderMapper mapper;
    private AdminPaymentQueries payments;
    private final NotificationService notifications = mock(NotificationService.class);

    private long adminId;
    private OrderResultService.Staff admin;
    private OrderResultService.Staff operator;

    @BeforeAll
    void migrate() {
        String url = System.getenv("GFS_TEST_PG_URL");
        assumeTrue(url != null && !url.isBlank(), "GFS_TEST_PG_URL not set: no database to test against");
        schema = "champs_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        DriverManagerDataSource ds = new DriverManagerDataSource(
                url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
                System.getenv("GFS_TEST_PG_USER"), System.getenv("GFS_TEST_PG_PASSWORD"));
        jdbc = new JdbcTemplate(ds);
        Flyway.configure().dataSource(ds).schemas(schema).defaultSchema(schema).locations("classpath:db/migration")
                .initSql("SET search_path TO " + schema + ", public").load().migrate();

        factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(ds);
        factory.setPackagesToScan("com.globalfutservice");
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setJpaPropertyMap(Map.of(
                "hibernate.hbm2ddl.auto", "validate",
                "hibernate.default_schema", schema,
                "hibernate.jdbc.time_zone", "UTC",
                "hibernate.physical_naming_strategy",
                "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy",
                "hibernate.implicit_naming_strategy",
                "org.springframework.boot.orm.jpa.hibernate.SpringImplicitNamingStrategy"));
        factory.afterPropertiesSet();
        tx = new TransactionTemplate(new JpaTransactionManager(factory.getObject()));
        EntityManager shared = SharedEntityManagerCreator.createSharedEntityManager(factory.getObject());
        JpaRepositoryFactory repositories = new JpaRepositoryFactory(shared);
        orders = repositories.getRepository(OrderRepository.class);
        events = repositories.getRepository(OrderEventRepository.class);
        results = repositories.getRepository(OrderResultRepository.class);
        refunds = repositories.getRepository(RefundRepository.class);
        credit = repositories.getRepository(StoreCreditRepository.class);
        points = repositories.getRepository(PointsLedgerRepository.class);
        SettlementProofRepository proofs = repositories.getRepository(SettlementProofRepository.class);
        RateCardRepository rates = repositories.getRepository(RateCardRepository.class);
        AccountRepository accounts = repositories.getRepository(AccountRepository.class);

        AppProperties props = mock(AppProperties.class);
        when(props.champs()).thenReturn(new AppProperties.Champs(true, 5, "STORE_CREDIT"));
        when(props.publicUrl()).thenReturn("https://globalfutservices.com");
        when(props.fulfilment()).thenReturn(new Binder(new MapConfigurationPropertySource(Map.of(
                "f.backup-codes-required", "3"))).bind("f", AppProperties.Fulfilment.class).get());

        ObjectMapper json = new ObjectMapper();
        StoreCreditService storeCredit = new StoreCreditService(credit, clock);
        LoyaltyService loyalty = new LoyaltyService(points, props, clock);
        ResultRules rules = new ResultRules(List.of(new ChampsWinsLadder(rates)), props, json);
        orderService = new OrderService(orders, events, mock(PaymentRepository.class), mock(PaymentGateway.class),
                mock(QuoteService.class), loyalty, mock(AffiliateService.class), mock(CredentialVaultService.class),
                notifications, accounts, mock(CoachingService.class), mock(CouponService.class),
                mock(CustomerFeedService.class), json, props, clock, AfterCommit.immediate(),
                mock(AutoDispatchQueue.class), storeCredit, rules, results);
        service = new OrderResultService(orderService, orders, results, proofs, rules, storeCredit, loyalty, refunds,
                accounts, notifications, AfterCommit.immediate(), props, clock);
        refundService = new RefundService(orderService, refunds, results);
        job = new SettlementDefaultJob(results, service, rules, mock(com.globalfutservice.scheduling.SchedulerLock.class),
                clock);
        OrderPaymentState paymentState = mock(OrderPaymentState.class);
        when(paymentState.of(any())).thenReturn(new OrderPaymentState.View(null, null));
        mapper = new OrderMapper(json, props, mock(DiscordVerificationService.class), mock(DiscordBotClient.class),
                mock(CoachingService.class), mock(VendorOrderLedger.class), paymentState, clock,
                new OrderResultViews(results, props));
        payments = new AdminPaymentQueries(new NamedParameterJdbcTemplate(ds), clock);

        adminId = jdbc.queryForObject("""
                insert into account (public_id, email, email_normalised, password_hash, role, display_name)
                values ('acc_admin', 'owner@example.test', 'owner@example.test', 'x', 'ADMIN', 'Vinay') returning id
                """, Long.class);
        admin = new OrderResultService.Staff(adminId, "acc_admin", true);
        operator = new OrderResultService.Staff(adminId, "acc_operator", false);

        // The Champs Wins price list in FC27, as it stood when the orders were priced -- and
        // 14 wins repriced since, to ₹3,000, which no order priced before must ever see.
        String[][] tiers = {{"WINS_9", "120000"}, {"WINS_10", "155000"}, {"WINS_11", "190000"},
            {"WINS_12", "210000"}, {"WINS_13", "260000"}, {"WINS_15", "400000"}};
        int sort = 1;
        for (String[] tier : tiers) {
            jdbc.update("""
                    insert into rate_card (season, sku, variant, currency, price_unit, unit_price_minor, label, sort_order,
                                           valid_from)
                    values ('FC27', 'BOOST_CHAMPS', ?, 'INR', 'FLAT', ?, ?, ?, '2026-01-01T00:00:00Z')
                    """, tier[0], Long.parseLong(tier[1]), tier[0].substring(5) + " wins", sort++);
        }
        jdbc.update("""
                insert into rate_card (season, sku, variant, currency, price_unit, unit_price_minor, label, sort_order,
                                       valid_from, valid_to)
                values ('FC27', 'BOOST_CHAMPS', 'WINS_14', 'INR', 'FLAT', 350000, '14 wins', 6,
                        '2026-01-01T00:00:00Z', '2026-10-09T00:00:00Z')
                """);
        jdbc.update("""
                insert into rate_card (season, sku, variant, currency, price_unit, unit_price_minor, label, sort_order,
                                       valid_from)
                values ('FC27', 'BOOST_CHAMPS', 'WINS_14', 'INR', 'FLAT', 300000, '14 wins', 6, '2026-10-09T00:00:00Z')
                """);
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

    @BeforeEach
    void reset() {
        clock.now = NOW;
        clearInvocations(notifications);
    }

    // ------------------------------------------------------------------ fixtures

    private <T> T inTx(Supplier<T> work) {
        return tx.execute(s -> work.get());
    }

    private void run(Runnable work) {
        tx.executeWithoutResult(s -> work.run());
    }

    private long customer() {
        String id = UUID.randomUUID().toString().substring(0, 8);
        return jdbc.queryForObject("""
                insert into account (public_id, email, email_normalised, password_hash)
                values (?, ?, ?, 'x') returning id
                """, Long.class, "acc_" + id, id + "@example.test", id + "@example.test");
    }

    /** A paid, in-progress 15-win order: ₹4,000, 5% coupon, ₹95 fee, ₹3,895 paid, 20 points pending. */
    private OrderEntity order(Long accountId) {
        String ref = "GFS-27-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        long id = jdbc.queryForObject("""
                insert into orders (public_ref, account_id, season, sku, variant, quantity, delivery_method, currency,
                                    subtotal_minor, total_minor, price_breakdown, points_earned, status, quote_id,
                                    guest_email, created_at)
                values (?, ?, 'FC27', 'BOOST_CHAMPS', 'WINS_15', 1, 'COMFORT_TRADE', 'INR', 400000, 389500, ?::jsonb,
                        20, 'IN_PROGRESS', ?, 'buyer@example.test', '2026-10-08T02:55:00Z')
                returning id
                """, Long.class, ref, accountId, BREAKDOWN, "q_" + ref);
        return load(id);
    }

    private OrderEntity load(long id) {
        return inTx(() -> orders.findById(id).orElseThrow());
    }

    private OrderResultEntity result(OrderEntity o) {
        return inTx(() -> results.findById(o.getId()).orElseThrow());
    }

    private static ResultDtos.ResultInput wins(int level) {
        return new ResultDtos.ResultInput(level, false, null, null);
    }

    private OrderEntity deliver(OrderEntity o, int level) {
        return inTx(() -> service.deliver(orders.findById(o.getId()).orElseThrow(), wins(level), null, operator));
    }

    private void choose(OrderEntity o, String method) {
        run(() -> service.choose(orders.findById(o.getId()).orElseThrow(), method, o.getAccountId()));
    }

    private void paid(OrderEntity o, boolean confirmed) {
        run(() -> service.markPaid(orders.findById(o.getId()).orElseThrow(),
                new ResultDtos.PaidRequest("PAYOP", "RF-9001", confirmed), admin));
    }

    private void issue(OrderEntity o) {
        run(() -> service.issue(orders.findById(o.getId()).orElseThrow(), new ResultDtos.IssueRequest(true), admin));
    }

    private void update(OrderEntity o, ResultDtos.ResultUpdate u, OrderResultService.Staff who) {
        run(() -> service.update(orders.findById(o.getId()).orElseThrow(), u, who));
    }

    private List<OrderEventEntity> history(OrderEntity o) {
        return inTx(() -> events.findByOrderIdOrderByCreatedAtAsc(o.getId()));
    }

    /** One pass of the default job, in a transaction as its @Transactional service call has in the application. */
    private int sweep() {
        return inTx(() -> job.sweep());
    }

    private long creditBalance(long accountId) {
        return inTx(() -> credit.balanceOf(accountId, Currency.INR));
    }

    // ------------------------------------------------------------------ delivering

    @Test
    @DisplayName("a Champions order cannot be marked delivered without Wins Achieved")
    void deliverNeedsWins() {
        OrderEntity o = order(customer());
        assertThatThrownBy(() -> run(() -> orderService.transition(orders.findById(o.getId()).orElseThrow(),
                OrderStatus.DELIVERED, Actor.OPERATOR, adminId, "acc_admin", null)))
                .isInstanceOfSatisfying(ApiExceptions.ConflictException.class,
                        e -> assertThat(e.code()).isEqualTo("wins_achieved_required"));
        assertThat(load(o.getId()).getStatus()).isEqualTo(OrderStatus.IN_PROGRESS);
    }

    @Test
    @DisplayName("delivering records the result and settles nothing: ₹475 Pending, the choice open for 5 days")
    void deliveringSettlesNothing() {
        long buyer = customer();
        OrderEntity o = deliver(order(buyer), 14);

        assertThat(o.getStatus()).isEqualTo(OrderStatus.DELIVERED);
        OrderResultEntity r = result(o);
        // (₹4,000 − ₹3,500) × (₹3,800 ÷ ₹4,000): the coupon scales it, the ₹95 fee is not in it,
        // and 14 wins is priced at ₹3,500 -- what it cost when the order was priced, not today's ₹3,000.
        assertThat(r.getSettlementMinor()).isEqualTo(47_500);
        assertThat(r.getAchievedPriceMinor()).isEqualTo(350_000);
        assertThat(r.getServicePaidMinor()).isEqualTo(380_000);
        assertThat(r.getStatus()).isEqualTo(OrderResultEntity.Status.PENDING);
        assertThat(r.getMethod()).isNull();
        assertThat(r.getChooseBy()).isEqualTo(NOW.plus(Duration.ofDays(5)));
        assertThat(inTx(() -> refunds.findByOrderIdAndKind(o.getId(), RefundEntity.Kind.SETTLEMENT))).isEmpty();
        assertThat(creditBalance(buyer)).isZero();
        assertThat(history(o)).last().satisfies(e -> {
            assertThat(e.getToStatus()).isEqualTo(OrderStatus.DELIVERED);
            assertThat(e.getKind()).isEqualTo("RESULT_DELIVERED");
            assertThat(e.getDetail()).isEqualTo("14");
        });
        // The delivery email carries the result.
        ArgumentCaptor<OrderNotification> sent = ArgumentCaptor.forClass(OrderNotification.class);
        verify(notifications).orderDelivered(sent.capture());
        assertThat(sent.getValue().settlement().settlementFormatted()).isEqualTo("₹475.00");
        assertThat(sent.getValue().settlement().chooseBy()).isEqualTo(NOW.plus(Duration.ofDays(5)));
    }

    @Test
    @DisplayName("every win achieved: nothing owed, no choice, nothing to settle")
    void allAchieved() {
        OrderEntity o = deliver(order(customer()), 15);
        OrderResultEntity r = result(o);
        assertThat(r.getSettlementMinor()).isZero();
        assertThat(r.getStatus()).isEqualTo(OrderResultEntity.Status.NONE);
        assertThat(r.getChooseBy()).isNull();
        assertThatThrownBy(() -> choose(o, "REFUND")).isInstanceOf(ApiExceptions.ConflictException.class);
    }

    @Test
    @DisplayName("below the lowest tier: the amount by hand, a note required, capped at what was paid for the service")
    void belowLowest() {
        OrderEntity o = order(customer());
        assertThatThrownBy(() -> run(() -> service.deliver(orders.findById(o.getId()).orElseThrow(),
                new ResultDtos.ResultInput(7, true, 150_000L, " "), null, operator)))
                .isInstanceOf(ApiExceptions.BadRequestException.class).hasMessageContaining("note");
        assertThatThrownBy(() -> run(() -> service.deliver(orders.findById(o.getId()).orElseThrow(),
                new ResultDtos.ResultInput(7, true, 380_001L, "Seven wins"), null, operator)))
                .isInstanceOf(ApiExceptions.BadRequestException.class).hasMessageContaining("₹3,800.00");
        assertThatThrownBy(() -> run(() -> service.deliver(orders.findById(o.getId()).orElseThrow(),
                new ResultDtos.ResultInput(9, true, 1_000L, "Nine is a tier"), null, operator)))
                .isInstanceOf(ApiExceptions.BadRequestException.class);
        assertThat(load(o.getId()).getStatus()).isEqualTo(OrderStatus.IN_PROGRESS);

        inTx(() -> service.deliver(orders.findById(o.getId()).orElseThrow(),
                new ResultDtos.ResultInput(7, true, 150_000L, "Seven wins; agreed with the client."), null, operator));
        OrderResultEntity r = result(o);
        assertThat(r.isBelowLowest()).isTrue();
        assertThat(r.getAchievedLevel()).isEqualTo(7);
        assertThat(r.getSettlementMinor()).isEqualTo(150_000);
        assertThat(history(o)).anySatisfy(e -> {
            assertThat(e.isStaffOnly()).isTrue();
            assertThat(e.getReason()).contains("Seven wins; agreed with the client.");
        });
    }

    // ------------------------------------------------------------------ choosing

    @Test
    @DisplayName("the customer chooses once; after that only an admin changes it, until it is settled")
    void oneChoice() {
        OrderEntity o = deliver(order(customer()), 14);
        choose(o, "REFUND");
        OrderResultEntity r = result(o);
        assertThat(r.getMethod()).isEqualTo(OrderResultEntity.Method.REFUND);
        assertThat(r.getMethodSetBy()).isEqualTo(OrderResultEntity.SetBy.CUSTOMER);
        assertThat(r.awaitsChoice()).isFalse();

        assertThatThrownBy(() -> choose(o, "STORE_CREDIT")).isInstanceOfSatisfying(ApiExceptions.ConflictException.class,
                e -> assertThat(e.code()).isEqualTo("already_chosen"));
        assertThatThrownBy(() -> update(o, new ResultDtos.ResultUpdate(null, null, null, "STORE_CREDIT", null), operator))
                .isInstanceOf(ApiExceptions.ForbiddenException.class);

        update(o, new ResultDtos.ResultUpdate(null, null, null, "STORE_CREDIT", null), admin);
        assertThat(result(o).getMethodSetBy()).isEqualTo(OrderResultEntity.SetBy.ADMIN);
    }

    @Test
    @DisplayName("wins stay editable, the amount following, until it is settled")
    void editable() {
        OrderEntity o = deliver(order(customer()), 14);
        update(o, new ResultDtos.ResultUpdate(13, false, null, null, null), operator);
        // (₹4,000 − ₹2,600) × 0.95
        assertThat(result(o).getSettlementMinor()).isEqualTo(133_000);
        assertThat(history(o)).last().satisfies(e -> {
            assertThat(e.getKind()).isEqualTo("RESULT_UPDATED");
            assertThat(e.getDetail()).isEqualTo("13");
            assertThat(e.isStaffOnly()).isFalse();
        });
    }

    // ------------------------------------------------------------------ settling

    @Test
    @DisplayName("Paid needs confirming; it records a settlement refund, takes back pending points, and locks")
    void paid() {
        OrderEntity o = deliver(order(customer()), 14);
        choose(o, "REFUND");
        assertThatThrownBy(() -> paid(o, false)).isInstanceOf(ApiExceptions.BadRequestException.class);
        assertThat(result(o).getStatus()).isEqualTo(OrderResultEntity.Status.PENDING);
        assertThatThrownBy(() -> run(() -> service.markPaid(orders.findById(o.getId()).orElseThrow(),
                new ResultDtos.PaidRequest("PAYOP", "RF-1", true), operator)))
                .isInstanceOf(ApiExceptions.ForbiddenException.class);

        paid(o, true);
        OrderResultEntity r = result(o);
        assertThat(r.getStatus()).isEqualTo(OrderResultEntity.Status.PAID);
        assertThat(r.getSettledBy()).isEqualTo(adminId);
        assertThat(r.getSettledAt()).isEqualTo(NOW);
        RefundEntity refund = inTx(() -> refunds.findByOrderIdAndKind(o.getId(), RefundEntity.Kind.SETTLEMENT))
                .orElseThrow();
        assertThat(refund.getAmountMinor()).isEqualTo(47_500);
        assertThat(refund.getMethod()).isEqualTo(RefundMethod.PAYOP);
        assertThat(refund.getReference()).isEqualTo("RF-9001");
        assertThat(r.getRefundId()).isEqualTo(refund.getId());
        // Pending points: 20 × ₹475 / ₹3,800 = 2.5 -> 3 come off before they ever land.
        assertThat(r.getPointsRemoved()).isEqualTo(3);
        assertThat(load(o.getId()).getPointsEarned()).isEqualTo(17);
        // Still delivered: the settlement is not the order's status.
        assertThat(load(o.getId()).getStatus()).isEqualTo(OrderStatus.DELIVERED);
        verify(notifications).settlementSettled(any());

        assertThatThrownBy(() -> update(o, new ResultDtos.ResultUpdate(13, false, null, null, null), admin))
                .isInstanceOfSatisfying(ApiExceptions.ConflictException.class,
                        e -> assertThat(e.code()).isEqualTo("result_locked"));
        assertThatThrownBy(() -> paid(o, true)).isInstanceOf(ApiExceptions.ConflictException.class);
    }

    @Test
    @DisplayName("Issued adds the credit in the same step; points already credited come off the balance")
    void issued() {
        long buyer = customer();
        OrderEntity o = deliver(order(buyer), 14);
        // The points landed already: the order completed before it was settled.
        jdbc.update("update orders set points_settled_at = ? where id = ?", Timestamp.from(NOW), o.getId());
        jdbc.update("insert into points_ledger (account_id, order_id, entry_type, amount, description) "
                + "values (?, ?, 'EARNED', 20, 'Earned')", buyer, o.getId());
        choose(o, "STORE_CREDIT");
        issue(o);

        OrderResultEntity r = result(o);
        assertThat(r.getStatus()).isEqualTo(OrderResultEntity.Status.ISSUED);
        assertThat(creditBalance(buyer)).isEqualTo(47_500);
        assertThat(inTx(() -> credit.findByOrderIdAndEntryType(o.getId(), StoreCreditEntryEntity.Type.ISSUED)))
                .isPresent();
        assertThat(inTx(() -> points.balanceOf(buyer))).isEqualTo(17);
        assertThat(r.getPointsRemoved()).isEqualTo(3);
        assertThat(load(o.getId()).getPointsEarned()).as("the order's own figure is left as it landed").isEqualTo(20);
    }

    @Test
    @DisplayName("points come off a credited balance, never below zero")
    void pointsNeverBelowZero() {
        long buyer = customer();
        OrderEntity o = deliver(order(buyer), 14);
        jdbc.update("update orders set points_settled_at = ? where id = ?", Timestamp.from(NOW), o.getId());
        jdbc.update("insert into points_ledger (account_id, order_id, entry_type, amount, description) "
                + "values (?, ?, 'EARNED', 20, 'Earned')", buyer, o.getId());
        jdbc.update("insert into points_ledger (account_id, order_id, entry_type, amount, description) "
                + "values (?, null, 'MANUAL_ADJUSTMENT', -19, 'Spent elsewhere')", buyer);
        choose(o, "STORE_CREDIT");
        issue(o);
        assertThat(inTx(() -> points.balanceOf(buyer))).isZero();
        assertThat(result(o).getPointsRemoved()).isEqualTo(1);
    }

    @Test
    @DisplayName("store credit that cannot be added stays Pending: nothing is marked Issued without it")
    void issueFailsStaysPending() {
        OrderEntity o = deliver(order(null), 14);
        update(o, new ResultDtos.ResultUpdate(null, null, null, "STORE_CREDIT", null), admin);
        assertThatThrownBy(() -> issue(o)).isInstanceOfSatisfying(ApiExceptions.ConflictException.class,
                e -> assertThat(e.code()).isEqualTo("no_account"));
        assertThat(result(o).getStatus()).isEqualTo(OrderResultEntity.Status.PENDING);
        assertThat(inTx(() -> credit.findByOrderIdAndEntryType(o.getId(), StoreCreditEntryEntity.Type.ISSUED))).isEmpty();
    }

    // ------------------------------------------------------------------ the default

    @Test
    @DisplayName("five days without a choice: store credit issued automatically; a chosen one is left alone")
    void defaultAfterFiveDays() {
        long quiet = customer();
        long decided = customer();
        OrderEntity unchosen = deliver(order(quiet), 14);
        OrderEntity chosen = deliver(order(decided), 14);
        choose(chosen, "REFUND");

        // The schema is shared with the other tests here, so what is asserted is these two orders.
        clock.now = NOW.plus(Duration.ofDays(5)).minusSeconds(60);
        sweep();
        assertThat(result(unchosen).getStatus()).as("not yet due").isEqualTo(OrderResultEntity.Status.PENDING);
        assertThat(creditBalance(quiet)).isZero();

        clock.now = NOW.plus(Duration.ofDays(5)).plusSeconds(60);
        assertThat(sweep()).isPositive();

        OrderResultEntity r = result(unchosen);
        assertThat(r.getMethod()).isEqualTo(OrderResultEntity.Method.STORE_CREDIT);
        assertThat(r.getMethodSetBy()).isEqualTo(OrderResultEntity.SetBy.AUTOMATIC);
        assertThat(r.getStatus()).isEqualTo(OrderResultEntity.Status.ISSUED);
        assertThat(r.getSettledBy()).isNull();
        assertThat(creditBalance(quiet)).isEqualTo(47_500);
        assertThat(history(unchosen)).last().satisfies(e -> {
            assertThat(e.getKind()).isEqualTo("SETTLEMENT_ISSUED_AUTO");
            assertThat(e.getReason()).contains("issued automatically after 5 days");
        });
        verify(notifications, atLeastOnce()).settlementSettled(any());

        OrderResultEntity left = result(chosen);
        assertThat(left.getMethod()).isEqualTo(OrderResultEntity.Method.REFUND);
        assertThat(left.getStatus()).isEqualTo(OrderResultEntity.Status.PENDING);
        assertThat(creditBalance(decided)).isZero();
        sweep();
        assertThat(creditBalance(quiet)).as("once only").isEqualTo(47_500);
    }

    // ------------------------------------------------------------------ refunds

    @Test
    @DisplayName("a full refund after a settlement: the total less what was settled, and the order says so")
    void fullRefundAfterSettlement() {
        OrderEntity o = deliver(order(customer()), 14);
        choose(o, "REFUND");
        paid(o, true);
        run(() -> orderService.transition(orders.findById(o.getId()).orElseThrow(), OrderStatus.DISPUTED,
                Actor.OPERATOR, adminId, "acc_admin", null));

        RefundEntity full = inTx(() -> refundService.record(o.getPublicRef(), RefundMethod.UPI, "412345678901",
                "Account flagged", adminId, "acc_admin"));
        assertThat(full.getAmountMinor()).isEqualTo(342_000);
        assertThat(full.getKind()).isEqualTo(RefundEntity.Kind.FULL);
        assertThat(load(o.getId()).getStatus()).isEqualTo(OrderStatus.REFUNDED);
        assertThat(history(o)).last().satisfies(e -> assertThat(e.getReason()).isEqualTo(
                "Refunded ₹3,420.00 by UPI, reference 412345678901. ₹475.00 was already settled as a refund."));
        assertThat(result(o).getStatus()).isEqualTo(OrderResultEntity.Status.PAID);
    }

    @Test
    @DisplayName("refunded in full while a settlement is still owed: the settlement is closed, the whole total sent back")
    void fullRefundCancelsOwed() {
        OrderEntity o = deliver(order(customer()), 14);
        run(() -> orderService.transition(orders.findById(o.getId()).orElseThrow(), OrderStatus.DISPUTED,
                Actor.OPERATOR, adminId, "acc_admin", null));
        RefundEntity full = inTx(() -> refundService.record(o.getPublicRef(), RefundMethod.PAYPAL, "PP-1",
                "Account flagged", adminId, "acc_admin"));
        assertThat(full.getAmountMinor()).isEqualTo(389_500);
        assertThat(result(o).getStatus()).isEqualTo(OrderResultEntity.Status.CANCELLED);
        clock.now = NOW.plus(Duration.ofDays(6));
        sweep();
        assertThat(result(o).getStatus()).as("the default never touches it").isEqualTo(OrderResultEntity.Status.CANCELLED);
        assertThat(creditBalance(o.getAccountId())).isZero();
    }

    @Test
    @DisplayName("the settlement refund shows on the Payments page; the payment stays a Success")
    void onPayments() {
        OrderEntity o = deliver(order(customer()), 14);
        jdbc.update("""
                insert into manual_payment_claim (order_id, method, destination, reference, status, submitted_at,
                                                  reviewed_at, reviewed_by)
                values (?, 'UPI', 'gfs@upi', '412345678901', 'VERIFIED', ?, ?, ?)
                """, o.getId(), Timestamp.from(Instant.parse("2026-10-08T03:00:00Z")),
                Timestamp.from(Instant.parse("2026-10-08T03:30:00Z")), adminId);
        choose(o, "REFUND");
        paid(o, true);

        AdminPaymentQueries.Row row = inTx(() -> payments.search(
                new AdminPaymentQueries.Filter(null, null, null, null, o.getPublicRef()), 0, 25)).items().get(0);
        assertThat(row.status()).isEqualTo("SUCCESS");
        assertThat(row.refund()).isNull();
        assertThat(row.settlementRefund().amountMinor()).isEqualTo(47_500);
        assertThat(row.settlementRefund().method()).isEqualTo("PAYOP");
        assertThat(row.settlementRefund().reference()).isEqualTo("RF-9001");
        assertThat(inTx(() -> payments.search(new AdminPaymentQueries.Filter(null, null, null, null, "RF-9001"), 0, 25))
                .items()).extracting(AdminPaymentQueries.Row::publicRef).containsExactly(o.getPublicRef());
    }

    // ------------------------------------------------------------------ staff only

    @Test
    @DisplayName("an admin's note is staff-only: never on the customer's page, never in an email")
    void staffNotesStayStaff() {
        OrderEntity o = deliver(order(customer()), 14);
        String secret = "Customer was rude in chat; do not offer goodwill";
        update(o, new ResultDtos.ResultUpdate(null, null, null, null, secret), operator);
        List<OrderEventEntity> all = history(o);
        assertThat(all).anySatisfy(e -> {
            assertThat(e.getReason()).isEqualTo(secret);
            assertThat(e.isStaffOnly()).isTrue();
        });

        OrderEntity loaded = load(o.getId());
        OrderDtos.OrderResponse customer = inTx(() -> mapper.toResponse(loaded, all, false));
        assertThat(customer.timeline()).extracting(OrderDtos.OrderEventDto::reason).doesNotContain(secret);
        assertThat(customer.timeline()).noneMatch(OrderDtos.OrderEventDto::staffOnly);
        assertThat(customer.result().settlementFormatted()).isEqualTo("₹475.00");
        OrderDtos.OrderResponse staff = inTx(() -> mapper.toAdminResponse(loaded, all, false));
        assertThat(staff.timeline()).extracting(OrderDtos.OrderEventDto::reason).contains(secret);

        choose(o, "REFUND");
        paid(o, true);
        ArgumentCaptor<OrderNotification> sent = ArgumentCaptor.forClass(OrderNotification.class);
        verify(notifications).settlementSettled(sent.capture());
        EmailTemplate.Brand brand = new EmailTemplate.Brand("https://globalfutservices.com", "globalfutservices.com",
                "https://discord.gg/x", "Discord", "https://instagram.com/x", "Instagram");
        var mail = ChampionsEmails.settled(sent.getValue(), brand, "https://x/track", "https://x/support");
        assertThat(mail.html()).doesNotContain("rude").contains("₹475.00");
        assertThat(mail.text()).doesNotContain("rude");
        var delivered = ChampionsEmails.delivered(inTx(() -> orderService.notificationFor(load(o.getId()))), brand,
                "https://x/track", "https://x/support");
        assertThat(delivered.html()).doesNotContain("rude");
    }

    // ------------------------------------------------------------------ store credit

    @Test
    @DisplayName("store credit spent on an order comes back if the order is abandoned, and never twice")
    void creditReturnedOnAbandon() {
        long buyer = customer();
        OrderEntity source = deliver(order(buyer), 14);
        choose(source, "STORE_CREDIT");
        issue(source);
        assertThat(creditBalance(buyer)).isEqualTo(47_500);

        StoreCreditService storeCredit = new StoreCreditService(credit, clock);
        long next = jdbc.queryForObject("""
                insert into orders (public_ref, account_id, season, sku, quantity, delivery_method, currency,
                                    subtotal_minor, total_minor, price_breakdown, status, quote_id, guest_email,
                                    store_credit_minor)
                values ('GFS-27-SPEND001', ?, 'FC27', 'TRADING_SERVICE', 1, 'PLAYER_AUCTION', 'INR', 100000, 53800,
                        '{"lines":[]}'::jsonb, 'AWAITING_PAYMENT', 'q_spend001', 'buyer@example.test', 47500)
                returning id
                """, Long.class, buyer);
        run(() -> storeCredit.spend(buyer, next, Currency.INR, 47_500, "GFS-27-SPEND001"));
        assertThat(creditBalance(buyer)).isZero();
        assertThatThrownBy(() -> run(() -> storeCredit.spend(buyer, source.getId(), Currency.INR, 1, "again")))
                .isInstanceOfSatisfying(ApiExceptions.ConflictException.class,
                        e -> assertThat(e.code()).isEqualTo("store_credit_changed"));

        run(() -> orderService.transition(orders.findById(next).orElseThrow(), OrderStatus.ABANDONED, Actor.SYSTEM,
                null, "sweep", "Not paid"));
        assertThat(creditBalance(buyer)).isEqualTo(47_500);
        run(() -> storeCredit.giveBack(buyer, next, Currency.INR, 47_500, "GFS-27-SPEND001", "Again"));
        assertThat(creditBalance(buyer)).as("once only").isEqualTo(47_500);
        assertThat(inTx(() -> credit.balanceOf(buyer, Currency.EUR))).as("one currency at a time").isZero();
        verify(notifications, never()).settlementSettled(null);
    }
}
