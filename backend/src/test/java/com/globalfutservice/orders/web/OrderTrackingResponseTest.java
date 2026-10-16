package com.globalfutservice.orders.web;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.globalfutservice.coaching.CoachingService;
import com.globalfutservice.config.AppProperties;
import com.globalfutservice.domain.catalog.Sku;
import com.globalfutservice.domain.money.Currency;
import com.globalfutservice.domain.orders.DeliveryMethod;
import com.globalfutservice.domain.orders.OrderStatus;
import com.globalfutservice.fulfilment.VendorOrderLedger;
import com.globalfutservice.notify.DiscordBotClient;
import com.globalfutservice.notify.discord.DiscordVerificationService;
import com.globalfutservice.orders.OrderEntity;
import com.globalfutservice.orders.OrderPaymentState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What the customer's tracking page and order list are told about a coin transfer: whether the
 * partner has it (the "Track your order" button), and how far it has got, from where the
 * vendor poller writes it.
 */
class OrderTrackingResponseTest {

    private final VendorOrderLedger ledger = mock(VendorOrderLedger.class);
    private OrderMapper mapper;
    private OrderEntity order;

    @BeforeEach
    void setUp() {
        OrderPaymentState payment = mock(OrderPaymentState.class);
        when(payment.of(any())).thenReturn(new OrderPaymentState.View(null, null));
        AppProperties props = mock(AppProperties.class);
        when(props.publicUrl()).thenReturn("https://globalfutservices.com");
        mapper = new OrderMapper(new ObjectMapper(), props, mock(DiscordVerificationService.class),
                mock(DiscordBotClient.class), mock(CoachingService.class), ledger, payment);
        order = new OrderEntity("GFS-26-TRACK001", "q_1", "FC27", Sku.TRADING_SERVICE, null, null,
                new BigDecimal("0.5"), DeliveryMethod.PLAYER_AUCTION, Currency.INR, 10000, 10000,
                "{\"lines\":[],\"total\":{\"minor\":10000}}");
        ReflectionTestUtils.setField(order, "id", 7L);
        ReflectionTestUtils.setField(order, "status", OrderStatus.IN_PROGRESS);
    }

    @Test
    @DisplayName("before the partner has it: no Track button, no progress, and the vendor is not even asked")
    void beforeOnboarding() {
        ReflectionTestUtils.setField(order, "status", OrderStatus.READY_FOR_DELIVERY);
        OrderDtos.OrderResponse r = mapper.toResponse(order, List.of(), true);
        assertThat(r.transferStarted()).isFalse();
        assertThat(r.deliveredCoins()).isNull();
        assertThat(mapper.toAdminSummary(order, true).transferStarted()).isFalse();
        verify(ledger, never()).progress(anyLong());
    }

    @Test
    @DisplayName("with the partner: the Track button, and delivered of ordered as the poller last read it")
    void progressFromThePoller() {
        ReflectionTestUtils.setField(order, "transferStartedAt", Instant.parse("2026-10-07T10:00:00Z"));
        when(ledger.progress(7L)).thenReturn(Optional.of(new VendorOrderLedger.Progress(500, 200L)));

        OrderDtos.OrderResponse r = mapper.toResponse(order, List.of(), true);

        assertThat(r.transferStarted()).isTrue();
        assertThat(r.orderedCoins()).isEqualTo(500L);
        assertThat(r.deliveredCoins()).isEqualTo(200L);
        assertThat(mapper.toAdminSummary(order, true).transferStarted()).isTrue();
    }

    @Test
    @DisplayName("just accepted, nothing reported yet: the order's size, and nothing delivered")
    void startedNothingYet() {
        ReflectionTestUtils.setField(order, "transferStartedAt", Instant.parse("2026-10-07T10:00:00Z"));
        when(ledger.progress(7L)).thenReturn(Optional.of(new VendorOrderLedger.Progress(500, null)));
        OrderDtos.OrderResponse r = mapper.toResponse(order, List.of(), true);
        assertThat(r.orderedCoins()).isEqualTo(500L);
        assertThat(r.deliveredCoins()).isNull();
    }

    @Test
    @DisplayName("the partner's tracking link: on the owner's own coin order only -- never the guest lookup or staff")
    void partnerLinkOwnerOnly() {
        String link = "https://transferstatus.top/abc12345?lang=de";
        when(ledger.partnerLink(7L)).thenReturn(Optional.of(new VendorOrderLedger.PartnerLink(link, "abc12345", null,
                "lang=de")));

        assertThat(mapper.toOwnerResponse(order, List.of(), true).partnerTrackingUrl()).isEqualTo(link);
        assertThat(mapper.toResponse(order, List.of(), true).partnerTrackingUrl()).isNull();
        assertThat(mapper.toAdminResponse(order, List.of(), true).partnerTrackingUrl()).isNull();

        ReflectionTestUtils.setField(order, "sku", Sku.BOOST_CHAMPS);
        assertThat(mapper.toOwnerResponse(order, List.of(), true).partnerTrackingUrl()).isNull();
    }

    @Test
    @DisplayName("waiting for our staff: the customer is told we are checking, and the progress still shows")
    void underReview() {
        ReflectionTestUtils.setField(order, "transferStartedAt", Instant.parse("2026-10-07T10:00:00Z"));
        when(ledger.progress(7L)).thenReturn(Optional.of(new VendorOrderLedger.Progress(500, 110L, true)));
        OrderDtos.OrderResponse r = mapper.toResponse(order, List.of(), true);
        assertThat(r.transferUnderReview()).isTrue();
        assertThat(r.deliveredCoins()).isEqualTo(110L);

        when(ledger.progress(7L)).thenReturn(Optional.of(new VendorOrderLedger.Progress(500, 110L)));
        assertThat(mapper.toResponse(order, List.of(), true).transferUnderReview()).isFalse();
    }

    @Test
    @DisplayName("stopped by our team: the customer is told it is paused, with the progress so far")
    void paused() {
        ReflectionTestUtils.setField(order, "transferStartedAt", Instant.parse("2026-10-15T16:08:00Z"));
        when(ledger.progress(7L)).thenReturn(Optional.of(new VendorOrderLedger.Progress(50, 28L, false, true)));
        OrderDtos.OrderResponse r = mapper.toResponse(order, List.of(), true);
        assertThat(r.transferPaused()).isTrue();
        assertThat(r.transferUnderReview()).isFalse();
        assertThat(r.deliveredCoins()).isEqualTo(28L);

        when(ledger.progress(7L)).thenReturn(Optional.of(new VendorOrderLedger.Progress(50, 28L)));
        assertThat(mapper.toResponse(order, List.of(), true).transferPaused()).isFalse();
    }

    @Test
    @DisplayName("held for a refused sign-in: the form to enter it again, and which part is wrong")
    void signInAsk() {
        ReflectionTestUtils.setField(order, "status", OrderStatus.ON_HOLD);
        when(ledger.customerAction(7L)).thenReturn(Optional.of("RESUBMIT_SIGN_IN"));
        OrderDtos.OrderResponse r = mapper.toOwnerResponse(order, List.of(), true);
        assertThat(r.nextAction()).isEqualTo("RESUBMIT_CREDENTIALS");
        assertThat(r.customerAction()).isEqualTo("RESUBMIT_SIGN_IN");

        // What we asked after a refused send comes first: here, the backup codes.
        when(ledger.signInProblem(7L)).thenReturn(Optional.of("NEW_BACKUP_CODES"));
        assertThat(mapper.toOwnerResponse(order, List.of(), true).customerAction()).isEqualTo("NEW_BACKUP_CODES");

        // Any other hold still says to get in touch.
        when(ledger.signInProblem(7L)).thenReturn(Optional.empty());
        when(ledger.customerAction(7L)).thenReturn(Optional.of("SIGN_OUT_CONSOLE"));
        assertThat(mapper.toOwnerResponse(order, List.of(), true).nextAction()).isEqualTo("CONTACT_SUPPORT");
    }

    @Test
    @DisplayName("partner link hidden from customers: no button, no link state -- whatever is stored")
    void partnerLinkHidden() {
        mapper.setPartnerLinks(com.globalfutservice.fulfilment.PartnerLinkVisibility.HIDDEN);
        ReflectionTestUtils.setField(order, "transferStartedAt", Instant.parse("2026-10-07T10:00:00Z"));
        when(ledger.partnerLink(7L)).thenReturn(Optional.of(new VendorOrderLedger.PartnerLink(
                "https://transferstatus.top/abc12345", "abc12345", null, null)));
        OrderDtos.OrderResponse r = mapper.toOwnerResponse(order, List.of(), true);
        assertThat(r.partnerTrackingUrl()).isNull();
        assertThat(r.partnerLinkState()).isNull();
    }

    @Test
    @DisplayName("partner link shown, none yet: PENDING while one is coming, UNAVAILABLE when there will be none")
    void partnerLinkStates() {
        ReflectionTestUtils.setField(order, "transferStartedAt", Instant.parse("2026-10-07T10:00:00Z"));
        when(ledger.partnerLink(7L)).thenReturn(Optional.empty());
        when(ledger.trackingStatus(7L)).thenReturn(Optional.of(
                new VendorOrderLedger.TrackingLinkStatus(null, null, null, 1, "HTTP_500", null)));
        assertThat(mapper.toOwnerResponse(order, List.of(), true).partnerLinkState()).isEqualTo("PENDING");

        when(ledger.trackingStatus(7L)).thenReturn(Optional.of(
                new VendorOrderLedger.TrackingLinkStatus(null, null, "UNAVAILABLE", 1, "ORDER_FINISHED", null)));
        assertThat(mapper.toOwnerResponse(order, List.of(), true).partnerLinkState()).isEqualTo("UNAVAILABLE");

        // Delivered: nothing more is coming, so nothing is promised.
        ReflectionTestUtils.setField(order, "status", OrderStatus.DELIVERED);
        when(ledger.trackingStatus(7L)).thenReturn(Optional.of(
                new VendorOrderLedger.TrackingLinkStatus(null, null, null, 1, "HTTP_500", null)));
        assertThat(mapper.toOwnerResponse(order, List.of(), true).partnerLinkState()).isNull();
    }

    @Test
    @DisplayName("STATUS_PAGE, the default style: FUT Transfer's status page with the code, and the shop when there is one")
    void statusPageStyle() {
        mapper.setPartnerLinks(new com.globalfutservice.fulfilment.PartnerLinkVisibility(true,
                com.globalfutservice.fulfilment.PartnerLinkVisibility.Style.STATUS_PAGE));
        when(ledger.partnerLink(7L)).thenReturn(Optional.of(new VendorOrderLedger.PartnerLink(
                "https://transferstatus.top/abc12345", "abc12345", "gfs shop", null)));
        assertThat(mapper.toOwnerResponse(order, List.of(), true).partnerTrackingUrl())
                .isEqualTo("https://futtransfer.top/orderStatus?code=abc12345&shop=gfs+shop");

        when(ledger.partnerLink(7L)).thenReturn(Optional.of(new VendorOrderLedger.PartnerLink(
                "https://transferstatus.top/abc12345", "abc12345", null, null)));
        assertThat(mapper.toOwnerResponse(order, List.of(), true).partnerTrackingUrl())
                .isEqualTo("https://futtransfer.top/orderStatus?code=abc12345");
        // Still never on the guest lookup.
        assertThat(mapper.toResponse(order, List.of(), true).partnerTrackingUrl()).isNull();
    }

    @Test
    @DisplayName("no link stored yet: the owner sees no Track order button")
    void noPartnerLinkYet() {
        when(ledger.partnerLink(7L)).thenReturn(Optional.empty());
        assertThat(mapper.toOwnerResponse(order, List.of(), true).partnerTrackingUrl()).isNull();
    }

    @Test
    @DisplayName("the coupon the order was placed with travels with it, so its price line can name it")
    void couponCode() {
        assertThat(mapper.toResponse(order, List.of(), true).couponCode()).isNull();
        order.setCouponCode("SAVE10");
        assertThat(mapper.toResponse(order, List.of(), true).couponCode()).isEqualTo("SAVE10");
    }
}
