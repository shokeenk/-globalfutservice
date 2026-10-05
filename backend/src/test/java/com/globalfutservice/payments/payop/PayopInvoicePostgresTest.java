package com.globalfutservice.payments.payop;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import com.globalfutservice.orders.OrderEntity;
import com.globalfutservice.orders.OrderRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * What only the database can promise, against a real PostgreSQL: one active Payop attempt
 * per order however two requests race, when an order's invoice stops being payable, and
 * that the abandoned-checkout sweep leaves an order alone while it is.
 *
 * <p>Runs when GFS_TEST_PG_URL, GFS_TEST_PG_USER and GFS_TEST_PG_PASSWORD are set; skipped
 * otherwise. A fresh, randomly named schema each run, dropped afterwards.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PayopInvoicePostgresTest {

    private static final Instant NOW = Instant.parse("2026-10-04T12:00:00Z");

    private String schema;
    private DriverManagerDataSource ds;
    private JdbcTemplate jdbc;
    private LocalContainerEntityManagerFactoryBean factory;
    private EntityManager em;
    private PayopInvoiceRepository invoices;
    private OrderRepository orders;

    @BeforeAll
    void migrate() {
        String url = System.getenv("GFS_TEST_PG_URL");
        assumeTrue(url != null && !url.isBlank(), "GFS_TEST_PG_URL not set: no database to test against");
        schema = "payop_test_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        ds = new DriverManagerDataSource(url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
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
        em = factory.getObject().createEntityManager();
        JpaRepositoryFactory repositories = new JpaRepositoryFactory(em);
        invoices = repositories.getRepository(PayopInvoiceRepository.class);
        orders = repositories.getRepository(OrderRepository.class);
    }

    @AfterAll
    void drop() {
        if (em != null) {
            em.close();
            factory.destroy();
        }
        if (jdbc != null) {
            jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    private long order(String ref, String status, Instant createdAt) {
        return jdbc.queryForObject("""
                insert into orders (public_ref, season, sku, quantity, delivery_method, currency, subtotal_minor,
                                    total_minor, price_breakdown, status, quote_id, guest_email, created_at)
                values (?, 'FC27', 'TRADING_SERVICE', 1, 'PLAYER_AUCTION', 'EUR', 10000, 9225, '{}'::jsonb, ?, ?,
                        'buyer@example.test', ?)
                returning id
                """, Long.class, ref, status, "q_" + ref, Timestamp.from(createdAt));
    }

    private long invoice(long orderId, String status, String invoiceId, Instant expiresAt) {
        return jdbc.queryForObject("""
                insert into payop_invoice (order_id, attempt_id, invoice_id, status, method_id, method_name,
                                           method_version, fixed_eur, percent, fx_rate, fx_source, fx_date, currency,
                                           net_minor, fee_minor, total_minor, amount_sent, country, expires_at)
                values (?, ?, ?, ?, 381, 'Bank transfer', 1, 0.30, 4.0, 1, 'NONE', '2026-10-04', 'EUR',
                        9000, 407, 9407, '94.07', 'DE', ?)
                returning id
                """, Long.class, orderId, UUID.randomUUID(), invoiceId, status, Timestamp.from(expiresAt));
    }

    @Test
    @DisplayName("two active attempts for one order cannot both exist, whichever request gets there first")
    void oneActivePerOrder() {
        long o = order("GFS-26-RACE0001", "AWAITING_PAYMENT", NOW);
        invoice(o, "OPEN", "inv-race-1", NOW.plus(1, ChronoUnit.DAYS));
        assertThatThrownBy(() -> invoice(o, "CREATING", null, NOW.plus(1, ChronoUnit.DAYS)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("payop_invoice_one_active_uk");
        // Inactive ones are not limited: an order can have many past attempts.
        invoice(o, "EXPIRED", "inv-race-2", NOW.plus(1, ChronoUnit.DAYS));
        invoice(o, "FAILED", null, NOW.plus(1, ChronoUnit.DAYS));
    }

    @Test
    @DisplayName("an attempt's total must be its price plus its fee")
    void totalAddsUp() {
        long o = order("GFS-26-SUMS0001", "AWAITING_PAYMENT", NOW);
        assertThatThrownBy(() -> jdbc.update("""
                insert into payop_invoice (order_id, attempt_id, status, method_id, method_name, method_version,
                                           fixed_eur, percent, fx_rate, fx_source, fx_date, currency, net_minor,
                                           fee_minor, total_minor, amount_sent, expires_at)
                values (?, ?, 'OPEN', 381, 'x', 1, 0.30, 4.0, 1, 'NONE', '2026-10-04', 'EUR', 9000, 407, 9000,
                        '90.00', now())
                """, o, UUID.randomUUID())).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("payable until the latest unexpired invoice Payop holds, whatever its status here")
    void payableUntil() {
        Instant later = NOW.plus(20, ChronoUnit.HOURS);
        long open = order("GFS-26-PAYB0001", "AWAITING_PAYMENT", NOW);
        invoice(open, "OPEN", "inv-pay-1", later);
        long replaced = order("GFS-26-PAYB0002", "AWAITING_PAYMENT", NOW);
        invoice(replaced, "EXPIRED", "inv-pay-2", later);      // replaced by another method: still payable
        long neverMade = order("GFS-26-PAYB0003", "AWAITING_PAYMENT", NOW);
        invoice(neverMade, "FAILED", null, later);              // Payop never created it
        long creating = order("GFS-26-PAYB0004", "AWAITING_PAYMENT", NOW);
        invoice(creating, "CREATING", null, later);              // may be about to exist
        long lapsed = order("GFS-26-PAYB0005", "AWAITING_PAYMENT", NOW);
        invoice(lapsed, "OPEN", "inv-pay-5", NOW.minusSeconds(1));

        PayopInvoiceEntity.Status c = PayopInvoiceEntity.Status.CREATING;
        assertThat(invoices.payableUntil(open, NOW, c)).hasValueSatisfying(t -> assertThat(t).isEqualTo(later));
        assertThat(invoices.payableUntil(replaced, NOW, c)).isPresent();
        assertThat(invoices.payableUntil(neverMade, NOW, c)).isEmpty();
        assertThat(invoices.payableUntil(creating, NOW, c)).isPresent();
        assertThat(invoices.payableUntil(lapsed, NOW, c)).isEmpty();
        assertThat(invoices.findByStatusAndExpiresAtLessThanEqual(PayopInvoiceEntity.Status.OPEN, NOW))
                .extracting(PayopInvoiceEntity::getInvoiceId).contains("inv-pay-5").doesNotContain("inv-pay-1");
    }

    @Test
    @DisplayName("the abandoned-checkout sweep leaves an order alone while a Payop invoice for it is payable")
    void sweepWaits() {
        Instant old = NOW.minus(3, ChronoUnit.DAYS);
        long plain = order("GFS-26-SWEP0001", "AWAITING_PAYMENT", old);
        long withInvoice = order("GFS-26-SWEP0002", "AWAITING_PAYMENT", old);
        invoice(withInvoice, "OPEN", "inv-swep-2", NOW.plus(5, ChronoUnit.HOURS));
        long lapsedInvoice = order("GFS-26-SWEP0003", "AWAITING_PAYMENT", old);
        invoice(lapsedInvoice, "EXPIRED", "inv-swep-3", NOW.minus(1, ChronoUnit.HOURS));

        List<Long> stale = orders.findStaleUnpaid(NOW.minus(48, ChronoUnit.HOURS), NOW,
                PayopInvoiceEntity.Status.CREATING).stream().map(OrderEntity::getId).toList();
        assertThat(stale).contains(plain, lapsedInvoice).doesNotContain(withInvoice);
    }
}
