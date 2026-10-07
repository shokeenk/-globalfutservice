package com.globalfutservice.fulfilment;

import java.math.BigDecimal;
import java.time.Clock;
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
import com.globalfutservice.credentials.web.CredentialDtos;
import com.globalfutservice.domain.catalog.Platform;
import com.globalfutservice.domain.catalog.Sku;
import com.globalfutservice.domain.orders.OrderStatus;
import com.globalfutservice.fulfilment.FakeFutTransfer.Reply;
import com.globalfutservice.notify.FulfilmentAlert;
import com.globalfutservice.notify.NotificationService;
import com.globalfutservice.orders.OrderEntity;
import com.globalfutservice.orders.OrderRepository;
import com.globalfutservice.orders.OrderService;
import com.globalfutservice.scheduling.SchedulerLock;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Sending a paid coin order to FUT Transfer without an admin, against a real PostgreSQL and a
 * fake partner: through Approve's own release, exactly once, and never at the payment's cost.
 *
 * <p>Runs when GFS_TEST_PG_URL, GFS_TEST_PG_USER and GFS_TEST_PG_PASSWORD are set; skipped
 * otherwise. A fresh, randomly named schema each run, dropped afterwards.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AutoDispatchPostgresTest {

    private static final AppProperties.FutTransferAutoDispatch ON =
            new AppProperties.FutTransferAutoDispatch(true, null, Duration.ofSeconds(15));

    private String schema;
    private DriverManagerDataSource ds;
    private JdbcTemplate jdbc;
    private FakeFutTransfer vendor;
    private final ObjectMapper mapper = new ObjectMapper();
    private int nextVendorId = 1;

    @BeforeAll
    void migrate() {
        String url = System.getenv("GFS_TEST_PG_URL");
        assumeTrue(url != null && !url.isBlank(), "GFS_TEST_PG_URL not set: no database to test against");
        schema = "auto_test_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        ds = new DriverManagerDataSource(url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema + ",public",
                System.getenv("GFS_TEST_PG_USER"), System.getenv("GFS_TEST_PG_PASSWORD"));
        jdbc = new JdbcTemplate(ds);
        Flyway.configure().dataSource(ds).schemas(schema).defaultSchema(schema).locations("classpath:db/migration")
                .initSql("SET search_path TO " + schema + ", public").load().migrate();
        // The admin who clicks Approve: the history names a real account.
        long adminId = jdbc.queryForObject("insert into account (public_id, email, email_normalised, role, "
                + "password_hash) values ('acc_admin', 'admin@example.test', 'admin@example.test', 'ADMIN', 'x') "
                + "returning id", Long.class);
        admin = new VendorOrderActions.Admin(adminId, "admin@example.test", "acc_admin");
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

    /** A paid coin order, as markPaid leaves it when the sign-in was given at checkout. */
    private long paidOrder(String ref, String millions) {
        return jdbc.queryForObject("""
                insert into orders (public_ref, season, sku, quantity, delivery_method, currency, subtotal_minor,
                                    total_minor, price_breakdown, status, quote_id, guest_email)
                values (?, 'FC26', 'TRADING_SERVICE', ?::numeric, 'PLAYER_AUCTION', 'INR', 100000, 100000, ?::jsonb,
                        'READY_FOR_DELIVERY', ?, 'buyer@example.test')
                returning id
                """, Long.class, ref, millions, "{\"quantity\":" + millions + "}", "q_" + ref);
    }

    private String status(long orderId) {
        return jdbc.queryForObject("select status from orders where id = ?", String.class, orderId);
    }

    /** The order as the application reads it: its status always the database's. */
    private OrderEntity orderFor(String ref) {
        Map<String, Object> row = jdbc.queryForMap("select id, quantity from orders where public_ref = ?", ref);
        long id = (Long) row.get("id");
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

    /** One application instance, with automatic sending as given and the sign-in on file or not. */
    private final class App {
        final AppProperties props;
        final NotificationService notifications = mock(NotificationService.class);
        final AutoDispatchQueue queue;
        final FulfilmentRelease release;
        final AutoDispatchWorker worker;
        final TransferStartedNotices notices;
        final VendorOrderLedger ledger;

        App(AppProperties.FutTransferAutoDispatch auto, boolean signInOnFile) {
            props = VendorTestSupport.props(vendor.baseUrl(), Duration.ofMillis(1500), auto);
            NamedParameterJdbcTemplate named = new NamedParameterJdbcTemplate(ds);
            CredentialVaultService vault = mock(CredentialVaultService.class);
            when(vault.status(anyLong())).thenReturn(new CredentialDtos.VaultStatus(signInOnFile, false, null, 0));
            when(vault.reveal(anyLong(), any())).thenReturn(VendorTestSupport.signIn());
            VendorControl control = VendorTestSupport.running();
            ledger = new VendorOrderLedger(named);
            SupplierFulfilmentService supplier = new SupplierFulfilmentService(new FutTransferClient(props, mapper,
                    control, new VendorCallLog(named)).withoutRetryPauses(), control, vault, ledger, notifications,
                    props, mapper);

            // The order service's part in a release: find the order, move it on.
            OrderService orderService = mock(OrderService.class);
            when(orderService.requireAny(anyString())).thenAnswer(inv -> orderFor(inv.getArgument(0)));
            when(orderService.notificationFor(any())).thenAnswer(inv -> new com.globalfutservice.notify.OrderNotification(
                    ((OrderEntity) inv.getArgument(0)).getPublicRef(), "IN_PROGRESS", "Buy Coins", "₹1,000.00",
                    "buyer@example.test", null, "PLAYER_AUCTION", "TRADING_SERVICE", "PlayStation", null, null, null,
                    null));
            when(orderService.transition(any(), any(), any(), any(), any(), any())).thenAnswer(inv -> {
                OrderEntity o = inv.getArgument(0);
                OrderStatus to = inv.getArgument(1);
                jdbc.update("update orders set status = ? where id = ?", to.name(), o.getId());
                return orderFor(o.getPublicRef());
            });
            OrderRepository orders = mock(OrderRepository.class);
            when(orders.findById(anyLong())).thenAnswer(inv -> java.util.Optional.of(orderFor(jdbc.queryForObject(
                    "select public_ref from orders where id = ?", String.class, (Long) inv.getArgument(0)))));

            VendorOrderActionLog history = new VendorOrderActionLog(named);
            queue = new AutoDispatchQueue(named, props);
            release = new FulfilmentRelease(orderService, vault, supplier, history);
            worker = new AutoDispatchWorker(queue, release, supplier, control, ledger, history, vault, orders,
                    notifications, mock(SchedulerLock.class), props, mapper, Clock.systemUTC());
            notices = new TransferStartedNotices(named, orders, orderService, notifications, mock(SchedulerLock.class));
        }

        void paid(String ref) {
            queue.paid(orderFor(ref));
        }

        List<String> alertCodes() {
            ArgumentCaptor<FulfilmentAlert> captor = ArgumentCaptor.forClass(FulfilmentAlert.class);
            verify(notifications, org.mockito.Mockito.atLeast(0)).fulfilmentAlert(captor.capture());
            return captor.getAllValues().stream().map(FulfilmentAlert::code).toList();
        }
    }

    private VendorOrderActions.Admin admin;

    private String acceptAs() {
        return String.format("cccccccc-0000-0000-0000-%012d", nextVendorId++);
    }

    private Map<String, Object> queued(long orderId) {
        return jdbc.queryForMap("select * from auto_dispatch where order_id = ?", orderId);
    }

    private List<String> history(long orderId) {
        return jdbc.queryForList("select action || ' ' || coalesce(actor_label, '-') || ' ' || outcome "
                + "|| coalesce(' ' || code, '') from vendor_order_action where order_id = ? order by id", String.class,
                orderId);
    }

    // ----------------------------------------------------------------- tests ---

    @Test
    @DisplayName("on: a paid coin order is queued in the payment and sent once, \"Sent automatically\", IN_PROGRESS")
    void sentExactlyOnce() {
        App app = new App(ON, true);
        long id = paidOrder("GFS-26-AUTO0001", "0.5");
        app.paid("GFS-26-AUTO0001");
        app.paid("GFS-26-AUTO0001"); // a repeat queues nothing more
        assertThat(queued(id).get("state")).isEqualTo("QUEUED");
        vendor.on("/orderAPI", Reply.ok(FakeFutTransfer.accepted(acceptAs())));

        assertThat(app.worker.process(id)).isEqualTo(AutoDispatchWorker.Outcome.SENT);
        app.worker.runDue();
        assertThat(app.worker.process(id)).isEqualTo(AutoDispatchWorker.Outcome.NOT_QUEUED);

        assertThat(vendor.calls("/orderAPI")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select state from vendor_order where order_id = ?", String.class, id))
                .isEqualTo("SUBMITTED");
        assertThat(status(id)).isEqualTo("IN_PROGRESS");
        assertThat(queued(id).get("state")).isEqualTo("SENT");
        assertThat(history(id)).containsExactly("AUTO_DISPATCH automatic DONE");
    }

    @Test
    @DisplayName("off: nothing is queued or sent; the order waits for Approve, which sends it, \"Sent by\" the admin")
    void offWaitsForApprove() {
        App app = new App(AppProperties.FutTransferAutoDispatch.OFF, true);
        long id = paidOrder("GFS-26-AUTO0002", "0.5");
        app.paid("GFS-26-AUTO0002");
        app.worker.runDue();

        assertThat(jdbc.queryForObject("select count(*) from auto_dispatch where order_id = ?", Long.class, id)).isZero();
        assertThat(vendor.calls("/orderAPI")).isZero();
        assertThat(status(id)).isEqualTo("READY_FOR_DELIVERY");

        vendor.on("/orderAPI", Reply.ok(FakeFutTransfer.accepted(acceptAs())));
        assertThat(app.release.release("GFS-26-AUTO0002", FulfilmentRelease.Releaser.admin(admin)).sent()).isTrue();
        assertThat(status(id)).isEqualTo("IN_PROGRESS");
        assertThat(history(id)).containsExactly("APPROVE admin@example.test DONE");
    }

    @Test
    @DisplayName("over GFS_FUTTRANSFER_AUTO_DISPATCH_MAX_K: left for Approve, staff told why, nothing sent")
    void overLimitWaits() {
        App app = new App(new AppProperties.FutTransferAutoDispatch(true, 400L, Duration.ofSeconds(15)), true);
        long id = paidOrder("GFS-26-AUTO0003", "0.5"); // 500K
        app.paid("GFS-26-AUTO0003");

        assertThat(app.worker.process(id)).isEqualTo(AutoDispatchWorker.Outcome.LEFT_FOR_APPROVE);

        assertThat(vendor.calls("/orderAPI")).isZero();
        assertThat(queued(id).get("state")).isEqualTo("LEFT_FOR_APPROVE");
        assertThat((String) queued(id).get("reason")).contains("500K is more than the automatic limit of 400K");
        assertThat(history(id)).containsExactly("AUTO_DISPATCH automatic REFUSED OVER_LIMIT");
        assertThat(app.alertCodes()).containsExactly("OVER_LIMIT");
        assertThat(status(id)).isEqualTo("READY_FOR_DELIVERY");
    }

    @Test
    @DisplayName("no sign-in on file: left for Approve with the reason, staff told, the vault never read")
    void missingSignInWaits() {
        App app = new App(ON, false);
        long id = paidOrder("GFS-26-AUTO0004", "0.5");
        app.paid("GFS-26-AUTO0004");

        assertThat(app.worker.process(id)).isEqualTo(AutoDispatchWorker.Outcome.LEFT_FOR_APPROVE);

        assertThat(vendor.calls("/orderAPI")).isZero();
        assertThat(queued(id).get("reason_code")).isEqualTo("NO_SIGN_IN");
        assertThat((String) queued(id).get("reason")).contains("sign-in is not on file yet");
        assertThat(app.alertCodes()).containsExactly("NO_SIGN_IN");
    }

    @Test
    @DisplayName("refused by the partner: Needs review with an alert, and the order stays paid where it was")
    void refusalNeedsReview() {
        App app = new App(ON, true);
        long id = paidOrder("GFS-26-AUTO0005", "0.5");
        app.paid("GFS-26-AUTO0005");
        vendor.on("/orderAPI", Reply.of(400, "{\"error\":\"MissingData\"}"));

        assertThat(app.worker.process(id)).isEqualTo(AutoDispatchWorker.Outcome.NEEDS_REVIEW);

        assertThat(vendor.calls("/orderAPI")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select state from vendor_order where order_id = ?", String.class, id))
                .isEqualTo("NEEDS_REVIEW");
        assertThat(queued(id).get("state")).isEqualTo("NEEDS_REVIEW");
        assertThat(status(id)).isEqualTo("READY_FOR_DELIVERY");
        assertThat(app.alertCodes()).contains("FAILED");
        // Nothing is sent again by itself.
        app.worker.runDue();
        assertThat(vendor.calls("/orderAPI")).isEqualTo(1);
    }

    @Test
    @DisplayName("an answer that cannot be read: Needs review, the order still paid, never sent twice")
    void unreadableNeedsReview() {
        App app = new App(ON, true);
        long id = paidOrder("GFS-26-AUTO0006", "0.5");
        app.paid("GFS-26-AUTO0006");
        vendor.on("/orderAPI", Reply.of(418, "{\"error\":\"teapot\"}"));

        assertThat(app.worker.process(id)).isEqualTo(AutoDispatchWorker.Outcome.NEEDS_REVIEW);
        assertThat(jdbc.queryForObject("select state from vendor_order where order_id = ?", String.class, id))
                .isEqualTo("NEEDS_REVIEW");
        assertThat(status(id)).isEqualTo("READY_FOR_DELIVERY");
        assertThat(vendor.calls("/orderAPI")).isEqualTo(1);
    }

    @Test
    @DisplayName("a passing cause (cooldown unreadable): tried again later, then Needs review after the last try")
    void transientRetriesThenReview() {
        App app = new App(ON, true);
        long id = paidOrder("GFS-26-AUTO0007", "0.5");
        app.paid("GFS-26-AUTO0007");
        vendor.on("/getCooldownStatus", Reply.of(500, "{}"));

        assertThat(app.worker.process(id)).isEqualTo(AutoDispatchWorker.Outcome.RETRY);
        assertThat(queued(id).get("state")).isEqualTo("QUEUED");
        assertThat(jdbc.queryForObject("select next_attempt_at > now() + interval '50 seconds' from auto_dispatch "
                + "where order_id = ?", Boolean.class, id)).isTrue();
        app.worker.runDue(); // not due yet
        assertThat(queued(id).get("attempts")).isEqualTo(1);

        jdbc.update("update auto_dispatch set attempts = ?, next_attempt_at = now() where order_id = ?",
                AutoDispatchWorker.MAX_TRIES - 1, id);
        assertThat(app.worker.process(id)).isEqualTo(AutoDispatchWorker.Outcome.NEEDS_REVIEW);
        assertThat(vendor.calls("/orderAPI")).isZero();
        assertThat(status(id)).isEqualTo("READY_FOR_DELIVERY");
        assertThat(app.alertCodes()).contains("NOT_SENT");
    }

    // ------------------------------------------- "your coin transfer has started" --

    /** Orders other tests sent share this schema; their notices are not this test's to count. */
    private void quietEarlierOrders() {
        jdbc.update("update orders set transfer_notice_at = now() where transfer_notice_at is null");
    }

    private Map<String, Object> started(long orderId) {
        return jdbc.queryForMap("select transfer_started_at, transfer_notice_at from orders where id = ?", orderId);
    }

    @Test
    @DisplayName("sent automatically: the transfer-started email goes once, and the progress is readable")
    void startedEmailAfterAutoSend() {
        quietEarlierOrders();
        App app = new App(ON, true);
        long id = paidOrder("GFS-26-MAIL0001", "0.5");
        app.paid("GFS-26-MAIL0001");
        assertThat(app.notices.sendDue()).isZero(); // not before the partner has it
        vendor.on("/orderAPI", Reply.ok(FakeFutTransfer.accepted(acceptAs())));
        app.worker.process(id);

        assertThat(started(id).get("transfer_started_at")).isNotNull();
        assertThat(app.notices.sendDue()).isEqualTo(1);
        assertThat(app.notices.sendDue()).isZero();
        verify(app.notifications, org.mockito.Mockito.times(1)).transferStarted(any());

        // What the tracking page's bar reads, as the poller leaves it.
        jdbc.update("update vendor_order set vendor_amount_ordered_k = 500, amount_delivered_k = 200 where order_id = ?", id);
        assertThat(app.ledger.progress(id)).hasValue(new VendorOrderLedger.Progress(500, 200L));
    }

    @Test
    @DisplayName("sent by an admin's Approve: the same email, once")
    void startedEmailAfterApprove() {
        quietEarlierOrders();
        App app = new App(AppProperties.FutTransferAutoDispatch.OFF, true);
        long id = paidOrder("GFS-26-MAIL0002", "0.5");
        vendor.on("/orderAPI", Reply.ok(FakeFutTransfer.accepted(acceptAs())));
        app.release.release("GFS-26-MAIL0002", FulfilmentRelease.Releaser.admin(admin));

        assertThat(app.notices.sendDue()).isEqualTo(1);
        assertThat(app.notices.sendDue()).isZero();
        verify(app.notifications, org.mockito.Mockito.times(1)).transferStarted(any());
        assertThat(started(id).get("transfer_notice_at")).isNotNull();
    }

    @Test
    @DisplayName("answer lost, a lookup confirms the order: started, and the email goes once")
    void startedEmailAfterLookup() {
        quietEarlierOrders();
        App app = new App(ON, true);
        long id = paidOrder("GFS-26-MAIL0003", "0.5");
        app.paid("GFS-26-MAIL0003");
        vendor.on("/orderAPI", Reply.of(500, "{}"));
        vendor.on("/orderStatusAPI", Reply.ok(FakeFutTransfer.status("GFS-26-MAIL0003", 500)));
        assertThat(app.worker.process(id)).isEqualTo(AutoDispatchWorker.Outcome.SENT);

        assertThat(app.notices.sendDue()).isEqualTo(1);
        verify(app.notifications).transferStarted(any());
    }

    @Test
    @DisplayName("not before the partner has it: paid, refused or unsent orders get no email and no Track button")
    void noEmailBeforeOnboarding() {
        quietEarlierOrders();
        App app = new App(ON, true);
        long waiting = paidOrder("GFS-26-MAIL0004", "0.5");
        long refused = paidOrder("GFS-26-MAIL0005", "0.5");
        app.paid("GFS-26-MAIL0005");
        vendor.on("/orderAPI", Reply.of(400, "{\"error\":\"MissingData\"}"));
        app.worker.process(refused);

        assertThat(app.notices.sendDue()).isZero();
        assertThat(started(waiting).get("transfer_started_at")).isNull();
        assertThat(started(refused).get("transfer_started_at")).isNull();
        verify(app.notifications, never()).transferStarted(any());
    }

    @Test
    @DisplayName("the email failing never touches the order, and it is not sent again")
    void emailFailureLeavesOrderAlone() {
        quietEarlierOrders();
        App app = new App(ON, true);
        long id = paidOrder("GFS-26-MAIL0006", "0.5");
        app.paid("GFS-26-MAIL0006");
        vendor.on("/orderAPI", Reply.ok(FakeFutTransfer.accepted(acceptAs())));
        app.worker.process(id);
        org.mockito.Mockito.doThrow(new IllegalStateException("smtp down")).when(app.notifications)
                .transferStarted(any());

        assertThat(app.notices.sendDue()).isZero();
        assertThat(app.notices.sendDue()).isZero();
        verify(app.notifications, org.mockito.Mockito.times(1)).transferStarted(any());
        assertThat(status(id)).isEqualTo("IN_PROGRESS");
        assertThat(jdbc.queryForObject("select state from vendor_order where order_id = ?", String.class, id))
                .isEqualTo("SUBMITTED");
    }

    @Test
    @DisplayName("Approve clicked while automatic sending runs, from another instance: one vendor order, sent once")
    void approveDuringAutoDispatch() throws Exception {
        App auto = new App(ON, true);
        App clicking = new App(ON, true);
        long id = paidOrder("GFS-26-AUTO0008", "0.5");
        auto.paid("GFS-26-AUTO0008");
        // Slow, so the second caller arrives while the first is still waiting on the partner.
        vendor.on("/orderAPI", new Reply(200, FakeFutTransfer.accepted(acceptAs()), 500));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> both = new ArrayList<>();
        both.add(pool.submit(() -> { go.await(); return auto.worker.process(id); }));
        both.add(pool.submit(() -> {
            go.await();
            return clicking.release.release("GFS-26-AUTO0008", FulfilmentRelease.Releaser.admin(admin)).release();
        }));
        go.countDown();
        for (Future<?> f : both) {
            f.get(20, TimeUnit.SECONDS);
        }
        pool.shutdown();
        // Whichever lost comes round again and finds it sent.
        jdbc.update("update auto_dispatch set next_attempt_at = now() where order_id = ?", id);
        auto.worker.runDue();

        assertThat(vendor.calls("/orderAPI")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from vendor_order where order_id = ?", Long.class, id))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject("select state from vendor_order where order_id = ?", String.class, id))
                .isEqualTo("SUBMITTED");
        assertThat(status(id)).isEqualTo("IN_PROGRESS");
        assertThat(queued(id).get("state")).isEqualTo("SENT");
        assertThat(history(id)).filteredOn(h -> h.endsWith(" DONE")).hasSize(1);
        verify(auto.notifications, never()).fulfilmentAlert(any());
    }
}
