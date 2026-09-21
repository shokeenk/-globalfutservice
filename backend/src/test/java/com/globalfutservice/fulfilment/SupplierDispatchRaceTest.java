package com.globalfutservice.fulfilment;

import com.globalfutservice.config.AppProperties;
import com.globalfutservice.credentials.CredentialVaultService;
import com.globalfutservice.credentials.web.CredentialDtos;
import com.globalfutservice.domain.catalog.Platform;
import com.globalfutservice.domain.catalog.Sku;
import com.globalfutservice.domain.orders.DeliveryMethod;
import com.globalfutservice.orders.OrderEntity;
import com.globalfutservice.orders.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The guard that stops a customer's coins being sent twice.
 *
 * <p>Reading {@code supplierOrderId}, finding it null and then calling the supplier is a
 * race with a window as wide as the HTTP call. Two operator clicks on a slow button both
 * see null, both submit, and the partner fulfils both — real money, and not recoverable
 * by apologising. The claim is what makes the check and the decision one statement, and
 * these are the tests that say so.
 */
class SupplierDispatchRaceTest {

    private static final long ORDER_ID = 7L;
    private static final int MAX_ATTEMPTS = 3;

    private FutTransferClient client;
    private OrderRepository orders;
    private SupplierDispatchClaim claim;
    private SupplierFulfilmentService service;

    @BeforeEach
    void setUp() {
        client = mock(FutTransferClient.class);
        CredentialVaultService vault = mock(CredentialVaultService.class);
        orders = mock(OrderRepository.class);
        claim = mock(SupplierDispatchClaim.class);

        AppProperties props = mock(AppProperties.class);
        when(props.futTransfer()).thenReturn(new AppProperties.FutTransfer(
                true, "https://futtransfer.top", "api@example.com", "key",
                "snipe", 2, Duration.ofSeconds(60), Duration.ofSeconds(15), MAX_ATTEMPTS));

        when(client.isEnabled()).thenReturn(true);
        when(vault.reveal(anyLong(), any())).thenReturn(mock(CredentialDtos.RevealedCredentials.class));
        when(client.submitOrder(anyString(), any(), any(), anyLong(), any()))
                .thenReturn(new FutTransferClient.Accepted("SUP-1"));

        service = new SupplierFulfilmentService(client, vault, orders, props, claim);
    }

    private OrderEntity order(String supplierOrderId, int attempts) {
        OrderEntity o = mock(OrderEntity.class);
        when(o.getId()).thenReturn(ORDER_ID);
        when(o.getPublicRef()).thenReturn("GFS-26-RACE");
        when(o.getSku()).thenReturn(Sku.TRADING_SERVICE);
        when(o.getPlatform()).thenReturn(Platform.PC);
        when(o.getDeliveryMethod()).thenReturn(DeliveryMethod.PLAYER_AUCTION);
        when(o.getQuantity()).thenReturn(new BigDecimal("3.00"));
        when(o.getSupplierOrderId()).thenReturn(supplierOrderId);
        when(o.getSupplierDispatchAttempts()).thenReturn(attempts);
        return o;
    }

    @Test
    @DisplayName("the winner of the claim is the only caller that reaches the supplier")
    void losingTheClaimSendsNothing() {
        // Built before stubbing: Mockito rejects a mock created inside a when(...).
        OrderEntity inFlight = order(null, 1);
        OrderEntity mine = order(null, 0);
        when(claim.tryClaim(ORDER_ID, MAX_ATTEMPTS)).thenReturn(false);
        when(orders.findById(ORDER_ID)).thenReturn(Optional.of(inFlight));

        assertThat(service.dispatch(mine)).isNull();

        verify(client, never()).submitOrder(anyString(), any(), any(), anyLong(), any());
    }

    @Test
    @DisplayName("a second click while the first is in flight is told so, not told it failed")
    void concurrentOperatorClickGetsAnHonestMessage() {
        OrderEntity inFlight = order(null, 1);
        OrderEntity mine = order(null, 0);
        when(claim.tryClaim(ORDER_ID, MAX_ATTEMPTS)).thenReturn(false);
        when(orders.findById(ORDER_ID)).thenReturn(Optional.of(inFlight));

        assertThatThrownBy(() -> service.approveAndDispatch(mine, 42L))
                .isInstanceOf(FutTransferClient.FutTransferException.class)
                .hasMessageContaining("already being released")
                // The operator must not be left thinking the partner rejected it.
                .hasMessageContaining("not been sent twice");

        verify(client, never()).submitOrder(anyString(), any(), any(), anyLong(), any());
    }

    @Test
    @DisplayName("losing the claim to a caller that finished reports that id, not a failure")
    void concurrentWinnerFinishedIsASuccess() {
        OrderEntity finished = order("SUP-WON", 1);
        OrderEntity mine = order(null, 0);
        OrderEntity mineAgain = order(null, 0);
        when(claim.tryClaim(ORDER_ID, MAX_ATTEMPTS)).thenReturn(false);
        when(orders.findById(ORDER_ID)).thenReturn(Optional.of(finished));

        assertThat(service.dispatch(mine)).isEqualTo("SUP-WON");
        assertThat(service.approveAndDispatch(mineAgain, 42L)).isEqualTo("SUP-WON");

        verify(client, never()).submitOrder(anyString(), any(), any(), anyLong(), any());
    }

    @Test
    @DisplayName("an order already with the supplier never reaches the claim at all")
    void alreadyDispatchedShortCircuits() {
        assertThat(service.dispatch(order("SUP-EARLIER", 1))).isEqualTo("SUP-EARLIER");

        verify(claim, never()).tryClaim(anyLong(), anyInt());
        verify(client, never()).submitOrder(anyString(), any(), any(), anyLong(), any());
    }

    @Test
    @DisplayName("winning the claim submits once and records the partner's id durably")
    void winningDispatchesAndRecords() {
        OrderEntity mine = order(null, 0);
        when(claim.tryClaim(ORDER_ID, MAX_ATTEMPTS)).thenReturn(true);

        assertThat(service.dispatch(mine)).isEqualTo("SUP-1");

        verify(client).submitOrder(anyString(), any(), any(), anyLong(), any());
        // Through the claim bean, so it commits on its own rather than riding a
        // transaction that is still open across the HTTP call.
        verify(claim).recordAccepted(ORDER_ID, "SUP-1");
    }

    @Test
    @DisplayName("a parked order is refused with its count, not silently skipped")
    void parkedOrderTellsTheOperatorWhy() {
        OrderEntity exhausted = order(null, MAX_ATTEMPTS);
        OrderEntity mine = order(null, MAX_ATTEMPTS);
        when(claim.tryClaim(ORDER_ID, MAX_ATTEMPTS)).thenReturn(false);
        when(orders.findById(ORDER_ID)).thenReturn(Optional.of(exhausted));

        assertThatThrownBy(() -> service.approveAndDispatch(mine, 42L))
                .isInstanceOf(FutTransferClient.FutTransferException.class)
                .hasMessageContaining("already been tried")
                .hasMessageContaining("by hand");

        verify(client, never()).submitOrder(anyString(), any(), any(), anyLong(), any());
    }

    @Test
    @DisplayName("a failed submission leaves no supplier id recorded")
    void failureRecordsNothing() {
        OrderEntity afterAttempt = order(null, 1);
        OrderEntity mine = order(null, 0);
        when(claim.tryClaim(ORDER_ID, MAX_ATTEMPTS)).thenReturn(true);
        when(client.submitOrder(anyString(), any(), any(), anyLong(), any()))
                .thenThrow(new FutTransferClient.FutTransferException("partner said no"));
        when(orders.findById(ORDER_ID)).thenReturn(Optional.of(afterAttempt));

        assertThat(service.dispatch(mine)).isNull();

        verify(claim, never()).recordAccepted(anyLong(), anyString());
    }
}
