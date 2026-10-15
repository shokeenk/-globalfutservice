package com.globalfutservice.fulfilment;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.globalfutservice.config.AppProperties;
import com.globalfutservice.credentials.CredentialVaultService;
import com.globalfutservice.domain.catalog.Sku;
import com.globalfutservice.domain.orders.Actor;
import com.globalfutservice.domain.orders.CustomerAction;
import com.globalfutservice.domain.orders.OrderStatus;
import com.globalfutservice.fulfilment.FakeFutTransfer.Reply;
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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The customer's corrected sign-in, sent to FUT Transfer by itself, against a real PostgreSQL
 * and a local stand-in for the partner: once per submission, at most once every two minutes,
 * a refused sign-in back to the customer with what is wrong, anything else to an admin, and
 * the admin's own button still working beside it.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SignInResendsPostgresTest {

    private static final String CONTINUED = "{\"updatedPassword\":true,\"updatedBA\":true,\"wasContinued\":true}";
    private static final String SAVED = "{\"updatedPassword\":true,\"updatedBA\":true,\"wasContinued\":false}";

    private TestDatabase db;
    private FakeFutTransfer vendor;
    private VendorOrderLedger ledger;
    private VendorControl control;
    private CredentialVaultService vault;
    private OrderService orderService;
    private NotificationService notifications;
    private VendorOrderActions actions;
    private AutoDispatchQueue queue;
    private SignInResends resends;
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
        AppProperties props = VendorTestSupport.props(vendor.baseUrl(), Duration.ofMillis(800));
        control = new VendorControl(db.named, mock(NotificationService.class), props);
        FutTransferClient client = new FutTransferClient(props, new ObjectMapper(), control, new VendorCallLog(db.named))
                .withoutRetryPauses();
        ledger = new VendorOrderLedger(db.named);
        vault = mock(CredentialVaultService.class);
        when(vault.reveal(anyLong(), any())).thenReturn(VendorTestSupport.signIn());
        notifications = mock(NotificationService.class);
        orderService = mock(OrderService.class);
        when(orderService.requireAny(anyString())).thenAnswer(inv -> order(inv.getArgument(0)));
        when(orderService.transition(any(), any(), any(), any(), anyString(), anyString())).thenAnswer(inv -> {
            OrderEntity o = inv.getArgument(0);
            statuses.put(o.getPublicRef(), inv.getArgument(1));
            return order(o.getPublicRef());
        });
        when(orderService.askForSignInAgain(any(), any())).thenAnswer(inv -> {
            OrderEntity o = inv.getArgument(0);
            statuses.put(o.getPublicRef(), OrderStatus.ON_HOLD);
            return order(o.getPublicRef());
        });
        actions = new VendorOrderActions(client, control, ledger, new VendorOrderActionLog(db.named), vault,
                orderService, props, new ObjectMapper());
        OrderRepository orders = mock(OrderRepository.class);
        when(orders.findById(anyLong())).thenAnswer(inv -> ids.entrySet().stream()
                .filter(e -> e.getValue().equals(inv.getArgument(0))).findFirst().map(e -> order(e.getKey())));
        queue = new AutoDispatchQueue(db.named, props);
        resends = new SignInResends(ledger, actions, control, orders, orderService, vault, notifications,
                new SchedulerLock(db.ds), props);
    }

    @AfterEach
    void tearDown() {
        vendor.close();
    }

    // --------------------------------------------------------------- fixtures ---

    private OrderEntity order(String ref) {
        OrderEntity o = mock(OrderEntity.class);
        when(o.getId()).thenReturn(ids.get(ref));
        when(o.getPublicRef()).thenReturn(ref);
        when(o.getSku()).thenReturn(Sku.TRADING_SERVICE);
        when(o.getStatus()).thenAnswer(inv -> statuses.get(ref));
        return o;
    }

    /** FUT Transfer holds the order for the customer, after refusing their sign-in during the transfer. */
    private long held(String ref, String vid, String action) {
        long id = db.order(ref, OrderStatus.ON_HOLD.name(), "0.5");
        db.vendorOrder(id, ref, vid, "AWAITING_CUSTOMER", 500);
        db.jdbc.update("update vendor_order set customer_action = ? where order_id = ?", action, id);
        ids.put(ref, id);
        statuses.put(ref, OrderStatus.ON_HOLD);
        return id;
    }

    /** The customer enters new details on their order page: on hold -> ready, recorded for sending. */
    private void reenter(String ref) {
        statuses.put(ref, OrderStatus.READY_FOR_DELIVERY);
        queue.signInReentered(order(ref));
    }

    private Map<String, Object> row(long id) {
        return db.jdbc.queryForMap("select * from vendor_order where order_id = ?", id);
    }

    private void twoMinutesOn(long id) {
        db.jdbc.update("update vendor_order set signin_sent_at = signin_sent_at - interval '3 minutes', "
                + "resubmitted_at = resubmitted_at - interval '3 minutes' where order_id = ?", id);
    }

    // ----------------------------------------------------------------- tests ---

    @Test
    @DisplayName("the customer re-enters their details: sent once, the order restarted, and never again")
    void sentOncePerSubmission() {
        long id = held("GFS-26-RESEND01", "vid-resend-01", "RESUBMIT_SIGN_IN");
        vendor.on("/correctCredentialsAPI", Reply.ok(CONTINUED));

        reenter("GFS-26-RESEND01");
        assertThat(resends.sendDue()).isOne();

        assertThat(vendor.calls("/correctCredentialsAPI")).isOne();
        var sent = vendor.requests().stream().filter(r -> r.path().equals("/correctCredentialsAPI")).findFirst()
                .orElseThrow().body();
        assertThat(sent.get("orderID").asText()).isEqualTo("vid-resend-01");
        assertThat(sent.get("continue").asInt()).isEqualTo(1);
        assertThat(row(id).get("state")).isEqualTo("SUBMITTED");
        // Restarted as the system: nobody at GFS clicked anything.
        verify(orderService).transition(any(), eq(OrderStatus.IN_PROGRESS), eq(Actor.SYSTEM), eq(null), eq("GFS"),
                anyString());

        for (int i = 0; i < 3; i++) {
            twoMinutesOn(id);
            resends.sendDue();
        }
        assertThat(vendor.calls("/correctCredentialsAPI")).as("one submission, one send").isOne();
        assertThat(vendor.calls("/orderAPI") + vendor.calls("/buyCoinsAPI")).as("never a new order").isZero();
    }

    @Test
    @DisplayName("several workers at once: still one send for the submission")
    void onceUnderConcurrency() throws Exception {
        held("GFS-26-RESEND02", "vid-resend-02", "RESUBMIT_SIGN_IN");
        vendor.on("/correctCredentialsAPI", new Reply(200, CONTINUED, 300));
        reenter("GFS-26-RESEND02");

        ExecutorService threads = Executors.newFixedThreadPool(4);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> all = new ArrayList<>();
        for (int i = 0; i < 4; i++) all.add(threads.submit(() -> { go.await(); return resends.sendDue(); }));
        go.countDown();
        for (Future<Integer> f : all) f.get(20, TimeUnit.SECONDS);
        threads.shutdown();

        assertThat(vendor.calls("/correctCredentialsAPI")).isOne();
    }

    @Test
    @DisplayName("the partner asks us to wait: not again for two minutes, then once")
    void atMostOnceEveryTwoMinutes() {
        long id = held("GFS-26-RESEND03", "vid-resend-03", "RESUBMIT_SIGN_IN");
        vendor.on("/correctCredentialsAPI", Reply.of(429, "{\"error\":\"tooManyRequests\"}"));
        reenter("GFS-26-RESEND03");

        resends.sendDue();
        resends.sendDue();
        assertThat(vendor.calls("/correctCredentialsAPI")).as("not again within two minutes").isOne();
        assertThat(row(id).get("state")).isEqualTo("AWAITING_CUSTOMER");

        vendor.on("/correctCredentialsAPI", Reply.ok(CONTINUED));
        twoMinutesOn(id);
        resends.sendDue();
        assertThat(vendor.calls("/correctCredentialsAPI")).isEqualTo(2);
        assertThat(row(id).get("state")).isEqualTo("SUBMITTED");
    }

    @Test
    @DisplayName("refused again for the sign-in: deleted, and the customer told which part -- password or codes")
    void refusedBackToTheCustomer() {
        for (String[] c : new String[][]{{"InvalidPassword", "RESUBMIT_SIGN_IN"}, {"InvalidBA2", "NEW_BACKUP_CODES"}}) {
            String ref = "GFS-26-RESEND" + c[1].charAt(0) + c[0].length();
            long id = held(ref, "vid-" + ref, "RESUBMIT_SIGN_IN");
            vendor.on("/correctCredentialsAPI", Reply.of(400, "{\"error\":\"" + c[0] + "\"}"));
            reenter(ref);

            resends.sendDue();

            assertThat(row(id).get("state")).isEqualTo("AWAITING_CUSTOMER");
            assertThat(row(id).get("customer_action")).isEqualTo(c[1]);
            assertThat(ledger.signInProblem(id)).hasValue(c[1]);
            verify(vault).purge(eq(id), anyString());
            verify(orderService).askForSignInAgain(any(), eq(CustomerAction.valueOf(c[1])));
            assertThat(statuses.get(ref)).isEqualTo(OrderStatus.ON_HOLD);
        }
        verify(notifications, never()).customerActionNeeded(any()); // said by askForSignInAgain, mocked here
    }

    @Test
    @DisplayName("not restarted, refused for another reason, or no answer: to an admin, with one alert")
    void otherFailuresToReview() {
        String[][] cases = {
                {"GFS-26-RESENDF1", "saved"},
                {"GFS-26-RESENDF2", "refused"},
                {"GFS-26-RESENDF3", "uncertain"}};
        for (String[] c : cases) {
            long id = held(c[0], "vid-" + c[0], "RESUBMIT_SIGN_IN");
            vendor.on("/correctCredentialsAPI", switch (c[1]) {
                case "saved" -> Reply.ok(SAVED);
                case "refused" -> Reply.of(400, "{\"error\":\"orderFinished\"}");
                default -> Reply.of(500, "{}");
            });
            reenter(c[0]);
            resends.sendDue();
            assertThat(row(id).get("state")).as(c[1]).isEqualTo("NEEDS_REVIEW");
            assertThat(row(id).get("last_error_code")).as(c[1]).isEqualTo(SignInResends.NOT_SENT);
        }
        verify(notifications, times(3)).fulfilmentAlert(any());
        verify(orderService, never()).askForSignInAgain(any(), any());
    }

    @Test
    @DisplayName("an admin's button keeps working, and whichever goes first, the details are sent once")
    void adminButtonStillWorks() {
        long id = held("GFS-26-RESEND04", "vid-resend-04", "RESUBMIT_SIGN_IN");
        vendor.on("/correctCredentialsAPI", Reply.ok(CONTINUED));
        reenter("GFS-26-RESEND04");

        VendorOrderActions.Result r = actions.sendCorrectedSignIn(order("GFS-26-RESEND04"),
                new VendorOrderActions.Admin(7L, "admin@example.test", "acc_admin"));
        assertThat(r.status()).isEqualTo(VendorOrderActions.Status.DONE);
        resends.sendDue();
        twoMinutesOn(id);
        resends.sendDue();

        assertThat(vendor.calls("/correctCredentialsAPI")).isOne();
        verify(orderService).transition(any(), eq(OrderStatus.IN_PROGRESS), eq(Actor.OPERATOR), eq(7L), eq("acc_admin"),
                anyString());
    }

    @Test
    @DisplayName("calls paused: nothing sent, and the submission still due once they resume")
    void pausedWaits() {
        long id = held("GFS-26-RESEND05", "vid-resend-05", "RESUBMIT_SIGN_IN");
        vendor.on("/correctCredentialsAPI", Reply.ok(CONTINUED));
        reenter("GFS-26-RESEND05");
        db.jdbc.update("update vendor_control set paused = true");

        assertThat(resends.sendDue()).isZero();
        assertThat(vendor.calls("/correctCredentialsAPI")).isZero();

        db.jdbc.update("update vendor_control set paused = false");
        assertThat(resends.sendDue()).isOne();
        assertThat(row(id).get("state")).isEqualTo("SUBMITTED");
    }

    @Test
    @DisplayName("a hold staff made, with FUT Transfer not waiting for the customer: re-entered details are not sent")
    void staffHoldNotSent() {
        long id = db.order("GFS-26-RESEND06", OrderStatus.ON_HOLD.name(), "0.5");
        db.vendorOrder(id, "GFS-26-RESEND06", "vid-resend-06", "IN_DELIVERY", 500);
        ids.put("GFS-26-RESEND06", id);
        statuses.put("GFS-26-RESEND06", OrderStatus.ON_HOLD);
        vendor.on("/correctCredentialsAPI", Reply.ok(CONTINUED));

        reenter("GFS-26-RESEND06");
        resends.sendDue();

        assertThat(vendor.calls("/correctCredentialsAPI")).isZero();
        assertThat(row(id).get("signin_resubmitted_at")).isNull();
    }

    @Test
    @DisplayName("entering new details clears what we had asked them to fix")
    void reentryClearsTheAsk() {
        long id = held("GFS-26-RESEND07", "vid-resend-07", "RESUBMIT_SIGN_IN");
        ledger.setSignInProblem(id, CustomerAction.NEW_BACKUP_CODES);
        assertThat(ledger.signInProblem(id)).hasValue("NEW_BACKUP_CODES");

        reenter("GFS-26-RESEND07");

        assertThat(ledger.signInProblem(id)).isEqualTo(Optional.empty());
    }
}
