package com.globalfutservice.admin;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Set;

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
                mock(AccountRepository.class), vendorOrders, Clock.systemUTC());

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
}
