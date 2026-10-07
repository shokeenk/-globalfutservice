package com.globalfutservice.orders;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.globalfutservice.affiliate.AffiliateService;
import com.globalfutservice.coaching.AfterCommit;
import com.globalfutservice.coaching.CoachingService;
import com.globalfutservice.config.AppProperties;
import com.globalfutservice.credentials.CredentialVaultService;
import com.globalfutservice.domain.catalog.Sku;
import com.globalfutservice.domain.pricing.Quote;
import com.globalfutservice.identity.AccountRepository;
import com.globalfutservice.loyalty.LoyaltyService;
import com.globalfutservice.notify.NotificationService;
import com.globalfutservice.notify.feed.CustomerFeedService;
import com.globalfutservice.orders.web.OrderDtos;
import com.globalfutservice.payments.PaymentGateway;
import com.globalfutservice.payments.PaymentRepository;
import com.globalfutservice.pricing.CouponService;
import com.globalfutservice.pricing.QuoteService;
import com.globalfutservice.web.ApiExceptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A coin order is placed for the platform its quote was priced for. A quote without one --
 * signed before quotes required it, or by any other route -- is refused at the order, and
 * the order service never supplies a platform of its own.
 */
class CoinOrderPlatformTest {

    private final OrderRepository orders = mock(OrderRepository.class);
    private final QuoteService quotes = mock(QuoteService.class);
    private final OrderService service = new OrderService(orders, mock(OrderEventRepository.class),
            mock(PaymentRepository.class), mock(PaymentGateway.class), quotes,
            mock(LoyaltyService.class), mock(AffiliateService.class), mock(CredentialVaultService.class),
            mock(NotificationService.class), mock(AccountRepository.class), mock(CoachingService.class),
            mock(CouponService.class), mock(CustomerFeedService.class), new ObjectMapper(),
            mock(AppProperties.class), Clock.systemUTC(), AfterCommit.immediate());

    @Test
    @DisplayName("a coin order whose quote has no platform is refused before anything is recorded")
    void noPlatformRefused() {
        Quote quote = mock(Quote.class);
        when(quote.sku()).thenReturn(Sku.TRADING_SERVICE);
        when(quote.platform()).thenReturn(null);
        when(quotes.verifyOrThrow(any(), any())).thenReturn(quote);

        assertThatThrownBy(() -> service.create(mock(OrderDtos.CreateOrderRequest.class), null))
                .isInstanceOfSatisfying(ApiExceptions.BadRequestException.class,
                        e -> assertThat(e.code()).isEqualTo("platform_required"));

        verify(orders, never()).existsByQuoteId(any());
        verify(orders, never()).save(any());
    }
}
