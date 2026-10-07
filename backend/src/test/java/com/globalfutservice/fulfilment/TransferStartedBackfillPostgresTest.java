package com.globalfutservice.fulfilment;

import java.util.Map;
import java.util.UUID;

import com.globalfutservice.notify.NotificationService;
import com.globalfutservice.orders.OrderRepository;
import com.globalfutservice.orders.OrderService;
import com.globalfutservice.scheduling.SchedulerLock;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Deploying the transfer-started email sends nobody one about a transfer that began -- or ended
 * -- before it existed: V43 marks every order already at the partner as told.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TransferStartedBackfillPostgresTest {

    private String schema;
    private DriverManagerDataSource ds;
    private JdbcTemplate jdbc;

    @BeforeAll
    void migrate() {
        String url = System.getenv("GFS_TEST_PG_URL");
        assumeTrue(url != null && !url.isBlank(), "GFS_TEST_PG_URL not set: no database to test against");
        schema = "started_test_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        ds = new DriverManagerDataSource(url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema + ",public",
                System.getenv("GFS_TEST_PG_USER"), System.getenv("GFS_TEST_PG_PASSWORD"));
        jdbc = new JdbcTemplate(ds);
        flyway("42").migrate();
        // As production stands before V43: one order long delivered, one still at the partner, one not sent.
        order("GFS-26-OLDDONE1", "DELIVERED", true);
        order("GFS-26-OLDRUN01", "IN_PROGRESS", true);
        order("GFS-26-NOTSENT1", "READY_FOR_DELIVERY", false);
        flyway("latest").migrate();
    }

    private Flyway flyway(String target) {
        return Flyway.configure().dataSource(ds).schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration").target(target)
                .initSql("SET search_path TO " + schema + ", public").load();
    }

    private void order(String ref, String status, boolean atPartner) {
        long id = jdbc.queryForObject("""
                insert into orders (public_ref, season, sku, quantity, delivery_method, currency, subtotal_minor,
                                    total_minor, price_breakdown, status, quote_id, guest_email)
                values (?, 'FC26', 'TRADING_SERVICE', 0.5, 'PLAYER_AUCTION', 'INR', 100000, 100000,
                        '{"quantity":0.5}'::jsonb, ?, ?, 'buyer@example.test')
                returning id
                """, Long.class, ref, status, "q_" + ref);
        if (atPartner) {
            jdbc.update("""
                    insert into vendor_order (order_id, external_ref, state, amount_ordered_k, attempts, order_mode,
                                              vendor_order_id, submitted_at)
                    values (?, ?, 'SUBMITTED', 500, 1, 'OWN_SENDERS', ?, now() - interval '3 days')
                    """, id, ref, "vid-" + ref);
        }
    }

    @AfterAll
    void drop() {
        if (jdbc != null && schema != null) {
            jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    private Map<String, Object> row(String ref) {
        return jdbc.queryForMap("select transfer_started_at, transfer_notice_at from orders where public_ref = ?", ref);
    }

    @Test
    @DisplayName("orders already at the partner are marked started and told; the sweep then sends nothing")
    void backfilledAsTold() {
        assertThat(row("GFS-26-OLDDONE1").get("transfer_started_at")).isNotNull();
        assertThat(row("GFS-26-OLDDONE1").get("transfer_notice_at")).isNotNull();
        assertThat(row("GFS-26-OLDRUN01").get("transfer_notice_at")).isNotNull();
        assertThat(row("GFS-26-NOTSENT1").get("transfer_started_at")).isNull();

        NotificationService notifications = mock(NotificationService.class);
        TransferStartedNotices notices = new TransferStartedNotices(new NamedParameterJdbcTemplate(ds),
                mock(OrderRepository.class), mock(OrderService.class), notifications, mock(SchedulerLock.class));
        assertThat(notices.sendDue()).isZero();
        verify(notifications, never()).transferStarted(any());
    }
}
