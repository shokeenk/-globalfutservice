package com.globalfutservice.fulfilment;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.globalfutservice.config.AppProperties;
import com.globalfutservice.credentials.CredentialVaultService;
import com.globalfutservice.domain.catalog.Platform;
import com.globalfutservice.domain.catalog.Sku;
import com.globalfutservice.fulfilment.FakeFutTransfer.Reply;
import com.globalfutservice.fulfilment.SupplierFulfilmentService.Result;
import com.globalfutservice.notify.NotificationService;
import com.globalfutservice.orders.OrderEntity;
import com.globalfutservice.orders.OrderRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The guarantee itself, against a real PostgreSQL: the unique row in {@code vendor_order}
 * is what stops an order being sent twice, and here nothing stands in for it.
 *
 * <p>Runs when {@code GFS_TEST_PG_URL}, {@code GFS_TEST_PG_USER} and
 * {@code GFS_TEST_PG_PASSWORD} point at a database the test may create a schema in; it is
 * skipped otherwise. Every run migrates a fresh, randomly named schema with the real
 * migrations and drops it afterwards, so it never touches the application's own tables.
 * The vendor is a local HTTP server; no real order is ever placed.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class VendorDispatchPostgresTest {

    /**
     * Each test that is accepted gets its own vendor id: they share one schema, and the
     * unique constraint on the vendor's id rightly refuses one id for two orders.
     */
    private static final String RACE_ID = "bbbbbbbb-0000-0000-0000-000000000001";

    private String schema;
    private DriverManagerDataSource ds;
    private JdbcTemplate jdbc;
    private FakeFutTransfer vendor;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeAll
    void migrate() {
        String url = System.getenv("GFS_TEST_PG_URL");
        assumeTrue(url != null && !url.isBlank(), "GFS_TEST_PG_URL not set: no database to prove the guard against");
        schema = "vendor_test_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        ds = new DriverManagerDataSource(url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema + ",public",
                System.getenv("GFS_TEST_PG_USER"), System.getenv("GFS_TEST_PG_PASSWORD"));
        jdbc = new JdbcTemplate(ds);

        // Up to the migration before this one, then orders as they stood before it...
        flyway("34").migrate();
        seedLegacyOrders();
        // ...then this migration, backfill and all.
        flyway("latest").migrate();
    }

    private Flyway flyway(String target) {
        return Flyway.configure().dataSource(ds).schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration").target(target)
                .initSql("SET search_path TO " + schema + ", public").load();
    }

    @AfterAll
    void dropSchema() {
        if (jdbc != null && schema != null) {
            jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    @BeforeEach
    void startVendor() throws Exception {
        vendor = new FakeFutTransfer();
    }

    @AfterEach
    void stopVendor() {
        vendor.close();
    }

    // --------------------------------------------------------------- fixtures ---

    private long insertOrder(String ref, String sku, String status, String quantity) {
        String delivered = "DELIVERED".equals(status) || "COMPLETED".equals(status) ? "now()" : "null";
        return jdbc.queryForObject("""
                insert into orders (public_ref, season, sku, quantity, delivery_method, currency, subtotal_minor,
                                    total_minor, price_breakdown, status, quote_id, guest_email,
                                    delivered_at, guarantee_expires_at)
                values (?, 'FC26', ?, ?::numeric, ?, 'INR', 100000, 100000, ?::jsonb, ?, ?, 'buyer@example.test',
                        %s, %s)
                returning id
                """.formatted(delivered, "null".equals(delivered) ? "null" : "now() + interval '7 days'"),
                Long.class, ref, sku, quantity,
                "COACHING".equals(sku) ? "SCHEDULED_SESSION" : "PLAYER_AUCTION",
                "{\"quantity\":" + quantity + "}", status, "q_" + ref);
    }

    private void seedLegacyOrders() {
        long sent = insertOrder("GFS-26-LEGACYSENT", "TRADING_SERVICE", "IN_PROGRESS", "1.0");
        jdbc.update("update orders set supplier_order_id = 'SUP-LEGACY', supplier_dispatch_attempts = 1 where id = ?", sent);
        long lost = insertOrder("GFS-26-LEGACYLOST", "TRADING_SERVICE", "READY_FOR_DELIVERY", "0.5");
        jdbc.update("update orders set supplier_dispatch_attempts = 2 where id = ?", lost);
        long done = insertOrder("GFS-26-LEGACYDONE", "TRADING_SERVICE", "DELIVERED", "0.3");
        jdbc.update("update orders set supplier_order_id = 'SUP-DONE', supplier_dispatch_attempts = 1 where id = ?", done);
        insertOrder("GFS-26-LEGACYCOACH", "COACHING", "PAID", "1");
    }

    private OrderEntity order(long id, String ref, String quantity) {
        OrderEntity o = mock(OrderEntity.class);
        when(o.getId()).thenReturn(id);
        when(o.getPublicRef()).thenReturn(ref);
        when(o.getSku()).thenReturn(Sku.TRADING_SERVICE);
        when(o.getPlatform()).thenReturn(Platform.PLAYSTATION);
        when(o.getQuantity()).thenReturn(new BigDecimal(quantity));
        when(o.getPriceBreakdown()).thenReturn("{\"quantity\":" + quantity + "}");
        return o;
    }

    /** One application instance: its own client and ledger, the shared database. */
    private SupplierFulfilmentService instance() {
        AppProperties props = VendorTestSupport.props(vendor.baseUrl(), Duration.ofMillis(800));
        CredentialVaultService vault = mock(CredentialVaultService.class);
        when(vault.reveal(anyLong(), any())).thenReturn(VendorTestSupport.signIn());
        VendorControl control = VendorTestSupport.running();
        return new SupplierFulfilmentService(new FutTransferClient(props, mapper, control,
                new VendorCallLog(new NamedParameterJdbcTemplate(ds))).withoutRetryPauses(), control, vault,
                new VendorOrderLedger(new NamedParameterJdbcTemplate(ds)), mock(NotificationService.class), props, mapper);
    }

    private Map<String, Object> row(long orderId) {
        return jdbc.queryForMap("select * from vendor_order where order_id = ?", orderId);
    }

    // ----------------------------------------------------------------- tests ---

    @Test
    @DisplayName("V35 carries orders already at the vendor, and sends unexplained earlier attempts to review")
    void backfill() {
        Map<String, Object> sent = jdbc.queryForMap("select v.* from vendor_order v join orders o on o.id = v.order_id where o.public_ref = 'GFS-26-LEGACYSENT'");
        assertThat(sent.get("state")).isEqualTo("SUBMITTED");
        assertThat(sent.get("vendor_order_id")).isEqualTo("SUP-LEGACY");
        assertThat(sent.get("amount_ordered_k")).isEqualTo(1000L);

        Map<String, Object> lost = jdbc.queryForMap("select v.* from vendor_order v join orders o on o.id = v.order_id where o.public_ref = 'GFS-26-LEGACYLOST'");
        assertThat(lost.get("state")).isEqualTo("NEEDS_REVIEW");
        assertThat(lost.get("last_error_code")).isEqualTo("EARLIER_ATTEMPT_UNKNOWN");
        assertThat(lost.get("attempts")).isEqualTo(2);

        assertThat(jdbc.queryForObject("select v.state from vendor_order v join orders o on o.id = v.order_id where o.public_ref = 'GFS-26-LEGACYDONE'", String.class))
                .isEqualTo("DELIVERED");
        assertThat(jdbc.queryForObject("select count(*) from vendor_order v join orders o on o.id = v.order_id where o.sku = 'COACHING'", Integer.class))
                .isZero();
    }

    @Test
    @DisplayName("double clicks, concurrent admins and two instances: exactly one /orderAPI call")
    void concurrentApprovals() throws Exception {
        long id = insertOrder("GFS-26-RACE0001", "TRADING_SERVICE", "READY_FOR_DELIVERY", "2.0");
        // Slow enough that every caller arrives while the first is still waiting on the vendor.
        vendor.on("/orderAPI", new Reply(200, FakeFutTransfer.accepted(RACE_ID), 400));
        List<SupplierFulfilmentService> instances = List.of(instance(), instance());

        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<SupplierFulfilmentService.Release>> results = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            SupplierFulfilmentService app = instances.get(i % 2);
            results.add(pool.submit(() -> {
                go.await();
                return app.approveAndDispatch(order(id, "GFS-26-RACE0001", "2.0"), 1L);
            }));
        }
        go.countDown();
        List<Result> outcomes = new ArrayList<>();
        for (Future<SupplierFulfilmentService.Release> f : results) {
            outcomes.add(f.get(10, TimeUnit.SECONDS).result());
        }
        pool.shutdown();

        assertThat(vendor.calls("/orderAPI")).isEqualTo(1);
        assertThat(outcomes).containsOnlyOnce(Result.SUBMITTED);
        assertThat(outcomes).allMatch(r -> r == Result.SUBMITTED || r == Result.IN_FLIGHT || r == Result.ALREADY_SUBMITTED);
        assertThat(jdbc.queryForList("select result || ' ' || coalesce(vendor_order_id, '-') from vendor_call "
                + "where endpoint = '/orderAPI' and 'GFS-26-RACE0001' = any(order_refs)", String.class))
                .containsExactly("ACCEPTED " + RACE_ID);
        assertThat(row(id).get("state")).isEqualTo("SUBMITTED");
        assertThat(row(id).get("vendor_order_id")).isEqualTo(RACE_ID);
        assertThat(jdbc.queryForObject("select supplier_order_id from orders where id = ?", String.class, id))
                .isEqualTo(RACE_ID);
        assertThat(jdbc.queryForObject("select version from orders where id = ?", Long.class, id)).isEqualTo(1L);

        // And once it is there, another click sends nothing.
        assertThat(instance().approveAndDispatch(order(id, "GFS-26-RACE0001", "2.0"), 1L).result())
                .isEqualTo(Result.ALREADY_SUBMITTED);
        assertThat(vendor.calls("/orderAPI")).isEqualTo(1);
    }

    @Test
    @DisplayName("a redeploy mid-request leaves SUBMITTING behind, and the next Approve sends nothing")
    void crashedMidRequest() {
        long id = insertOrder("GFS-26-CRASH001", "TRADING_SERVICE", "READY_FOR_DELIVERY", "1.0");
        jdbc.update("insert into vendor_order (order_id, external_ref, state, amount_ordered_k, order_mode) values (?, 'GFS-26-CRASH001', 'SUBMITTING', 1000, 'OWN_SENDERS')", id);
        vendor.on("/orderAPI", Reply.ok(FakeFutTransfer.ORDER_ACCEPTED));

        assertThat(instance().approveAndDispatch(order(id, "GFS-26-CRASH001", "1.0"), 1L).result())
                .isEqualTo(Result.IN_FLIGHT);
        assertThat(vendor.requests()).isEmpty();
    }

    @Test
    @DisplayName("timeout and no confirmation: needs review, and every later Approve sends nothing")
    void timeoutNotConfirmed() {
        long id = insertOrder("GFS-26-TIMEOUT1", "TRADING_SERVICE", "READY_FOR_DELIVERY", "0.5");
        vendor.on("/orderAPI", Reply.slow(2_000));
        vendor.on("/orderStatusAPI", Reply.of(404, "notFound"));

        assertThat(instance().approveAndDispatch(order(id, "GFS-26-TIMEOUT1", "0.5"), 1L).result())
                .isEqualTo(Result.NEEDS_REVIEW);
        assertThat(row(id).get("state")).isEqualTo("NEEDS_REVIEW");
        assertThat(row(id).get("last_error_code")).isEqualTo("TIMEOUT");

        assertThat(instance().approveAndDispatch(order(id, "GFS-26-TIMEOUT1", "0.5"), 1L).result())
                .isEqualTo(Result.NOT_SENT);
        assertThat(vendor.calls("/orderAPI")).isEqualTo(1);
    }

    @Test
    @DisplayName("every attempt is recorded: endpoint, status, typed result, our reference, timing, no body")
    void callsRecorded() {
        long id = insertOrder("GFS-26-AUDIT001", "TRADING_SERVICE", "READY_FOR_DELIVERY", "0.5");
        vendor.on("/orderAPI", Reply.slow(2_000));
        vendor.on("/orderStatusAPI", Reply.of(404, "notFound"));

        instance().approveAndDispatch(order(id, "GFS-26-AUDIT001", "0.5"), 1L);

        List<Map<String, Object>> calls = jdbc.queryForList("""
                select endpoint, domain, http_status, result, error_code, duration_ms
                  from vendor_call where 'GFS-26-AUDIT001' = any(order_refs) order by id
                """);
        assertThat(calls).extracting(c -> c.get("endpoint") + " " + c.get("http_status") + " " + c.get("result")
                        + " " + c.get("error_code"))
                .containsExactly(
                        "/getCooldownStatus 200 OK null",
                        "/orderAPI null UNCERTAIN TIMEOUT",
                        "/orderStatusAPI 404 NEEDS_REVIEW notFound");
        assertThat(calls).allMatch(c -> "PRIMARY".equals(c.get("domain")));
        assertThat((Integer) calls.get(1).get("duration_ms")).isGreaterThanOrEqualTo(700);

        // The order's timeline reads the same rows.
        assertThat(new VendorCallLog(new NamedParameterJdbcTemplate(ds)).forOrder("GFS-26-AUDIT001"))
                .extracting(VendorCallLog.Call::endpoint)
                .containsExactly("/getCooldownStatus", "/orderAPI", "/orderStatusAPI");
    }

    @Test
    @DisplayName("timeout and a lookup that finds it: submitted, polled, and never sent again")
    void timeoutConfirmed() {
        long id = insertOrder("GFS-26-TIMEOUT2", "TRADING_SERVICE", "READY_FOR_DELIVERY", "0.5");
        vendor.on("/orderAPI", Reply.slow(2_000));
        vendor.on("/orderStatusAPI", Reply.ok(FakeFutTransfer.status("GFS-26-TIMEOUT2", 500)));

        assertThat(instance().approveAndDispatch(order(id, "GFS-26-TIMEOUT2", "0.5"), 1L).result())
                .isEqualTo(Result.SUBMITTED);
        assertThat(row(id).get("state")).isEqualTo("SUBMITTED");
        assertThat(row(id).get("vendor_amount_ordered_k")).isEqualTo(500L);

        // No vendor id, but the poller still finds it.
        assertThat(new VendorOrderLedger(new NamedParameterJdbcTemplate(ds)).openForPolling())
                .extracting(VendorOrderLedger.PollRow::orderId).contains(id);
        assertThat(vendor.calls("/orderAPI")).isEqualTo(1);
    }

    @Test
    @DisplayName("a definite refusal can be approved again, once, reusing the same row")
    void retryAfterRefusal() {
        long id = insertOrder("GFS-26-RETRY001", "TRADING_SERVICE", "READY_FOR_DELIVERY", "1.0");
        vendor.on("/orderAPI", Reply.of(400, "InvalidAmount"));
        assertThat(instance().approveAndDispatch(order(id, "GFS-26-RETRY001", "1.0"), 1L).result())
                .isEqualTo(Result.FAILED);
        assertThat(row(id).get("state")).isEqualTo("FAILED");

        vendor.on("/orderAPI", Reply.ok(FakeFutTransfer.accepted("bbbbbbbb-0000-0000-0000-000000000002")));
        assertThat(instance().approveAndDispatch(order(id, "GFS-26-RETRY001", "1.0"), 1L).result())
                .isEqualTo(Result.SUBMITTED);
        assertThat(row(id).get("attempts")).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from vendor_order where order_id = ?", Integer.class, id)).isOne();

        assertThat(instance().approveAndDispatch(order(id, "GFS-26-RETRY001", "1.0"), 1L).result())
                .isEqualTo(Result.ALREADY_SUBMITTED);
        assertThat(vendor.calls("/orderAPI")).isEqualTo(2);
    }

    @Test
    @DisplayName("out of attempts: refused without calling the vendor")
    void attemptsExhausted() {
        long id = insertOrder("GFS-26-TRIED003", "TRADING_SERVICE", "READY_FOR_DELIVERY", "1.0");
        jdbc.update("insert into vendor_order (order_id, external_ref, state, amount_ordered_k, attempts, order_mode) values (?, 'GFS-26-TRIED003', 'FAILED', 1000, 3, 'OWN_SENDERS')", id);

        assertThat(instance().approveAndDispatch(order(id, "GFS-26-TRIED003", "1.0"), 1L).message())
                .contains("tried 3 times");
        assertThat(vendor.requests()).isEmpty();
    }

    @Test
    @DisplayName("a vendor id already linked to another order is not accepted silently")
    void vendorIdConflict() {
        long first = insertOrder("GFS-26-DUPE0001", "TRADING_SERVICE", "READY_FOR_DELIVERY", "1.0");
        long second = insertOrder("GFS-26-DUPE0002", "TRADING_SERVICE", "READY_FOR_DELIVERY", "1.0");
        vendor.on("/orderAPI", Reply.ok("{\"orderID\":\"aaaaaaaa-1111-2222-3333-444444444444\"}"));

        assertThat(instance().approveAndDispatch(order(first, "GFS-26-DUPE0001", "1.0"), 1L).result())
                .isEqualTo(Result.SUBMITTED);
        assertThat(instance().approveAndDispatch(order(second, "GFS-26-DUPE0002", "1.0"), 1L).result())
                .isEqualTo(Result.NEEDS_REVIEW);
        assertThat(row(second).get("last_error_code")).isEqualTo("VENDOR_ID_CONFLICT");
    }

    @Test
    @DisplayName("the pause trips once, alerts once, holds in the database, and records who resumed it")
    void pauseSwitch() {
        NotificationService notifications = mock(NotificationService.class);
        AppProperties props = VendorTestSupport.props(vendor.baseUrl(), Duration.ofMillis(800));
        VendorControl control = new VendorControl(new NamedParameterJdbcTemplate(ds), notifications, props);
        Long admin = jdbc.queryForObject("""
                insert into account (public_id, email, email_normalised, password_hash, role)
                values ('acc_pause_admin', 'admin@example.test', 'admin@example.test', 'x', 'ADMIN') returning id
                """, Long.class);

        assertThat(control.isPaused()).isFalse();
        control.pause("HTTP_403 /orderAPI", "GFS-26-PAUSE001");
        control.pause("HTTP_403 /orderStatusBulkAPI", null);
        assertThat(control.isPaused()).isTrue();
        assertThat(control.state().orElseThrow().reason()).isEqualTo("HTTP_403 /orderAPI");
        org.mockito.Mockito.verify(notifications, org.mockito.Mockito.times(1)).fulfilmentAlert(any());

        // Another instance sees the same switch.
        assertThat(new VendorControl(new NamedParameterJdbcTemplate(ds), notifications, props).isPaused()).isTrue();

        assertThat(control.resume(admin)).isTrue();
        assertThat(control.isPaused()).isFalse();
        assertThat(jdbc.queryForObject("select resumed_by from vendor_control", Long.class)).isEqualTo(admin);
        assertThat(control.resume(admin)).isFalse();
    }

    @Test
    @DisplayName("no sign-in, backup code or key is ever stored, including the codes the vendor sends back")
    void nothingSecretStored() {
        long id = insertOrder("GFS-26-SECRET01", "TRADING_SERVICE", "READY_FOR_DELIVERY", "0.5");
        vendor.on("/orderAPI", Reply.slow(2_000));
        vendor.on("/orderStatusAPI", Reply.ok(FakeFutTransfer.status("GFS-26-SECRET01", 500)));

        try (VendorTestSupport.LogCapture logs = new VendorTestSupport.LogCapture()) {
            instance().approveAndDispatch(order(id, "GFS-26-SECRET01", "0.5"), 1L);

            String stored = String.join("\n", jdbc.queryForList("select row_to_json(v)::text from vendor_order v", String.class))
                    + String.join("\n", jdbc.queryForList("select row_to_json(c)::text from vendor_call c", String.class))
                    + String.join("\n", jdbc.queryForList("select row_to_json(o)::text from orders o", String.class));
            for (String secret : VendorTestSupport.secrets()) {
                assertThat(stored).as("a secret in the database").doesNotContain(secret);
                assertThat(logs.all()).as("a secret in the log").doesNotContain(secret);
            }
        }
    }
}
