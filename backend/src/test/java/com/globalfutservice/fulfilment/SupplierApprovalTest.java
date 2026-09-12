package com.globalfutservice.fulfilment;

import com.globalfutservice.config.AppProperties;
import com.globalfutservice.credentials.CredentialVaultService;
import com.globalfutservice.domain.catalog.Platform;
import com.globalfutservice.domain.catalog.Sku;
import com.globalfutservice.domain.orders.DeliveryMethod;
import com.globalfutservice.orders.OrderEntity;
import com.globalfutservice.orders.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Releasing a customer's EA sign-in to the fulfilment partner.
 *
 * <p>This is the single most consequential action in the system: it is the only path that
 * sends a customer's account credentials outside our own infrastructure, and it cannot be
 * undone -- once the partner holds them, nothing in this codebase takes them back.
 *
 * <p>So the properties worth asserting are the refusals. A release that silently reports
 * success when the partner rejected it would leave an operator believing an order was
 * being worked while it sat untouched; a release that happens without an operator asking
 * for it is the behaviour this whole change exists to remove.
 */
class SupplierApprovalTest {

    private static final long OPERATOR = 42L;

    private FutTransferClient client;
    private CredentialVaultService vault;
    private OrderRepository orders;
    private SupplierFulfilmentService service;

    @BeforeEach
    void setUp() {
        client = mock(FutTransferClient.class);
        vault = mock(CredentialVaultService.class);
        orders = mock(OrderRepository.class);

        AppProperties props = mock(AppProperties.class);
        when(props.futTransfer()).thenReturn(new AppProperties.FutTransfer(
                true, "https://futtransfer.top", "api@example.com", "key",
                "snipe", 2, java.time.Duration.ofSeconds(60),
                java.time.Duration.ofSeconds(15), 3));

        when(client.isEnabled()).thenReturn(true);
        service = new SupplierFulfilmentService(client, vault, orders, props);
    }

    private static OrderEntity readyOrder() {
        OrderEntity order = mock(OrderEntity.class);
        when(order.getId()).thenReturn(7L);
        when(order.getPublicRef()).thenReturn("GFS-26-READY");
        when(order.getDeliveryMethod()).thenReturn(DeliveryMethod.PLAYER_AUCTION);
        when(order.getSku()).thenReturn(Sku.TRADING_SERVICE);
        when(order.getPlatform()).thenReturn(Platform.PC);
        when(order.getQuantity()).thenReturn(new BigDecimal("3.00"));
        when(order.getSupplierOrderId()).thenReturn(null);
        when(order.getSupplierDispatchAttempts()).thenReturn(0);
        return order;
    }

    @Test
    @DisplayName("a refusal from the partner throws rather than reporting success")
    void refusalThrows() {
        OrderEntity order = readyOrder();
        when(vault.reveal(anyLong(), any())).thenThrow(
                new IllegalStateException("nothing in the vault"));

        /*
         * dispatch() swallows this and returns null, which is correct for the customer
         * path it was written for. On the operator path it must not: somebody is watching
         * the screen and would otherwise be told nothing happened while believing the
         * order had gone out.
         */
        assertThatThrownBy(() -> service.approveAndDispatch(order, OPERATOR))
                .isInstanceOf(FutTransferClient.FutTransferException.class)
                .hasMessageContaining("GFS-26-READY");
    }

    @Test
    @DisplayName("is refused outright when the partner is not configured")
    void refusedWhenDisabled() {
        when(client.isEnabled()).thenReturn(false);

        // Not silence. With the integration off, the order is fulfillable by hand and the
        // operator needs to be told that rather than left looking at an unchanged screen.
        assertThatThrownBy(() -> service.approveAndDispatch(readyOrder(), OPERATOR))
                .isInstanceOf(FutTransferClient.FutTransferException.class)
                .hasMessageContaining("not configured");

        verify(client, never()).submitOrder(anyString(), anyString(), any(), anyLong(), any());
    }

    @Test
    @DisplayName("never submits the same order twice")
    void alreadyReleasedIsANoOp() {
        OrderEntity already = readyOrder();
        when(already.getSupplierOrderId()).thenReturn("supplier-123");

        // Re-submitting would hand the partner a second copy of the same sign-in and,
        // worse, could start a second transfer against the customer's account.
        assertThat(service.approveAndDispatch(already, OPERATOR)).isEqualTo("supplier-123");
        verify(client, never()).submitOrder(anyString(), anyString(), any(), anyLong(), any());
    }

    @Test
    @DisplayName("never sends a boosting order to an API that only moves coins")
    void boostingIsNeverDispatched() {
        OrderEntity boost = readyOrder();
        when(boost.getSku()).thenReturn(Sku.BOOST_CHAMPS);
        when(boost.getPlatform()).thenReturn(null);
        when(boost.getQuantity()).thenReturn(new BigDecimal("1.00"));

        /*
         * Boosting reaches this point looking dispatchable: it is paid, it holds a
         * sign-in, and its delivery method is the same as a coin order's -- which is how
         * it used to get through, the old guard having excluded only coaching. What the
         * partner would have received is an order to move 1,000K coins on a null
         * platform, because a flat SKU's quantity is 1 and it carries no platform.
         */
        assertThatThrownBy(() -> service.approveAndDispatch(boost, OPERATOR))
                .isInstanceOf(FutTransferClient.FutTransferException.class);

        verify(client, never()).submitOrder(anyString(), anyString(), any(), anyLong(), any());
        // And the vault is never opened for an order that was never going out.
        verify(vault, never()).reveal(anyLong(), any());
    }

    @Test
    @DisplayName("returns the partner's order id when it is accepted")
    void acceptedReturnsSupplierId() {
        OrderEntity order = readyOrder();
        when(vault.reveal(anyLong(), any())).thenReturn(
                new com.globalfutservice.credentials.web.CredentialDtos.RevealedCredentials(
                        "player@example.com", "hunter2hunter2", java.util.List.of("123456"),
                        null, null));
        when(client.submitOrder(anyString(), anyString(), any(), anyLong(), any()))
                .thenReturn(new FutTransferClient.Accepted("supplier-999"));

        assertThat(service.approveAndDispatch(order, OPERATOR)).isEqualTo("supplier-999");
        verify(order).setSupplierOrderId("supplier-999");
    }
}
