package com.globalfutservice.fulfilment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.globalfutservice.config.AppProperties;
import com.globalfutservice.credentials.CredentialVaultService;
import com.globalfutservice.domain.catalog.Platform;
import com.globalfutservice.domain.catalog.Sku;
import com.globalfutservice.domain.orders.OrderStatus;
import com.globalfutservice.fulfilment.FakeFutTransfer.Reply;
import com.globalfutservice.fulfilment.SupplierFulfilmentService.Result;
import com.globalfutservice.notify.NotificationService;
import com.globalfutservice.notify.OrderNotification;
import com.globalfutservice.orders.OrderEntity;
import com.globalfutservice.orders.OrderRepository;
import com.globalfutservice.orders.OrderService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
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
 * The customer's tracking link from FUT Transfer, against a real PostgreSQL and a fake partner:
 * asked for once the order's id is committed, apart from placing it; kept verbatim, https only;
 * a failure that never touches the order and is asked about again later; a "finished" refusal
 * that stops it; one email; never in the call log or the logs; the backfill; and still one
 * /buyCoinsAPI call per order.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PartnerTrackingLinkPostgresTest {

    /** The vendor's own example, with branding and language parameters on it. */
    private static final String LINK = "https://transferstatus.top/abc12345?shop=gfs&lang=de";
    private static final String SHORT_CODE = "abc12345";

    private TestDatabase db;
    private FakeFutTransfer vendor;
    private NotificationService notifications;
    private VendorOrderLedger ledger;
    private VendorControl control;
    private PartnerTrackingLinks links;
    private SupplierFulfilmentService supplier;
    private final ObjectMapper mapper = new ObjectMapper();
    private int nextVendorId;

    @BeforeAll
    void migrate() {
        db = TestDatabase.migrated();
    }

    @AfterAll
    void drop() {
        if (db != null) db.drop();
    }

    @BeforeEach
    void setUp() throws Exception {
        db.jdbc.update("update vendor_control set next_poll_at = null, backoff_level = 0, paused = false");
        vendor = new FakeFutTransfer();
        vendor.on("/buyConditionAPI", Reply.ok("{\"balance\":155.5821}"));
        AppProperties props = VendorTestSupport.publicPool(vendor.baseUrl(), Duration.ofMillis(800),
                VendorTestSupport.ORDER_AMOUNT_POOL);
        notifications = mock(NotificationService.class);
        ledger = new VendorOrderLedger(db.named);
        control = new VendorControl(db.named, notifications, props);
        FutTransferClient client = new FutTransferClient(props, mapper, control, new VendorCallLog(db.named))
                .withoutRetryPauses();
        OrderRepository orders = mock(OrderRepository.class);
        when(orders.findById(anyLong())).thenAnswer(inv -> Optional.of(entity(inv.getArgument(0))));
        OrderService orderService = mock(OrderService.class);
        when(orderService.notificationFor(any())).thenAnswer(inv -> {
            OrderEntity o = inv.getArgument(0);
            return new OrderNotification(o.getPublicRef(), o.getStatus().name(), "Buy Coins — 500K (PlayStation)",
                    "₹1,000.00", "buyer@example.test", null, "COMFORT_TRADE", "TRADING_SERVICE", "PlayStation",
                    null, null, null, null);
        });
        links = new PartnerTrackingLinks(client, ledger, orders, orderService, notifications, Clock.systemUTC());
        CredentialVaultService vault = mock(CredentialVaultService.class);
        when(vault.reveal(anyLong(), any())).thenReturn(VendorTestSupport.signIn());
        supplier = new SupplierFulfilmentService(client, control, vault, ledger, notifications, props, mapper, links);
    }

    @AfterEach
    void tearDown() {
        vendor.close();
    }

    // --------------------------------------------------------------- fixtures ---

    private String vendorId() {
        return "cccccccc-1111-0000-0000-%012d".formatted(++nextVendorId);
    }

    private String status(long orderId) {
        return db.jdbc.queryForObject("select status from orders where id = ?", String.class, orderId);
    }

    /** The order as the application reads it, its status the database's. */
    private OrderEntity entity(long id) {
        Map<String, Object> row = db.jdbc.queryForMap("select public_ref, quantity from orders where id = ?", id);
        String ref = (String) row.get("public_ref");
        BigDecimal quantity = (BigDecimal) row.get("quantity");
        OrderEntity o = mock(OrderEntity.class);
        when(o.getId()).thenReturn(id);
        when(o.getPublicRef()).thenReturn(ref);
        when(o.getSku()).thenReturn(Sku.TRADING_SERVICE);
        when(o.getPlatform()).thenReturn(Platform.PLAYSTATION);
        when(o.getQuantity()).thenReturn(quantity);
        when(o.getPriceBreakdown()).thenReturn("{\"quantity\":" + quantity.toPlainString() + "}");
        when(o.getStatus()).thenAnswer(inv -> OrderStatus.valueOf(status(id)));
        return o;
    }

    private long paid(String ref) {
        return db.order(ref, "READY_FOR_DELIVERY", "0.5");
    }

    private static String linkReply(String fullUrl, String orderId) {
        return """
                {"success":true,"shortCode":"%s","shop":"gfs","specialParams":"lang=de","fullUrl":"%s","orderID":"%s"}
                """.formatted(SHORT_CODE, fullUrl, orderId);
    }

    private Map<String, Object> row(long orderId) {
        return db.jdbc.queryForMap("select * from vendor_order where order_id = ?", orderId);
    }

    /** The call log for one order: endpoint, result and our own code, oldest first. */
    private List<String> audit(String ref) {
        return db.jdbc.queryForList("select endpoint || ' ' || result || ' ' || coalesce(error_code, '-') "
                + "from vendor_call where ? = any(order_refs) and endpoint <> '/buyConditionAPI' "
                + "and endpoint <> '/getCooldownStatus' order by id", String.class, ref);
    }

    private void dueNow(long orderId) {
        db.jdbc.update("update vendor_order set tracking_link_next_at = now() where order_id = ?", orderId);
    }

    private List<String> emailedLinks() {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(notifications, org.mockito.Mockito.atLeast(0)).coinsOnTheWay(any(), captor.capture());
        return captor.getAllValues();
    }

    // ----------------------------------------------------------------- tests ---

    @Test
    @DisplayName("asked for once, after the order's id is committed, on the order's own domain, and stored verbatim")
    void storedAfterCommit() {
        long id = paid("GFS-26-LINK0001");
        String vid = vendorId();
        vendor.on("/buyCoinsAPI", Reply.ok(FakeFutTransfer.accepted(vid)));
        AtomicReference<String> seenAtLinkTime = new AtomicReference<>();
        vendor.on("/generateOrderStatusLink", body -> {
            // What another connection sees while the link is being asked for: already committed.
            seenAtLinkTime.set(db.jdbc.queryForObject(
                    "select state || ' ' || coalesce(vendor_order_id, '-') from vendor_order where order_id = ?",
                    String.class, id));
            return Reply.ok(linkReply(LINK, vid));
        });

        SupplierFulfilmentService.Release r = supplier.approveAndDispatch(entity(id), 1L);

        assertThat(r.result()).isEqualTo(Result.SUBMITTED);
        assertThat(seenAtLinkTime.get()).isEqualTo("SUBMITTED " + vid);
        assertThat(vendor.calls("/generateOrderStatusLink")).isOne();
        FakeFutTransfer.Request asked = vendor.requests().stream()
                .filter(q -> q.path().equals("/generateOrderStatusLink")).findFirst().orElseThrow();
        assertThat(asked.body().get("orderID").asText()).isEqualTo(vid);
        assertThat(asked.body().get("apiUser").asText()).isEqualTo("api@example.test");
        assertThat(row(id).get("tracking_url")).isEqualTo(LINK);
        assertThat(row(id).get("tracking_link_state")).isEqualTo("STORED");
        assertThat(row(id).get("tracking_url_at")).isNotNull();
        assertThat(emailedLinks()).containsExactly(LINK);

        // Asked for once: later polls do not ask again, or email again.
        links.sweep();
        links.sweep();
        assertThat(vendor.calls("/generateOrderStatusLink")).isOne();
        assertThat(emailedLinks()).hasSize(1);
        assertThat(vendor.calls("/buyCoinsAPI")).isOne();
    }

    @Test
    @DisplayName("inside a caller's transaction: nothing is asked until it has committed, then once")
    void afterTheCallersCommit() {
        long id = paid("GFS-26-LINK0008");
        String vid = vendorId();
        vendor.on("/buyCoinsAPI", Reply.ok(FakeFutTransfer.accepted(vid)));
        AtomicReference<String> seenAtLinkTime = new AtomicReference<>();
        vendor.on("/generateOrderStatusLink", body -> {
            seenAtLinkTime.set(db.jdbc.queryForObject(
                    "select state || ' ' || coalesce(vendor_order_id, '-') from vendor_order where order_id = ?",
                    String.class, id));
            return Reply.ok(linkReply(LINK, vid));
        });
        org.springframework.transaction.support.TransactionTemplate tx =
                new org.springframework.transaction.support.TransactionTemplate(
                        new org.springframework.jdbc.datasource.DataSourceTransactionManager(db.ds));

        tx.executeWithoutResult(status -> {
            assertThat(supplier.approveAndDispatch(entity(id), 1L).result()).isEqualTo(Result.SUBMITTED);
            assertThat(vendor.calls("/generateOrderStatusLink")).as("not while the transaction is open").isZero();
        });

        assertThat(vendor.calls("/generateOrderStatusLink")).isOne();
        // Everything the caller's transaction wrote was visible to another connection by then.
        assertThat(seenAtLinkTime.get()).isEqualTo("SUBMITTED " + vid);
        assertThat(vendor.calls("/buyCoinsAPI")).isOne();
    }

    @Test
    @DisplayName("a caller's transaction that rolls back: no link asked in-line; the poll asks instead")
    void callerRollsBack() {
        long id = paid("GFS-26-LINK0009");
        String vid = vendorId();
        vendor.on("/buyCoinsAPI", Reply.ok(FakeFutTransfer.accepted(vid)));
        vendor.on("/generateOrderStatusLink", Reply.ok(linkReply(LINK, vid)));
        org.springframework.transaction.support.TransactionTemplate tx =
                new org.springframework.transaction.support.TransactionTemplate(
                        new org.springframework.jdbc.datasource.DataSourceTransactionManager(db.ds));

        tx.executeWithoutResult(status -> {
            supplier.approveAndDispatch(entity(id), 1L);
            status.setRollbackOnly();
        });

        assertThat(vendor.calls("/generateOrderStatusLink")).isZero();
    }

    @Test
    @DisplayName("a failed link call leaves the order untouched, and is asked for again later until it works")
    void failureTouchesNothing() {
        long id = paid("GFS-26-LINK0002");
        String vid = vendorId();
        vendor.on("/buyCoinsAPI", Reply.ok(FakeFutTransfer.accepted(vid)));
        vendor.on("/generateOrderStatusLink", Reply.of(500, "{\"error\":\"Database operation failed\"}"));

        SupplierFulfilmentService.Release r = supplier.approveAndDispatch(entity(id), 1L);

        assertThat(r.result()).isEqualTo(Result.SUBMITTED);
        assertThat(row(id).get("state")).isEqualTo("SUBMITTED");
        assertThat(row(id).get("vendor_order_id")).isEqualTo(vid);
        assertThat(row(id).get("attempts")).isEqualTo(1);
        assertThat(row(id).get("last_error_code")).isNull();
        assertThat(row(id).get("tracking_url")).isNull();
        assertThat(row(id).get("tracking_link_state")).isNull();
        assertThat(row(id).get("tracking_link_error")).isEqualTo("HTTP_500");
        assertThat(audit("GFS-26-LINK0002")).containsExactly("/buyCoinsAPI ACCEPTED -", "/generateOrderStatusLink TRANSIENT HTTP_500");
        assertThat(db.jdbc.queryForObject("select tracking_link_next_at > now() + interval '50 seconds' "
                + "from vendor_order where order_id = ?", Boolean.class, id)).isTrue();
        verify(notifications, never()).coinsOnTheWay(any(), anyString());

        // Not due yet: not asked again.
        links.sweep();
        assertThat(vendor.calls("/generateOrderStatusLink")).isOne();

        // Its turn, and the partner answers this time.
        vendor.on("/generateOrderStatusLink", Reply.ok(linkReply(LINK, vid)));
        dueNow(id);
        assertThat(links.sweep()).isOne();
        assertThat(row(id).get("tracking_url")).isEqualTo(LINK);
        assertThat(row(id).get("state")).isEqualTo("SUBMITTED");
        assertThat(emailedLinks()).containsExactly(LINK);
        assertThat(vendor.calls("/buyCoinsAPI")).as("never sent again").isOne();
    }

    @Test
    @DisplayName("\"finished, can't generate\": recorded as unavailable, never asked again, and calls are not paused")
    void finishedStopsRetries() {
        long id = paid("GFS-26-LINK0003");
        String vid = vendorId();
        vendor.on("/buyCoinsAPI", Reply.ok(FakeFutTransfer.accepted(vid)));
        vendor.on("/generateOrderStatusLink",
                Reply.of(403, "{\"success\":false,\"error\":\"Cannot generate a link for a finished order\"}"));

        assertThat(supplier.approveAndDispatch(entity(id), 1L).result()).isEqualTo(Result.SUBMITTED);

        assertThat(row(id).get("tracking_link_state")).isEqualTo("UNAVAILABLE");
        assertThat(row(id).get("tracking_link_error")).isEqualTo(FutTransferClient.LINK_ORDER_FINISHED);
        assertThat(control.isPaused()).as("a refused link is not refused credentials").isFalse();
        assertThat(audit("GFS-26-LINK0003")).contains("/generateOrderStatusLink REFUSED ORDER_FINISHED");
        dueNow(id);
        links.sweep();
        links.sweep();
        assertThat(vendor.calls("/generateOrderStatusLink")).isOne();
        verify(notifications, never()).coinsOnTheWay(any(), anyString());
    }

    @Test
    @DisplayName("only an https:// link is kept; anything else is not stored and is asked for again")
    void httpsOnly() {
        long id = paid("GFS-26-LINK0004");
        String vid = vendorId();
        vendor.on("/buyCoinsAPI", Reply.ok(FakeFutTransfer.accepted(vid)));
        vendor.on("/generateOrderStatusLink", Reply.ok(linkReply("http://transferstatus.top/abc12345", vid)));

        supplier.approveAndDispatch(entity(id), 1L);

        assertThat(row(id).get("tracking_url")).isNull();
        assertThat(row(id).get("tracking_link_state")).isNull();
        assertThat(row(id).get("tracking_link_error")).isEqualTo("NOT_HTTPS");
        assertThat(audit("GFS-26-LINK0004")).contains("/generateOrderStatusLink REFUSED NOT_HTTPS");
        for (String bad : List.of("javascript:alert(1)", "https://", " https://transferstatus.top/x", "ftp://x.y/z")) {
            assertThat(FutTransferClient.isHttpsUrl(bad)).as(bad).isFalse();
        }
        assertThat(FutTransferClient.isHttpsUrl(LINK)).isTrue();
        // And the database itself refuses anything else.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> db.jdbc.update(
                "update vendor_order set tracking_url = 'http://x', tracking_link_state = 'STORED' where order_id = ?",
                id)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("the email goes exactly once, whoever asks and however often")
    void emailOnce() {
        long id = paid("GFS-26-LINK0005");
        String vid = vendorId();
        vendor.on("/buyCoinsAPI", Reply.ok(FakeFutTransfer.accepted(vid)));
        vendor.on("/generateOrderStatusLink", Reply.ok(linkReply(LINK, vid)));
        supplier.approveAndDispatch(entity(id), 1L);

        for (int i = 0; i < 3; i++) {
            links.sweep();
            links.afterAccepted(id);
            links.generateNow(id);
        }
        // Two pollers at once, from two instances.
        ExecutorService threads = Executors.newFixedThreadPool(4);
        List<Future<?>> all = new ArrayList<>();
        for (int i = 0; i < 4; i++) all.add(threads.submit(() -> links.sweep()));
        all.forEach(f -> {
            try {
                f.get(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        threads.shutdown();

        verify(notifications, times(1)).coinsOnTheWay(any(), eq(LINK));
        assertThat(row(id).get("tracking_email_at")).isNotNull();
        assertThat(vendor.calls("/generateOrderStatusLink")).isOne();
    }

    @Test
    @DisplayName("neither the link nor its short code reaches the call log or any log line")
    void masked() {
        long id = paid("GFS-26-LINK0006");
        String vid = vendorId();
        vendor.on("/buyCoinsAPI", Reply.ok(FakeFutTransfer.accepted(vid)));
        vendor.on("/generateOrderStatusLink", Reply.ok(linkReply(LINK, vid)));

        String logged;
        try (VendorTestSupport.LogCapture logs = new VendorTestSupport.LogCapture()) {
            supplier.approveAndDispatch(entity(id), 1L);
            links.sweep();
            logged = logs.all();
        }

        String audit = String.join("\n", db.jdbc.queryForList(
                "select concat_ws(' ', endpoint, domain, http_status, result, error_code, array_to_string(order_refs, ','),"
                        + " vendor_order_id) from vendor_call where 'GFS-26-LINK0006' = any(order_refs)", String.class));
        assertThat(audit).contains("/generateOrderStatusLink").doesNotContain(SHORT_CODE).doesNotContain("transferstatus");
        assertThat(logged).contains("Tracking link stored for GFS-26-LINK0006")
                .doesNotContain(SHORT_CODE).doesNotContain("transferstatus");
        assertThat(VendorCallLog.masked("https://transferstatus.top/abc12345")).isEqualTo("MASKED");
        assertThat(VendorCallLog.masked("HTTP_500")).isEqualTo("HTTP_500");
        assertThat(new FutTransferClient.LinkReady(LINK).toString()).doesNotContain(SHORT_CODE);
    }

    @Test
    @DisplayName("backfill: an order sent before links existed gets one on the next poll; a finished one does not")
    void backfill() {
        long open = db.order("GFS-26-LINKOLD1", "IN_PROGRESS", "0.5");
        String openVid = vendorId();
        db.vendorOrder(open, "GFS-26-LINKOLD1", openVid, "IN_DELIVERY", 500);
        long done = db.order("GFS-26-LINKOLD2", "DELIVERED", "0.5");
        db.vendorOrder(done, "GFS-26-LINKOLD2", vendorId(), "DELIVERED", 500);
        vendor.on("/generateOrderStatusLink", body -> Reply.ok(linkReply(LINK, body.get("orderID").asText())));

        links.sweep();

        assertThat(vendor.requests().stream().filter(q -> q.path().equals("/generateOrderStatusLink"))
                .map(q -> q.body().get("orderID").asText()).toList()).containsExactly(openVid);
        assertThat(row(open).get("tracking_url")).isEqualTo(LINK);
        assertThat(row(done).get("tracking_url")).isNull();
        assertThat(emailedLinks()).containsExactly(LINK);
    }

    @Test
    @DisplayName("concurrent approvals with the link step: still exactly one /buyCoinsAPI call, and one link")
    void stillOnce() throws Exception {
        long id = paid("GFS-26-LINK0007");
        String vid = vendorId();
        vendor.on("/buyCoinsAPI", new Reply(200, FakeFutTransfer.accepted(vid), 400));
        vendor.on("/generateOrderStatusLink", Reply.ok(linkReply(LINK, vid)));

        ExecutorService threads = Executors.newFixedThreadPool(6);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<SupplierFulfilmentService.Release>> results = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            results.add(threads.submit(() -> {
                go.await();
                return supplier.approveAndDispatch(entity(id), 1L);
            }));
        }
        go.countDown();
        List<Result> outcomes = new ArrayList<>();
        for (Future<SupplierFulfilmentService.Release> f : results) outcomes.add(f.get(15, TimeUnit.SECONDS).result());
        threads.shutdown();

        assertThat(vendor.calls("/buyCoinsAPI")).isOne();
        assertThat(outcomes).containsOnlyOnce(Result.SUBMITTED);
        assertThat(vendor.calls("/generateOrderStatusLink")).isOne();
        assertThat(row(id).get("tracking_url")).isEqualTo(LINK);
        verify(notifications, times(1)).coinsOnTheWay(any(), eq(LINK));
    }
}
