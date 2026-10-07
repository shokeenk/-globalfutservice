package com.globalfutservice.payments.payop;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.globalfutservice.config.AppProperties;
import com.globalfutservice.domain.orders.OrderStatus;
import com.globalfutservice.notify.NotificationService;
import com.globalfutservice.orders.OrderEntity;
import com.globalfutservice.orders.OrderRepository;
import com.globalfutservice.orders.OrderService;
import com.globalfutservice.payments.PaymentRepository;
import com.globalfutservice.payments.WebhookLedger;
import com.globalfutservice.scheduling.SchedulerLock;
import jakarta.persistence.EntityManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * An IPN and the reconciliation job confirming the same payment at the same moment, against
 * a real PostgreSQL: applied once. Both have Payop's answer before either applies it -- a
 * barrier holds them there -- so without the invoice's row lock both would see it unpaid.
 *
 * <p>Runs when GFS_TEST_PG_URL, GFS_TEST_PG_USER and GFS_TEST_PG_PASSWORD are set; skipped
 * otherwise. A fresh, randomly named schema each run, dropped afterwards.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PayopApplyOncePostgresTest {

    private static final Clock NOW = Clock.fixed(Instant.parse("2026-10-07T10:00:00Z"), ZoneOffset.UTC);
    private static final String INVOICE = "d024f697-ba2d-456f-910e-4d7fdfd338dd";
    private static final String TXID = "dca59ca5-be19-470d-9494-9b76944e0241";
    private static final String REF = "GFS-26-RACE0001";

    private String schema;
    private JdbcTemplate jdbc;
    private LocalContainerEntityManagerFactoryBean factory;
    private JpaTransactionManager transactions;
    private PayopInvoiceRepository invoices;
    private OrderRepository orders;
    private PaymentRepository payments;
    private com.globalfutservice.payments.ManualPaymentClaimRepository claims;

    @BeforeAll
    void migrate() {
        String url = System.getenv("GFS_TEST_PG_URL");
        assumeTrue(url != null && !url.isBlank(), "GFS_TEST_PG_URL not set: no database to test against");
        schema = "payop_race_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
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
        transactions = new JpaTransactionManager(factory.getObject());
        // Each thread's transaction gets its own entity manager, as in the running application.
        EntityManager shared = SharedEntityManagerCreator.createSharedEntityManager(factory.getObject());
        JpaRepositoryFactory repositories = new JpaRepositoryFactory(shared);
        invoices = repositories.getRepository(PayopInvoiceRepository.class);
        orders = repositories.getRepository(OrderRepository.class);
        payments = repositories.getRepository(PaymentRepository.class);
        claims = repositories.getRepository(com.globalfutservice.payments.ManualPaymentClaimRepository.class);
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

    @Test
    @DisplayName("IPN and job at the same moment: one PAID, one ALREADY_APPLIED, one payment, markPaid once")
    void appliedOnce() throws Exception {
        long orderId = jdbc.queryForObject("""
                insert into orders (public_ref, season, sku, quantity, delivery_method, currency, subtotal_minor,
                                    total_minor, price_breakdown, status, quote_id, guest_email, created_at)
                values (?, 'FC27', 'TRADING_SERVICE', 1, 'PLAYER_AUCTION', 'EUR', 10000, 9225, '{}'::jsonb,
                        'AWAITING_PAYMENT', 'q_race', 'buyer@example.test', ?)
                returning id
                """, Long.class, REF, Timestamp.from(NOW.instant().minusSeconds(900)));
        UUID attemptUuid = UUID.randomUUID();
        long attemptId = jdbc.queryForObject("""
                insert into payop_invoice (order_id, attempt_id, invoice_id, status, method_id, method_name,
                                           method_version, fixed_eur, percent, fx_rate, fx_source, fx_date, currency,
                                           net_minor, fee_minor, total_minor, amount_sent, country, expires_at,
                                           created_at)
                values (?, ?, ?, 'OPEN', 381, 'Bank transfer', 1, 0.30, 4.0, 1, 'NONE', '2026-10-04', 'EUR',
                        9000, 407, 9407, '94.07', 'DE', ?, ?)
                returning id
                """, Long.class, orderId, attemptUuid, INVOICE, Timestamp.from(NOW.instant().plusSeconds(80_000)),
                Timestamp.from(NOW.instant().minusSeconds(600)));

        // Both callers get Payop's answer, then wait for each other before applying it.
        CyclicBarrier bothConfirmed = new CyclicBarrier(2);
        PayopClient client = mock(PayopClient.class);
        when(client.invoice(INVOICE)).thenReturn(new PayopClient.InvoiceInfo(1, TXID));
        when(client.transaction(TXID)).thenAnswer(inv -> {
            bothConfirmed.await(10, TimeUnit.SECONDS);
            return new PayopClient.Transaction(TXID, 2, null, REF, new BigDecimal("94.07"), "EUR",
                    attemptUuid.toString());
        });
        OrderService orderService = mock(OrderService.class);
        doAnswer(inv -> {
            OrderEntity paid = inv.getArgument(0);
            ReflectionTestUtils.setField(paid, "status", OrderStatus.PAID);
            return null;
        }).when(orderService).markPaid(any(OrderEntity.class), anyString());
        WebhookLedger ledger = mock(WebhookLedger.class);
        when(ledger.recordOrRetry(eq("PAYOP"), anyString(), anyString(), anyString())).thenReturn(Optional.of(1L));
        AppProperties props = mock(AppProperties.class);
        when(props.publicUrl()).thenReturn("https://globalfutservices.com");
        when(props.payop()).thenReturn(PayopStartupCheckTest.payop(true, "pub", "secret", "jwt", "606", null));

        PayopCallbackService callbacks = new PayopCallbackService(invoices, client, orders, orderService, payments,
                claims, ledger, mock(NotificationService.class), props, new ObjectMapper(), transactions, NOW);
        PayopReconciliation job = new PayopReconciliation(invoices, client, callbacks, mock(SchedulerLock.class),
                props, NOW);
        PayopCallbackService.Ipn ipn = new PayopCallbackService.Ipn(INVOICE, TXID, 2, 1, REF, attemptUuid.toString());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<PayopCallbackService.Outcome> fromIpn = pool.submit(() -> callbacks.handle(ipn));
            Future<List<PayopReconciliation.Checked>> fromJob = pool.submit(job::sweep);
            PayopCallbackService.Outcome a = fromIpn.get(30, TimeUnit.SECONDS);
            PayopCallbackService.Outcome b = fromJob.get(30, TimeUnit.SECONDS).get(0).outcome();

            assertThat(List.of(a, b)).containsExactlyInAnyOrder(PayopCallbackService.Outcome.PAID,
                    PayopCallbackService.Outcome.ALREADY_APPLIED);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("select count(*) from payment where provider = 'PAYOP' and provider_payment_id = ?",
                Long.class, TXID)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select status from payop_invoice where id = ?", String.class, attemptId))
                .isEqualTo("PAID");
        assertThat(jdbc.queryForObject("select status from orders where id = ?", String.class, orderId))
                .isEqualTo("PAID");
        verify(orderService, times(1)).markPaid(any(OrderEntity.class), anyString());
    }
}
