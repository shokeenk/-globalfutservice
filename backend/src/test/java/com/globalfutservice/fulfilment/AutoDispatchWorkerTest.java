package com.globalfutservice.fulfilment;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.globalfutservice.affiliate.AffiliateService;
import com.globalfutservice.coaching.AfterCommit;
import com.globalfutservice.coaching.CoachingService;
import com.globalfutservice.config.AppProperties;
import com.globalfutservice.credentials.CredentialVaultService;
import com.globalfutservice.credentials.web.CredentialDtos;
import com.globalfutservice.domain.catalog.Sku;
import com.globalfutservice.domain.money.Currency;
import com.globalfutservice.domain.orders.DeliveryMethod;
import com.globalfutservice.domain.orders.OrderStatus;
import com.globalfutservice.identity.AccountRepository;
import com.globalfutservice.loyalty.LoyaltyService;
import com.globalfutservice.notify.FulfilmentAlert;
import com.globalfutservice.notify.NotificationService;
import com.globalfutservice.notify.feed.CustomerFeedService;
import com.globalfutservice.orders.OrderEntity;
import com.globalfutservice.orders.OrderEventRepository;
import com.globalfutservice.orders.OrderRepository;
import com.globalfutservice.orders.OrderService;
import com.globalfutservice.payments.PaymentGateway;
import com.globalfutservice.payments.PaymentRepository;
import com.globalfutservice.pricing.CouponService;
import com.globalfutservice.pricing.QuoteService;
import com.globalfutservice.scheduling.SchedulerLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

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
 * The automatic queue's decisions that need no partner: where an order is queued, what leaves
 * it for Approve, and what is tried again. The sends themselves are AutoDispatchPostgresTest.
 */
class AutoDispatchWorkerTest {

    private static final Clock NOW = Clock.fixed(Instant.parse("2026-10-07T12:00:00Z"), ZoneOffset.UTC);
    private static final AppProperties.FutTransferAutoDispatch ON =
            new AppProperties.FutTransferAutoDispatch(true, null, Duration.ofSeconds(15));

    private final AutoDispatchQueue queue = mock(AutoDispatchQueue.class);
    private final FulfilmentRelease release = mock(FulfilmentRelease.class);
    private final SupplierFulfilmentService supplier = mock(SupplierFulfilmentService.class);
    private final VendorControl control = mock(VendorControl.class);
    private final VendorOrderLedger ledger = mock(VendorOrderLedger.class);
    private final VendorOrderActionLog history = mock(VendorOrderActionLog.class);
    private final CredentialVaultService vault = mock(CredentialVaultService.class);
    private final OrderRepository orders = mock(OrderRepository.class);
    private final NotificationService notifications = mock(NotificationService.class);
    private AppProperties props;
    private OrderEntity order;

    @BeforeEach
    void setUp() {
        props = VendorTestSupport.props("https://futtransfer.example.test", Duration.ofSeconds(1), ON);
        order = mock(OrderEntity.class);
        when(order.getId()).thenReturn(7L);
        when(order.getPublicRef()).thenReturn("GFS-26-UNIT0001");
        when(order.getSku()).thenReturn(Sku.TRADING_SERVICE);
        when(order.getStatus()).thenReturn(OrderStatus.READY_FOR_DELIVERY);
        when(order.getQuantity()).thenReturn(new BigDecimal("0.5"));
        when(order.getPriceBreakdown()).thenReturn("{\"quantity\":0.5}");
        when(orders.findById(7L)).thenReturn(Optional.of(order));
        when(queue.find(7L)).thenReturn(Optional.of(row(0)));
        when(supplier.isEnabled()).thenReturn(true);
        when(ledger.find(7L)).thenReturn(Optional.empty());
        when(vault.status(7L)).thenReturn(new CredentialDtos.VaultStatus(true, false, null, 0));
    }

    private static AutoDispatchQueue.Row row(int attempts) {
        return new AutoDispatchQueue.Row(7L, AutoDispatchQueue.QUEUED, attempts, NOW.instant(), null, null,
                NOW.instant());
    }

    private AutoDispatchWorker worker() {
        return new AutoDispatchWorker(queue, release, supplier, control, ledger, history, vault, orders,
                notifications, mock(SchedulerLock.class), props, new ObjectMapper(), NOW);
    }

    private List<String> alerts() {
        ArgumentCaptor<FulfilmentAlert> captor = ArgumentCaptor.forClass(FulfilmentAlert.class);
        verify(notifications, org.mockito.Mockito.atLeast(0)).fulfilmentAlert(captor.capture());
        return captor.getAllValues().stream().map(FulfilmentAlert::code).toList();
    }

    @Test
    @DisplayName("the order becomes PAID through the real order service: queued, inside the payment's transaction")
    void markPaidQueues() {
        AppProperties p = mock(AppProperties.class);
        when(p.fulfilment()).thenReturn(new Binder(new MapConfigurationPropertySource(
                Map.of("f.backup-codes-required", "3"))).bind("f", AppProperties.Fulfilment.class).get());
        OrderRepository repo = mock(OrderRepository.class);
        when(repo.save(any(OrderEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(vault.hasCredentials(9L)).thenReturn(true);
        OrderService service = new OrderService(repo, mock(OrderEventRepository.class), mock(PaymentRepository.class),
                mock(PaymentGateway.class), mock(QuoteService.class), mock(LoyaltyService.class),
                mock(AffiliateService.class), vault, notifications, mock(AccountRepository.class),
                mock(CoachingService.class), mock(CouponService.class), mock(CustomerFeedService.class),
                new ObjectMapper(), p, NOW, AfterCommit.immediate(), queue);
        OrderEntity coins = new OrderEntity("GFS-26-UNIT0009", "q_9", "FC27", Sku.TRADING_SERVICE, null, null,
                BigDecimal.ONE, DeliveryMethod.PLAYER_AUCTION, Currency.INR, 10000, 10000, "{}");
        ReflectionTestUtils.setField(coins, "id", 9L);
        ReflectionTestUtils.setField(coins, "status", OrderStatus.AWAITING_PAYMENT);

        service.markPaid(coins, "UPI 412345678901");

        assertThat(coins.getStatus()).isEqualTo(OrderStatus.READY_FOR_DELIVERY);
        verify(queue).paid(coins);
    }

    @Test
    @DisplayName("calls paused, or FUT Transfer off: left for Approve, and staff are told why")
    void pausedOrOff() {
        when(control.isPaused()).thenReturn(true);
        assertThat(worker().process(7L)).isEqualTo(AutoDispatchWorker.Outcome.LEFT_FOR_APPROVE);
        verify(queue).settle(eq(7L), eq(AutoDispatchQueue.LEFT_FOR_APPROVE), eq("PAUSED"), anyString());

        when(control.isPaused()).thenReturn(false);
        when(supplier.isEnabled()).thenReturn(false);
        assertThat(worker().process(7L)).isEqualTo(AutoDispatchWorker.Outcome.LEFT_FOR_APPROVE);
        verify(queue).settle(eq(7L), eq(AutoDispatchQueue.LEFT_FOR_APPROVE), eq("FUT_TRANSFER_OFF"), anyString());

        assertThat(alerts()).containsExactly("PAUSED", "FUT_TRANSFER_OFF");
        verify(release, never()).release(anyString(), any());
    }

    @Test
    @DisplayName("switched off after it was queued: left for Approve quietly -- nobody has anything to fix")
    void switchedOff() {
        props = VendorTestSupport.props("https://futtransfer.example.test", Duration.ofSeconds(1),
                AppProperties.FutTransferAutoDispatch.OFF);
        assertThat(worker().process(7L)).isEqualTo(AutoDispatchWorker.Outcome.LEFT_FOR_APPROVE);
        assertThat(alerts()).isEmpty();
        verify(release, never()).release(anyString(), any());
    }

    @Test
    @DisplayName("an error of ours mid-send: tried again in a minute, then two, four, eight; never sent twice")
    void ourErrorRetries() {
        when(release.release(anyString(), any())).thenThrow(new IllegalStateException("db blip"));
        assertThat(worker().process(7L)).isEqualTo(AutoDispatchWorker.Outcome.RETRY);
        verify(queue).retryAt(eq(7L), eq(NOW.instant().plus(Duration.ofMinutes(1))), eq("ERROR"), anyString());

        when(queue.find(7L)).thenReturn(Optional.of(row(3)));
        assertThat(worker().process(7L)).isEqualTo(AutoDispatchWorker.Outcome.RETRY);
        verify(queue).retryAt(eq(7L), eq(NOW.instant().plus(Duration.ofMinutes(8))), eq("ERROR"), anyString());

        when(queue.find(7L)).thenReturn(Optional.of(row(AutoDispatchWorker.MAX_TRIES - 1)));
        assertThat(worker().process(7L)).isEqualTo(AutoDispatchWorker.Outcome.NEEDS_REVIEW);
        verify(queue).settle(eq(7L), eq(AutoDispatchQueue.NEEDS_REVIEW), eq("ERROR"), anyString());
    }

    @Test
    @DisplayName("already with the partner because an admin sent it: settled, nothing sent, nobody alerted")
    void alreadySentByAdmin() {
        when(ledger.find(7L)).thenReturn(Optional.of(mock(VendorOrderLedger.Row.class)));
        assertThat(worker().process(7L)).isEqualTo(AutoDispatchWorker.Outcome.NOT_QUEUED);
        verify(release, never()).release(anyString(), any());
        assertThat(alerts()).isEmpty();
    }

    @Test
    @DisplayName("Approve and the queue share one release: a stored sign-in that fails today's rules is not sent")
    void storedSignInChecked() {
        // Two codes stored, three required: refused before the partner is called, for Approve too.
        AppProperties strict = VendorTestSupport.props("https://127.0.0.1:1", Duration.ofMillis(200), ON);
        when(strict.fulfilment()).thenReturn(new Binder(new MapConfigurationPropertySource(
                Map.of("f.backup-codes-required", "3"))).bind("f", AppProperties.Fulfilment.class).get());
        CredentialVaultService vault2 = mock(CredentialVaultService.class);
        when(vault2.reveal(anyLong(), any())).thenReturn(VendorTestSupport.signIn());
        FutTransferClient client = mock(FutTransferClient.class);
        when(client.isEnabled()).thenReturn(true);
        when(client.cooldown(anyString(), anyString())).thenReturn(
                new FutTransferClient.ReadOk<>(new FutTransferClient.Cooldown(true, 0)));
        VendorOrderLedger ledger2 = mock(VendorOrderLedger.class);
        when(ledger2.find(7L)).thenReturn(Optional.empty());
        SupplierFulfilmentService real = new SupplierFulfilmentService(client, VendorTestSupport.running(), vault2,
                ledger2, notifications, strict, new ObjectMapper());

        SupplierFulfilmentService.Release r = real.approveAndDispatch(order, null);

        assertThat(r.result()).isEqualTo(SupplierFulfilmentService.Result.NOT_SENT);
        assertThat(r.message()).contains("Backup code 3 is required.");
        verify(ledger2, never()).claim(anyLong(), anyString(), anyLong(), org.mockito.ArgumentMatchers.anyInt(), any());
    }
}
