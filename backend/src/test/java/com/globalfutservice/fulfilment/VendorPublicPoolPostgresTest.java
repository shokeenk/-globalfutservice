package com.globalfutservice.fulfilment;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.globalfutservice.config.AppProperties;
import com.globalfutservice.credentials.CredentialVaultService;
import com.globalfutservice.domain.catalog.Platform;
import com.globalfutservice.domain.catalog.Sku;
import com.globalfutservice.fulfilment.FakeFutTransfer.Reply;
import com.globalfutservice.fulfilment.SupplierFulfilmentService.Result;
import com.globalfutservice.notify.NotificationService;
import com.globalfutservice.orders.OrderEntity;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Coin orders bought from FUT Transfer's public seller pool, against a real PostgreSQL.
 *
 * <p>Every guarantee /orderAPI has holds for /buyCoinsAPI: one row per order decides who
 * sends, a lost answer is looked up and never resent, and nothing goes to the backup
 * domain. On top: what is sent and recorded, the balance kept for margin tracking, the
 * refusals that keep the customer's sign-in, and a retry that follows the configuration
 * of the moment.
 *
 * <p>Runs when {@code GFS_TEST_PG_URL} is set, in a throwaway schema. The vendor is a local
 * HTTP server; no real order is ever placed.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class VendorPublicPoolPostgresTest {

    /** The collection's "Get Buy Conditions" shape, with public stock at 4.00 (console) and 6.00 (PC). */
    private static final String CONDITIONS = FakeFutTransfer.POOL_CONDITIONS;

    private static final AppProperties.FutTransferPublicPool ORDER_AMOUNT = VendorTestSupport.ORDER_AMOUNT_POOL;

    private String schema;
    private DriverManagerDataSource ds;
    private JdbcTemplate jdbc;
    private FakeFutTransfer vendor;
    private FakeFutTransfer backup;
    private final ObjectMapper mapper = new ObjectMapper();
    private long legacyOrderId;
    private int nextId;

    @BeforeAll
    void migrate() {
        String url = System.getenv("GFS_TEST_PG_URL");
        assumeTrue(url != null && !url.isBlank(), "GFS_TEST_PG_URL not set: no database to test against");
        schema = "pool_test_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        ds = new DriverManagerDataSource(url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema + ",public",
                System.getenv("GFS_TEST_PG_USER"), System.getenv("GFS_TEST_PG_PASSWORD"));
        jdbc = new JdbcTemplate(ds);

        // A vendor order placed through /orderAPI before V40 existed...
        flyway("39").migrate();
        legacyOrderId = insertOrder("GFS-26-BEFOREV40", "0.3");
        jdbc.update("""
                insert into vendor_order (order_id, external_ref, vendor_order_id, state, amount_ordered_k)
                values (?, 'GFS-26-BEFOREV40', 'dddddddd-0000-0000-0000-000000000040', 'SUBMITTED', 300)
                """, legacyOrderId);
        // ...then V40.
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
        backup = new FakeFutTransfer();
        vendor.on("/getCooldownStatus", Reply.ok(FakeFutTransfer.COOLDOWN_READY));
        vendor.on("/buyConditionAPI", Reply.ok(CONDITIONS));
    }

    @AfterEach
    void stopVendor() {
        vendor.close();
        backup.close();
    }

    // --------------------------------------------------------------- fixtures ---

    private long insertOrder(String ref, String quantity) {
        return jdbc.queryForObject("""
                insert into orders (public_ref, season, sku, quantity, delivery_method, currency, subtotal_minor,
                                    total_minor, price_breakdown, status, quote_id, guest_email)
                values (?, 'FC26', 'TRADING_SERVICE', ?::numeric, 'PLAYER_AUCTION', 'INR', 100000, 100000, ?::jsonb,
                        'READY_FOR_DELIVERY', ?, 'buyer@example.test')
                returning id
                """, Long.class, ref, quantity, "{\"quantity\":" + quantity + "}", "q_" + ref);
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

    /** A fresh vendor id: the schema is shared, and one id may belong to one order. */
    private String vendorId() {
        return "cccccccc-0000-0000-0000-%012d".formatted(++nextId);
    }

    /** One application instance, with the vault and the alerts it reports to. */
    private record App(SupplierFulfilmentService service, CredentialVaultService vault,
                       NotificationService notifications) {
        Release approve(OrderEntity order) {
            return new Release(service.approveAndDispatch(order, 1L));
        }
    }

    private record Release(SupplierFulfilmentService.Release r) {
    }

    private App app(AppProperties.FutTransferOrderMode mode, AppProperties.FutTransferPublicPool pool) {
        AppProperties props = VendorTestSupport.props(vendor.baseUrl(), backup.baseUrl(), Duration.ofMillis(800),
                mode, pool);
        CredentialVaultService vault = mock(CredentialVaultService.class);
        when(vault.reveal(anyLong(), any())).thenReturn(VendorTestSupport.signIn());
        NotificationService notifications = mock(NotificationService.class);
        VendorControl control = VendorTestSupport.running();
        SupplierFulfilmentService service = new SupplierFulfilmentService(new FutTransferClient(props, mapper, control,
                new VendorCallLog(new NamedParameterJdbcTemplate(ds))).withoutRetryPauses(), control, vault,
                new VendorOrderLedger(new NamedParameterJdbcTemplate(ds)), notifications, props, mapper);
        return new App(service, vault, notifications);
    }

    private App pool() {
        return app(AppProperties.FutTransferOrderMode.PUBLIC_POOL, ORDER_AMOUNT);
    }

    private Map<String, Object> row(long orderId) {
        return jdbc.queryForMap("select * from vendor_order where order_id = ?", orderId);
    }

    private static Set<String> fields(JsonNode body) {
        Set<String> out = new TreeSet<>();
        body.fieldNames().forEachRemaining(out::add);
        return out;
    }

    private JsonNode lastBuy() {
        return vendor.requests().stream().filter(r -> r.path().equals("/buyCoinsAPI"))
                .reduce((a, b) -> b).orElseThrow().body();
    }

    // ----------------------------------------------------------------- tests ---

    @Test
    @DisplayName("V40: everything sent before it went through /orderAPI, and its method was not recorded")
    void backfill() {
        Map<String, Object> legacy = row(legacyOrderId);
        assertThat(legacy.get("order_mode")).isEqualTo("OWN_SENDERS");
        assertThat(legacy.get("transfer_method")).isNull();
        assertThat(legacy.get("buy_now_threshold_sent")).isNull();
        assertThat(legacy.get("balance_at_send")).isNull();
    }

    @Test
    @DisplayName("public pool: /buyCoinsAPI with the guide's defaults, the amount in K as threshold, and it is all recorded")
    void sendsAndRecords() {
        long id = insertOrder("GFS-26-POOLSEND1", "0.5");
        String vid = vendorId();
        vendor.on("/buyCoinsAPI", Reply.ok(FakeFutTransfer.accepted(vid)));

        assertThat(pool().approve(order(id, "GFS-26-POOLSEND1", "0.5")).r().result()).isEqualTo(Result.SUBMITTED);

        assertThat(vendor.calls("/orderAPI")).isZero();
        JsonNode sent = lastBuy();
        assertThat(sent.get("transferMethod").asText()).isEqualTo("targetedSnipe");
        assertThat(sent.get("topUpEnabled").asInt()).isEqualTo(300);
        assertThat(sent.get("autoFinishCycle").asInt()).isEqualTo(1);
        assertThat(sent.get("amount").asLong()).isEqualTo(500);
        // FUT Transfer's console price (4.00) + 10%, as a number: never 0, never the order's 500K.
        assertThat(sent.get("buyNowThreshold").isNumber()).isTrue();
        assertThat(sent.get("buyNowThreshold").decimalValue()).isEqualByComparingTo("4.40");
        // 0 = off, sent explicitly.
        assertThat(sent.has("minTransferAmount")).isTrue();
        assertThat(sent.get("minTransferAmount").asInt()).isZero();
        // The same read the threshold came from, once, for the order's platform.
        assertThat(vendor.calls("/buyConditionAPI")).isOne();
        assertThat(vendor.requests().stream().filter(q -> q.path().equals("/buyConditionAPI")).findFirst().orElseThrow()
                .body().get("platform").asText()).isEqualTo("PS");
        assertThat(fields(sent)).doesNotContain("supplierID", "privateSupplier", "playerToBuy", "maxPrice", "senderGroup");

        Map<String, Object> r = row(id);
        assertThat(r.get("state")).isEqualTo("SUBMITTED");
        assertThat(r.get("vendor_order_id")).isEqualTo(vid);
        assertThat(r.get("order_mode")).isEqualTo("PUBLIC_POOL");
        assertThat(r.get("transfer_method")).isEqualTo("targetedSnipe");
        assertThat((BigDecimal) r.get("buy_now_threshold_sent")).isEqualByComparingTo("4.40");
        assertThat((BigDecimal) r.get("supplier_rate_sent")).isEqualByComparingTo("4");
        assertThat((BigDecimal) r.get("threshold_margin_pct")).isEqualByComparingTo("10");
        assertThat((BigDecimal) r.get("transfer_fee_at_send")).isEqualByComparingTo("0");
        assertThat(r.get("max_price_sent")).isNull();
        assertThat((BigDecimal) r.get("balance_at_send")).isEqualByComparingTo("5000");
        assertThat(vendor.calls("/buyCoinsAPI")).as("exactly one /buyCoinsAPI call").isOne();
    }

    @Test
    @DisplayName("FUT Transfer's prices cannot be read: nothing claimed or sent, and it is tried again later")
    void priceReadFails() {
        long id = insertOrder("GFS-26-POOLBAL01", "0.5");
        vendor.on("/buyConditionAPI", Reply.of(503, "down"));
        backup.on("/buyConditionAPI", Reply.of(503, "down"));
        vendor.on("/buyCoinsAPI", Reply.ok(FakeFutTransfer.accepted(vendorId())));
        App a = pool();

        SupplierFulfilmentService.Release r = a.approve(order(id, "GFS-26-POOLBAL01", "0.5")).r();

        assertThat(r.result()).isEqualTo(Result.NOT_SENT);
        assertThat(r.retryable()).as("temporary: the queue tries again").isTrue();
        assertThat(r.message()).contains("could not be read");
        assertThat(vendor.calls("/buyCoinsAPI")).isZero();
        assertThat(jdbc.queryForObject("select count(*) from vendor_order where order_id = ?", Integer.class, id)).isZero();
        verify(a.vault(), never()).reveal(anyLong(), any());

        // Prices back: sent, once.
        vendor.on("/buyConditionAPI", Reply.ok(CONDITIONS));
        assertThat(pool().approve(order(id, "GFS-26-POOLBAL01", "0.5")).r().result()).isEqualTo(Result.SUBMITTED);
        assertThat(vendor.calls("/buyCoinsAPI")).isOne();
    }

    @Test
    @DisplayName("FUT Transfer's price above the limit: nothing sent, left for Approve with the price and the limit")
    void aboveLimit() {
        long id = insertOrder("GFS-26-POOLLIM01", "0.5");
        var limited = new AppProperties.FutTransferPublicPool(AppProperties.BuyNowThresholdMode.FIXED, null, false, null,
                BigDecimal.TEN, new BigDecimal("3.50"), null);
        SupplierFulfilmentService.Release r = app(AppProperties.FutTransferOrderMode.PUBLIC_POOL, limited)
                .approve(order(id, "GFS-26-POOLLIM01", "0.5")).r();

        assertThat(r.result()).isEqualTo(Result.NOT_SENT);
        assertThat(r.retryable()).as("waits for an admin").isFalse();
        assertThat(r.message()).startsWith("FUT Transfer price €4/100K is above your limit €3.5.");
        assertThat(vendor.calls("/buyCoinsAPI")).isZero();
        assertThat(jdbc.queryForObject("select count(*) from vendor_order where order_id = ?", Integer.class, id)).isZero();

        // Only the margin over the limit: sent at the limit.
        var capped = new AppProperties.FutTransferPublicPool(AppProperties.BuyNowThresholdMode.FIXED, null, false, null,
                BigDecimal.TEN, new BigDecimal("4.20"), null);
        vendor.on("/buyCoinsAPI", Reply.ok(FakeFutTransfer.accepted(vendorId())));
        assertThat(app(AppProperties.FutTransferOrderMode.PUBLIC_POOL, capped)
                .approve(order(id, "GFS-26-POOLLIM01", "0.5")).r().result()).isEqualTo(Result.SUBMITTED);
        assertThat(lastBuy().get("buyNowThreshold").decimalValue()).isEqualByComparingTo("4.20");
        assertThat(vendor.calls("/buyCoinsAPI")).isOne();
    }

    @Test
    @DisplayName("PC orders use FUT Transfer's PC prices: 6.00 + 10%")
    void pcPrices() {
        long id = insertOrder("GFS-26-POOLPC001", "0.5");
        vendor.on("/buyCoinsAPI", Reply.ok(FakeFutTransfer.accepted(vendorId())));
        OrderEntity o = order(id, "GFS-26-POOLPC001", "0.5");
        when(o.getPlatform()).thenReturn(Platform.PC);

        assertThat(pool().approve(o).r().result()).isEqualTo(Result.SUBMITTED);
        assertThat(lastBuy().get("buyNowThreshold").decimalValue()).isEqualByComparingTo("6.60");
        assertThat(vendor.requests().stream().filter(q -> q.path().equals("/buyConditionAPI")).findFirst().orElseThrow()
                .body().get("platform").asText()).isEqualTo("PC");
    }

    @Test
    @DisplayName("FIXED threshold and maxPrice, when switched on, are sent as configured and recorded")
    void fixedAndMaxPrice() {
        long id = insertOrder("GFS-26-POOLFIX01", "1.0");
        vendor.on("/buyCoinsAPI", Reply.ok(FakeFutTransfer.accepted(vendorId())));
        var fixed = new AppProperties.FutTransferPublicPool(AppProperties.BuyNowThresholdMode.FIXED,
                new BigDecimal("8.63"), true, new BigDecimal("9.25"));

        assertThat(app(AppProperties.FutTransferOrderMode.PUBLIC_POOL, fixed).approve(order(id, "GFS-26-POOLFIX01", "1.0"))
                .r().result()).isEqualTo(Result.SUBMITTED);

        JsonNode sent = lastBuy();
        assertThat(sent.get("buyNowThreshold").decimalValue()).isEqualByComparingTo("8.63");
        assertThat(sent.get("maxPrice").decimalValue()).isEqualByComparingTo("9.25");
        assertThat((BigDecimal) row(id).get("buy_now_threshold_sent")).isEqualByComparingTo("8.63");
        assertThat((BigDecimal) row(id).get("max_price_sent")).isEqualByComparingTo("9.25");
    }

    @Test
    @DisplayName("a needed setting that is missing: Approve refuses and says which, before the vault or the vendor")
    void missingSettingRefused() {
        long id = insertOrder("GFS-26-POOLMISS1", "0.5");
        // No threshold set is not missing: it is worked out from FUT Transfer's prices. maxPrice on with no value is.
        var noPrice = new AppProperties.FutTransferPublicPool(AppProperties.BuyNowThresholdMode.FIXED, null, true, null);
        App a = app(AppProperties.FutTransferOrderMode.PUBLIC_POOL, noPrice);
        SupplierFulfilmentService.Release r = a.approve(order(id, "GFS-26-POOLMISS1", "0.5")).r();
        assertThat(r.result()).isEqualTo(Result.NOT_SENT);
        assertThat(r.message()).contains("GFS_FUTTRANSFER_MAX_PRICE").contains("Nothing was sent");
        verify(a.vault(), never()).reveal(anyLong(), any());

        assertThat(jdbc.queryForObject("select count(*) from vendor_order where order_id = ?", Integer.class, id)).isZero();
        assertThat(vendor.requests()).isEmpty();
    }

    @Test
    @DisplayName("402, 406, supplierOverpriced: FAILED with the reason, sign-in kept, staff alerted, never retried alone")
    void refusalsKeepTheSignIn() {
        String[][] cases = {
                {"402", "{\"error\":\"insufficientFunds\"}", "insufficientFunds", "balance"},
                {"406", "insufficientStock", "insufficientStock", "enough coins"},
                {"400", "{\"error\":\"supplierOverpriced\"}", "supplierOverpriced", "supplierOverpriced"},
        };
        int n = 0;
        for (String[] c : cases) {
            String ref = "GFS-26-POOLREF0" + (++n);
            long id = insertOrder(ref, "0.5");
            vendor.on("/buyCoinsAPI", Reply.of(Integer.parseInt(c[0]), c[1]));
            long callsBefore = vendor.calls("/buyCoinsAPI");
            App a = pool();

            SupplierFulfilmentService.Release r = a.approve(order(id, ref, "0.5")).r();

            assertThat(r.result()).as(c[0]).isEqualTo(Result.FAILED);
            assertThat(r.message()).as(c[0]).contains(c[3]).contains("sign-in is kept");
            assertThat(row(id).get("state")).as(c[0]).isEqualTo("FAILED");
            assertThat(row(id).get("last_error_code")).as(c[0]).isEqualTo(c[2]);
            verify(a.vault(), never()).purge(anyLong(), anyString());
            verify(a.notifications(), times(1)).fulfilmentAlert(any());
            assertThat(vendor.calls("/buyCoinsAPI") - callsBefore).as(c[0] + ": no automatic retry").isOne();
        }

        // Once the cause is fixed, an admin approves again: the same row, a second attempt.
        long id = jdbc.queryForObject("select id from orders where public_ref = 'GFS-26-POOLREF01'", Long.class);
        vendor.on("/buyCoinsAPI", Reply.ok(FakeFutTransfer.accepted(vendorId())));
        assertThat(pool().approve(order(id, "GFS-26-POOLREF01", "0.5")).r().result()).isEqualTo(Result.SUBMITTED);
        assertThat(row(id).get("attempts")).isEqualTo(2);
    }

    @Test
    @DisplayName("a 400 the collection does not document for /buyCoinsAPI goes to review, sign-in kept")
    void undocumentedRefusalToReview() {
        long id = insertOrder("GFS-26-POOLUNK01", "0.5");
        vendor.on("/buyCoinsAPI", Reply.of(400, "{\"error\":\"SomethingNew\"}"));
        App a = pool();

        assertThat(a.approve(order(id, "GFS-26-POOLUNK01", "0.5")).r().result()).isEqualTo(Result.NEEDS_REVIEW);

        assertThat(row(id).get("state")).isEqualTo("NEEDS_REVIEW");
        assertThat(row(id).get("last_error_code")).isEqualTo("SomethingNew");
        verify(a.vault(), never()).purge(anyLong(), anyString());
        verify(a.notifications(), times(1)).fulfilmentAlert(any());
        // And nothing more is sent until an admin decides.
        assertThat(pool().approve(order(id, "GFS-26-POOLUNK01", "0.5")).r().result()).isEqualTo(Result.NOT_SENT);
        assertThat(vendor.calls("/buyCoinsAPI")).isOne();
    }

    @Test
    @DisplayName("public pool, refused for the sign-in (InvalidPassword, InvalidBA1-5): deleted, and the customer "
            + "asked for the part that is wrong -- never a silent NOT_SENT")
    void signInRefusalReachesTheCustomer() {
        for (String[] c : new String[][]{{"InvalidPassword", "RESUBMIT_SIGN_IN"}, {"InvalidBA2", "NEW_BACKUP_CODES"}}) {
            String ref = "GFS-26-POOLPW" + c[0].length() + c[1].charAt(0);
            long id = insertOrder(ref, "0.5");
            vendor.on("/buyCoinsAPI", Reply.of(400, "{\"error\":\"" + c[0] + "\"}"));
            App a = pool();

            SupplierFulfilmentService.Release r = a.approve(order(id, ref, "0.5")).r();

            assertThat(r.result()).isEqualTo(Result.FAILED_SIGN_IN);
            assertThat(r.reasonCode()).isEqualTo(c[1]);
            assertThat(row(id).get("state")).isEqualTo("FAILED");
            assertThat(row(id).get("last_error_code")).isEqualTo(c[0]);
            assertThat(jdbc.queryForObject("select signin_problem from orders where id = ?", String.class, id))
                    .isEqualTo(c[1]);
            verify(a.vault()).purge(eq(id), anyString());
        }
    }

    @Test
    @DisplayName("public pool: sent without asking /getCooldownStatus, which FUT Transfer only answers for /orderAPI")
    void noCooldownCheckInThePool() {
        long id = insertOrder("GFS-26-POOLCOOL1", "0.05");
        // What production saw for every public-pool order: 400 apiOrderOnly.
        vendor.on("/getCooldownStatus", Reply.of(400, "{\"error\":\"apiOrderOnly\"}"));
        vendor.on("/buyCoinsAPI", Reply.ok(FakeFutTransfer.accepted(vendorId())));

        SupplierFulfilmentService.Release r = pool().approve(order(id, "GFS-26-POOLCOOL1", "0.05")).r();

        assertThat(r.result()).isEqualTo(Result.SUBMITTED);
        assertThat(vendor.calls("/getCooldownStatus")).isZero();
        assertThat(vendor.calls("/buyCoinsAPI")).isOne();
        assertThat(row(id).get("state")).isEqualTo("SUBMITTED");
    }

    @Test
    @DisplayName("429 on /buyCoinsAPI: a definite refusal, never looked up, no attempt used, and sent once the limit passes")
    void rateLimitedIsARefusal() {
        long id = insertOrder("GFS-26-POOL429A1", "0.5");
        vendor.on("/buyCoinsAPI", Reply.of(429, "{\"error\":\"rateLimitExceeded\"}"));
        long lookupsBefore = vendor.calls("/orderStatusAPI");

        // More 429s than the order has attempts: none of them uses one up.
        for (int i = 0; i < 4; i++) {
            SupplierFulfilmentService.Release r = pool().approve(order(id, "GFS-26-POOL429A1", "0.5")).r();
            assertThat(r.result()).isEqualTo(Result.FAILED);
            assertThat(r.retryable()).isTrue();
            assertThat(r.reasonCode()).isEqualTo(SupplierFulfilmentService.RATE_LIMITED);
            assertThat(r.message()).contains("HTTP 429").contains("Nothing was created").doesNotContain("shortly");
        }
        assertThat(row(id).get("state")).isEqualTo("FAILED");
        assertThat(row(id).get("last_error_code")).isEqualTo("RATE_LIMITED");
        // Every call is counted, and still more than the 3 attempts an order has: 429s never use them up.
        assertThat(row(id).get("attempts")).isEqualTo(4);
        assertThat((String) row(id).get("review_reason")).contains("rateLimitExceeded");
        // A refusal, not an "uncertain" answer: nothing is looked up.
        assertThat(vendor.calls("/orderStatusAPI")).isEqualTo(lookupsBefore);

        vendor.on("/buyCoinsAPI", Reply.ok(FakeFutTransfer.accepted(vendorId())));
        assertThat(pool().approve(order(id, "GFS-26-POOL429A1", "0.5")).r().result()).isEqualTo(Result.SUBMITTED);
        assertThat(vendor.calls("/buyCoinsAPI")).isEqualTo(5);
        assertThat(row(id).get("attempts")).isEqualTo(5);
        assertThat(jdbc.queryForObject("select count(*) from vendor_order where order_id = ?", Long.class, id))
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("double clicks, concurrent admins and two instances: exactly one /buyCoinsAPI call")
    void concurrentApprovals() throws Exception {
        long id = insertOrder("GFS-26-POOLRACE1", "2.0");
        String vid = vendorId();
        vendor.on("/buyCoinsAPI", new Reply(200, FakeFutTransfer.accepted(vid), 400));
        List<App> instances = List.of(pool(), pool());

        ExecutorService threads = Executors.newFixedThreadPool(8);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<SupplierFulfilmentService.Release>> results = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            App app = instances.get(i % 2);
            results.add(threads.submit(() -> {
                go.await();
                return app.approve(order(id, "GFS-26-POOLRACE1", "2.0")).r();
            }));
        }
        go.countDown();
        List<Result> outcomes = new ArrayList<>();
        for (Future<SupplierFulfilmentService.Release> f : results) {
            outcomes.add(f.get(10, TimeUnit.SECONDS).result());
        }
        threads.shutdown();

        assertThat(vendor.calls("/buyCoinsAPI")).isOne();
        assertThat(outcomes).containsOnlyOnce(Result.SUBMITTED);
        assertThat(outcomes).allMatch(r -> r == Result.SUBMITTED || r == Result.IN_FLIGHT || r == Result.ALREADY_SUBMITTED);
        assertThat(row(id).get("vendor_order_id")).isEqualTo(vid);

        assertThat(pool().approve(order(id, "GFS-26-POOLRACE1", "2.0")).r().result()).isEqualTo(Result.ALREADY_SUBMITTED);
        assertThat(vendor.calls("/buyCoinsAPI")).isOne();
    }

    @Test
    @DisplayName("a timeout is looked up by our reference, never resent, and never touches the backup domain")
    void timeoutLookedUp() {
        long id = insertOrder("GFS-26-POOLTIME1", "0.5");
        vendor.on("/buyCoinsAPI", Reply.slow(2_000));
        vendor.on("/orderStatusAPI", Reply.ok(FakeFutTransfer.status("GFS-26-POOLTIME1", 500)));
        backup.on("/buyCoinsAPI", Reply.ok(FakeFutTransfer.accepted(vendorId())));

        assertThat(pool().approve(order(id, "GFS-26-POOLTIME1", "0.5")).r().result()).isEqualTo(Result.SUBMITTED);
        assertThat(row(id).get("state")).isEqualTo("SUBMITTED");
        assertThat(vendor.calls("/buyCoinsAPI")).isOne();
        assertThat(backup.calls("/buyCoinsAPI")).isZero();

        assertThat(pool().approve(order(id, "GFS-26-POOLTIME1", "0.5")).r().result()).isEqualTo(Result.ALREADY_SUBMITTED);
        assertThat(vendor.calls("/buyCoinsAPI")).isOne();
    }

    @Test
    @DisplayName("a retry follows the mode configured now, and records it")
    void retryUsesCurrentMode() {
        long id = insertOrder("GFS-26-POOLMODE1", "1.0");
        vendor.on("/orderAPI", Reply.of(400, "InvalidAmount"));
        assertThat(app(AppProperties.FutTransferOrderMode.OWN_SENDERS, ORDER_AMOUNT)
                .approve(order(id, "GFS-26-POOLMODE1", "1.0")).r().result()).isEqualTo(Result.FAILED);
        assertThat(row(id).get("order_mode")).isEqualTo("OWN_SENDERS");

        vendor.on("/buyCoinsAPI", Reply.ok(FakeFutTransfer.accepted(vendorId())));
        assertThat(pool().approve(order(id, "GFS-26-POOLMODE1", "1.0")).r().result()).isEqualTo(Result.SUBMITTED);

        Map<String, Object> r = row(id);
        assertThat(r.get("order_mode")).isEqualTo("PUBLIC_POOL");
        assertThat(r.get("attempts")).isEqualTo(2);
        assertThat((BigDecimal) r.get("buy_now_threshold_sent")).isEqualByComparingTo("4.40");
        assertThat(vendor.calls("/orderAPI")).isOne();
        assertThat(vendor.calls("/buyCoinsAPI")).isOne();
    }

    @Test
    @DisplayName("OWN_SENDERS still sends /orderAPI as before, and reads no balance")
    void ownSendersUnchanged() {
        long id = insertOrder("GFS-26-POOLOWN01", "0.5");
        vendor.on("/orderAPI", Reply.ok(FakeFutTransfer.accepted(vendorId())));

        assertThat(app(AppProperties.FutTransferOrderMode.OWN_SENDERS, ORDER_AMOUNT)
                .approve(order(id, "GFS-26-POOLOWN01", "0.5")).r().result()).isEqualTo(Result.SUBMITTED);

        assertThat(vendor.calls("/buyCoinsAPI")).isZero();
        assertThat(vendor.calls("/buyConditionAPI")).isZero();
        JsonNode sent = vendor.requests().stream().filter(r -> r.path().equals("/orderAPI")).findFirst().orElseThrow().body();
        assertThat(sent.get("senderGroup").asText()).isEqualTo("-1");
        assertThat(fields(sent)).doesNotContain("buyNowThreshold", "maxPrice");

        Map<String, Object> r = row(id);
        assertThat(r.get("order_mode")).isEqualTo("OWN_SENDERS");
        assertThat(r.get("transfer_method")).isEqualTo("targetedSnipe");
        assertThat(r.get("buy_now_threshold_sent")).isNull();
        assertThat(r.get("balance_at_send")).isNull();
    }
}
