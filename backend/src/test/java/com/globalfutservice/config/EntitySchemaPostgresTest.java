package com.globalfutservice.config;

import java.util.Map;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Every entity, validated against the schema the migrations actually build.
 *
 * <p>Production runs with {@code ddl-auto: validate}, so an entity that does not match its
 * table stops the application at startup -- and the host then keeps the previous build
 * running, so the deploy looks as if it never arrived (BinaryColumnMappingTest tells how
 * that went once). There is no Spring test database to catch it. This builds Hibernate the
 * way Spring Boot does -- same naming strategies -- over a freshly migrated PostgreSQL schema
 * and lets it validate all of them.
 *
 * <p>Needs GFS_TEST_PG_URL, GFS_TEST_PG_USER and GFS_TEST_PG_PASSWORD; skipped without them.
 */
class EntitySchemaPostgresTest {

    private DriverManagerDataSource ds;
    private String schema;

    @AfterEach
    void drop() {
        if (ds != null) {
            new JdbcTemplate(ds).execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    @Test
    @DisplayName("Hibernate's startup validation passes for every entity against the migrated schema")
    void everyEntityMatchesItsTable() {
        String url = System.getenv("GFS_TEST_PG_URL");
        assumeTrue(url != null && !url.isBlank(), "GFS_TEST_PG_URL not set: no database to test against");
        schema = "entity_test_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        ds = new DriverManagerDataSource(url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
                System.getenv("GFS_TEST_PG_USER"), System.getenv("GFS_TEST_PG_PASSWORD"));
        Flyway.configure().dataSource(ds).schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration")
                .initSql("SET search_path TO " + schema + ", public").load().migrate();

        LocalContainerEntityManagerFactoryBean factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(ds);
        factory.setPackagesToScan("com.globalfutservice");
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setJpaPropertyMap(Map.of(
                "hibernate.hbm2ddl.auto", "validate",
                "hibernate.default_schema", schema,
                "hibernate.jdbc.time_zone", "UTC",
                // What Spring Boot sets, so names resolve exactly as they do in production.
                "hibernate.physical_naming_strategy",
                "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy",
                "hibernate.implicit_naming_strategy",
                "org.springframework.boot.orm.jpa.hibernate.SpringImplicitNamingStrategy"));
        assertThatCode(() -> {
            factory.afterPropertiesSet();
            factory.destroy();
        }).doesNotThrowAnyException();
    }
}
