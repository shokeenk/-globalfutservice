package com.globalfutservice.coaching;

import com.globalfutservice.domain.coaching.SessionStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * "1 of 6 booked": which of an order's sessions count against what it bought.
 */
class CoachingServiceOrderSessionsTest {

    private static final Long ORDER = 42L;
    private static final Instant NOW = Instant.parse("2026-10-01T06:00:00Z");

    private CoachingSessionRepository sessions;
    private SessionCreditRepository credits;
    private CoachingService service;
    private long nextId = 1;

    @BeforeEach
    void setUp() {
        sessions = mock(CoachingSessionRepository.class);
        credits = mock(SessionCreditRepository.class);
        service = new CoachingService(mock(CoachRepository.class), mock(CoachAvailabilityRepository.class),
                mock(CoachTimeOffRepository.class), sessions, mock(CoachingSessionEventRepository.class),
                credits, mock(CoachExtraSlotRepository.class), mock(CoachingSettingsService.class),
                Clock.fixed(NOW, ZoneOffset.UTC), mock(CoachingAnnouncer.class));
    }

    private CoachingSessionEntity session(SessionStatus status, int dayOffset, boolean creditReturned) {
        Instant start = NOW.plus(Duration.ofDays(dayOffset));
        CoachingSessionEntity s = new CoachingSessionEntity("ses_" + nextId, 7L, 1L, ORDER, start,
                start.plus(Duration.ofMinutes(40)), "Europe/London");
        ReflectionTestUtils.setField(s, "id", nextId++);
        ReflectionTestUtils.setField(s, "status", status);
        ReflectionTestUtils.setField(s, "creditReturned", creditReturned);
        return s;
    }

    @Test
    @DisplayName("counts what took a credit or holds a slot, and nothing that gave one back")
    void counting() {
        CoachingSessionEntity held = session(SessionStatus.PENDING, 3, false);
        when(sessions.findByOrderIdOrderByIdAsc(ORDER)).thenReturn(List.of(
                session(SessionStatus.COMPLETED, -7, false),
                session(SessionStatus.NO_SHOW, -3, false),
                session(SessionStatus.CANCELLED_BY_CUSTOMER, -1, false),   // too late: spent
                session(SessionStatus.CANCELLED_BY_CUSTOMER, 1, true),     // in time: returned
                session(SessionStatus.CANCELLED_BY_COACH, 2, true),
                session(SessionStatus.RELEASED, 2, false),
                held,
                session(SessionStatus.SCHEDULED, 5, false)));
        when(credits.findGrantForOrder(ORDER)).thenReturn(Optional.of(
                SessionCreditEntity.granted(7L, ORDER, 6, NOW.plus(Duration.ofDays(30)), "GFS-26-X", 40)));

        CoachingService.OrderSessions result = service.sessionsForOrder(ORDER, 6);

        assertThat(result.booked()).isEqualTo(5);
        assertThat(result.total()).isEqualTo(6);
        assertThat(result.next()).containsSame(held);
    }

    @Test
    @DisplayName("before payment there is no grant yet, so the pack's size is what was bought")
    void beforePayment() {
        CoachingSessionEntity held = session(SessionStatus.PENDING, 3, false);
        when(sessions.findByOrderIdOrderByIdAsc(ORDER)).thenReturn(List.of(held));
        when(credits.findGrantForOrder(ORDER)).thenReturn(Optional.empty());

        CoachingService.OrderSessions result = service.sessionsForOrder(ORDER, 6);

        assertThat(result.booked()).isEqualTo(1);
        assertThat(result.total()).isEqualTo(6);
        assertThat(result.next()).containsSame(held);
    }

    @Test
    @DisplayName("nothing booked yet: 0 of the pack, and no next session")
    void nothingYet() {
        when(sessions.findByOrderIdOrderByIdAsc(ORDER)).thenReturn(List.of());
        when(credits.findGrantForOrder(ORDER)).thenReturn(Optional.empty());

        CoachingService.OrderSessions result = service.sessionsForOrder(ORDER, 1);

        assertThat(result.booked()).isZero();
        assertThat(result.total()).isEqualTo(1);
        assertThat(result.next()).isEmpty();
    }
}
