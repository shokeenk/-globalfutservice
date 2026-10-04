package com.globalfutservice.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.data.repository.Repository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Every entity, validated against the schema the migrations actually build, and every
 * repository's queries against the entities.
 *
 * <p>Production runs with {@code ddl-auto: validate}, so an entity that does not match its
 * table stops the application at startup -- and the host then keeps the previous build
 * running, so the deploy looks as if it never arrived (BinaryColumnMappingTest tells how
 * that went once). A repository method whose name does not parse, or whose JPQL names a
 * field that is not there, stops startup the same way. There is no Spring test database to
 * catch either. This builds Hibernate the way Spring Boot does -- same naming strategies --
 * over a freshly migrated PostgreSQL schema, lets it validate every entity, then creates
 * every Spring Data repository, which parses each derived query and compiles each JPQL one.
 *
 * <p>Needs GFS_TEST_PG_URL, GFS_TEST_PG_USER and GFS_TEST_PG_PASSWORD; skipped without them.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EntitySchemaPostgresTest {

    private DriverManagerDataSource ds;
    private String schema;

    @BeforeAll
    void migrate() {
        String url = System.getenv("GFS_TEST_PG_URL");
        assumeTrue(url != null && !url.isBlank(), "GFS_TEST_PG_URL not set: no database to test against");
        schema = "entity_test_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        ds = new DriverManagerDataSource(url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
                System.getenv("GFS_TEST_PG_USER"), System.getenv("GFS_TEST_PG_PASSWORD"));
        Flyway.configure().dataSource(ds).schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration")
                .initSql("SET search_path TO " + schema + ", public").load().migrate();
    }

    @AfterAll
    void drop() {
        if (ds != null) {
            new JdbcTemplate(ds).execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    private LocalContainerEntityManagerFactoryBean factory(String ddlAuto) {
        LocalContainerEntityManagerFactoryBean factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(ds);
        factory.setPackagesToScan("com.globalfutservice");
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setJpaPropertyMap(Map.of(
                "hibernate.hbm2ddl.auto", ddlAuto,
                "hibernate.default_schema", schema,
                "hibernate.jdbc.time_zone", "UTC",
                // What Spring Boot sets, so names resolve exactly as they do in production.
                "hibernate.physical_naming_strategy",
                "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy",
                "hibernate.implicit_naming_strategy",
                "org.springframework.boot.orm.jpa.hibernate.SpringImplicitNamingStrategy"));
        return factory;
    }

    @Test
    @DisplayName("Hibernate's startup validation passes for every entity against the migrated schema")
    void everyEntityMatchesItsTable() {
        LocalContainerEntityManagerFactoryBean factory = factory("validate");
        assertThatCode(() -> {
            factory.afterPropertiesSet();
            factory.destroy();
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("every repository can be created: each derived query parses and each JPQL query compiles")
    void everyRepositoryQueryCompiles() throws Exception {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false) {
            @Override
            protected boolean isCandidateComponent(AnnotatedBeanDefinition definition) {
                return definition.getMetadata().isInterface();
            }
        };
        scanner.addIncludeFilter(new AssignableTypeFilter(Repository.class));
        List<Class<?>> repositories = new ArrayList<>();
        for (BeanDefinition definition : scanner.findCandidateComponents("com.globalfutservice")) {
            repositories.add(Class.forName(definition.getBeanClassName()));
        }
        assertThat(repositories).as("the scan should find the application's repositories").hasSizeGreaterThan(20);

        LocalContainerEntityManagerFactoryBean factory = factory("none");
        factory.afterPropertiesSet();
        EntityManagerFactory emf = factory.getObject();
        EntityManager em = emf.createEntityManager();
        try {
            JpaRepositoryFactory repositoryFactory = new JpaRepositoryFactory(em);
            List<String> broken = new ArrayList<>();
            for (Class<?> type : repositories) {
                try {
                    repositoryFactory.getRepository(type);
                } catch (RuntimeException e) {
                    Throwable root = e;
                    while (root.getCause() != null && root.getCause() != root) {
                        root = root.getCause();
                    }
                    broken.add(type.getSimpleName() + ": " + root.getMessage());
                }
            }
            assertThat(broken).as("repositories whose queries would stop the application starting").isEmpty();
        } finally {
            em.close();
            factory.destroy();
        }
    }
}
