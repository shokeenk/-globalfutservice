package com.globalfutservice.fulfilment;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.globalfutservice.config.AppProperties;
import com.globalfutservice.credentials.CredentialVaultService;
import com.globalfutservice.domain.orders.Actor;
import com.globalfutservice.domain.orders.OrderStatus;
import com.globalfutservice.fulfilment.FakeFutTransfer.Reply;
import com.globalfutservice.fulfilment.VendorOrderActions.Status;
import com.globalfutservice.orders.OrderEntity;
import com.globalfutservice.orders.OrderService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Resume, stop, mark finished, clear to send again, link and resolve, against a real
 * PostgreSQL and a stand-in vendor: what each sends, what it changes, what it refuses, and
 * that every one is on the record with who did it.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class VendorAdminActionsPostgresTest {

    private static final VendorOrderActions.Admin ADMIN = new VendorOrderActions.Admin(null, "admin@example.test", "acc_admin");

    private TestDatabase db;
    private FakeFutTransfer vendor;
    private VendorOrderLedger ledger;
    private OrderService orderService;
    private VendorOrderActions actions;
    private final Map<String, OrderStatus> statuses = new ConcurrentHashMap<>();
    private final Map<String, Long> ids = new ConcurrentHashMap<>();

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
        db.jdbc.update("delete from vendor_order_action");
        db.jdbc.update("delete from vendor_call");
        db.jdbc.update("delete from vendor_order");
        db.jdbc.update("update vendor_control set paused = false");
        vendor = new FakeFutTransfer();
        AppProperties props = VendorTestSupport.props(vendor.baseUrl(), Duration.ofMillis(800));
        VendorControl control = VendorTestSupport.running();
        FutTransferClient client = new FutTransferClient(props, new ObjectMapper(), control,
                new VendorCallLog(db.named)).withoutRetryPauses();
        ledger = new VendorOrderLedger(db.named);
        orderService = mock(OrderService.class);
        when(orderService.requireAny(anyString())).thenAnswer(inv -> order(inv.getArgument(0)));
        when(orderService.transition(any(), any(), any(), any(), anyString(), anyString())).thenAnswer(inv -> {
            OrderEntity o = inv.getArgument(0);
            statuses.put(o.getPublicRef(), inv.getArgument(1));
            return order(o.getPublicRef());
        });
        actions = new VendorOrderActions(client, control, ledger, new VendorOrderActionLog(db.named),
                mock(CredentialVaultService.class), orderService, props);
    }

    @AfterEach
    void tearDown() {
        vendor.close();
    }

    private OrderEntity order(String ref) {
        OrderEntity o = mock(OrderEntity.class);
        when(o.getId()).thenReturn(ids.get(ref));
        when(o.getPublicRef()).thenReturn(ref);
        when(o.getStatus()).thenAnswer(inv -> statuses.get(ref));
        return o;
    }

    private OrderEntity at(String ref, String vendorId, String state, OrderStatus status) {
        long id = db.order(ref, status.name(), "0.5");
        db.vendorOrder(id, ref, vendorId, state, 500);
        ids.put(ref, id);
        statuses.put(ref, status);
        return order(ref);
    }

    private Map<String, Object> row(String ref) {
        return db.jdbc.queryForMap("select * from vendor_order where order_id = ?", ids.get(ref));
    }

    private List<Map<String, Object>> audit(String ref) {
        return db.jdbc.queryForList("select * from vendor_order_action where order_id = ? order by id", ids.get(ref));
    }

    // ------------------------------------------------------------------ resume ---

    @Test
    @DisplayName("resume: the vendor restarts it, the order is back in progress, and it is on the record")
    void resume() {
        OrderEntity o = at("GFS-26-RESUME01", "vid-resume-01", "AWAITING_CUSTOMER", OrderStatus.ON_HOLD);
        vendor.on("/resumeOrderAPI", Reply.ok("{\"outcome\":\"resumed\"}"));

        assertThat(actions.resume(o, ADMIN).status()).isEqualTo(Status.DONE);

        assertThat(vendor.requests()).singleElement().satisfies(r -> {
            assertThat(r.path()).isEqualTo("/resumeOrderAPI");
            assertThat(r.body().path("mode").asText()).isEqualTo("resume");
            assertThat(r.body().path("orderID").asText()).isEqualTo("vid-resume-01");
        });
        assertThat(row("GFS-26-RESUME01").get("state")).isEqualTo("SUBMITTED");
        verify(orderService).transition(any(), eq(OrderStatus.IN_PROGRESS), eq(Actor.OPERATOR), eq(null),
                eq("acc_admin"), anyString());
        assertThat(audit("GFS-26-RESUME01")).singleElement().satisfies(a -> {
            assertThat(a.get("action")).isEqualTo("RESUME");
            assertThat(a.get("outcome")).isEqualTo("DONE");
        });
    }

    @Test
    @DisplayName("resume during the console cooldown: refused in plain words, nothing changes, and it can be tried again")
    void resumeCooldown() {
        OrderEntity o = at("GFS-26-RESUME02", "vid-resume-02", "AWAITING_CUSTOMER", OrderStatus.ON_HOLD);
        vendor.on("/resumeOrderAPI", Reply.of(429, "Too Many Requests"));

        VendorOrderActions.Result r = actions.resume(o, ADMIN);

        assertThat(r.status()).isEqualTo(Status.REFUSED);
        assertThat(r.message()).contains("cooldown after a console sign-in failure");
        assertThat(row("GFS-26-RESUME02").get("state")).isEqualTo("AWAITING_CUSTOMER");
        assertThat(row("GFS-26-RESUME02").get("resubmitted_at")).isNull();
        verify(orderService, never()).transition(any(), any(), any(), any(), anyString(), anyString());
    }

    // -------------------------------------------------------- stop and finish ---

    @Test
    @DisplayName("stop: sent once as mode stop; the order is left to the poll, which records how it ends")
    void stop() {
        OrderEntity o = at("GFS-26-STOP0001", "vid-stop-01", "IN_DELIVERY", OrderStatus.IN_PROGRESS);
        vendor.on("/resumeOrderAPI", Reply.ok("{\"outcome\":\"stopped\"}"));

        assertThat(actions.stop(o, ADMIN).status()).isEqualTo(Status.DONE);

        assertThat(vendor.requests()).singleElement()
                .satisfies(r -> assertThat(r.body().path("mode").asText()).isEqualTo("stop"));
        assertThat(row("GFS-26-STOP0001").get("state")).isEqualTo("IN_DELIVERY");
        assertThat(audit("GFS-26-STOP0001")).extracting(a -> a.get("action")).containsExactly("STOP");
    }

    @Test
    @DisplayName("mark finished says what was ordered against what was delivered")
    void markFinished() {
        OrderEntity o = at("GFS-26-FINISH01", "vid-finish-01", "PARTIALLY_DELIVERED", OrderStatus.IN_PROGRESS);
        db.jdbc.update("update vendor_order set amount_delivered_k = 420 where order_id = ?", ids.get("GFS-26-FINISH01"));
        vendor.on("/markFinishedAPI", Reply.ok("{\"outcome\":\"marked\"}"));

        VendorOrderActions.Result r = actions.markFinished(o, ADMIN);

        assertThat(r.status()).isEqualTo(Status.DONE);
        assertThat(r.message()).contains("420K of 500K");
        assertThat(vendor.calls("/markFinishedAPI")).isEqualTo(1);
    }

    @Test
    @DisplayName("nothing is sent to an order that is finished, or that we cannot address by the vendor's id")
    void notAddressable() {
        OrderEntity done = at("GFS-26-NOADDR01", "vid-noaddr-01", "DELIVERED", OrderStatus.DELIVERED);
        assertThat(actions.stop(done, ADMIN).status()).isEqualTo(Status.NOT_SENT);
        assertThat(actions.markFinished(done, ADMIN).status()).isEqualTo(Status.NOT_SENT);
        assertThat(actions.resume(done, ADMIN).status()).isEqualTo(Status.NOT_SENT);

        OrderEntity noId = at("GFS-26-NOADDR02", null, "NEEDS_REVIEW", OrderStatus.READY_FOR_DELIVERY);
        assertThat(actions.stop(noId, ADMIN).message()).contains("Link it first");

        assertThat(vendor.requests()).isEmpty();
    }

    // ------------------------------------------------------------------ retry ---

    @Test
    @DisplayName("clear to send again: only with the admin's word, and only if our lookup does not find it")
    void allowResend() {
        OrderEntity o = at("GFS-26-RETRY001", null, "NEEDS_REVIEW", OrderStatus.READY_FOR_DELIVERY);

        assertThat(actions.allowResend(o, ADMIN, false).status()).isEqualTo(Status.NOT_SENT);
        assertThat(vendor.requests()).isEmpty();

        // Unscripted, the stand-in answers 404: the vendor does not have it.
        VendorOrderActions.Result r = actions.allowResend(o, ADMIN, true);

        assertThat(r.status()).isEqualTo(Status.DONE);
        assertThat(r.message()).contains("approve the order");
        assertThat(row("GFS-26-RETRY001").get("state")).isEqualTo("FAILED");
        assertThat(row("GFS-26-RETRY001").get("last_error_code")).isEqualTo("ADMIN_CONFIRMED_ABSENT");
        assertThat(vendor.calls("/orderAPI")).isZero();
        // And Approve may now claim it.
        assertThat(ledger.claim(ids.get("GFS-26-RETRY001"), "GFS-26-RETRY001", 500, 3))
                .isInstanceOf(VendorOrderLedger.Claimed.class);
    }

    @Test
    @DisplayName("clear to send again is refused if the vendor has it, has one for another amount, or cannot be asked")
    void allowResendRefused() {
        OrderEntity found = at("GFS-26-RETRY002", null, "NEEDS_REVIEW", OrderStatus.READY_FOR_DELIVERY);
        vendor.on("/orderStatusAPI", Reply.ok(FakeFutTransfer.status("GFS-26-RETRY002", 500)));
        assertThat(actions.allowResend(found, ADMIN, true).message()).contains("does have").contains("Link it");

        OrderEntity other = at("GFS-26-RETRY003", null, "NEEDS_REVIEW", OrderStatus.READY_FOR_DELIVERY);
        vendor.on("/orderStatusAPI", Reply.ok(FakeFutTransfer.status("GFS-26-RETRY003", 900)));
        assertThat(actions.allowResend(other, ADMIN, true).message()).contains("different amount");

        OrderEntity down = at("GFS-26-RETRY004", null, "NEEDS_REVIEW", OrderStatus.READY_FOR_DELIVERY);
        vendor.on("/orderStatusAPI", Reply.of(503, "down"));
        assertThat(actions.allowResend(down, ADMIN, true).message()).contains("Could not check");

        OrderEntity withId = at("GFS-26-RETRY005", "vid-retry-05", "NEEDS_REVIEW", OrderStatus.READY_FOR_DELIVERY);
        assertThat(actions.allowResend(withId, ADMIN, true).message()).contains("exists there");

        for (String ref : List.of("GFS-26-RETRY002", "GFS-26-RETRY003", "GFS-26-RETRY004", "GFS-26-RETRY005")) {
            assertThat(row(ref).get("state")).as(ref).isEqualTo("NEEDS_REVIEW");
        }
        assertThat(audit("GFS-26-RETRY002")).singleElement().satisfies(a -> {
            assertThat(a.get("outcome")).isEqualTo("REFUSED");
            assertThat(a.get("code")).isEqualTo("FOUND");
        });
        assertThat(vendor.calls("/orderAPI")).isZero();
    }

    // ------------------------------------------------------------------- link ---

    @Test
    @DisplayName("link by our reference: confirmed by lookup for our amount, watched again, the order started")
    void linkByReference() {
        OrderEntity o = at("GFS-26-LINK0001", null, "NEEDS_REVIEW", OrderStatus.READY_FOR_DELIVERY);
        vendor.on("/orderStatusAPI", Reply.ok(FakeFutTransfer.status("GFS-26-LINK0001", 500)));

        assertThat(actions.link(o, ADMIN, null).status()).isEqualTo(Status.DONE);

        assertThat(row("GFS-26-LINK0001").get("state")).isEqualTo("SUBMITTED");
        verify(orderService).transition(any(), eq(OrderStatus.IN_PROGRESS), any(), any(), anyString(), anyString());
        assertThat(audit("GFS-26-LINK0001")).extracting(a -> a.get("action")).containsExactly("LINK");
    }

    @Test
    @DisplayName("link by the vendor's id: only for our amount, and never an id another order holds")
    void linkById() {
        OrderEntity o = at("GFS-26-LINK0002", null, "NEEDS_REVIEW", OrderStatus.READY_FOR_DELIVERY);
        vendor.on("/orderStatusBulkAPI", Reply.ok("{\"vid-link-02\":{\"status\":\"active\",\"amountOrdered\":900}}"));
        assertThat(actions.link(o, ADMIN, "vid-link-02").message()).contains("900K, not 500K");
        assertThat(row("GFS-26-LINK0002").get("state")).isEqualTo("NEEDS_REVIEW");

        at("GFS-26-LINK0003", "vid-taken", "SUBMITTED", OrderStatus.IN_PROGRESS);
        vendor.on("/orderStatusBulkAPI", Reply.ok("{\"vid-taken\":{\"status\":\"active\",\"amountOrdered\":500}}"));
        assertThat(actions.link(o, ADMIN, "vid-taken").message()).contains("already linked");
        assertThat(row("GFS-26-LINK0002").get("state")).isEqualTo("NEEDS_REVIEW");

        vendor.on("/orderStatusBulkAPI", Reply.ok("{\"vid-link-02b\":{\"status\":\"active\",\"amountOrdered\":500}}"));
        assertThat(actions.link(o, ADMIN, "vid-link-02b").status()).isEqualTo(Status.DONE);
        assertThat(row("GFS-26-LINK0002").get("vendor_order_id")).isEqualTo("vid-link-02b");
        assertThat(db.jdbc.queryForObject("select supplier_order_id from orders where id = ?", String.class,
                ids.get("GFS-26-LINK0002"))).isEqualTo("vid-link-02b");
    }

    // ---------------------------------------------------------------- resolve ---

    @Test
    @DisplayName("resolve: closed with the admin's note, sending nothing, and only from a state that needs a decision")
    void resolve() {
        OrderEntity o = at("GFS-26-RESOLVE1", "vid-resolve-1", "PARTIALLY_DELIVERED", OrderStatus.IN_PROGRESS);
        assertThat(actions.resolve(o, ADMIN, "ok").status()).isEqualTo(Status.NOT_SENT);

        assertThat(actions.resolve(o, ADMIN, "Refunded the 80K not delivered").status()).isEqualTo(Status.DONE);
        assertThat(row("GFS-26-RESOLVE1").get("state")).isEqualTo("RESOLVED");
        assertThat(row("GFS-26-RESOLVE1").get("review_reason")).isEqualTo("Refunded the 80K not delivered");

        OrderEntity working = at("GFS-26-RESOLVE2", "vid-resolve-2", "IN_DELIVERY", OrderStatus.IN_PROGRESS);
        assertThat(actions.resolve(working, ADMIN, "Trying to close it").message()).contains("IN_DELIVERY");

        assertThat(vendor.requests()).isEmpty();
        verify(orderService, never()).transition(any(), any(), any(), any(), anyString(), anyString());
    }

    // ------------------------------------------------------------------ views ---

    @Test
    @DisplayName("the review list holds what needs a decision, longest-waiting first, and nothing else")
    void needingReview() {
        at("GFS-26-LIST0001", null, "NEEDS_REVIEW", OrderStatus.READY_FOR_DELIVERY);
        at("GFS-26-LIST0002", "vid-list-2", "PARTIALLY_DELIVERED", OrderStatus.IN_PROGRESS);
        at("GFS-26-LIST0003", "vid-list-3", "IN_DELIVERY", OrderStatus.IN_PROGRESS);
        db.jdbc.update("update vendor_order set updated_at = now() - interval '1 hour' where external_ref = 'GFS-26-LIST0002'");

        assertThat(ledger.needingReview()).extracting(VendorOrderLedger.ReviewItem::externalRef)
                .containsExactly("GFS-26-LIST0002", "GFS-26-LIST0001");
    }

    @Test
    @DisplayName("the section shows the vendor order, its calls and its actions, and offers only what would be tried")
    void section() {
        OrderEntity o = at("GFS-26-SECTION1", "vid-section-1", "AWAITING_CUSTOMER", OrderStatus.ON_HOLD);
        vendor.on("/resumeOrderAPI", Reply.of(429, "wait"));
        actions.resume(o, ADMIN);

        VendorOrderActions.Section s = actions.section(o, new VendorCallLog(db.named).forOrder("GFS-26-SECTION1"));

        assertThat(s.vendorOrder().state()).isEqualTo("AWAITING_CUSTOMER");
        assertThat(s.vendorOrder().amountOrderedK()).isEqualTo(500);
        assertThat(s.calls()).extracting(VendorCallLog.Call::endpoint).containsExactly("/resumeOrderAPI");
        assertThat(s.actions()).extracting(VendorOrderActionLog.Entry::outcome).containsExactly("REFUSED");
        // On hold: the customer has not re-entered details, so no corrected sign-in to send yet.
        assertThat(s.available()).containsExactly("RESUME", "STOP", "MARK_FINISHED", "RESOLVE");
    }

    @Test
    @DisplayName("the queue counts every order the partner has as with it, id or not; a definite refusal is not")
    void atPartner() {
        long neverSent = db.order("GFS-26-ATP00001", "READY_FOR_DELIVERY", "0.5");
        at("GFS-26-ATP00002", null, "SUBMITTED", OrderStatus.READY_FOR_DELIVERY);
        at("GFS-26-ATP00003", null, "FAILED", OrderStatus.READY_FOR_DELIVERY);
        at("GFS-26-ATP00004", "vid-atp-4", "RESOLVED", OrderStatus.IN_PROGRESS);

        assertThat(ledger.atPartner(List.of(neverSent, ids.get("GFS-26-ATP00002"), ids.get("GFS-26-ATP00003"),
                ids.get("GFS-26-ATP00004")))).containsExactlyInAnyOrder(ids.get("GFS-26-ATP00002"),
                ids.get("GFS-26-ATP00004"));
        assertThat(ledger.atPartner(List.of())).isEmpty();
    }

    @Test
    @DisplayName("what the page offers follows each action's own rules")
    void available() {
        assertThat(VendorOrderActions.available(null, OrderStatus.READY_FOR_DELIVERY)).isEmpty();
        assertThat(VendorOrderActions.available(detail("AWAITING_CUSTOMER", "v"), OrderStatus.READY_FOR_DELIVERY))
                .containsExactly("SEND_SIGN_IN", "RESUME", "STOP", "MARK_FINISHED", "RESOLVE");
        assertThat(VendorOrderActions.available(detail("NEEDS_REVIEW", null), OrderStatus.READY_FOR_DELIVERY))
                .containsExactly("RETRY", "LINK", "RESOLVE");
        assertThat(VendorOrderActions.available(detail("NEEDS_REVIEW", "v"), OrderStatus.IN_PROGRESS))
                .containsExactly("RESUME", "STOP", "MARK_FINISHED", "LINK", "RESOLVE");
        assertThat(VendorOrderActions.available(detail("IN_DELIVERY", "v"), OrderStatus.IN_PROGRESS))
                .containsExactly("STOP", "MARK_FINISHED");
        assertThat(VendorOrderActions.available(detail("DELIVERED", "v"), OrderStatus.DELIVERED)).isEmpty();
        assertThat(VendorOrderActions.available(detail("FAILED", null), OrderStatus.READY_FOR_DELIVERY))
                .containsExactly("RESOLVE");
    }

    private static VendorOrderLedger.Detail detail(String state, String vendorId) {
        return new VendorOrderLedger.Detail(state, "GFS-26-X", vendorId, 500, null, null, null, null, null, null,
                null, null, 1, null, null, null, 0, null, null, null, null, null);
    }

    @Test
    @DisplayName("nothing an admin does here ever places an order")
    void neverPlaces() {
        OrderEntity o = at("GFS-26-NEVER001", null, "NEEDS_REVIEW", OrderStatus.READY_FOR_DELIVERY);
        actions.allowResend(o, ADMIN, true);
        actions.link(o, ADMIN, null);
        actions.resolve(o, ADMIN, "Given up after a check");
        assertThat(vendor.calls("/orderAPI")).isZero();
        assertThat(db.jdbc.queryForObject("select count(*) from vendor_call where endpoint = '/orderAPI'",
                Integer.class)).isZero();
        verify(orderService, never()).transition(any(), eq(OrderStatus.DELIVERED), any(), any(), anyString(),
                anyString());
    }
}
