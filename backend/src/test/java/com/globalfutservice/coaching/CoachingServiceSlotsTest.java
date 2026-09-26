package com.globalfutservice.coaching;

import com.globalfutservice.domain.coaching.CoachingPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The slots a customer is offered, with the admin's settings in force: the minimum notice,
 * the buffer between sessions, and one-off extra windows.
 */
class CoachingServiceSlotsTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    /** 05:00 on Monday 28 Sep 2026 in India. */
    private static final Instant NOW = ist(28, 5, 0);
    private static final CoachingPolicy BASE = new CoachingPolicy(
            Duration.ofMinutes(60), Duration.ofMinutes(40), Duration.ofMinutes(30),
            Duration.ofHours(2), Duration.ofDays(60), Duration.ofHours(12), 2,
            Duration.ofMinutes(15), Duration.ofDays(30));

    private CoachAvailabilityRepository availability;
    private CoachTimeOffRepository timeOff;
    private CoachingSessionRepository sessions;
    private CoachExtraSlotRepository extraSlots;
    private CoachingSettingsService settings;
    private CoachingService service;
    private CoachEntity coach;

    private static Instant ist(int day, int hour, int minute) {
        return LocalDateTime.of(2026, 9, day, hour, minute).atZone(IST).toInstant();
    }

    @BeforeEach
    void setUp() {
        availability = mock(CoachAvailabilityRepository.class);
        timeOff = mock(CoachTimeOffRepository.class);
        sessions = mock(CoachingSessionRepository.class);
        extraSlots = mock(CoachExtraSlotRepository.class);
        settings = mock(CoachingSettingsService.class);
        service = new CoachingService(mock(CoachRepository.class), availability, timeOff, sessions,
                mock(CoachingSessionEventRepository.class), mock(SessionCreditRepository.class),
                extraSlots, settings, Clock.fixed(NOW, ZoneOffset.UTC), mock(CoachingAnnouncer.class));
        coach = new CoachEntity("vinay", "Vinay", "Asia/Kolkata");
        when(availability.findByCoachId(any())).thenReturn(List.of(
                new CoachAvailabilityEntity(null, DayOfWeek.MONDAY, LocalTime.of(11, 0), LocalTime.of(14, 0)),
                new CoachAvailabilityEntity(null, DayOfWeek.MONDAY, LocalTime.of(17, 0), LocalTime.of(22, 0))));
        settingsAre(Duration.ofHours(12), Duration.ZERO);
    }

    private void settingsAre(Duration notice, Duration buffer) {
        when(settings.current()).thenReturn(new CoachingSettingsService.Settings(
                notice, buffer, Duration.ofHours(2), Duration.ofMinutes(60), Duration.ofMinutes(40)));
        when(settings.effectivePolicy()).thenReturn(
                BASE.withSettings(notice, Duration.ofMinutes(60), Duration.ofMinutes(40)));
    }

    private List<Instant> monday(Duration length) {
        return service.availableSlots(coach, ist(28, 0, 0), ist(29, 0, 0), length);
    }

    private static CoachingSessionEntity booked(Instant start, Instant end) {
        return new CoachingSessionEntity("ses_x", 1L, null, null, start, end, "Asia/Kolkata");
    }

    @Test
    @DisplayName("nothing within twelve hours: Monday's lunchtime slots are gone at 05:00")
    void twelveHourCutOff() {
        List<Instant> slots = monday(Duration.ofHours(1));

        assertThat(slots).allMatch(s -> !s.isBefore(ist(28, 17, 0)));
        assertThat(slots).doesNotContain(ist(28, 11, 0), ist(28, 13, 0));
        assertThat(slots).first().isEqualTo(ist(28, 17, 0));
    }

    @Test
    @DisplayName("with two hours' notice the lunchtime slots come back")
    void noticeIsTheAdminsSetting() {
        settingsAre(Duration.ofHours(2), Duration.ZERO);

        assertThat(monday(Duration.ofHours(1))).contains(ist(28, 11, 0), ist(28, 13, 0));
    }

    @Test
    @DisplayName("the buffer keeps a gap either side of a booked session")
    void buffer() {
        when(sessions.busyBetween(any(), any(), any()))
                .thenReturn(List.of(booked(ist(28, 19, 0), ist(28, 20, 0))));

        assertThat(monday(Duration.ofHours(1))).containsExactly(
                ist(28, 17, 0), ist(28, 17, 30), ist(28, 18, 0),
                ist(28, 20, 0), ist(28, 20, 30), ist(28, 21, 0));

        settingsAre(Duration.ofHours(12), Duration.ofMinutes(30));
        assertThat(monday(Duration.ofHours(1))).containsExactly(
                ist(28, 17, 0), ist(28, 17, 30), ist(28, 20, 30), ist(28, 21, 0));
    }

    @Test
    @DisplayName("blocked time and an extra window both count")
    void blockedAndExtra() {
        when(timeOff.overlapping(any(), any(), any())).thenReturn(List.of(
                new CoachTimeOffEntity(null, ist(28, 17, 0), ist(28, 20, 0), "away")));
        when(extraSlots.overlapping(any(), any(), any())).thenReturn(List.of(
                new CoachExtraSlotEntity(null, ist(28, 23, 0), ist(29, 0, 0), "late one", 1L)));

        assertThat(monday(Duration.ofHours(1))).containsExactly(
                ist(28, 20, 0), ist(28, 20, 30), ist(28, 21, 0), ist(28, 23, 0));
    }

    @Test
    @DisplayName("a package checkout is offered forty-minute sessions")
    void packageLength() {
        List<Instant> hour = monday(Duration.ofHours(1));
        List<Instant> forty = monday(Duration.ofMinutes(40));

        assertThat(hour).doesNotContain(ist(28, 21, 30));
        assertThat(forty).contains(ist(28, 21, 0));
        assertThat(forty).last().isEqualTo(ist(28, 21, 0));
    }
}
