package com.globalfutservice.fulfilment;

import java.math.BigDecimal;
import java.time.Duration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.globalfutservice.config.AppProperties;
import com.globalfutservice.credentials.CredentialVaultService;
import com.globalfutservice.domain.catalog.Platform;
import com.globalfutservice.domain.catalog.Sku;
import com.globalfutservice.fulfilment.FakeFutTransfer.Reply;
import com.globalfutservice.fulfilment.SupplierFulfilmentService.Result;
import com.globalfutservice.notify.FulfilmentAlert;
import com.globalfutservice.notify.NotificationService;
import com.globalfutservice.orders.OrderEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What releasing an order decides, against a vendor played by a local HTTP server.
 *
 * <p>The ledger is a mock here, so these are about the decisions: which outcome leads to
 * which record, and above all that nothing but a claim leads to {@code /orderAPI}, and
 * nothing leads to it twice. {@code VendorDispatchPostgresTest} proves the claim itself
 * against a real database.
 */
class SupplierReleaseTest {

    private static final long ORDER_ID = 7L;
    private static final String REF = "GFS-26-RELEASE1";

    private FakeFutTransfer vendor;
    private VendorOrderLedger ledger;
    private CredentialVaultService vault;
    private NotificationService notifications;
    private VendorControl control;
    private SupplierFulfilmentService service;

    @BeforeEach
    void setUp() throws Exception {
        vendor = new FakeFutTransfer();
        AppProperties props = VendorTestSupport.props(vendor.baseUrl(), Duration.ofMillis(800));
        ObjectMapper mapper = new ObjectMapper();
        ledger = mock(VendorOrderLedger.class);
        vault = mock(CredentialVaultService.class);
        notifications = mock(NotificationService.class);
        when(vault.reveal(anyLong(), any())).thenReturn(VendorTestSupport.signIn());
        when(ledger.claim(anyLong(), anyString(), anyLong(), anyInt())).thenReturn(new VendorOrderLedger.Claimed(1));
        when(ledger.markSubmitted(anyLong(), anyString())).thenReturn(true);
        when(ledger.markConfirmedByLookup(anyLong(), anyString(), any())).thenReturn(true);
        control = VendorTestSupport.running();
        service = new SupplierFulfilmentService(new FutTransferClient(props, mapper, control, VendorTestSupport.noCallLog()), control, vault, ledger,
                notifications, props, mapper);
    }

    @AfterEach
    void tearDown() {
        vendor.close();
    }

    private static OrderEntity order(Sku sku) {
        OrderEntity o = mock(OrderEntity.class);
        when(o.getId()).thenReturn(ORDER_ID);
        when(o.getPublicRef()).thenReturn(REF);
        when(o.getSku()).thenReturn(sku);
        when(o.getPlatform()).thenReturn(Platform.PC);
        when(o.getQuantity()).thenReturn(new BigDecimal("0.50"));
        when(o.getPriceBreakdown()).thenReturn("{\"quantity\":0.5}");
        return o;
    }

    private SupplierFulfilmentService.Release release() {
        return service.approveAndDispatch(order(Sku.TRADING_SERVICE), 99L);
    }

    @Test
    @DisplayName("accepted: submitted once, and the vendor's id recorded")
    void accepted() {
        vendor.on("/orderAPI", Reply.ok(FakeFutTransfer.ORDER_ACCEPTED));

        SupplierFulfilmentService.Release r = release();

        assertThat(r.result()).isEqualTo(Result.SUBMITTED);
        assertThat(r.vendorOrderId()).isEqualTo(FakeFutTransfer.VENDOR_ID);
        verify(ledger).claim(ORDER_ID, REF, 500, 3);
        verify(ledger).markSubmitted(ORDER_ID, FakeFutTransfer.VENDOR_ID);
        assertThat(vendor.calls("/orderAPI")).isEqualTo(1);
    }

    @Test
    @DisplayName("timeout, then the lookup finds it: recorded as submitted, and /orderAPI is not called again")
    void timeoutFound() {
        vendor.on("/orderAPI", Reply.slow(2_000));
        vendor.on("/orderStatusAPI", Reply.ok(FakeFutTransfer.status(REF, 500)));

        SupplierFulfilmentService.Release r = release();

        assertThat(r.result()).isEqualTo(Result.SUBMITTED);
        verify(ledger).markConfirmedByLookup(eq(ORDER_ID), eq("TIMEOUT"), any());
        assertThat(vendor.calls("/orderAPI")).isEqualTo(1);
        assertThat(vendor.calls("/orderStatusAPI")).isEqualTo(1);
    }

    @Test
    @DisplayName("timeout, then the lookup finds nothing: needs review, an admin is alerted, and nothing is re-sent")
    void timeoutNotFound() {
        vendor.on("/orderAPI", Reply.slow(2_000));
        vendor.on("/orderStatusAPI", Reply.of(404, "notFound"));

        SupplierFulfilmentService.Release r = release();

        assertThat(r.result()).isEqualTo(Result.NEEDS_REVIEW);
        assertThat(r.message()).contains("Check the FUT Transfer dashboard for " + REF);
        verify(ledger).markNeedsReview(eq(ORDER_ID), eq("TIMEOUT"), anyString());
        verify(ledger, never()).markSubmitted(anyLong(), anyString());
        ArgumentCaptor<FulfilmentAlert> alert = ArgumentCaptor.forClass(FulfilmentAlert.class);
        verify(notifications).fulfilmentAlert(alert.capture());
        assertThat(alert.getValue().publicRef()).isEqualTo(REF);
        assertThat(vendor.calls("/orderAPI")).isEqualTo(1);
    }

    @Test
    @DisplayName("an unreadable answer is handled like a timeout: looked up, never re-sent")
    void unparseable() {
        vendor.on("/orderAPI", Reply.ok("<html>oops</html>"));
        vendor.on("/orderStatusAPI", Reply.ok("{\"error\":\"not found\"}"));

        assertThat(release().result()).isEqualTo(Result.NEEDS_REVIEW);
        verify(ledger).markNeedsReview(eq(ORDER_ID), eq("UNPARSEABLE_RESPONSE"), anyString());
        assertThat(vendor.calls("/orderAPI")).isEqualTo(1);
    }

    @Test
    @DisplayName("a refused sign-in fails permanently, and the sign-in is deleted")
    void signInRefused() {
        vendor.on("/orderAPI", Reply.of(400, "InvalidPassword"));

        SupplierFulfilmentService.Release r = release();

        assertThat(r.result()).isEqualTo(Result.FAILED_SIGN_IN);
        verify(ledger).markFailed(eq(ORDER_ID), eq("InvalidPassword"), anyString());
        verify(vault).purge(eq(ORDER_ID), anyString());
        assertThat(vendor.calls("/orderStatusAPI")).isZero();
    }

    @Test
    @DisplayName("a refused order fails, but keeps the customer's sign-in")
    void orderRefused() {
        vendor.on("/orderAPI", Reply.of(400, "InvalidAmount"));

        assertThat(release().result()).isEqualTo(Result.FAILED);
        verify(ledger).markFailed(eq(ORDER_ID), eq("InvalidAmount"), anyString());
        verify(vault, never()).purge(anyLong(), anyString());
    }

    @Test
    @DisplayName("an undocumented answer goes to an admin, without a lookup or a second send")
    void unrecognised() {
        vendor.on("/orderAPI", Reply.of(406, "insufficientStock"));

        assertThat(release().result()).isEqualTo(Result.NEEDS_REVIEW);
        verify(ledger).markNeedsReview(eq(ORDER_ID), eq("insufficientStock"), anyString());
        assertThat(vendor.calls("/orderAPI")).isEqualTo(1);
        assertThat(vendor.calls("/orderStatusAPI")).isZero();
    }

    private void existing(VendorOrderLedger.Row row) {
        when(ledger.find(ORDER_ID)).thenReturn(java.util.Optional.of(row));
        when(ledger.claim(anyLong(), anyString(), anyLong(), anyInt())).thenReturn(new VendorOrderLedger.NotClaimed(row));
    }

    @Test
    @DisplayName("a second click while the first is in flight is told so, sends nothing, and reads no sign-in")
    void inFlight() {
        existing(row(VendorOrderLedger.SUBMITTING, null, 1));

        SupplierFulfilmentService.Release r = release();

        assertThat(r.result()).isEqualTo(Result.IN_FLIGHT);
        assertThat(r.message()).contains("has not been sent twice");
        assertThat(vendor.requests()).isEmpty();
        verify(vault, never()).reveal(anyLong(), any());
    }

    @Test
    @DisplayName("an order already with the vendor reports its id and sends nothing")
    void alreadySubmitted() {
        existing(row(VendorOrderLedger.SUBMITTED, "SUP-EARLIER", 1));

        SupplierFulfilmentService.Release r = release();

        assertThat(r.result()).isEqualTo(Result.ALREADY_SUBMITTED);
        assertThat(r.vendorOrderId()).isEqualTo("SUP-EARLIER");
        assertThat(vendor.requests()).isEmpty();
    }

    @Test
    @DisplayName("an order waiting for review, or out of attempts, is refused with the reason")
    void notSendable() {
        existing(row(VendorOrderLedger.NEEDS_REVIEW, null, 1));
        assertThat(release().message()).contains("needs review");

        existing(row(VendorOrderLedger.FAILED, null, 3));
        assertThat(release().message()).contains("tried 3 times");
        assertThat(vendor.requests()).isEmpty();
    }

    @Test
    @DisplayName("an EA account in the vendor's cooldown is not sent, and nothing is claimed")
    void cooldown() {
        vendor.on("/getCooldownStatus", Reply.ok(FakeFutTransfer.COOLDOWN_2H));

        SupplierFulfilmentService.Release r = release();

        assertThat(r.result()).isEqualTo(Result.NOT_SENT);
        assertThat(r.message()).contains("cooldown for another 2h 0m");
        verify(ledger, never()).claim(anyLong(), anyString(), anyLong(), anyInt());
        assertThat(vendor.calls("/orderAPI")).isZero();
        assertThat(vendor.requests().get(0).body().get("account").asText()).isEqualTo("customer@example.test");
    }

    @Test
    @DisplayName("a cooldown answer we cannot trust also sends nothing")
    void cooldownUnreadable() {
        vendor.on("/getCooldownStatus", Reply.ok(FakeFutTransfer.COOLDOWN_DENIED));
        assertThat(release().message()).contains("Could not check").contains("UNPARSEABLE_RESPONSE");

        vendor.on("/getCooldownStatus", Reply.of(404, "notFound"));
        assertThat(release().message()).contains("Could not check");
        verify(ledger, never()).claim(anyLong(), anyString(), anyLong(), anyInt());
        assertThat(vendor.calls("/orderAPI")).isZero();
    }

    @Test
    @DisplayName("an inexact amount is refused before anything is claimed or sent")
    void inexactAmount() {
        OrderEntity o = order(Sku.TRADING_SERVICE);
        when(o.getQuantity()).thenReturn(new BigDecimal("0.0005"));

        assertThat(service.approveAndDispatch(o, 99L).result()).isEqualTo(Result.NOT_SENT);
        verify(ledger, never()).claim(anyLong(), anyString(), anyLong(), anyInt());
        assertThat(vendor.requests()).isEmpty();
    }

    @Test
    @DisplayName("never sends a boosting order to an API that only moves coins")
    void boostingNeverSent() {
        assertThat(service.approveAndDispatch(order(Sku.BOOST_CHAMPS), 99L).result()).isEqualTo(Result.NOT_SENT);
        verify(ledger, never()).claim(anyLong(), anyString(), anyLong(), anyInt());
        assertThat(vendor.requests()).isEmpty();
    }

    private static VendorOrderLedger.Row row(String state, String vendorId, int attempts) {
        return new VendorOrderLedger.Row(ORDER_ID, REF, vendorId, state, 500, attempts, null,
                "Check the dashboard", null);
    }
}
