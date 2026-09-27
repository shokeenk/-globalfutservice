package com.globalfutservice.orders;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.globalfutservice.affiliate.AffiliateService;
import com.globalfutservice.coaching.AfterCommit;
import com.globalfutservice.coaching.CoachingService;
import com.globalfutservice.config.AppProperties;
import com.globalfutservice.credentials.CredentialVaultService;
import com.globalfutservice.domain.catalog.Sku;
import com.globalfutservice.domain.money.Currency;
import com.globalfutservice.domain.money.Money;
import com.globalfutservice.domain.orders.Actor;
import com.globalfutservice.domain.orders.DeliveryMethod;
import com.globalfutservice.domain.orders.OrderStatus;
import com.globalfutservice.identity.AccountRepository;
import com.globalfutservice.loyalty.LoyaltyService;
import com.globalfutservice.notify.NotificationService;
import com.globalfutservice.notify.OrderNotification;
import com.globalfutservice.notify.feed.CustomerFeedService;
import com.globalfutservice.payments.PaymentGateway;
import com.globalfutservice.payments.PaymentRepository;
import com.globalfutservice.pricing.CouponService;
import com.globalfutservice.pricing.QuoteService;
import com.globalfutservice.web.ApiExceptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * "Request Sign-in" on the Orders page: the same email again, to the customer only, no
 * more than once in six hours, and only while the order is actually waiting for it.
 */
class CredentialReminderTest {

    private static final Instant NOW = Instant.parse("2026-09-27T06:00:00Z");

    @Nested
    @DisplayName("the six-hour limit")
    class Limit {

        @Test
        @DisplayName("a first reminder can go at once")
        void firstGoesNow() {
            assertThat(CredentialReminders.notBefore(List.of(), NOW)).isEmpty();
        }

        @Test
        @DisplayName("inside six hours of the last one, it names when the next may go")
        void tooSoon() {
            Instant last = NOW.minus(Duration.ofHours(2));
            assertThat(CredentialReminders.notBefore(List.of(NOW.minus(Duration.ofDays(2)), last), NOW))
                    .contains(last.plus(Duration.ofHours(6)));
        }

        @Test
        @DisplayName("six hours after the last one, it can go again")
        void afterGap() {
            assertThat(CredentialReminders.notBefore(List.of(NOW.minus(Duration.ofHours(6))), NOW)).isEmpty();
        }

        @Test
        @DisplayName("only an operator's pending-to-pending event counts as a reminder")
        void reminderShape() {
            assertThat(CredentialReminders.isReminder(event(OrderStatus.CREDENTIALS_PENDING,
                    OrderStatus.CREDENTIALS_PENDING, Actor.OPERATOR))).isTrue();
            assertThat(CredentialReminders.isReminder(event(OrderStatus.PAID,
                    OrderStatus.CREDENTIALS_PENDING, Actor.SYSTEM))).isFalse();
            assertThat(CredentialReminders.isReminder(event(OrderStatus.CREDENTIALS_PENDING,
                    OrderStatus.READY_FOR_DELIVERY, Actor.OPERATOR))).isFalse();
        }
    }

    @Nested
    @DisplayName("OrderService.remindCredentials")
    class Service {

        private OrderEventRepository events;
        private CredentialVaultService vault;
        private NotificationService notifications;
        private OrderService service;
        private OrderEntity order;

        @BeforeEach
        void setUp() {
            events = mock(OrderEventRepository.class);
            vault = mock(CredentialVaultService.class);
            notifications = mock(NotificationService.class);
            service = new OrderService(mock(OrderRepository.class), events,
                    mock(PaymentRepository.class), mock(PaymentGateway.class),
                    mock(QuoteService.class), mock(LoyaltyService.class),
                    mock(AffiliateService.class), vault, notifications,
                    mock(AccountRepository.class), mock(CoachingService.class),
                    mock(CouponService.class), mock(CustomerFeedService.class),
                    new ObjectMapper(), mock(AppProperties.class),
                    Clock.fixed(NOW, ZoneOffset.UTC), AfterCommit.immediate());

            order = mock(OrderEntity.class);
            when(order.getId()).thenReturn(7L);
            when(order.getPublicRef()).thenReturn("GFS-26-REMIND01");
            when(order.getSku()).thenReturn(Sku.TRADING_SERVICE);
            when(order.getStatus()).thenReturn(OrderStatus.CREDENTIALS_PENDING);
            when(order.getDeliveryMethod()).thenReturn(DeliveryMethod.COMFORT_TRADE);
            when(order.getServiceLabel()).thenReturn("Buy Coins — 500K (PlayStation)");
            when(order.getGuestEmail()).thenReturn("player@example.test");
            when(order.total()).thenReturn(Money.ofMinor(825000, Currency.INR));
            when(events.findByOrderIdOrderByCreatedAtAsc(7L)).thenReturn(List.of());
        }

        @Test
        @DisplayName("records the reminder on the timeline and emails the customer")
        void sends() {
            Instant sentAt = service.remindCredentials(order, 3L, "acc_operator");

            assertThat(sentAt).isEqualTo(NOW);
            ArgumentCaptor<OrderEventEntity> saved = ArgumentCaptor.forClass(OrderEventEntity.class);
            verify(events).save(saved.capture());
            assertThat(CredentialReminders.isReminder(saved.getValue())).isTrue();
            assertThat(saved.getValue().getReason()).isEqualTo(CredentialReminders.REASON);

            ArgumentCaptor<OrderNotification> sent = ArgumentCaptor.forClass(OrderNotification.class);
            verify(notifications).credentialsReminder(sent.capture());
            assertThat(sent.getValue().publicRef()).isEqualTo("GFS-26-REMIND01");
            // Not the first-time request, which also alerts the operator channels.
            verify(notifications, never()).credentialsNeeded(any());
        }

        @Test
        @DisplayName("refuses when the order is not waiting for a sign-in")
        void wrongState() {
            when(order.getStatus()).thenReturn(OrderStatus.READY_FOR_DELIVERY);

            assertThatThrownBy(() -> service.remindCredentials(order, 3L, "acc_operator"))
                    .isInstanceOf(ApiExceptions.ConflictException.class)
                    .hasMessageContaining("not waiting for a sign-in");
            verify(notifications, never()).credentialsReminder(any());
        }

        @Test
        @DisplayName("refuses when the sign-in is already on file")
        void alreadySent() {
            when(vault.hasCredentials(7L)).thenReturn(true);

            assertThatThrownBy(() -> service.remindCredentials(order, 3L, "acc_operator"))
                    .isInstanceOf(ApiExceptions.ConflictException.class)
                    .hasMessageContaining("already sent");
            verify(notifications, never()).credentialsReminder(any());
        }

        @Test
        @DisplayName("refuses a second reminder inside six hours, saying when the next can go")
        void tooSoon() {
            OrderEventEntity earlier = event(OrderStatus.CREDENTIALS_PENDING,
                    OrderStatus.CREDENTIALS_PENDING, Actor.OPERATOR);
            ReflectionTestUtils.setField(earlier, "createdAt", NOW.minus(Duration.ofHours(1)));
            when(events.findByOrderIdOrderByCreatedAtAsc(7L)).thenReturn(List.of(earlier));

            // 05:00Z + 6h = 11:00Z, which is 4:30 PM in India.
            assertThatThrownBy(() -> service.remindCredentials(order, 3L, "acc_operator"))
                    .isInstanceOf(ApiExceptions.ConflictException.class)
                    .hasMessageContaining("27 Sep, 4:30 PM IST");
            verify(events, never()).save(any());
            verify(notifications, never()).credentialsReminder(any());
        }
    }

    private static OrderEventEntity event(OrderStatus from, OrderStatus to, Actor actor) {
        return new OrderEventEntity(7L, from, to, actor, 3L, "acc_operator", null);
    }
}
