package com.globalfutservice.admin;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.globalfutservice.coaching.CoachingService;
import com.globalfutservice.config.AppProperties;
import com.globalfutservice.domain.money.Currency;
import com.globalfutservice.domain.money.Money;
import com.globalfutservice.fulfilment.VendorOrderLedger;
import com.globalfutservice.notify.DiscordBotClient;
import com.globalfutservice.notify.discord.DiscordVerificationService;
import com.globalfutservice.orders.OrderEntity;
import com.globalfutservice.orders.OrderPaymentState;
import com.globalfutservice.orders.OrderRepository;
import com.globalfutservice.orders.web.OrderDtos;
import com.globalfutservice.orders.web.OrderMapper;
import com.globalfutservice.pricing.CouponDeletionEntity;
import com.globalfutservice.pricing.CouponDeletionRepository;
import com.globalfutservice.pricing.CouponEntity;
import com.globalfutservice.pricing.CouponRedemptionRepository;
import com.globalfutservice.pricing.CouponRepository;
import com.globalfutservice.pricing.CouponService;
import com.globalfutservice.web.ApiExceptions;
import jakarta.persistence.EntityManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;

/**
 * Deleting a coupon against the real schema: V45's partial unique index, the redemption
 * that holds a used coupon in place, and checkout reading a deleted code as not valid.
 * Runs when GFS_TEST_PG_URL is set, in a throwaway schema.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CouponDeletionPostgresTest {

    private static final Instant NOW = Instant.parse("2026-10-10T10:00:00Z");

    private String schema;
    private JdbcTemplate jdbc;
    private LocalContainerEntityManagerFactoryBean factory;
    private TransactionTemplate tx;
    private CouponRepository coupons;
    private CouponRedemptionRepository redemptions;
    private CouponDeletionRepository deletions;
    private OrderRepository orders;
    private CouponDeletion deletion;
    private CouponService checkout;
    private long adminId;
    private long customerId;

    @BeforeAll
    void migrate() {
        String url = System.getenv("GFS_TEST_PG_URL");
        assumeTrue(url != null && !url.isBlank(), "GFS_TEST_PG_URL not set: no database to test against");
        schema = "coupon_del_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
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
                "hibernate.hbm2ddl.auto", "none",
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
        coupons = repositories.getRepository(CouponRepository.class);
        redemptions = repositories.getRepository(CouponRedemptionRepository.class);
        deletions = repositories.getRepository(CouponDeletionRepository.class);
        orders = repositories.getRepository(OrderRepository.class);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        deletion = new CouponDeletion(coupons, redemptions, deletions, orders, clock);
        checkout = new CouponService(coupons, redemptions, Clock.systemUTC());
        adminId = jdbc.queryForObject("""
                insert into account (public_id, email, email_normalised, password_hash, role)
                values ('acc_admin', 'owner@example.test', 'owner@example.test', 'x', 'ADMIN') returning id
                """, Long.class);
        customerId = jdbc.queryForObject("""
                insert into account (public_id, email, email_normalised, password_hash)
                values ('acc_buyer', 'buyer@example.test', 'buyer@example.test', 'x') returning id
                """, Long.class);
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

    private CouponEntity create(String code) {
        return tx.execute(s -> coupons.saveAndFlush(new CouponEntity(code, 1_000, adminId)));
    }

    /** An order placed with the coupon: its code, its frozen coupon line, and -- if held -- its redemption. */
    private long orderWith(CouponEntity coupon, boolean redemptionHeld) {
        String ref = "GFS-26-" + coupon.getCode();
        long orderId = jdbc.queryForObject("""
                insert into orders (public_ref, season, sku, quantity, delivery_method, currency, subtotal_minor,
                                    total_minor, price_breakdown, status, quote_id, guest_email, coupon_code, created_at)
                values (?, 'FC27', 'TRADING_SERVICE', 1, 'PLAYER_AUCTION', 'EUR', 10000, 9225, ?::jsonb,
                        'AWAITING_PAYMENT', ?, 'buyer@example.test', ?, ?)
                returning id
                """, Long.class, ref, """
                {"lines":[
                  {"code":"BASE","label":"Coins","amountMinor":10000,"amountFormatted":"€100.00"},
                  {"code":"COUPON_DISCOUNT","label":"Coupon %s (10%% off)","amountMinor":-1000,"amountFormatted":"-€10.00"},
                  {"code":"GATEWAY_FEE","label":"Payment processing (2.5%%)","amountMinor":225,"amountFormatted":"€2.25"}
                ],"totalMinor":9225}
                """.formatted(coupon.getCode()), "q_" + coupon.getCode(), coupon.getCode(),
                Timestamp.from(coupon.getCreatedAt().plusSeconds(60)));
        if (redemptionHeld) {
            jdbc.update("""
                    insert into coupon_redemption (coupon_id, order_id, account_id, discount_bps, discount_minor)
                    values (?, ?, null, 1000, 1000)
                    """, coupon.getId(), orderId);
            jdbc.update("update coupon set redeemed_count = redeemed_count + 1 where id = ?", coupon.getId());
        }
        return orderId;
    }

    private CouponDeletion.Deleted delete(CouponEntity coupon) {
        return tx.execute(s -> deletion.delete(coupon.getId(), adminId));
    }

    private boolean listed(String code) {
        return tx.execute(s -> coupons.findAllByDeletedAtIsNullOrderByCreatedAtDesc(PageRequest.of(0, 200))
                .getContent().stream().anyMatch(c -> c.getCode().equals(code)));
    }

    /** What checkout says about a code, for a signed-in customer who has not used it: null when it applies. */
    private String atCheckout(String code) {
        return tx.execute(s -> checkout.explain(code, customerId, Money.ofMinor(10_000, Currency.EUR)).orElse(null));
    }

    @Test
    @DisplayName("never used: deleted outright, and the deletion recorded -- who, when, which way")
    void unusedRemoved() {
        CouponEntity fresh = create("FRESH10");

        assertThat(delete(fresh).outcome()).isEqualTo(CouponDeletionEntity.Outcome.REMOVED);

        assertThat(coupons.findById(fresh.getId())).isEmpty();
        assertThat(listed("FRESH10")).isFalse();
        CouponDeletionEntity record = tx.execute(s -> deletions.findAllByOrderByDeletedAtDesc(PageRequest.of(0, 50))
                .getContent().stream().filter(d -> d.getCouponId().equals(fresh.getId())).findFirst().orElseThrow());
        assertThat(record.getCode()).isEqualTo("FRESH10");
        assertThat(record.getOutcome()).isEqualTo(CouponDeletionEntity.Outcome.REMOVED);
        assertThat(record.getDeletedBy()).isEqualTo(adminId);
        assertThat(record.getDeletedAt()).isEqualTo(NOW);
        assertThat(atCheckout("FRESH10")).isEqualTo("That code is not valid.");
    }

    @Test
    @DisplayName("used: kept and hidden -- off the list, never applied again, and its order exactly as it was")
    void usedHidden() {
        CouponEntity used = create("USED10");
        long orderId = orderWith(used, true);
        String frozen = jdbc.queryForObject("select price_breakdown::text from orders where id = ?", String.class, orderId);
        assertThat(atCheckout("USED10")).isNull();

        assertThat(delete(used).outcome()).isEqualTo(CouponDeletionEntity.Outcome.HIDDEN);

        CouponEntity kept = coupons.findById(used.getId()).orElseThrow();
        assertThat(kept.isDeleted()).isTrue();
        assertThat(kept.getDeletedBy()).isEqualTo(adminId);
        assertThat(kept.getDeletedAt()).isEqualTo(NOW);
        assertThat(listed("USED10")).isFalse();
        // Checkout: the normal "invalid code", no discount, and no redemption can be claimed.
        assertThat(atCheckout("USED10")).isEqualTo("That code is not valid.");
        java.util.Optional<CouponService.Resolved> resolved =
                tx.execute(s -> checkout.resolve("USED10", null, Money.ofMinor(10_000, Currency.EUR)));
        assertThat(resolved).isEmpty();
        Integer claimed = tx.execute(s -> coupons.claimRedemption(used.getId()));
        assertThat(claimed).isZero();
        // The order that used it: its redemption, its frozen breakdown, and the discount it shows.
        assertThat(redemptions.findByOrderId(orderId)).hasValueSatisfying(r ->
                assertThat(r.getCouponId()).isEqualTo(used.getId()));
        assertThat(jdbc.queryForObject("select price_breakdown::text from orders where id = ?", String.class, orderId))
                .isEqualTo(frozen);
        OrderEntity order = tx.execute(s -> orders.findById(orderId).orElseThrow());
        OrderMapper mapper = new OrderMapper(new ObjectMapper(), mock(AppProperties.class),
                mock(DiscordVerificationService.class), mock(DiscordBotClient.class), mock(CoachingService.class),
                mock(VendorOrderLedger.class), mock(OrderPaymentState.class));
        assertThat(mapper.lines(order)).filteredOn(l -> l.code().equals("COUPON_DISCOUNT"))
                .singleElement().satisfies(l -> {
                    assertThat(l.amountMinor()).isEqualTo(-1000);
                    assertThat(l.label()).isEqualTo("Coupon USED10 (10% off)");
                });
        assertThat(order.getCouponCode()).isEqualTo("USED10");
    }

    @Test
    @DisplayName("an order that handed its redemption back still shows the discount: the coupon counts as used")
    void releasedStillUsed() {
        CouponEntity back = create("BACK10");
        orderWith(back, false);
        assertThat(delete(back).outcome()).isEqualTo(CouponDeletionEntity.Outcome.HIDDEN);
        assertThat(coupons.findById(back.getId())).hasValueSatisfying(c -> assertThat(c.isDeleted()).isTrue());
    }

    @Test
    @DisplayName("the code can be issued again as a new coupon; past orders stay with the old one")
    void codeIssuedAgain() {
        CouponEntity old = create("AGAIN10");
        long orderId = orderWith(old, true);
        delete(old);

        CouponEntity renewed = create("AGAIN10");

        assertThat(renewed.getId()).isNotEqualTo(old.getId());
        assertThat(listed("AGAIN10")).isTrue();
        java.util.Optional<Long> current = tx.execute(s -> checkout.resolveIdFor("AGAIN10"));
        assertThat(current).hasValue(renewed.getId());
        assertThat(atCheckout("AGAIN10")).isNull();
        assertThat(redemptions.findByOrderId(orderId)).hasValueSatisfying(r ->
                assertThat(r.getCouponId()).isEqualTo(old.getId()));
        // Still one live coupon per code: a second is refused by the database itself.
        assertThatThrownBy(() -> create("AGAIN10")).hasMessageContaining("coupon_code_live_uk");
    }

    @Test
    @DisplayName("deleting a deleted coupon finds nothing")
    void deletedTwice() {
        CouponEntity used = create("TWICE10");
        orderWith(used, true);
        delete(used);
        assertThatThrownBy(() -> delete(used)).isInstanceOf(ApiExceptions.NotFoundException.class);
    }
}
