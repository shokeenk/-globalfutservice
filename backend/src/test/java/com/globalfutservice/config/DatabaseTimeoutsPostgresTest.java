package com.globalfutservice.config;

import java.io.InputStream;
import java.util.Map;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.yaml.snakeyaml.Yaml;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The server-side timeouts in application.yml reach Postgres on every connection, so no
 * stuck statement, row lock or forgotten transaction can hold a pooled connection for
 * minutes (2026-10-23). Read from the file itself, so the test and the config cannot drift.
 */
class DatabaseTimeoutsPostgresTest {

    @SuppressWarnings("unchecked")
    private static Map<String, Object> hikari() throws Exception {
        try (InputStream in = DatabaseTimeoutsPostgresTest.class.getResourceAsStream("/application.yml")) {
            Map<String, Object> root = (Map<String, Object>) new Yaml().loadAll(in).iterator().next();
            Map<String, Object> spring = (Map<String, Object>) root.get("spring");
            Map<String, Object> datasource = (Map<String, Object>) spring.get("datasource");
            return (Map<String, Object>) datasource.get("hikari");
        }
    }

    @Test
    @DisplayName("statement 30 s, row-lock wait 10 s, idle in a transaction 60 s; leaks reported after 20 s")
    @SuppressWarnings("unchecked")
    void timeoutsReachTheServer() throws Exception {
        String url = System.getenv("GFS_TEST_PG_URL");
        assumeTrue(url != null && !url.isBlank(), "GFS_TEST_PG_URL not set: no database to test against");
        Map<String, Object> hikari = hikari();
        assertThat(hikari.get("leak-detection-threshold")).isEqualTo("${GFS_DB_LEAK_DETECTION_MS:20000}");
        String options = (String) ((Map<String, Object>) hikari.get("data-source-properties")).get("options");

        try (HikariDataSource pool = new HikariDataSource()) {
            pool.setJdbcUrl(url);
            pool.setUsername(System.getenv("GFS_TEST_PG_USER"));
            pool.setPassword(System.getenv("GFS_TEST_PG_PASSWORD"));
            pool.setMaximumPoolSize(1);
            pool.addDataSourceProperty("options", options);
            JdbcTemplate jdbc = new JdbcTemplate(pool);
            assertThat(jdbc.queryForObject("show statement_timeout", String.class)).isEqualTo("30s");
            assertThat(jdbc.queryForObject("show lock_timeout", String.class)).isEqualTo("10s");
            assertThat(jdbc.queryForObject("show idle_in_transaction_session_timeout", String.class)).isEqualTo("1min");
        }
    }
}
