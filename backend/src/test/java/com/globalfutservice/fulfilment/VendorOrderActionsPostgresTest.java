package com.globalfutservice.fulfilment;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.globalfutservice.config.AppProperties;
import com.globalfutservice.credentials.CredentialVaultService;
import com.globalfutservice.domain.orders.Actor;
import com.globalfutservice.domain.orders.CustomerAction;
import com.globalfutservice.domain.orders.OrderStatus;
import com.globalfutservice.fulfilment.FakeFutTransfer.Reply;
import com.globalfutservice.fulfilment.VendorOrderActions.Status;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Admin actions on an order the vendor already has, against a real PostgreSQL: the claim
 * that lets one click through, what the ledger and the audit trail say afterwards, and how
 * the poll reads the vendor's reports just after a restart.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class VendorOrderActionsPostgresTest {

    private static final VendorOrderActions.Admin ADMIN = new VendorOrderActions.Admin(null, "admin@example.test", "acc_admin");

    private TestDatabase db;
    private FakeFutTransfer vendor;
    private AppProperties props;
    private VendorOrderLedger ledger;
    private VendorOrderActionLog actionLog;
    private CredentialVaultService vault;
    private OrderService orderService;
    private VendorOrderActions actions;
    private FutTransferClient client;
    private VendorControl control;
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
        db.jdbc.update("update vendor_control set next_poll_at = null, backoff_level = 0, paused = false");
        vendor = new FakeFutTransfer();
        props = VendorTestSupport.props(vendor.baseUrl(), Duration.ofMillis(800));
        control = VendorTestSupport.running();
        client = new FutTransferClient(props, new ObjectMapper(), control, new VendorCallLog(db.named))
                .withoutRetryPauses();
        ledger = new VendorOrderLedger(db.named);
        actionLog = new VendorOrderActionLog(db.named);
        vault = mock(CredentialVaultService.class);
        when(vault.reveal(anyLong(), any())).thenReturn(VendorTestSupport.signIn());
        orderService = mock(OrderService.class);
        when(orderService.requireAny(anyString())).thenAnswer(inv -> order(inv.getArgument(0)));
        when(orderService.transition(any(), any(), any(), any(), anyString(), anyString())).thenAnswer(inv -> {
            OrderEntity o = inv.getArgument(0);
            statuses.put(o.getPublicRef(), inv.getArgument(1));
            return order(o.getPublicRef());
        });
        actions = new VendorOrderActions(client, control, ledger, actionLog, vault, orderService, props);
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

    /** An order the vendor is holding for the customer, who has re-entered their details. */
    private OrderEntity waiting(String ref, String vendorId, OrderStatus status) {
        long id = db.order(ref, status.name(), "0.5");
        db.vendorOrder(id, ref, vendorId, "AWAITING_CUSTOMER", 500);
        db.jdbc.update("update vendor_order set customer_action = 'NEW_BACKUP_CODES', vendor_status = 'interrupted', "
                + "vendor_account_check = 'wrongBA' where order_id = ?", id);
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

    private static final String CONTINUED = "{\"updatedPassword\":true,\"updatedBA\":true,\"wasContinued\":true}";

    @Test
    @DisplayName("sent to the order the vendor holds, restarted, the order started, and who did it recorded")
    void sent() {
        OrderEntity o = waiting("GFS-26-SIGNIN01", "vid-signin-01", OrderStatus.READY_FOR_DELIVERY);
        vendor.on("/correctCredentialsAPI", Reply.ok(CONTINUED));

        VendorOrderActions.Result r = actions.sendCorrectedSignIn(o, ADMIN);

        assertThat(r.status()).isEqualTo(Status.DONE);
        assertThat(vendor.calls("/correctCredentialsAPI")).isEqualTo(1);
        assertThat(vendor.calls("/orderAPI")).isZero();
        assertThat(row("GFS-26-SIGNIN01")).satisfies(v -> {
            assertThat(v.get("state")).isEqualTo("SUBMITTED");
            assertThat(v.get("customer_action")).isNull();
            assertThat(v.get("resubmitted_at")).isNotNull();
        });
        // The timeline, which the customer's own API returns, is signed with the opaque id, not the email.
        verify(orderService).transition(any(), eq(OrderStatus.IN_PROGRESS), eq(Actor.OPERATOR), eq(null),
                eq("acc_admin"), eq(CustomerText.forState(VendorStatusMap.State.SUBMITTED, CustomerAction.NONE)));
        assertThat(audit("GFS-26-SIGNIN01")).singleElement().satisfies(a -> {
            assertThat(a.get("action")).isEqualTo("SEND_SIGN_IN");
            assertThat(a.get("outcome")).isEqualTo("DONE");
            assertThat(a.get("actor_label")).isEqualTo("admin@example.test");
        });
        assertThat(db.jdbc.queryForObject("select count(*) from vendor_call where endpoint = '/correctCredentialsAPI' "
                + "and result = 'OK' and 'GFS-26-SIGNIN01' = any(order_refs)", Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("eight admins clicking at once send it once")
    void concurrentClicks() throws Exception {
        OrderEntity o = waiting("GFS-26-SIGNIN02", "vid-signin-02", OrderStatus.READY_FOR_DELIVERY);
        vendor.on("/correctCredentialsAPI", new Reply(200, CONTINUED, 300));

        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<VendorOrderActions.Result>> results = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            results.add(pool.submit(() -> {
                go.await();
                return actions.sendCorrectedSignIn(o, ADMIN);
            }));
        }
        go.countDown();
        List<Status> outcomes = new ArrayList<>();
        for (Future<VendorOrderActions.Result> f : results) outcomes.add(f.get(10, TimeUnit.SECONDS).status());
        pool.shutdown();

        assertThat(vendor.calls("/correctCredentialsAPI")).isEqualTo(1);
        assertThat(outcomes).containsOnlyOnce(Status.DONE);
    }

    @Test
    @DisplayName("refused: nothing changes, the claim goes, and it can be sent again at once")
    void refused() {
        OrderEntity o = waiting("GFS-26-SIGNIN03", "vid-signin-03", OrderStatus.READY_FOR_DELIVERY);
        vendor.on("/correctCredentialsAPI", Reply.of(400, "{\"error\":\"InvalidBA1\"}"));

        VendorOrderActions.Result r = actions.sendCorrectedSignIn(o, ADMIN);

        assertThat(r.status()).isEqualTo(Status.REFUSED);
        assertThat(r.message()).contains("InvalidBA1").contains("Nothing changed");
        assertThat(row("GFS-26-SIGNIN03").get("state")).isEqualTo("AWAITING_CUSTOMER");
        assertThat(row("GFS-26-SIGNIN03").get("resubmitted_at")).isNull();
        assertThat(audit("GFS-26-SIGNIN03")).singleElement()
                .satisfies(a -> assertThat(a.get("outcome")).isEqualTo("REFUSED"));
        verify(orderService, never()).transition(any(), any(), any(), any(), anyString(), anyString());

        vendor.on("/correctCredentialsAPI", Reply.ok(CONTINUED));
        assertThat(actions.sendCorrectedSignIn(o, ADMIN).status()).isEqualTo(Status.DONE);
    }

    @Test
    @DisplayName("answer lost: uncertain, the claim kept, and a second click is refused rather than sent")
    void uncertain() {
        OrderEntity o = waiting("GFS-26-SIGNIN04", "vid-signin-04", OrderStatus.READY_FOR_DELIVERY);
        vendor.on("/correctCredentialsAPI", new Reply(200, CONTINUED, 2_000));

        VendorOrderActions.Result r = actions.sendCorrectedSignIn(o, ADMIN);
        assertThat(r.status()).isEqualTo(Status.UNCERTAIN);
        assertThat(r.message()).contains("TIMEOUT").contains("dashboard");
        assertThat(row("GFS-26-SIGNIN04").get("state")).isEqualTo("AWAITING_CUSTOMER");

        VendorOrderActions.Result again = actions.sendCorrectedSignIn(o, ADMIN);
        assertThat(again.status()).isEqualTo(Status.NOT_SENT);
        assertThat(vendor.calls("/correctCredentialsAPI")).isEqualTo(1);
        assertThat(audit("GFS-26-SIGNIN04")).extracting(a -> a.get("outcome")).containsExactly("UNCERTAIN");
    }

    @Test
    @DisplayName("saved but not restarted: said so, and nothing blocks a resume")
    void savedOnly() {
        OrderEntity o = waiting("GFS-26-SIGNIN05", "vid-signin-05", OrderStatus.READY_FOR_DELIVERY);
        vendor.on("/correctCredentialsAPI", Reply.ok("{\"updatedPassword\":true,\"wasContinued\":false}"));

        VendorOrderActions.Result r = actions.sendCorrectedSignIn(o, ADMIN);

        assertThat(r.status()).isEqualTo(Status.DONE);
        assertThat(r.message()).contains("did not restart");
        assertThat(row("GFS-26-SIGNIN05").get("state")).isEqualTo("AWAITING_CUSTOMER");
        assertThat(row("GFS-26-SIGNIN05").get("resubmitted_at")).isNull();
        verify(orderService, never()).transition(any(), any(), any(), any(), anyString(), anyString());
    }

    @Test
    @DisplayName("not sent -- and the sign-in never read -- unless the vendor is waiting and the customer has re-entered it")
    void preconditions() {
        OrderEntity onHold = waiting("GFS-26-SIGNIN06", "vid-signin-06", OrderStatus.ON_HOLD);
        assertThat(actions.sendCorrectedSignIn(onHold, ADMIN).message()).contains("has not entered new details");

        OrderEntity working = waiting("GFS-26-SIGNIN07", "vid-signin-07", OrderStatus.READY_FOR_DELIVERY);
        db.jdbc.update("update vendor_order set state = 'IN_DELIVERY' where order_id = ?", ids.get("GFS-26-SIGNIN07"));
        assertThat(actions.sendCorrectedSignIn(working, ADMIN).message()).contains("not waiting for new details");

        OrderEntity failed = waiting("GFS-26-SIGNIN08", "vid-signin-08", OrderStatus.READY_FOR_DELIVERY);
        db.jdbc.update("update vendor_order set state = 'FAILED', vendor_order_id = null where order_id = ?",
                ids.get("GFS-26-SIGNIN08"));
        assertThat(actions.sendCorrectedSignIn(failed, ADMIN).message()).contains("Approve the order");

        when(control.isPaused()).thenReturn(true);
        OrderEntity paused = waiting("GFS-26-SIGNIN09", "vid-signin-09", OrderStatus.READY_FOR_DELIVERY);
        assertThat(actions.sendCorrectedSignIn(paused, ADMIN).message()).contains("paused");

        verify(vault, never()).reveal(anyLong(), any());
        assertThat(vendor.requests()).isEmpty();
    }

    @Test
    @DisplayName("after a restart, the vendor repeating its old report is not the new details being refused")
    void staleReportAfterRestart() {
        OrderEntity o = waiting("GFS-26-SIGNIN10", "vid-signin-10", OrderStatus.READY_FOR_DELIVERY);
        vendor.on("/correctCredentialsAPI", Reply.ok(CONTINUED));
        actions.sendCorrectedSignIn(o, ADMIN);

        OrderRepository orders = mock(OrderRepository.class);
        when(orders.findById(anyLong())).thenAnswer(inv -> java.util.Optional.of(order("GFS-26-SIGNIN10")));
        NotificationService notifications = mock(NotificationService.class);
        VendorPoller poller = new VendorPoller(client, control, ledger, new SchedulerLock(db.ds), orderService,
                orders, vault, notifications, props);
        String stale = "{\"vid-signin-10\":{\"status\":\"interrupted\",\"accountCheck\":\"wrongBA\",\"economyState\":null,"
                + "\"amountOrdered\":500,\"amount\":0,\"wasAborted\":0}}";
        vendor.on("/orderStatusBulkAPI", Reply.ok(stale));

        poller.pollOnce();
        assertThat(row("GFS-26-SIGNIN10").get("state")).isEqualTo("SUBMITTED");
        verify(notifications, never()).customerActionNeeded(any());

        // Past the grace, the same report is believed: the new details were refused too.
        db.jdbc.update("update vendor_order set resubmitted_at = now() - interval '11 minutes' where order_id = ?",
                ids.get("GFS-26-SIGNIN10"));
        poller.pollOnce();
        assertThat(row("GFS-26-SIGNIN10").get("state")).isEqualTo("AWAITING_CUSTOMER");
    }

    @Test
    @DisplayName("after a restart, a different refusal is news at once")
    void differentReportAfterRestart() {
        OrderEntity o = waiting("GFS-26-SIGNIN11", "vid-signin-11", OrderStatus.READY_FOR_DELIVERY);
        vendor.on("/correctCredentialsAPI", Reply.ok(CONTINUED));
        actions.sendCorrectedSignIn(o, ADMIN);

        OrderRepository orders = mock(OrderRepository.class);
        when(orders.findById(anyLong())).thenAnswer(inv -> java.util.Optional.of(order("GFS-26-SIGNIN11")));
        VendorPoller poller = new VendorPoller(client, control, ledger, new SchedulerLock(db.ds), orderService,
                orders, vault, mock(NotificationService.class), props);
        vendor.on("/orderStatusBulkAPI", Reply.ok("{\"vid-signin-11\":{\"status\":\"interrupted\","
                + "\"accountCheck\":\"wrongUserPass\",\"economyState\":null,\"amountOrdered\":500,\"amount\":0,"
                + "\"wasAborted\":0}}"));

        poller.pollOnce();

        assertThat(row("GFS-26-SIGNIN11").get("state")).isEqualTo("AWAITING_CUSTOMER");
        assertThat(row("GFS-26-SIGNIN11").get("customer_action")).isEqualTo("RESUBMIT_SIGN_IN");
    }
}
