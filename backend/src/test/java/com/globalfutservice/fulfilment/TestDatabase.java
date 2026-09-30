package com.globalfutservice.fulfilment;

import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A throwaway schema on a real PostgreSQL, migrated with the application's own migrations.
 *
 * <p>Needs {@code GFS_TEST_PG_URL}, {@code GFS_TEST_PG_USER} and {@code GFS_TEST_PG_PASSWORD};
 * the test is skipped without them. The schema is randomly named and dropped by
 * {@link #drop()}, so it never touches the application's own tables.
 */
final class TestDatabase {

    final String schema;
    final DriverManagerDataSource ds;
    final JdbcTemplate jdbc;
    final NamedParameterJdbcTemplate named;

    private TestDatabase(String schema, DriverManagerDataSource ds) {
        this.schema = schema;
        this.ds = ds;
        this.jdbc = new JdbcTemplate(ds);
        this.named = new NamedParameterJdbcTemplate(ds);
    }

    static TestDatabase migrated() {
        String url = System.getenv("GFS_TEST_PG_URL");
        assumeTrue(url != null && !url.isBlank(), "GFS_TEST_PG_URL not set: no database to test against");
        String schema = "vendor_test_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        DriverManagerDataSource ds = new DriverManagerDataSource(
                url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema + ",public",
                System.getenv("GFS_TEST_PG_USER"), System.getenv("GFS_TEST_PG_PASSWORD"));
        Flyway.configure().dataSource(ds).schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration")
                .initSql("SET search_path TO " + schema + ", public").load().migrate();
        return new TestDatabase(schema, ds);
    }

    void drop() {
        jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }

    /** A paid coin order, as checkout leaves it. */
    long order(String ref, String status, String quantity) {
        return jdbc.queryForObject("""
                insert into orders (public_ref, season, sku, quantity, delivery_method, currency, subtotal_minor,
                                    total_minor, price_breakdown, status, quote_id, guest_email)
                values (?, 'FC26', 'TRADING_SERVICE', ?::numeric, 'PLAYER_AUCTION', 'INR', 100000, 100000, ?::jsonb,
                        ?, ?, 'buyer@example.test')
                returning id
                """, Long.class, ref, quantity, "{\"quantity\":" + quantity + "}", status, "q_" + ref);
    }

    /** A vendor order in the given state, as if sent earlier. */
    void vendorOrder(long orderId, String ref, String vendorId, String state, long amountK) {
        jdbc.update("""
                insert into vendor_order (order_id, external_ref, vendor_order_id, state, amount_ordered_k,
                                          submitted_at, last_progress_at)
                values (?, ?, ?, ?, ?, now(), now())
                """, orderId, ref, vendorId, state, amountK);
    }
}
