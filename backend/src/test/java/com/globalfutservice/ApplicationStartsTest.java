package com.globalfutservice;

import com.globalfutservice.admin.AdminOrderPaymentController;
import com.globalfutservice.payments.ResumePaymentService;
import com.globalfutservice.payments.payop.PayopStartToken;
import com.globalfutservice.payments.web.OrderPaymentController;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The whole application starts: every bean can be built and wired, and the migrations and
 * the entities agree.
 *
 * <p>Every other test builds a slice -- a service with its collaborators mocked, a
 * controller with its security. None of them would notice a bean Spring cannot build, and
 * one did reach main: a component with two constructors, neither marked, so Spring looked
 * for a no-argument one, found none, and the API refused to start on deploy. This is the
 * test that would have failed.
 *
 * <p>Against a fresh schema of the test database, with every outward channel left off (their
 * defaults) and dummy secrets. Closed after the class, so its scheduled jobs do not outlive
 * it into the rest of the run.
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfEnvironmentVariable(named = "GFS_TEST_PG_URL", matches = ".+")
class ApplicationStartsTest {

    private static final String SCHEMA = "boot_test_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        String url = System.getenv("GFS_TEST_PG_URL");
        // The schema first, then public, where the extensions the migrations use live.
        registry.add("spring.datasource.url",
                () -> url + (url.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA + ",public");
        registry.add("spring.datasource.username", () -> System.getenv("GFS_TEST_PG_USER"));
        registry.add("spring.datasource.password", () -> System.getenv("GFS_TEST_PG_PASSWORD"));
        registry.add("spring.flyway.schemas", () -> SCHEMA);
        registry.add("spring.flyway.default-schema", () -> SCHEMA);
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
        // Dummy values for the three settings with no default. Not secrets: nothing signs or
        // decrypts anything real in this test.
        registry.add("GFS_JWT_SECRET", () -> "test-only-jwt-secret-000000000000000000000000000000");
        registry.add("GFS_QUOTE_SECRET", () -> "test-only-quote-secret-00000000000000000000000000000");
        registry.add("GFS_CREDENTIAL_MASTER_KEY", () -> "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
    }

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("every bean is built and wired, the payment-resume ones included")
    void starts() {
        assertThat(context.getBean(PayopStartToken.class)).isNotNull();
        assertThat(context.getBean(ResumePaymentService.class)).isNotNull();
        assertThat(context.getBean(OrderPaymentController.class)).isNotNull();
        assertThat(context.getBean(AdminOrderPaymentController.class)).isNotNull();
    }

    @AfterAll
    static void drop() {
        String url = System.getenv("GFS_TEST_PG_URL");
        if (url == null || url.isBlank()) {
            return;
        }
        new JdbcTemplate(new DriverManagerDataSource(url, System.getenv("GFS_TEST_PG_USER"),
                System.getenv("GFS_TEST_PG_PASSWORD"))).execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
    }
}
