package com.globalfutservice.fulfilment;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.globalfutservice.config.AppProperties;
import com.globalfutservice.credentials.CredentialVaultService;
import com.globalfutservice.domain.catalog.Platform;
import com.globalfutservice.domain.catalog.Sku;
import com.globalfutservice.fulfilment.FakeFutTransfer.Reply;
import com.globalfutservice.notify.NotificationService;
import com.globalfutservice.orders.OrderEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A 403 means our API credentials were refused, and then nothing more is sent -- not an
 * order, not a status read -- until an admin resumes calls.
 */
class VendorPauseTest {

    private static final String REF = "GFS-26-PAUSE001";

    private FakeFutTransfer vendor;
    private VendorControl control;
    private FutTransferClient client;

    @BeforeEach
    void setUp() throws Exception {
        vendor = new FakeFutTransfer();
        control = VendorTestSupport.running();
        client = VendorTestSupport.client(VendorTestSupport.props(vendor.baseUrl(), Duration.ofMillis(800)), control);
    }

    @AfterEach
    void tearDown() {
        vendor.close();
    }

    @Test
    @DisplayName("a 403 on placing an order pauses every call")
    void placementTrips() {
        vendor.on("/orderAPI", Reply.of(403, "{\"error\":\"Unauthorized\"}"));

        client.submitOrder(REF, "Rahul", Platform.PC, 500, VendorTestSupport.signIn());

        verify(control).pause("HTTP_403 /orderAPI", REF);
    }

    @Test
    @DisplayName("a 403 on a status read pauses every call too")
    void readTrips() {
        vendor.on("/orderStatusBulkAPI", Reply.of(403, "{\"error\":\"Unauthorized\"}"));

        assertThat(client.statusByVendorIds(java.util.Map.of("vid-1", REF, "vid-2", "GFS-26-OTHER01")))
                .isEqualTo(new FutTransferClient.ReadFailed<>(FutTransferClient.ReadError.AUTH, "HTTP_403"));

        verify(control).pause(eq("HTTP_403 /orderStatusBulkAPI"), isNull());
    }

    @Test
    @DisplayName("while paused, nothing reaches the vendor: an order is refused unsent, a read reports AUTH")
    void pausedSendsNothing() {
        when(control.isPaused()).thenReturn(true);
        vendor.on("/orderAPI", Reply.ok(FakeFutTransfer.ORDER_ACCEPTED));

        assertThat(client.submitOrder(REF, "Rahul", Platform.PC, 500, VendorTestSupport.signIn()))
                .isEqualTo(new FutTransferClient.Refused(FutTransferClient.Reason.AUTH_FAILED, 0, FutTransferClient.PAUSED));
        assertThat(client.statusByVendorIds(java.util.Map.of("vid-1", REF)))
                .isEqualTo(new FutTransferClient.ReadFailed<>(FutTransferClient.ReadError.AUTH, FutTransferClient.PAUSED));
        assertThat(client.lookupByReference(REF, 500)).isEqualTo(new FutTransferClient.NotConfirmed(FutTransferClient.PAUSED));
        assertThat(vendor.requests()).isEmpty();
    }

    @Test
    @DisplayName("while paused, Approve says so and claims nothing")
    void pausedApproval() {
        when(control.isPaused()).thenReturn(true);
        AppProperties props = VendorTestSupport.props(vendor.baseUrl(), Duration.ofMillis(800));
        VendorOrderLedger ledger = mock(VendorOrderLedger.class);
        CredentialVaultService vault = mock(CredentialVaultService.class);
        SupplierFulfilmentService service = new SupplierFulfilmentService(client, control, vault, ledger,
                mock(NotificationService.class), props, new ObjectMapper());
        OrderEntity order = mock(OrderEntity.class);
        when(order.getId()).thenReturn(7L);
        when(order.getPublicRef()).thenReturn(REF);
        when(order.getSku()).thenReturn(Sku.TRADING_SERVICE);
        when(order.getQuantity()).thenReturn(new BigDecimal("0.5"));
        when(order.getPriceBreakdown()).thenReturn("{\"quantity\":0.5}");

        SupplierFulfilmentService.Release r = service.approveAndDispatch(order, 1L);

        assertThat(r.result()).isEqualTo(SupplierFulfilmentService.Result.NOT_SENT);
        assertThat(r.message()).contains("paused");
        verify(ledger, never()).claim(anyLong(), anyString(), anyLong(), anyInt());
        verify(vault, never()).reveal(anyLong(), org.mockito.ArgumentMatchers.any());
        assertThat(vendor.requests()).isEmpty();
    }
}
