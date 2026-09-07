package com.globalfutservice.orders;

import com.globalfutservice.web.ApiExceptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/**
 * Finding the order a customer is entitled to act on.
 *
 * <p>These exist because of a live failure: a signed-in customer placed a boosting order,
 * submitted their EA sign-in in the same breath, and was told "No such order." about an
 * order they had created seconds earlier.
 *
 * <p>The cause was a gap between two endpoints rather than a bug in either.
 * {@code POST /orders} is open to guests, so an expired access token does not make it
 * fail and does not make the browser refresh -- the server simply sees no caller and
 * stores the order with a null account_id. The credentials call that follows is not
 * open, so it does 401, the browser refreshes, retries, and arrives properly
 * authenticated at an order its account does not own. Every step behaved as written.
 */
class OrderLookupTest {

    private static final String REF = "GFS-26-000123";
    private static final String EMAIL = "buyer@example.com";

    private OrderRepository orders;
    private OrderService service;

    @BeforeEach
    void setUp() {
        orders = mock(OrderRepository.class);

        // Only the repository participates in a lookup. The rest are constructor ballast
        // and are never touched by these paths.
        service = new OrderService(orders,
                mock(com.globalfutservice.orders.OrderEventRepository.class),
                mock(com.globalfutservice.payments.PaymentRepository.class),
                mock(com.globalfutservice.payments.PaymentGateway.class),
                mock(com.globalfutservice.pricing.QuoteService.class),
                mock(com.globalfutservice.loyalty.LoyaltyService.class),
                mock(com.globalfutservice.affiliate.AffiliateService.class),
                mock(com.globalfutservice.credentials.CredentialVaultService.class),
                mock(com.globalfutservice.notify.NotificationService.class),
                mock(com.globalfutservice.identity.AccountRepository.class),
                mock(com.globalfutservice.coaching.CoachingService.class),
                mock(com.globalfutservice.pricing.CouponService.class),
                mock(com.fasterxml.jackson.databind.ObjectMapper.class),
                mock(com.globalfutservice.config.AppProperties.class, withSettings().defaultAnswer(
                        org.mockito.Answers.RETURNS_DEEP_STUBS)),
                java.time.Clock.systemUTC());
    }

    private static OrderEntity anOrder() {
        return mock(OrderEntity.class);
    }

    @Test
    @DisplayName("a signed-in owner is found by account alone, without sending an email")
    void ownerNeedsNoEmail() {
        OrderEntity mine = anOrder();
        when(orders.findByPublicRefAndAccountId(REF, 42L)).thenReturn(Optional.of(mine));

        assertThat(service.requireOwnedOrGuest(REF, 42L, null)).isSameAs(mine);
        // Falling through to an email lookup for a customer who plainly owns the order
        // would make the email load-bearing when it is not.
        verify(orders, org.mockito.Mockito.never())
                .findByPublicRefAndGuestEmail(anyString(), anyString());
    }

    @Test
    @DisplayName("an order placed while the token was stale is still reachable by email")
    void unlinkedOrderIsReachable() {
        // The live failure, reproduced: signed in, but the order carries no account_id
        // because the call that created it was made without a usable token.
        OrderEntity unlinked = anOrder();
        when(orders.findByPublicRefAndAccountId(REF, 42L)).thenReturn(Optional.empty());
        when(orders.findByPublicRefAndGuestEmail(REF, EMAIL)).thenReturn(Optional.of(unlinked));

        assertThat(service.requireOwnedOrGuest(REF, 42L, EMAIL)).isSameAs(unlinked);
    }

    @Test
    @DisplayName("somebody else's order is not reachable by being signed in")
    void wrongEmailStillRefused() {
        when(orders.findByPublicRefAndAccountId(REF, 42L)).thenReturn(Optional.empty());
        when(orders.findByPublicRefAndGuestEmail(any(), any())).thenReturn(Optional.empty());

        // The fallback widens who can reach an order they can prove is theirs. It must
        // not widen it to anyone holding a reference.
        assertThatThrownBy(() -> service.requireOwnedOrGuest(REF, 42L, "someone@else.com"))
                .isInstanceOf(ApiExceptions.NotFoundException.class);
    }

    @Test
    @DisplayName("no account match and no email is refused, not passed through")
    void noEmailNoMatch() {
        when(orders.findByPublicRefAndAccountId(REF, 42L)).thenReturn(Optional.empty());
        when(orders.findByPublicRefAndGuestEmail(any(), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.requireOwnedOrGuest(REF, 42L, null))
                .isInstanceOf(ApiExceptions.NotFoundException.class);
    }

    @Test
    @DisplayName("the guest lookup normalises reference case and email case")
    void guestLookupNormalises() {
        OrderEntity found = anOrder();
        when(orders.findByPublicRefAndGuestEmail(REF, EMAIL)).thenReturn(Optional.of(found));

        // A customer pasting a reference out of an email gets whatever case that email
        // used, and types their address however they please.
        assertThat(service.requireGuest("  gfs-26-000123 ", "  Buyer@Example.COM  "))
                .isSameAs(found);
    }

    @Test
    @DisplayName("requireById is a bare primary-key lookup and stays staff-only")
    void byIdIsUnfiltered() {
        when(orders.findById(7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.requireById(7L))
                .isInstanceOf(ApiExceptions.NotFoundException.class);
        verify(orders).findById(anyLong());
    }
}
