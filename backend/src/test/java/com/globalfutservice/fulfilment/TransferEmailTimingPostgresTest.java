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
import com.globalfutservice.scheduling.SchedulerLock;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.mockito.InOrder;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Which of the two coin-transfer emails a customer gets, against a real PostgreSQL.
 *
 * <p>The tracking link stored within five minutes of the transfer starting: only "your coins
 * are on the way". Not by then: "your coin transfer has started" at five minutes, as before,
 * and "on the way" when the link comes -- after it, never before. At most one of each, however
 * often and from however many places the sweeps run.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TransferEmailTimingPostgresTest {

    private static final String LINK = "https://transferstatus.top/abc12345?lang=en";

    private TestDatabase db;
    private FakeFutTransfer vendor;
    private NotificationService notifications;
    private PartnerTrackingLinks links;
    private TransferStartedNotices notices;
    private SupplierFulfilmentService supplier;
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
        // Orders other tests left are not this test's to count.
        db.jdbc.update("update orders set transfer_notice_at = now() where transfer_notice_at is null");
        db.jdbc.update("update vendor_order set tracking_email_at = now() where tracking_email_at is null");
        db.jdbc.update("update vendor_control set next_poll_at = null, backoff_level = 0, paused = false");
        vendor = new FakeFutTransfer();
        vendor.on("/buyConditionAPI", Reply.ok(FakeFutTransfer.POOL_CONDITIONS));
        AppProperties props = VendorTestSupport.publicPool(vendor.baseUrl(), Duration.ofMillis(800),
                VendorTestSupport.ORDER_AMOUNT_POOL);
        notifications = mock(NotificationService.class);
        VendorOrderLedger ledger = new VendorOrderLedger(db.named);
        VendorControl control = new VendorControl(db.named, notifications, props);
        FutTransferClient client = new FutTransferClient(props, new ObjectMapper(), control,
                new VendorCallLog(db.named)).withoutRetryPauses();
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
        notices = new TransferStartedNotices(db.named, orders, orderService, notifications, mock(SchedulerLock.class));
        CredentialVaultService vault = mock(CredentialVaultService.class);
        when(vault.reveal(anyLong(), any())).thenReturn(VendorTestSupport.signIn());
        supplier = new SupplierFulfilmentService(client, control, vault, ledger, notifications, props,
                new ObjectMapper(), links);
    }

    @AfterEach
    void tearDown() {
        vendor.close();
    }

    // --------------------------------------------------------------- fixtures ---

    private OrderEntity entity(long id) {
        Map<String, Object> row = db.jdbc.queryForMap("select public_ref, quantity from orders where id = ?", id);
        BigDecimal quantity = (BigDecimal) row.get("quantity");
        OrderEntity o = mock(OrderEntity.class);
        when(o.getId()).thenReturn(id);
        when(o.getPublicRef()).thenReturn((String) row.get("public_ref"));
        when(o.getSku()).thenReturn(Sku.TRADING_SERVICE);
        when(o.getPlatform()).thenReturn(Platform.PLAYSTATION);
        when(o.getQuantity()).thenReturn(quantity);
        when(o.getPriceBreakdown()).thenReturn("{\"quantity\":" + quantity.toPlainString() + "}");
        when(o.getStatus()).thenAnswer(inv -> OrderStatus.valueOf(db.jdbc.queryForObject(
                "select status from orders where id = ?", String.class, id)));
        return o;
    }

    private String vendorId() {
        return "dddddddd-2222-0000-0000-%012d".formatted(++nextVendorId);
    }

    private static String linkReply(String vid) {
        return "{\"success\":true,\"shortCode\":\"abc12345\",\"shop\":\"\",\"specialParams\":\"\",\"fullUrl\":\""
                + LINK + "\",\"orderID\":\"" + vid + "\"}";
    }

    /** Sent and accepted, with the link answered as given, at acceptance. */
    private long sent(String ref, String vid, Reply linkAnswer) {
        long id = db.order(ref, "READY_FOR_DELIVERY", "0.5");
        vendor.on("/buyCoinsAPI", Reply.ok(FakeFutTransfer.accepted(vid)));
        vendor.on("/generateOrderStatusLink", linkAnswer);
        assertThat(supplier.approveAndDispatch(entity(id), 1L).result()).isEqualTo(Result.SUBMITTED);
        return id;
    }

    /** As if the transfer started this long ago. */
    private void startedAgo(long orderId, Duration ago) {
        db.jdbc.update("update orders set transfer_started_at = now() - (? * interval '1 millisecond') where id = ?",
                ago.toMillis(), orderId);
    }

    /** The partner answers with the link now, and the order's next try is due. */
    private void linkNowAvailable(long orderId, String vid) {
        vendor.on("/generateOrderStatusLink", Reply.ok(linkReply(vid)));
        db.jdbc.update("update vendor_order set tracking_link_next_at = now() where order_id = ?", orderId);
    }

    private void settle() {
        for (int i = 0; i < 3; i++) {
            notices.sendDue();
            links.sweep();
        }
    }

    private Map<String, Object> stamps(long orderId) {
        return db.jdbc.queryForMap("""
                select o.transfer_started_at, o.transfer_notice_at, v.tracking_url_at, v.tracking_email_at
                  from orders o join vendor_order v on v.order_id = o.id where o.id = ?
                """, orderId);
    }

    // ----------------------------------------------------------------- tests ---

    @Test
    @DisplayName("link stored at acceptance: only \"on the way\"; \"started\" never goes, even long after")
    void linkAtOnce() {
        String vid = vendorId();
        long id = sent("GFS-26-TIME0001", vid, Reply.ok(linkReply(vid)));

        verify(notifications, times(1)).coinsOnTheWay(any(), eq(LINK));
        assertThat(stamps(id).get("transfer_notice_at")).as("settled as not needed").isNotNull();

        startedAgo(id, Duration.ofHours(2));
        settle();
        verify(notifications, never()).transferStarted(any());
        verify(notifications, times(1)).coinsOnTheWay(any(), anyString());
    }

    @Test
    @DisplayName("link stored by a later try, still within five minutes: only \"on the way\"")
    void linkWithinFiveMinutes() {
        String vid = vendorId();
        long id = sent("GFS-26-TIME0002", vid, Reply.of(500, "{}"));
        startedAgo(id, Duration.ofSeconds(4 * 60 + 50));

        assertThat(notices.sendDue()).as("the link still has ten seconds").isZero();
        linkNowAvailable(id, vid);
        assertThat(links.sweep()).isOne();

        verify(notifications, times(1)).coinsOnTheWay(any(), eq(LINK));
        startedAgo(id, Duration.ofMinutes(30));
        settle();
        verify(notifications, never()).transferStarted(any());
    }

    @Test
    @DisplayName("no link within five minutes: \"started\" at five minutes, then \"on the way\" when the link comes")
    void startedThenOnTheWay() {
        String vid = vendorId();
        long id = sent("GFS-26-TIME0003", vid, Reply.of(500, "{}"));

        startedAgo(id, Duration.ofSeconds(4 * 60 + 30));
        assertThat(notices.sendDue()).as("not before five minutes").isZero();
        startedAgo(id, Duration.ofSeconds(5 * 60 + 5));
        assertThat(notices.sendDue()).isOne();
        verify(notifications, never()).coinsOnTheWay(any(), anyString());

        linkNowAvailable(id, vid);
        links.sweep();
        settle();

        InOrder order = inOrder(notifications);
        order.verify(notifications).transferStarted(any());
        order.verify(notifications).coinsOnTheWay(any(), eq(LINK));
        verify(notifications, times(1)).transferStarted(any());
        verify(notifications, times(1)).coinsOnTheWay(any(), anyString());
    }

    @Test
    @DisplayName("link stored after five minutes but before \"started\" went out: \"on the way\" waits for it")
    void onTheWayWaitsForStarted() {
        String vid = vendorId();
        long id = sent("GFS-26-TIME0004", vid, Reply.of(500, "{}"));
        startedAgo(id, Duration.ofMinutes(6));

        linkNowAvailable(id, vid);
        assertThat(links.sweep()).isOne();
        assertThat(stamps(id).get("tracking_url_at")).isNotNull();
        assertThat(stamps(id).get("transfer_notice_at")).as("too late to replace \"started\"").isNull();
        verify(notifications, never()).coinsOnTheWay(any(), anyString());
        links.sweep();
        verify(notifications, never()).coinsOnTheWay(any(), anyString());

        assertThat(notices.sendDue()).isOne();
        links.sweep();

        InOrder order = inOrder(notifications);
        order.verify(notifications).transferStarted(any());
        order.verify(notifications).coinsOnTheWay(any(), eq(LINK));
        settle();
        verify(notifications, times(1)).transferStarted(any());
        verify(notifications, times(1)).coinsOnTheWay(any(), anyString());
    }

    @Test
    @DisplayName("the partner will not make a link: \"started\" at five minutes, and nothing else")
    void noLinkEver() {
        long id = sent("GFS-26-TIME0005", vendorId(),
                Reply.of(403, "{\"success\":false,\"error\":\"Cannot generate a link for a finished order\"}"));
        startedAgo(id, Duration.ofMinutes(6));

        settle();

        verify(notifications, times(1)).transferStarted(any());
        verify(notifications, never()).coinsOnTheWay(any(), anyString());
    }

    @Test
    @DisplayName("an order already told its transfer started, before this: its link's email goes as soon as it is stored")
    void alreadyToldBefore() {
        String vid = vendorId();
        long id = sent("GFS-26-TIME0006", vid, Reply.of(500, "{}"));
        db.jdbc.update("update orders set transfer_started_at = now() - interval '3 days', "
                + "transfer_notice_at = now() - interval '3 days' where id = ?", id);

        linkNowAvailable(id, vid);
        links.sweep();

        verify(notifications, times(1)).coinsOnTheWay(any(), eq(LINK));
        verify(notifications, never()).transferStarted(any());
    }

    @Test
    @DisplayName("right at five minutes, both sweeps from several threads at once: at most one of each, one \"on the way\"")
    void raceAtFiveMinutes() throws Exception {
        for (int round = 0; round < 6; round++) {
            String vid = vendorId();
            long id = sent("GFS-26-RACE%04d".formatted(round), vid, Reply.of(500, "{}"));
            // A whisker inside the five minutes: the link and "started" race for the order.
            startedAgo(id, Duration.ofMillis(299_900));
            linkNowAvailable(id, vid);

            ExecutorService threads = Executors.newFixedThreadPool(6);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<?>> all = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                all.add(threads.submit(() -> { go.await(); return notices.sendDue(); }));
                all.add(threads.submit(() -> { go.await(); return links.sweep(); }));
            }
            go.countDown();
            for (Future<?> f : all) f.get(20, TimeUnit.SECONDS);
            threads.shutdown();
            settle();

            String ref = "GFS-26-RACE%04d".formatted(round);
            verify(notifications, atMost(1)).transferStarted(org.mockito.ArgumentMatchers.argThat(n -> n.publicRef().equals(ref)));
            verify(notifications, times(1)).coinsOnTheWay(org.mockito.ArgumentMatchers.argThat(n -> n.publicRef().equals(ref)),
                    eq(LINK));
            assertThat(stamps(id).get("transfer_notice_at")).isNotNull();
            assertThat(stamps(id).get("tracking_email_at")).isNotNull();
        }
    }
}
