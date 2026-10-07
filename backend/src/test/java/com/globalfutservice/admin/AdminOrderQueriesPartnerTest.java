package com.globalfutservice.admin;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.globalfutservice.config.AppProperties;
import com.globalfutservice.credentials.CredentialVaultRepository;
import com.globalfutservice.domain.catalog.Platform;
import com.globalfutservice.domain.catalog.Sku;
import com.globalfutservice.domain.money.Currency;
import com.globalfutservice.domain.orders.DeliveryMethod;
import com.globalfutservice.domain.orders.OrderStatus;
import com.globalfutservice.fulfilment.VendorOrderLedger;
import com.globalfutservice.identity.AccountRepository;
import com.globalfutservice.orders.OrderEntity;
import com.globalfutservice.orders.OrderRepository;
import com.globalfutservice.payments.ManualPaymentClaimRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The queue's "with the partner": what decides whether Start Order is offered on a coin
 * order, and it has to agree with what the release itself would refuse.
 */
class AdminOrderQueriesPartnerTest {

    private static OrderEntity order(long id, String ref, String supplierOrderId) {
        OrderEntity o = new OrderEntity(ref, "q-" + ref, "FC26", Sku.TRADING_SERVICE, Platform.PC, null,
                new BigDecimal("0.5"), DeliveryMethod.PLAYER_AUCTION, Currency.INR, 100000L, 100000L, "{}");
        ReflectionTestUtils.setField(o, "id", id);
        ReflectionTestUtils.setField(o, "status", OrderStatus.READY_FOR_DELIVERY);
        o.setSupplierOrderId(supplierOrderId);
        return o;
    }

    @Test
    @DisplayName("an order the partner confirmed without giving its id counts as with the partner")
    void confirmedWithoutId() {
        ManualPaymentClaimRepository claims = mock(ManualPaymentClaimRepository.class);
        CredentialVaultRepository vault = mock(CredentialVaultRepository.class);
        VendorOrderLedger vendorOrders = mock(VendorOrderLedger.class);
        when(claims.findByOrderIdInOrderBySubmittedAtDesc(any())).thenReturn(List.of());
        when(vault.heldAmong(any())).thenReturn(List.of(1L, 2L, 3L));
        // 2 was confirmed by a lookup: a vendor order, no id. 1 has never been sent.
        when(vendorOrders.atPartner(any())).thenReturn(Set.of(2L, 3L));
        AdminOrderQueries queries = new AdminOrderQueries(mock(OrderRepository.class), claims, vault,
                mock(AccountRepository.class), vendorOrders, props(), Clock.systemUTC());

        List<AdminOrderViews.Row> rows = queries.rows(List.of(
                order(1, "GFS-26-NEVERSENT", null),
                order(2, "GFS-26-NOPARTNERID", null),
                order(3, "GFS-26-WITHID0001", "vid-3")));

        assertThat(rows).extracting(AdminOrderViews.Row::publicRef, AdminOrderViews.Row::withPartner)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("GFS-26-NEVERSENT", false),
                        org.assertj.core.groups.Tuple.tuple("GFS-26-NOPARTNERID", true),
                        org.assertj.core.groups.Tuple.tuple("GFS-26-WITHID0001", true));
    }

    private static AppProperties props() {
        AppProperties props = mock(AppProperties.class);
        when(props.publicUrl()).thenReturn("https://globalfutservices.com");
        return props;
    }

    @Test
    @DisplayName("the Tracking column: a dash until the transfer starts, then the customer's own link and delivered of ordered")
    void trackingColumn() {
        ManualPaymentClaimRepository claims = mock(ManualPaymentClaimRepository.class);
        CredentialVaultRepository vault = mock(CredentialVaultRepository.class);
        VendorOrderLedger vendorOrders = mock(VendorOrderLedger.class);
        when(vendorOrders.progressAmong(any())).thenReturn(Map.of(
                2L, new VendorOrderLedger.Progress(500, 200L),
                3L, new VendorOrderLedger.Progress(500, null)));
        AdminOrderQueries queries = new AdminOrderQueries(mock(OrderRepository.class), claims, vault,
                mock(AccountRepository.class), vendorOrders, props(), Clock.systemUTC());

        OrderEntity queued = order(1, "GFS-26-QUEUED001", null);
        OrderEntity moving = order(2, "GFS-26-MOVING001", "vid-2");
        OrderEntity justStarted = order(3, "GFS-26-JUSTSTRT", "vid-3");
        ReflectionTestUtils.setField(moving, "transferStartedAt", Instant.parse("2026-10-07T10:00:00Z"));
        ReflectionTestUtils.setField(justStarted, "transferStartedAt", Instant.parse("2026-10-07T11:00:00Z"));

        List<AdminOrderViews.Row> rows = queries.rows(List.of(queued, moving, justStarted));

        // Before FUT Transfer has it: nothing to follow, the table shows a dash.
        assertThat(rows.get(0).tracking()).isNull();
        // The address the customer's emails link to (CoinEmailTrackingLinkTest pins those to
        // the same GFS_PUBLIC_URL + /track?ref= + reference), and how far it has got.
        assertThat(rows.get(1).tracking()).isEqualTo(new AdminOrderViews.Tracking(
                "https://globalfutservices.com/track?ref=GFS-26-MOVING001", 500L, 200L));
        assertThat(rows.get(2).tracking()).isEqualTo(new AdminOrderViews.Tracking(
                "https://globalfutservices.com/track?ref=GFS-26-JUSTSTRT", 500L, null));
        // One query for the page, and only about the orders whose transfer has started.
        verify(vendorOrders).progressAmong(List.of(2L, 3L));
    }
}
