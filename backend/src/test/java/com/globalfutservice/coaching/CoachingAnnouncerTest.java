package com.globalfutservice.coaching;

import com.globalfutservice.identity.AccountEntity;
import com.globalfutservice.identity.AccountRepository;
import com.globalfutservice.identity.AccountRole;
import com.globalfutservice.notify.CoachingBookingNotification;
import com.globalfutservice.notify.NotificationService;
import com.globalfutservice.orders.OrderEntity;
import com.globalfutservice.orders.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What the coach is told when a session moves.
 *
 * <p>Two things are worth testing here and they pull in opposite directions. The message
 * has to carry enough to act on — which session of how many, whose order, whether the
 * money arrived — and it has to be impossible for assembling it to break a booking that
 * has already been committed and already cost the customer a credit.
 */
class CoachingAnnouncerTest {

    private static final long SESSION_ID = 11L;
    private static final long ORDER_ID = 5L;
    private static final long ACCOUNT_ID = 3L;
    private static final long COACH_ID = 2L;
    private static final Instant START = Instant.parse("2026-10-01T13:30:00Z");

    private CoachingSessionRepository sessions;
    private SessionCreditRepository credits;
    private CoachRepository coaches;
    private OrderRepository orders;
    private AccountRepository accounts;
    private NotificationService notifications;
    private CoachingAnnouncer announcer;

    private CoachingSessionEntity session;

    @BeforeEach
    void setUp() {
        sessions = mock(CoachingSessionRepository.class);
        credits = mock(SessionCreditRepository.class);
        coaches = mock(CoachRepository.class);
        orders = mock(OrderRepository.class);
        accounts = mock(AccountRepository.class);
        notifications = mock(NotificationService.class);
        announcer = new CoachingAnnouncer(sessions, credits, coaches, orders, accounts,
                notifications);

        session = session(ORDER_ID);

        AccountEntity account = new AccountEntity("acc_1", "player@example.com", "hash",
                AccountRole.CUSTOMER);
        when(accounts.findById(ACCOUNT_ID)).thenReturn(Optional.of(account));

        CoachEntity coach = mock(CoachEntity.class);
        when(coach.getDisplayName()).thenReturn("Vinay");
        when(coaches.findById(COACH_ID)).thenReturn(Optional.of(coach));

        OrderEntity order = mock(OrderEntity.class);
        // The pack size is looked up by the order's id, not the session's.
        when(order.getId()).thenReturn(ORDER_ID);
        when(order.getPublicRef()).thenReturn("GFS-26-ABCD1234");
        when(order.getStatus()).thenReturn(com.globalfutservice.domain.orders.OrderStatus.PAID);
        when(orders.findById(ORDER_ID)).thenReturn(Optional.of(order));

        SessionCreditEntity grant = mock(SessionCreditEntity.class);
        when(grant.getAmount()).thenReturn(6);
        when(credits.findGrantForOrder(ORDER_ID)).thenReturn(Optional.of(grant));
    }

    private CoachingSessionEntity session(Long orderId) {
        return session(orderId, SESSION_ID);
    }

    /** A sibling in the same pack. Distinct id, because that is what positions it. */
    private CoachingSessionEntity session(Long orderId, long id) {
        CoachingSessionEntity s = mock(CoachingSessionEntity.class);
        when(s.getId()).thenReturn(id);
        when(s.getPublicRef()).thenReturn("SES-XYZ");
        when(s.getOrderId()).thenReturn(orderId);
        when(s.getAccountId()).thenReturn(ACCOUNT_ID);
        when(s.getCoachId()).thenReturn(COACH_ID);
        when(s.getStartsAt()).thenReturn(START);
        when(s.getEndsAt()).thenReturn(START.plusSeconds(40 * 60));
        when(s.getCustomerTimezone()).thenReturn("Europe/London");
        return s;
    }

    private CoachingBookingNotification captureBooked() {
        ArgumentCaptor<CoachingBookingNotification> captor =
                ArgumentCaptor.forClass(CoachingBookingNotification.class);
        verify(notifications).coachingBooked(captor.capture());
        return captor.getValue();
    }

    @Nested
    @DisplayName("what the message carries")
    class Payload {

        @Test
        @DisplayName("names the session's place in the pack it was bought as part of")
        void numbersTheSessionWithinItsPack() {
            // Third of the order's sessions, in the order they were booked. Built
            // before the stubbing: Mockito rejects a mock created inside a when(...).
            CoachingSessionEntity first = session(ORDER_ID, 9L);
            CoachingSessionEntity second = session(ORDER_ID, 10L);
            when(sessions.findByOrderIdOrderByIdAsc(ORDER_ID))
                    .thenReturn(List.of(first, second, session));

            announcer.booked(session);

            CoachingBookingNotification sent = captureBooked();
            assertThat(sent.sessionNumber()).isEqualTo(3);
            assertThat(sent.sessionsInPack()).isEqualTo(6);
            assertThat(sent.sessionLabel()).isEqualTo("3 of 6");
        }

        @Test
        @DisplayName("carries the order and whether it has been paid")
        void carriesTheOrder() {
            when(sessions.findByOrderIdOrderByIdAsc(ORDER_ID)).thenReturn(List.of(session));

            announcer.booked(session);

            CoachingBookingNotification sent = captureBooked();
            assertThat(sent.orderRef()).isEqualTo("GFS-26-ABCD1234");
            assertThat(sent.paymentStatus()).isEqualTo("PAID");
            assertThat(sent.customerEmail()).isEqualTo("player@example.com");
            assertThat(sent.coachName()).isEqualTo("Vinay");
        }

        @Test
        @DisplayName("keeps the customer's own zone, which is the one they will quote back")
        void keepsTheCustomerZone() {
            when(sessions.findByOrderIdOrderByIdAsc(ORDER_ID)).thenReturn(List.of(session));

            announcer.booked(session);

            assertThat(captureBooked().customerTimezone()).isEqualTo("Europe/London");
        }

        @Test
        @DisplayName("a reschedule says where it moved from")
        void rescheduleCarriesThePreviousTime() {
            when(sessions.findByOrderIdOrderByIdAsc(ORDER_ID)).thenReturn(List.of(session));
            Instant was = START.minusSeconds(86_400);

            announcer.rescheduled(session, was);

            ArgumentCaptor<CoachingBookingNotification> captor =
                    ArgumentCaptor.forClass(CoachingBookingNotification.class);
            verify(notifications).coachingRescheduled(captor.capture());
            assertThat(captor.getValue().previousStartsAt()).isEqualTo(was);
        }

        @Test
        @DisplayName("a session with no order behind it says so rather than inventing one")
        void noOrderIsNotAnError() {
            CoachingSessionEntity manual = session(null);

            announcer.booked(manual);

            CoachingBookingNotification sent = captureBooked();
            assertThat(sent.orderRef()).isNull();
            assertThat(sent.paymentStatus()).isNull();
            assertThat(sent.sessionLabel()).isEqualTo("—");
        }
    }

    @Nested
    @DisplayName("it cannot break a booking")
    class NeverThrows {

        @Test
        @DisplayName("a lookup that blows up is swallowed, and nothing is sent")
        void lookupFailureIsSwallowed() {
            when(sessions.findByOrderIdOrderByIdAsc(ORDER_ID))
                    .thenThrow(new RuntimeException("database went away"));

            assertThatCode(() -> announcer.booked(session)).doesNotThrowAnyException();

            verify(notifications, never()).coachingBooked(any());
        }

        @Test
        @DisplayName("a missing account does not stop the message")
        void missingAccountStillAnnounces() {
            when(accounts.findById(anyLong())).thenReturn(Optional.empty());
            when(sessions.findByOrderIdOrderByIdAsc(ORDER_ID)).thenReturn(List.of(session));

            announcer.booked(session);

            assertThat(captureBooked().customerEmail()).isNull();
        }

        @Test
        @DisplayName("cancelling announces the cancellation, not a booking")
        void cancelUsesItsOwnChannel() {
            when(sessions.findByOrderIdOrderByIdAsc(ORDER_ID)).thenReturn(List.of(session));

            announcer.cancelled(session);

            verify(notifications).coachingCancelled(any());
            verify(notifications, never()).coachingBooked(any());
        }
    }
}
