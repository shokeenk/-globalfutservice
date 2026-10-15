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

/** V53 on links already stored: the code is read off each link's path, so the status page can be built for it. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PartnerLinkPartsMigrationPostgresTest {

    private String schema;
    private DriverManagerDataSource ds;
    private JdbcTemplate jdbc;

    @BeforeAll
    void migrate() {
        String url = System.getenv("GFS_TEST_PG_URL");
        assumeTrue(url != null && !url.isBlank(), "GFS_TEST_PG_URL not set: no database to test against");
        schema = "link_parts_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        ds = new DriverManagerDataSource(url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema + ",public",
                System.getenv("GFS_TEST_PG_USER"), System.getenv("GFS_TEST_PG_PASSWORD"));
        jdbc = new JdbcTemplate(ds);

        flyway("52").migrate();
        stored("GFS-26-PARTS001", "https://transferstatus.top/zz9911aa");
        stored("GFS-26-PARTS002", "https://transferstatus.top/abc12345?lang=de");
        stored("GFS-26-PARTS003", "https://transferstatus.top/");
        flyway("latest").migrate();
    }

    @AfterAll
    void dropSchema() {
        if (jdbc != null && schema != null) {
            jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    private Flyway flyway(String target) {
        return Flyway.configure().dataSource(ds).schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration").target(target)
                .initSql("SET search_path TO " + schema + ", public").load();
    }

    private void stored(String ref, String link) {
        long id = jdbc.queryForObject("""
                insert into orders (public_ref, season, sku, quantity, delivery_method, currency, subtotal_minor,
                                    total_minor, price_breakdown, status, quote_id, guest_email)
                values (?, 'FC26', 'TRADING_SERVICE', 0.05, 'PLAYER_AUCTION', 'INR', 100000, 100000,
                        '{"quantity":0.05}'::jsonb, 'IN_PROGRESS', ?, 'buyer@example.test')
                returning id
                """, Long.class, ref, "q_" + ref);
        jdbc.update("""
                insert into vendor_order (order_id, external_ref, vendor_order_id, state, amount_ordered_k, submitted_at,
                                          order_mode, tracking_url, tracking_url_at, tracking_link_state)
                values (?, ?, ?, 'IN_DELIVERY', 50, now(), 'PUBLIC_POOL', ?, now(), 'STORED')
                """, id, ref, "vid-" + ref, link);
    }

    private Map<String, Object> row(String ref) {
        return jdbc.queryForMap("select * from vendor_order where external_ref = ?", ref);
    }

    @Test
    @DisplayName("the code from each stored link's path; the link itself unchanged; none where the path has none")
    void backfilled() {
        assertThat(row("GFS-26-PARTS001").get("tracking_short_code")).isEqualTo("zz9911aa");
        assertThat(row("GFS-26-PARTS002").get("tracking_short_code")).isEqualTo("abc12345");
        assertThat(row("GFS-26-PARTS002").get("tracking_url")).isEqualTo("https://transferstatus.top/abc12345?lang=de");
        assertThat(row("GFS-26-PARTS003").get("tracking_short_code")).isNull();
    }
}
