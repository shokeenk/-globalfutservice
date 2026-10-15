package com.globalfutservice.fulfilment;

import java.sql.Array;
import java.util.List;
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
 * V51 on data as production has it: orders already waiting for review over a word we did
 * not know were alerted about that word, so it is recorded as alerted -- reading them again
 * after the deploy tells nobody twice.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UnknownWordsMigrationPostgresTest {

    private String schema;
    private DriverManagerDataSource ds;
    private JdbcTemplate jdbc;

    @BeforeAll
    void migrate() {
        String url = System.getenv("GFS_TEST_PG_URL");
        assumeTrue(url != null && !url.isBlank(), "GFS_TEST_PG_URL not set: no database to test against");
        schema = "words_test_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        ds = new DriverManagerDataSource(url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema + ",public",
                System.getenv("GFS_TEST_PG_USER"), System.getenv("GFS_TEST_PG_PASSWORD"));
        jdbc = new JdbcTemplate(ds);

        flyway("50").migrate();
        review("GFS-26-WORDS001", "UNKNOWN_STATUS", "transfersInProgress", "started", "transfersInProgress");
        review("GFS-26-WORDS002", "UNKNOWN_ECONOMY_STATE", "partlyDelivered", "finished", "SomeNewState");
        review("GFS-26-WORDS003", "STALLED", "partlyDelivered", "finished", "transfersInProgress");
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

    private void review(String ref, String code, String status, String accountCheck, String economyState) {
        long id = jdbc.queryForObject("""
                insert into orders (public_ref, season, sku, quantity, delivery_method, currency, subtotal_minor,
                                    total_minor, price_breakdown, status, quote_id, guest_email)
                values (?, 'FC26', 'TRADING_SERVICE', 0.05, 'PLAYER_AUCTION', 'INR', 100000, 100000,
                        '{"quantity":0.05}'::jsonb, 'IN_PROGRESS', ?, 'buyer@example.test')
                returning id
                """, Long.class, ref, "q_" + ref);
        jdbc.update("""
                insert into vendor_order (order_id, external_ref, vendor_order_id, state, amount_ordered_k, submitted_at,
                                          last_error_code, vendor_status, vendor_account_check, vendor_economy_state,
                                          order_mode)
                values (?, ?, ?, 'NEEDS_REVIEW', 50, now(), ?, ?, ?, ?, 'PUBLIC_POOL')
                """, id, ref, "vid-" + ref, code, status, accountCheck, economyState);
    }

    private List<String> alerted(String ref) throws Exception {
        Array words = jdbc.queryForObject("select unknown_words_alerted from vendor_order where external_ref = ?",
                Array.class, ref);
        return List.of((String[]) words.getArray());
    }

    @Test
    @DisplayName("the word each unknown-word review holds is recorded as alerted; any other review, nothing")
    void backfilled() throws Exception {
        assertThat(alerted("GFS-26-WORDS001")).containsExactly("status:transfersinprogress");
        assertThat(alerted("GFS-26-WORDS002")).containsExactly("economyState:somenewstate");
        assertThat(alerted("GFS-26-WORDS003")).isEmpty();
    }
}
