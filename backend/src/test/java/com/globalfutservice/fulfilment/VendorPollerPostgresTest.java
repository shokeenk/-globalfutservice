package com.globalfutservice.fulfilment;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.globalfutservice.config.AppProperties;
import com.globalfutservice.credentials.CredentialVaultService;
import com.globalfutservice.domain.orders.Actor;
import com.globalfutservice.domain.orders.OrderStatus;
import com.globalfutservice.fulfilment.FakeFutTransfer.Reply;
import com.globalfutservice.domain.orders.CustomerAction;
import com.globalfutservice.notify.CustomerActionNotification;
import com.globalfutservice.notify.FulfilmentAlert;
import com.globalfutservice.notify.OrderNotification;
import com.globalfutservice.notify.NotificationService;
import com.globalfutservice.orders.OrderEntity;
import com.globalfutservice.orders.OrderRepository;
import com.globalfutservice.orders.OrderService;
import com.globalfutservice.scheduling.SchedulerLock;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The poll, end to end against a real PostgreSQL and a stand-in vendor: what it asks, what
 * it records, and when it moves an order or calls a person. The order service, the vault
 * and the notifications are the only stand-ins.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class VendorPollerPostgresTest {

    private TestDatabase db;
    private FakeFutTransfer vendor;
    private OrderService orderService;
    private OrderRepository orders;
    private CredentialVaultService vault;
    private NotificationService notifications;
    private VendorPoller poller;
    private VendorOrderLedger ledger;
    private VendorControl control;
    /** Every order's status as the stand-in order service has moved it. */
    private final Map<Long, OrderStatus> statuses = new java.util.concurrent.ConcurrentHashMap<>();

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
        db.jdbc.update("delete from vendor_call");
        db.jdbc.update("delete from vendor_order");
        db.jdbc.update("update vendor_control set next_poll_at = null, backoff_level = 0, paused = false");
        vendor = new FakeFutTransfer();
        AppProperties props = VendorTestSupport.props(vendor.baseUrl(), Duration.ofMillis(800));
        orderService = mock(OrderService.class);
        orders = mock(OrderRepository.class);
        vault = mock(CredentialVaultService.class);
        notifications = mock(NotificationService.class);
        ledger = new VendorOrderLedger(db.named);
        control = new VendorControl(db.named, notifications, props);
        FutTransferClient client = new FutTransferClient(props, new ObjectMapper(), control, new VendorCallLog(db.named))
                .withoutRetryPauses();
        poller = new VendorPoller(client, control, ledger, new SchedulerLock(db.ds), orderService, orders, vault,
                notifications, props);

        when(orders.findById(anyLong())).thenAnswer(inv -> Optional.of(orderEntity(inv.getArgument(0))));
        when(orderService.transition(any(), any(), any(), any(), anyString(), anyString())).thenAnswer(inv -> {
            OrderEntity o = inv.getArgument(0);
            OrderStatus to = inv.getArgument(1);
            statuses.put(o.getId(), to);
            return orderEntity(o.getId());
        });
        when(orderService.notificationFor(any())).thenAnswer(inv -> {
            OrderEntity o = inv.getArgument(0);
            return new OrderNotification(o.getPublicRef(), o.getStatus().name(), "Buy Coins — 500K", "₹1,000.00",
                    "buyer@example.test", null, "PLAYER_AUCTION", "TRADING_SERVICE", "PC", null, null, null, null);
        });
    }

    /** Everything the customer was told they have to do, in order. */
    private List<CustomerActionNotification> customerNotices() {
        ArgumentCaptor<CustomerActionNotification> captor = ArgumentCaptor.forClass(CustomerActionNotification.class);
        verify(notifications, org.mockito.Mockito.atLeast(0)).customerActionNeeded(captor.capture());
        return captor.getAllValues();
    }

    @AfterEach
    void tearDown() {
        vendor.close();
    }

    private OrderEntity orderEntity(long id) {
        OrderEntity o = mock(OrderEntity.class);
        when(o.getId()).thenReturn(id);
        when(o.getPublicRef()).thenReturn("ref-" + id);
        when(o.getStatus()).thenAnswer(inv -> statuses.getOrDefault(id, OrderStatus.IN_PROGRESS));
        return o;
    }

    private long sent(String ref, String vendorId, String state, OrderStatus orderStatus) {
        long id = db.order(ref, orderStatus.name(), "0.5");
        db.vendorOrder(id, ref, vendorId, state, 500);
        statuses.put(id, orderStatus);
        return id;
    }

    private static String report(String status, String accountCheck, String economyState, long ordered, long delivered,
                                 int aborted) {
        return "{\"status\":\"%s\",\"accountCheck\":%s,\"economyState\":%s,\"amountOrdered\":%d,\"amount\":%d,\"coinsUsed\":1000,\"toPay\":0,\"wasAborted\":%d,\"ba1\":\"99887766\"}"
                .formatted(status, accountCheck == null ? "null" : "\"" + accountCheck + "\"",
                        economyState == null ? "null" : "\"" + economyState + "\"", ordered, delivered, aborted);
    }

    private void bulk(Map<String, String> byVendorId) {
        StringBuilder json = new StringBuilder("{");
        byVendorId.forEach((id, r) -> json.append(json.length() > 1 ? "," : "").append('"').append(id).append("\":").append(r));
        vendor.on("/orderStatusBulkAPI", Reply.ok(json.append('}').toString()));
    }

    private Map<String, Object> row(long orderId) {
        return db.jdbc.queryForMap("select * from vendor_order where order_id = ?", orderId);
    }

    private List<FulfilmentAlert> alerts() {
        ArgumentCaptor<FulfilmentAlert> captor = ArgumentCaptor.forClass(FulfilmentAlert.class);
        verify(notifications, atLeastOnce()).fulfilmentAlert(captor.capture());
        return captor.getAllValues();
    }

    // ------------------------------------------------------------------ asking ---

    @Test
    @DisplayName("reads by the vendor's ids only, twenty at a time, never with externalID")
    void bulkByVendorId() {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            String vid = "cccccccc-0000-0000-0000-%012d".formatted(i);
            ids.add(vid);
            sent("GFS-26-BULK%04d".formatted(i), vid, "IN_DELIVERY", OrderStatus.IN_PROGRESS);
        }
        vendor.on("/orderStatusBulkAPI", body -> {
            StringBuilder json = new StringBuilder("{");
            body.get("orderIDs").forEach(id -> json.append(json.length() > 1 ? "," : "").append('"').append(id.asText())
                    .append("\":").append(report("partlyDelivered", "finished", "transfersInProgress", 500, 100, 0)));
            return Reply.ok(json.append('}').toString());
        });

        poller.pollOnce();

        List<JsonNode> bodies = vendor.requests().stream().filter(r -> r.path().equals("/orderStatusBulkAPI"))
                .map(FakeFutTransfer.Request::body).toList();
        assertThat(bodies).hasSize(2);
        assertThat(bodies.get(0).get("orderIDs").size()).isEqualTo(20);
        assertThat(bodies.get(1).get("orderIDs").size()).isEqualTo(5);
        assertThat(bodies).allMatch(b -> !b.has("externalID"));
        List<String> asked = new ArrayList<>();
        bodies.forEach(b -> b.get("orderIDs").forEach(id -> asked.add(id.asText())));
        assertThat(asked).containsExactlyInAnyOrderElementsOf(ids);
        assertThat(db.jdbc.queryForObject("select count(*) from vendor_order where amount_delivered_k = 100", Integer.class))
                .isEqualTo(25);
    }

    @Test
    @DisplayName("an order confirmed without a vendor id is read alone, by our reference")
    void byReference() {
        long id = sent("GFS-26-BYREF01", null, "SUBMITTED", OrderStatus.IN_PROGRESS);
        vendor.on("/orderStatusAPI", Reply.ok(FakeFutTransfer.status("GFS-26-BYREF01", 500)));

        poller.pollOnce();

        FakeFutTransfer.Request asked = vendor.requests().stream().filter(r -> r.path().equals("/orderStatusAPI"))
                .findFirst().orElseThrow();
        assertThat(asked.body().get("orderID").asText()).isEqualTo("GFS-26-BYREF01");
        assertThat(asked.body().get("externalID").asInt()).isEqualTo(1);
        assertThat(vendor.calls("/orderStatusBulkAPI")).isZero();
        assertThat(row(id).get("state")).isEqualTo("IN_DELIVERY");
    }

    // ---------------------------------------------------------------- delivery ---

    @Test
    @DisplayName("finished with every coin: delivered, the customer told in our words, the sign-in deleted")
    void delivered() {
        long id = sent("GFS-26-DONE0001", "vid-done", "IN_DELIVERY", OrderStatus.IN_PROGRESS);
        bulk(Map.of("vid-done", report("finished", "finished", "finished", 500, 500, 0)));

        poller.pollOnce();

        assertThat(row(id).get("state")).isEqualTo("DELIVERED");
        verify(orderService).transition(any(), eq(OrderStatus.DELIVERED), eq(Actor.SYSTEM), eq(null), eq("GFS"),
                eq("All your coins have been delivered."));
        verify(vault).purge(eq(id), anyString());
        verify(notifications, never()).fulfilmentAlert(any());
    }

    /** A poller running with the public pool configured, as after the switch. */
    private VendorPoller publicPoolPoller() {
        AppProperties props = VendorTestSupport.props(vendor.baseUrl(), VendorTestSupport.NO_BACKUP,
                Duration.ofMillis(800), AppProperties.FutTransferOrderMode.PUBLIC_POOL,
                VendorTestSupport.ORDER_AMOUNT_POOL);
        FutTransferClient client = new FutTransferClient(props, new ObjectMapper(), control, new VendorCallLog(db.named))
                .withoutRetryPauses();
        return new VendorPoller(client, control, ledger, new SchedulerLock(db.ds), orderService, orders, vault,
                notifications, props);
    }

    @Test
    @DisplayName("after the switch to the public pool, an order placed through /orderAPI is followed exactly as before")
    void ownSendersOrderAfterSwitch() {
        long id = sent("GFS-26-OLDMODE01", "vid-oldmode", "IN_DELIVERY", OrderStatus.IN_PROGRESS);
        assertThat(row(id).get("order_mode")).isEqualTo("OWN_SENDERS");
        bulk(Map.of("vid-oldmode", report("finished", "finished", "finished", 500, 500, 0)));

        publicPoolPoller().pollOnce();

        assertThat(row(id).get("state")).isEqualTo("DELIVERED");
        assertThat(row(id).get("order_mode")).isEqualTo("OWN_SENDERS");
        verify(orderService).transition(any(), eq(OrderStatus.DELIVERED), eq(Actor.SYSTEM), eq(null), eq("GFS"),
                eq("All your coins have been delivered."));
        assertThat(vendor.calls("/buyCoinsAPI")).isZero();
        assertThat(vendor.calls("/orderAPI")).isZero();
    }

    @Test
    @DisplayName("a report about a mother order is never delivered, even with every coin: an admin looks")
    void motherOrderNeverDelivered() {
        long id = db.order("GFS-26-MOTHER001", OrderStatus.IN_PROGRESS.name(), "0.5");
        db.vendorOrder(id, "GFS-26-MOTHER001", "vid-mother", "IN_DELIVERY", 500, "PUBLIC_POOL");
        statuses.put(id, OrderStatus.IN_PROGRESS);
        bulk(Map.of("vid-mother", report("finished", "finished", "finished", 500, 500, 0)
                .replace("\"wasAborted\"", "\"isMotherID\":1,\"wasAborted\"")));

        publicPoolPoller().pollOnce();

        assertThat(row(id).get("state")).isEqualTo("NEEDS_REVIEW");
        assertThat(row(id).get("last_error_code")).isEqualTo("MOTHER_ORDER");
        verify(orderService, never()).transition(any(), eq(OrderStatus.DELIVERED), any(), any(), anyString(), anyString());
        verify(vault, never()).purge(anyLong(), anyString());
        assertThat(alerts()).isNotEmpty();
    }

    /** A 10K order with the partner, delivering, sent in {@code mode}. */
    private long tenK(String ref, String vendorId, String mode) {
        long id = db.order(ref, OrderStatus.IN_PROGRESS.name(), "0.01");
        db.vendorOrder(id, ref, vendorId, "IN_DELIVERY", 10, mode);
        statuses.put(id, OrderStatus.IN_PROGRESS);
        return id;
    }

    @Test
    @DisplayName("no suitable sender on an own-senders order: to review, said plainly with what is left, staff told, customer not")
    void noSuitableSenderOwnSenders() {
        long id = tenK("GFS-26-NOSEND01", "vid-nosend-own", "OWN_SENDERS");
        bulk(Map.of("vid-nosend-own", report("interrupted", "finished", "noSuitableSender", 10, 3, 0)));

        poller.pollOnce();

        String expected = "No sender available for own-senders order: 3K of 10K delivered, 7K remaining.";
        assertThat(row(id).get("state")).isEqualTo("NEEDS_REVIEW");
        assertThat(row(id).get("last_error_code")).isEqualTo("NO_SUITABLE_SENDER");
        assertThat((String) row(id).get("review_reason")).startsWith(expected)
                .contains("economyState noSuitableSender");
        assertThat(alerts()).singleElement().satisfies(a -> {
            assertThat(a.headline()).isEqualTo("No sender available");
            assertThat(a.detail()).startsWith(expected);
            assertThat(a.code()).isEqualTo("NO_SUITABLE_SENDER");
        });
        // The customer's order is not moved, and they are not told anything.
        verify(orderService, never()).transition(any(), any(), any(), any(), anyString(), anyString());
        assertThat(customerNotices()).isEmpty();
        verify(vault, never()).purge(anyLong(), anyString());
        // Waiting for an admin now: no longer asked about, so no second alert.
        assertThat(ledger.openForPolling()).extracting(VendorOrderLedger.PollRow::orderId).doesNotContain(id);
        poller.pollOnce();
        assertThat(alerts()).hasSize(1);
    }

    @Test
    @DisplayName("no suitable sender on a public-pool order says no seller is left in the pool")
    void noSuitableSenderPublicPool() {
        long id = tenK("GFS-26-NOSEND02", "vid-nosend-pool", "PUBLIC_POOL");
        bulk(Map.of("vid-nosend-pool", report("partlyDelivered", "finished", "noSuitableSender", 10, 3, 0)));

        poller.pollOnce();

        assertThat(row(id).get("last_error_code")).isEqualTo("NO_SUITABLE_SENDER");
        assertThat((String) row(id).get("review_reason"))
                .startsWith("No seller available in the public pool for this order: 3K of 10K delivered, 7K remaining.");
        assertThat(alerts()).singleElement().satisfies(a -> assertThat(a.headline()).isEqualTo("No seller available"));
    }

    @Test
    @DisplayName("finished short: partly delivered, the amount kept, the order left alone, an admin told")
    void shortDelivery() {
        long id = sent("GFS-26-SHORT001", "vid-short", "IN_DELIVERY", OrderStatus.IN_PROGRESS);
        bulk(Map.of("vid-short", report("finished", "finished", "finished", 500, 420, 0)));

        poller.pollOnce();

        assertThat(row(id).get("state")).isEqualTo("PARTIALLY_DELIVERED");
        assertThat(row(id).get("amount_delivered_k")).isEqualTo(420L);
        assertThat(row(id).get("last_error_code")).isEqualTo("SHORT_DELIVERY");
        verify(orderService, never()).transition(any(), any(), any(), any(), anyString(), anyString());
        verify(vault, never()).purge(anyLong(), anyString());
        assertThat(alerts()).anyMatch(a -> a.headline().equals("Partly delivered") && a.detail().contains("420K of 500K"));
    }

    @Test
    @DisplayName("aborted or interrupted: never delivered, and an admin is told")
    void abortedOrInterrupted() {
        long aborted = sent("GFS-26-ABORT001", "vid-abort", "IN_DELIVERY", OrderStatus.IN_PROGRESS);
        long stopped = sent("GFS-26-INTER001", "vid-inter", "IN_DELIVERY", OrderStatus.IN_PROGRESS);
        bulk(Map.of("vid-abort", report("finished", "finished", "finished", 500, 500, 1),
                "vid-inter", report("interrupted", "finished", "entered", 500, 0, 0)));

        poller.pollOnce();

        assertThat(row(aborted).get("state")).isEqualTo("PARTIALLY_DELIVERED");
        assertThat(row(stopped).get("state")).isEqualTo("NEEDS_REVIEW");
        assertThat(row(stopped).get("last_error_code")).isEqualTo("INTERRUPTED");
        verify(orderService, never()).transition(any(), eq(OrderStatus.DELIVERED), any(), any(), anyString(), anyString());
        assertThat(alerts()).hasSize(2);
    }

    // ---------------------------------------------------------------- surprises ---

    @Test
    @DisplayName("an unknown status goes to an admin, with the raw code kept")
    void unknownStatus() {
        long id = sent("GFS-26-UNKN0001", "vid-unknown", "IN_DELIVERY", OrderStatus.IN_PROGRESS);
        bulk(Map.of("vid-unknown", report("almostThere", "finished", "transfersInProgress", 500, 100, 0)));

        poller.pollOnce();

        assertThat(row(id).get("state")).isEqualTo("NEEDS_REVIEW");
        assertThat(row(id).get("vendor_status")).isEqualTo("almostThere");
        assertThat(row(id).get("last_error_code")).isEqualTo("UNKNOWN_STATUS");
        assertThat(alerts()).anyMatch(a -> a.code().equals("UNKNOWN_STATUS") && a.detail().contains("almostThere"));
    }

    @Test
    @DisplayName("a code the customer can fix: the order goes on hold, with what to do, in our words")
    void customerFixable() {
        long id = sent("GFS-26-FIXME001", "vid-fix", "SUBMITTED", OrderStatus.IN_PROGRESS);
        bulk(Map.of("vid-fix", report("interrupted", "wrongBA", null, 500, 0, 0)));

        poller.pollOnce();

        assertThat(row(id).get("state")).isEqualTo("AWAITING_CUSTOMER");
        assertThat(ledger.customerAction(id)).contains("NEW_BACKUP_CODES");
        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        verify(orderService).transition(any(), eq(OrderStatus.ON_HOLD), eq(Actor.SYSTEM), eq(null), eq("GFS"), reason.capture());
        assertThat(reason.getValue()).contains("backup codes").doesNotContain("wrongBA");

        // Told once, by email and ticket, in the same words -- never the vendor's.
        assertThat(customerNotices()).singleElement().satisfies(n -> {
            assertThat(n.instruction()).isEqualTo(CustomerText.forAction(CustomerAction.NEW_BACKUP_CODES));
            assertThat(n.order().publicRef()).isEqualTo("ref-" + id);
        });
        // Staff hear about it separately, with the vendor's code.
        assertThat(alerts()).singleElement().satisfies(a -> assertThat(a.detail()).contains("wrongBA"));

        // The same report again is not news.
        poller.pollOnce();
        assertThat(customerNotices()).hasSize(1);
    }

    @Test
    @DisplayName("still waiting, but now for something else: the customer is asked again, for the new thing")
    void newAsk() {
        long id = sent("GFS-26-NEWASK01", "vid-newask", "SUBMITTED", OrderStatus.IN_PROGRESS);
        bulk(Map.of("vid-newask", report("interrupted", "wrongUserPass", null, 500, 0, 0)));
        poller.pollOnce();
        bulk(Map.of("vid-newask", report("interrupted", "console", null, 500, 0, 0)));
        poller.pollOnce();

        assertThat(row(id).get("state")).isEqualTo("AWAITING_CUSTOMER");
        assertThat(ledger.customerAction(id)).contains("SIGN_OUT_CONSOLE");
        assertThat(customerNotices()).extracting(CustomerActionNotification::instruction).containsExactly(
                CustomerText.forAction(CustomerAction.RESUBMIT_SIGN_IN),
                CustomerText.forAction(CustomerAction.SIGN_OUT_CONSOLE));
        // Moved on hold once; the second ask leaves it where it is.
        verify(orderService, times(1)).transition(any(), eq(OrderStatus.ON_HOLD), any(), any(), anyString(), anyString());
    }

    @Test
    @DisplayName("a customer who has already re-entered their details is not told the old ones were refused")
    void notToldAfterResubmitting() {
        long id = sent("GFS-26-RESUB001", "vid-resub", "AWAITING_CUSTOMER", OrderStatus.READY_FOR_DELIVERY);
        db.jdbc.update("update vendor_order set customer_action = 'RESUBMIT_SIGN_IN' where order_id = ?", id);
        bulk(Map.of("vid-resub", report("interrupted", "wrongBA", null, 500, 0, 0)));

        poller.pollOnce();

        assertThat(ledger.customerAction(id)).contains("NEW_BACKUP_CODES");
        assertThat(customerNotices()).isEmpty();
        verify(orderService, never()).transition(any(), any(), any(), any(), anyString(), anyString());
    }

    @Test
    @DisplayName("an order the state machine will not put on hold: staff are told, the customer is not")
    void notToldWhenNotHeld() {
        sent("GFS-26-NOHOLD01", "vid-nohold", "SUBMITTED", OrderStatus.DELIVERED);
        bulk(Map.of("vid-nohold", report("interrupted", "wrongBA", null, 500, 0, 0)));

        poller.pollOnce();

        assertThat(customerNotices()).isEmpty();
        assertThat(alerts()).extracting(FulfilmentAlert::code).contains("CUSTOMER_ACTION");
    }

    @Test
    @DisplayName("a sign-in held for review is listed for deletion once untouched past the retention, not before")
    void reviewRetention() {
        long old = sent("GFS-26-REVOLD01", "vid-revold", "NEEDS_REVIEW", OrderStatus.IN_PROGRESS);
        long fresh = sent("GFS-26-REVNEW01", "vid-revnew", "PARTIALLY_DELIVERED", OrderStatus.IN_PROGRESS);
        long purged = sent("GFS-26-REVGON01", "vid-revgon", "NEEDS_REVIEW", OrderStatus.IN_PROGRESS);
        long working = sent("GFS-26-REVWRK01", "vid-revwrk", "IN_DELIVERY", OrderStatus.IN_PROGRESS);
        for (long id : List.of(old, fresh, purged, working)) {
            db.jdbc.update("insert into credential_vault (order_id, purge_after) values (?, now() + interval '30 days')", id);
        }
        db.jdbc.update("update credential_vault set purged_at = now() where order_id = ?", purged);
        db.jdbc.update("update vendor_order set updated_at = now() - interval '73 hours' where order_id in (?, ?, ?)",
                old, purged, working);

        assertThat(ledger.heldForReviewLongerThan(Duration.ofHours(72))).containsExactly(old);
    }

    @Test
    @DisplayName("an id the vendor leaves out is counted, and after three in a row goes to an admin")
    void missingIds() {
        long kept = sent("GFS-26-KEPT0001", "vid-kept", "IN_DELIVERY", OrderStatus.IN_PROGRESS);
        long lost = sent("GFS-26-LOST0001", "vid-lost", "IN_DELIVERY", OrderStatus.IN_PROGRESS);
        bulk(Map.of("vid-kept", report("partlyDelivered", "finished", "transfersInProgress", 500, 200, 0)));

        poller.pollOnce();
        poller.pollOnce();
        assertThat(row(lost).get("missing_polls")).isEqualTo(2);
        assertThat(row(lost).get("state")).isEqualTo("IN_DELIVERY");

        poller.pollOnce();
        assertThat(row(lost).get("state")).isEqualTo("NEEDS_REVIEW");
        assertThat(row(lost).get("last_error_code")).isEqualTo("MISSING_FROM_POLL");
        assertThat(row(kept).get("missing_polls")).isEqualTo(0);
        assertThat(alerts()).anyMatch(a -> a.publicRef().equals("GFS-26-LOST0001"));
    }

    @Test
    @DisplayName("a send left SUBMITTING by a restart is looked up: found is submitted, anything else goes to an admin")
    void staleSubmitting() {
        long found = sent("GFS-26-STALEOK1", null, "SUBMITTING", OrderStatus.READY_FOR_DELIVERY);
        long lost = sent("GFS-26-STALENO1", null, "SUBMITTING", OrderStatus.READY_FOR_DELIVERY);
        db.jdbc.update("update vendor_order set updated_at = now() - interval '5 minutes'");
        vendor.on("/orderStatusAPI", body -> body.get("orderID").asText().equals("GFS-26-STALEOK1")
                ? Reply.ok(FakeFutTransfer.status("GFS-26-STALEOK1", 500)) : Reply.of(404, "notFound"));

        poller.pollOnce();

        assertThat(row(found).get("state")).isIn("SUBMITTED", "IN_DELIVERY");
        verify(orderService).transition(any(), eq(OrderStatus.IN_PROGRESS), any(), any(), eq("GFS"), anyString());
        assertThat(row(lost).get("state")).isEqualTo("NEEDS_REVIEW");
        assertThat(row(lost).get("last_error_code")).isEqualTo("STALE_SUBMITTING");
        assertThat(vendor.calls("/orderAPI")).isZero();
    }

    @Test
    @DisplayName("no progress for longer than the stall time goes to an admin")
    void stalled() {
        long id = sent("GFS-26-STALL001", "vid-stall", "IN_DELIVERY", OrderStatus.IN_PROGRESS);
        db.jdbc.update("update vendor_order set vendor_status = 'partlyDelivered', amount_delivered_k = 100, "
                + "last_progress_at = now() - interval '7 hours' where order_id = ?", id);
        bulk(Map.of("vid-stall", report("partlyDelivered", "finished", "transfersInProgress", 500, 100, 0)));

        poller.pollOnce();

        assertThat(row(id).get("state")).isEqualTo("NEEDS_REVIEW");
        assertThat(row(id).get("last_error_code")).isEqualTo("STALLED");
    }

    @Test
    @DisplayName("the schedule lives in the database: due, then not, and a troubled poll waits longer")
    void schedule() {
        assertThat(control.pollDue()).isTrue();
        Duration calm = control.scheduleNextPoll(false, Duration.ofSeconds(60), Duration.ZERO, Duration.ofMinutes(15));
        assertThat(calm).isEqualTo(Duration.ofSeconds(60));
        assertThat(control.pollDue()).isFalse();

        Duration first = control.scheduleNextPoll(true, Duration.ofSeconds(60), Duration.ZERO, Duration.ofMinutes(15));
        Duration second = control.scheduleNextPoll(true, Duration.ofSeconds(60), Duration.ZERO, Duration.ofMinutes(15));
        assertThat(first).isEqualTo(Duration.ofSeconds(120));
        assertThat(second).isEqualTo(Duration.ofSeconds(240));
        for (int i = 0; i < 10; i++) {
            control.scheduleNextPoll(true, Duration.ofSeconds(60), Duration.ZERO, Duration.ofMinutes(15));
        }
        assertThat(control.scheduleNextPoll(true, Duration.ofSeconds(60), Duration.ZERO, Duration.ofMinutes(15)))
                .isEqualTo(Duration.ofMinutes(15));
        assertThat(control.scheduleNextPoll(false, Duration.ofSeconds(60), Duration.ofSeconds(10), Duration.ofMinutes(15)))
                .isBetween(Duration.ofSeconds(60), Duration.ofSeconds(70));
    }

    @Test
    @DisplayName("rate limited or down: the poll reports trouble so the next waits longer, and nothing moves")
    void troubled() {
        long id = sent("GFS-26-TROUBLE1", "vid-trouble", "IN_DELIVERY", OrderStatus.IN_PROGRESS);
        vendor.on("/orderStatusBulkAPI", Reply.of(429, "Too many requests"));

        assertThat(poller.pollOnce()).isTrue();
        assertThat(row(id).get("state")).isEqualTo("IN_DELIVERY");
        assertThat(row(id).get("missing_polls")).isEqualTo(0);
    }

    @Test
    @DisplayName("the backup codes the vendor sends back are never stored")
    void noReturnedCodesStored() {
        sent("GFS-26-CODES001", "vid-codes", "IN_DELIVERY", OrderStatus.IN_PROGRESS);
        bulk(Map.of("vid-codes", report("partlyDelivered", "finished", "transfersInProgress", 500, 100, 0)));

        poller.pollOnce();

        String stored = String.join("\n", db.jdbc.queryForList("select row_to_json(v)::text from vendor_order v", String.class))
                + String.join("\n", db.jdbc.queryForList("select row_to_json(c)::text from vendor_call c", String.class));
        assertThat(stored).doesNotContain("99887766");
    }
}
