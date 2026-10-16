package com.globalfutservice.fulfilment;

import java.util.Map;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * V56 on orders already there: one an admin stopped before STOPPED existed (as GFS-26-ZTE3A759
 * was) becomes STOPPED, with who and when; and how each order's price ceiling came about.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StoppedBackfillMigrationPostgresTest {

    private String schema;
    private JdbcTemplate jdbc;

    @BeforeAll
    void migrate() {
        String url = System.getenv("GFS_TEST_PG_URL");
        assumeTrue(url != null && !url.isBlank(), "GFS_TEST_PG_URL not set: no database to test against");
        schema = "stopped_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        DriverManagerDataSource ds = new DriverManagerDataSource(url + (url.contains("?") ? "&" : "?")
                + "currentSchema=" + schema + ",public", System.getenv("GFS_TEST_PG_USER"),
                System.getenv("GFS_TEST_PG_PASSWORD"));
        jdbc = new JdbcTemplate(ds);

        flyway(ds, "55").migrate();
        long stopped = order("GFS-26-BACKF001", "vid-backf-01", "IN_DELIVERY", null, null, null);
        action(stopped, "RESUME", "DONE", "admin@example.test", "2026-10-15 16:10:00+00");
        action(stopped, "STOP", "DONE", "admin@example.test", "2026-10-15 16:21:00+00");
        action(stopped, "RESUME", "REFUSED", "admin@example.test", "2026-10-15 16:25:00+00");
        long resumed = order("GFS-26-BACKF002", "vid-backf-02", "IN_DELIVERY", null, null, null);
        action(resumed, "STOP", "DONE", "admin@example.test", "2026-10-15 15:00:00+00");
        action(resumed, "RESUME", "DONE", "admin@example.test", "2026-10-15 15:30:00+00");
        long refusedStop = order("GFS-26-BACKF003", "vid-backf-03", "IN_DELIVERY", null, null, null);
        action(refusedStop, "STOP", "REFUSED", "admin@example.test", "2026-10-15 15:00:00+00");
        long delivered = order("GFS-26-BACKF004", "vid-backf-04", "DELIVERED", null, null, null);
        action(delivered, "STOP", "DONE", "admin@example.test", "2026-10-15 15:00:00+00");
        order("GFS-26-PRICE001", "vid-price-01", "IN_DELIVERY", "4.40", "4.00", "10");
        order("GFS-26-PRICE002", "vid-price-02", "IN_DELIVERY", "4.10", "4.00", null);
        order("GFS-26-PRICE003", "vid-price-03", "IN_DELIVERY", "50", null, null);
        flyway(ds, "latest").migrate();
    }

    @AfterAll
    void dropSchema() {
        if (jdbc != null && schema != null) {
            jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    private Flyway flyway(DriverManagerDataSource ds, String target) {
        return Flyway.configure().dataSource(ds).schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration").target(target)
                .initSql("SET search_path TO " + schema + ", public").load();
    }

    private long order(String ref, String vid, String state, String threshold, String rate, String margin) {
        long id = jdbc.queryForObject("""
                insert into orders (public_ref, season, sku, quantity, delivery_method, currency, subtotal_minor,
                                    total_minor, price_breakdown, status, quote_id, guest_email)
                values (?, 'FC26', 'TRADING_SERVICE', 0.05, 'PLAYER_AUCTION', 'INR', 61715, 61715,
                        '{"quantity":0.05}'::jsonb, 'IN_PROGRESS', ?, 'buyer@example.test')
                returning id
                """, Long.class, ref, "q_" + ref);
        jdbc.update("""
                insert into vendor_order (order_id, external_ref, vendor_order_id, state, amount_ordered_k, submitted_at,
                                          order_mode, buy_now_threshold_sent, supplier_rate_sent, threshold_margin_pct,
                                          amount_delivered_k, vendor_status)
                values (?, ?, ?, ?, 50, now(), 'PUBLIC_POOL', ?::numeric, ?::numeric, ?::numeric, 28, 'partlyDelivered')
                """, id, ref, vid, state, threshold, rate, margin);
        return id;
    }

    private void action(long orderId, String action, String outcome, String who, String at) {
        jdbc.update("""
                insert into vendor_order_action (order_id, action, actor_label, outcome, created_at)
                values (?, ?, ?, ?, ?::timestamptz)
                """, orderId, action, who, outcome, at);
    }

    private Map<String, Object> row(String ref) {
        return jdbc.queryForMap("select * from vendor_order where external_ref = ?", ref);
    }

    @Test
    @DisplayName("stopped by an admin and not resumed since: STOPPED, with who and when; anything else as it was")
    void stoppedBackfilled() {
        Map<String, Object> zte = row("GFS-26-BACKF001");
        assertThat(zte.get("state")).isEqualTo("STOPPED");
        assertThat(zte.get("stopped_by")).isEqualTo("admin@example.test");
        assertThat(zte.get("stopped_at").toString()).contains("2026-10-15");
        assertThat(zte.get("last_error_code")).isEqualTo("STOPPED_BY_ADMIN");
        assertThat(zte.get("amount_delivered_k")).isEqualTo(28L);

        assertThat(row("GFS-26-BACKF002").get("state")).as("resumed since").isEqualTo("IN_DELIVERY");
        assertThat(row("GFS-26-BACKF003").get("state")).as("the stop was refused").isEqualTo("IN_DELIVERY");
        assertThat(row("GFS-26-BACKF004").get("state")).as("already delivered").isEqualTo("DELIVERED");
    }

    @Test
    @DisplayName("how each ceiling came about: the market, the Render override, or before automatic pricing")
    void priceSource() {
        assertThat(row("GFS-26-PRICE001").get("threshold_source")).isEqualTo("MARKET");
        assertThat(row("GFS-26-PRICE002").get("threshold_source")).isEqualTo("FIXED_OVERRIDE");
        assertThat(row("GFS-26-PRICE003").get("threshold_source")).isNull();
    }
}
