package com.globalfutservice.coaching;

import com.globalfutservice.domain.coaching.CoachingPolicy;
import com.globalfutservice.domain.coaching.SessionActor;
import com.globalfutservice.domain.coaching.SessionStatus;
import com.globalfutservice.web.ApiExceptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A slot picked at checkout: held until the payment is verified, then booked -- or let go.
 */
class CoachingServiceHoldsTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    /** 05:00 on Monday 28 Sep 2026 in India; with 12 hours' notice, 17:00 is the first slot. */
    private static final Instant NOW = ist(28, 5, 0);
    private static final Long ACCOUNT = 7L;
    private static final Long ORDER = 42L;
    private static final CoachingPolicy BASE = new CoachingPolicy(
            Duration.ofMinutes(60), Duration.ofMinutes(40), Duration.ofMinutes(30),
            Duration.ofHours(2), Duration.ofDays(60), Duration.ofHours(12), 2,
            Duration.ofMinutes(15), Duration.ofDays(30));

    private CoachRepository coaches;
    private CoachingSessionRepository sessions;
    private CoachingSessionEventRepository events;
    private SessionCreditRepository credits;
    private CoachingSettingsService settings;
    private CoachingAnnouncer announcer;
    private CoachingService service;

    private static Instant ist(int day, int hour, int minute) {
        return LocalDateTime.of(2026, 9, day, hour, minute).atZone(IST).toInstant();
    }

    @BeforeEach
    void setUp() {
        coaches = mock(CoachRepository.class);
        CoachAvailabilityRepository availability = mock(CoachAvailabilityRepository.class);
        sessions = mock(CoachingSessionRepository.class);
        events = mock(CoachingSessionEventRepository.class);
        credits = mock(SessionCreditRepository.class);
        settings = mock(CoachingSettingsService.class);
        announcer = mock(CoachingAnnouncer.class);
        service = new CoachingService(coaches, availability, mock(CoachTimeOffRepository.class),
                sessions, events, credits, mock(CoachExtraSlotRepository.class), settings,
                Clock.fixed(NOW, ZoneOffset.UTC), announcer);

        when(coaches.findByPublicId("vinay"))
                .thenReturn(Optional.of(new CoachEntity("vinay", "Vinay", "Asia/Kolkata")));
        when(availability.findByCoachId(any())).thenReturn(List.of(
                new CoachAvailabilityEntity(null, DayOfWeek.MONDAY, LocalTime.of(11, 0), LocalTime.of(14, 0)),
                new CoachAvailabilityEntity(null, DayOfWeek.MONDAY, LocalTime.of(17, 0), LocalTime.of(22, 0))));
        when(settings.current()).thenReturn(new CoachingSettingsService.Settings(Duration.ofHours(12),
                Duration.ZERO, Duration.ofHours(2), Duration.ofMinutes(60), Duration.ofMinutes(40)));
        when(settings.effectivePolicy()).thenReturn(
                BASE.withSettings(Duration.ofHours(12), Duration.ofMinutes(60), Duration.ofMinutes(40)));
        when(settings.sessionLengthFor("SINGLE_SESSION")).thenReturn(Duration.ofMinutes(60));
        when(settings.sessionLengthFor("MONTHLY_6_SESSIONS")).thenReturn(Duration.ofMinutes(40));
        when(sessions.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
    }

    private CoachingSessionEntity hold(String variant, Instant at) {
        return service.holdForOrder(ACCOUNT, ORDER, variant, "vinay", at, "America/New_York");
    }

    private CoachingSessionEntity pending() {
        return CoachingSessionEntity.hold("ses_h", ACCOUNT, 1L, ORDER, ist(28, 19, 0),
                ist(28, 20, 0), "Asia/Kolkata", NOW.plus(Duration.ofHours(2)));
    }

    @Nested
    class Holding {

        @Test
        @DisplayName("takes the slot as PENDING for two hours, spending no credit")
        void holds() {
            CoachingSessionEntity held = hold("SINGLE_SESSION", ist(28, 19, 0));

            assertThat(held.getStatus()).isEqualTo(SessionStatus.PENDING);
            assertThat(held.getHoldExpiresAt()).isEqualTo(NOW.plus(Duration.ofHours(2)));
            assertThat(held.getOrderId()).isEqualTo(ORDER);
            assertThat(held.getEndsAt()).isEqualTo(ist(28, 20, 0));
            assertThat(held.getCustomerTimezone()).isEqualTo("America/New_York");
            verify(credits, never()).save(any());
            verify(announcer).held(held);
        }

        @Test
        @DisplayName("a package checkout holds a forty-minute session")
        void packageLength() {
            CoachingSessionEntity held = hold("MONTHLY_6_SESSIONS", ist(28, 21, 0));

            assertThat(held.getEndsAt()).isEqualTo(ist(28, 21, 40));
        }

        @Test
        @DisplayName("refuses a slot inside the twelve-hour notice")
        void cutOff() {
            assertThatThrownBy(() -> hold("SINGLE_SESSION", ist(28, 13, 0)))
                    .isInstanceOf(ApiExceptions.ConflictException.class)
                    .hasMessage("That slot was just taken, please pick another.");
            verify(sessions, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("loses a race for the same slot cleanly: the database decides, the loser is told")
        void race() {
            when(sessions.saveAndFlush(any())).thenThrow(
                    new DataIntegrityViolationException("coaching_session_no_overlap"));

            assertThatThrownBy(() -> hold("SINGLE_SESSION", ist(28, 19, 0)))
                    .isInstanceOf(ApiExceptions.ConflictException.class)
                    .hasMessage("That slot was just taken, please pick another.");
            verify(announcer, never()).held(any());
        }

        @Test
        @DisplayName("needs a signed-in customer")
        void signedIn() {
            assertThatThrownBy(() -> service.holdForOrder(null, ORDER, "SINGLE_SESSION", "vinay",
                    ist(28, 19, 0), "Asia/Kolkata"))
                    .isInstanceOf(ApiExceptions.ForbiddenException.class);
        }

        @Test
        @DisplayName("refuses a time zone that does not exist")
        void badZone() {
            assertThatThrownBy(() -> service.holdForOrder(ACCOUNT, ORDER, "SINGLE_SESSION", "vinay",
                    ist(28, 19, 0), "Mars/Olympus"))
                    .isInstanceOf(ApiExceptions.BadRequestException.class);
        }
    }

    @Nested
    class Confirming {

        @Test
        @DisplayName("payment verified: the hold becomes a booking and spends one credit")
        void confirms() {
            CoachingSessionEntity hold = pending();
            when(sessions.findFirstByOrderIdAndStatus(ORDER, SessionStatus.PENDING))
                    .thenReturn(Optional.of(hold));
            when(credits.balanceOf(ACCOUNT)).thenReturn(6);

            Optional<CoachingSessionEntity> booked =
                    service.confirmHoldForOrder(ORDER, SessionActor.SYSTEM, null);

            assertThat(booked).containsSame(hold);
            assertThat(hold.getStatus()).isEqualTo(SessionStatus.SCHEDULED);
            assertThat(hold.getSettledAt()).isNull();
            ArgumentCaptor<SessionCreditEntity> spent = ArgumentCaptor.forClass(SessionCreditEntity.class);
            verify(credits).save(spent.capture());
            assertThat(spent.getValue().getAmount()).isEqualTo(-1);
            verify(announcer).confirmed(hold);
        }

        @Test
        @DisplayName("an order that holds no slot is left alone")
        void nothingToConfirm() {
            when(sessions.findFirstByOrderIdAndStatus(ORDER, SessionStatus.PENDING))
                    .thenReturn(Optional.empty());

            assertThat(service.confirmHoldForOrder(ORDER, SessionActor.SYSTEM, null)).isEmpty();
            verify(credits, never()).save(any());
        }

        @Test
        @DisplayName("no credit, no booking: a hold is never confirmed for free")
        void noCredit() {
            CoachingSessionEntity hold = pending();
            when(sessions.findFirstByOrderIdAndStatus(ORDER, SessionStatus.PENDING))
                    .thenReturn(Optional.of(hold));
            when(credits.balanceOf(ACCOUNT)).thenReturn(0);

            assertThat(service.confirmHoldForOrder(ORDER, SessionActor.SYSTEM, null)).isEmpty();
            assertThat(hold.getStatus()).isEqualTo(SessionStatus.PENDING);
            verify(credits, never()).save(any());
        }
    }

    @Nested
    class Releasing {

        @Test
        @DisplayName("payment rejected: the hold is released and the slot freed, no credit touched")
        void rejectionFrees() {
            CoachingSessionEntity hold = pending();
            when(sessions.findFirstByOrderIdAndStatus(ORDER, SessionStatus.PENDING))
                    .thenReturn(Optional.of(hold));

            service.releaseHoldForOrder(ORDER, SessionActor.OPERATOR, 9L, "payment rejected");

            assertThat(hold.getStatus()).isEqualTo(SessionStatus.RELEASED);
            assertThat(hold.getStatus().releasesSlot()).isTrue();
            verify(credits, never()).save(any());
            verify(announcer).released(eq(hold), eq("payment rejected"));
        }

        @Test
        @DisplayName("expired holds are swept and released")
        void expiry() {
            CoachingSessionEntity a = pending();
            CoachingSessionEntity b = CoachingSessionEntity.hold("ses_b", 8L, 1L, 43L,
                    ist(28, 20, 0), ist(28, 21, 0), "Asia/Kolkata", NOW.minusSeconds(60));
            when(sessions.expiredHolds(NOW)).thenReturn(List.of(a, b));

            assertThat(service.releaseExpiredHolds()).isEqualTo(2);

            assertThat(a.getStatus()).isEqualTo(SessionStatus.RELEASED);
            assertThat(b.getStatus()).isEqualTo(SessionStatus.RELEASED);
        }

        @Test
        @DisplayName("proof submitted: the hold is extended, and never shortened")
        void extension() {
            CoachingSessionEntity hold = pending();
            when(sessions.findFirstByOrderIdAndStatus(ORDER, SessionStatus.PENDING))
                    .thenReturn(Optional.of(hold));

            service.extendHoldForOrder(ORDER, NOW.plus(Duration.ofHours(48)));
            assertThat(hold.getHoldExpiresAt()).isEqualTo(NOW.plus(Duration.ofHours(48)));

            service.extendHoldForOrder(ORDER, NOW.plus(Duration.ofHours(1)));
            assertThat(hold.getHoldExpiresAt()).isEqualTo(NOW.plus(Duration.ofHours(48)));
        }
    }

    @Nested
    class PackageLimits {

        @Test
        @DisplayName("once a package's sessions are spent, booking another is refused")
        void spent() {
            when(credits.balanceOf(ACCOUNT)).thenReturn(0);

            assertThatThrownBy(() -> service.book(ACCOUNT, "vinay", ist(28, 19, 0),
                    "Asia/Kolkata", null))
                    .isInstanceOf(ApiExceptions.ConflictException.class)
                    .hasMessageContaining("no coaching sessions left");
            verify(sessions, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("with sessions left, the next booking spends one")
        void oneLeft() {
            when(credits.balanceOf(ACCOUNT)).thenReturn(1);

            CoachingSessionEntity booked = service.book(ACCOUNT, "vinay", ist(28, 19, 0),
                    "Asia/Kolkata", null);

            assertThat(booked.getStatus()).isEqualTo(SessionStatus.SCHEDULED);
            ArgumentCaptor<SessionCreditEntity> spent = ArgumentCaptor.forClass(SessionCreditEntity.class);
            verify(credits).save(spent.capture());
            assertThat(spent.getValue().getAmount()).isEqualTo(-1);
        }
    }
}
