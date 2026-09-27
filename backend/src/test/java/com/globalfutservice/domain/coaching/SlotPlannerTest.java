package com.globalfutservice.domain.coaching;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Slots are the coach's weekly hours and one-off extra windows, minus what is booked or
 * held, minus blocked time -- laid out on the coach's wall clock and stored as instants.
 */
class SlotPlannerTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final CoachingPolicy POLICY = new CoachingPolicy(
            Duration.ofMinutes(60), Duration.ofMinutes(40), Duration.ofMinutes(30),
            Duration.ofHours(12), Duration.ofDays(60), Duration.ofHours(12), 2,
            Duration.ofMinutes(15), Duration.ofDays(30));

    /** Monday 28 Sep 2026 and Tuesday 29 Sep, in India. */
    private static final TimeRange TWO_DAYS = new TimeRange(ist(28, 0, 0), ist(30, 0, 0));
    private static final List<AvailabilityRule> MONDAY_EVENING =
            List.of(new AvailabilityRule(DayOfWeek.MONDAY, LocalTime.of(18, 0), LocalTime.of(21, 0)));

    private static Instant ist(int day, int hour, int minute) {
        return LocalDateTime.of(2026, 9, day, hour, minute).atZone(IST).toInstant();
    }

    private static List<Instant> plan(List<AvailabilityRule> rules, List<TimeRange> extra,
                                      List<TimeRange> busy, Duration length) {
        return SlotPlanner.bookableStarts(IST, rules, extra, busy, TWO_DAYS, POLICY, length);
    }

    @Test
    @DisplayName("an hour-long session fits the weekly hours on the half-hour grid")
    void weeklyHours() {
        assertThat(plan(MONDAY_EVENING, List.of(), List.of(), Duration.ofHours(1)))
                .containsExactly(ist(28, 18, 0), ist(28, 18, 30), ist(28, 19, 0),
                        ist(28, 19, 30), ist(28, 20, 0));
    }

    @Test
    @DisplayName("a booking or hold takes out every slot that would overlap it")
    void minusBookings() {
        List<TimeRange> busy = List.of(new TimeRange(ist(28, 19, 0), ist(28, 20, 0)));

        assertThat(plan(MONDAY_EVENING, List.of(), busy, Duration.ofHours(1)))
                .containsExactly(ist(28, 18, 0), ist(28, 20, 0));
    }

    @Test
    @DisplayName("blocked time takes out slots too")
    void minusBlocks() {
        List<TimeRange> busy = List.of(
                new TimeRange(ist(28, 19, 0), ist(28, 20, 0)),     // booked
                new TimeRange(ist(28, 20, 0), ist(28, 21, 0)));    // blocked

        assertThat(plan(MONDAY_EVENING, List.of(), busy, Duration.ofHours(1)))
                .containsExactly(ist(28, 18, 0));
    }

    @Test
    @DisplayName("a one-off extra window adds slots outside the weekly hours")
    void plusExtra() {
        List<TimeRange> tuesdayMorning = List.of(new TimeRange(ist(29, 10, 0), ist(29, 11, 30)));

        assertThat(plan(MONDAY_EVENING, tuesdayMorning, List.of(), Duration.ofHours(1)))
                .containsExactly(ist(28, 18, 0), ist(28, 18, 30), ist(28, 19, 0),
                        ist(28, 19, 30), ist(28, 20, 0), ist(29, 10, 0), ist(29, 10, 30));
    }

    @Test
    @DisplayName("an extra window alone is enough: no weekly hours needed")
    void extraWithoutRules() {
        List<TimeRange> tuesdayMorning = List.of(new TimeRange(ist(29, 10, 0), ist(29, 11, 0)));

        assertThat(plan(List.of(), tuesdayMorning, List.of(), Duration.ofHours(1)))
                .containsExactly(ist(29, 10, 0));
    }

    @Test
    @DisplayName("blocked time beats an extra window, as it beats the weekly hours")
    void blockBeatsExtra() {
        List<TimeRange> extra = List.of(new TimeRange(ist(29, 10, 0), ist(29, 11, 30)));
        List<TimeRange> blocked = List.of(new TimeRange(ist(29, 10, 0), ist(29, 10, 30)));

        assertThat(plan(List.of(), extra, blocked, Duration.ofHours(1)))
                .containsExactly(ist(29, 10, 30));
    }

    @Test
    @DisplayName("a forty-minute package session fits where an hour does not")
    void packageLength() {
        List<AvailabilityRule> short_ = List.of(
                new AvailabilityRule(DayOfWeek.MONDAY, LocalTime.of(18, 0), LocalTime.of(18, 40)));

        assertThat(plan(short_, List.of(), List.of(), Duration.ofHours(1))).isEmpty();
        assertThat(plan(short_, List.of(), List.of(), Duration.ofMinutes(40)))
                .containsExactly(ist(28, 18, 0));
    }

    @Test
    @DisplayName("slots are instants: 18:00 in India is 08:30 the same morning in New York")
    void customerOutsideIndia() {
        Instant first = plan(MONDAY_EVENING, List.of(), List.of(), Duration.ofHours(1)).get(0);

        assertThat(first).isEqualTo(Instant.parse("2026-09-28T12:30:00Z"));
        assertThat(first.atZone(ZoneId.of("America/New_York")).toLocalDateTime())
                .isEqualTo(LocalDateTime.of(2026, 9, 28, 8, 30));
        assertThat(first.atZone(ZoneId.of("Europe/London")).toLocalDateTime())
                .isEqualTo(LocalDateTime.of(2026, 9, 28, 13, 30));
    }

    @Test
    @DisplayName("the legality check agrees with the list, extra windows included")
    void isBookableAgrees() {
        List<TimeRange> extra = List.of(new TimeRange(ist(29, 10, 0), ist(29, 11, 0)));

        assertThat(SlotPlanner.isBookable(ist(29, 10, 0), IST, MONDAY_EVENING, extra, List.of(),
                TWO_DAYS, POLICY, Duration.ofHours(1))).isTrue();
        assertThat(SlotPlanner.isBookable(ist(29, 10, 15), IST, MONDAY_EVENING, extra, List.of(),
                TWO_DAYS, POLICY, Duration.ofHours(1))).isFalse();
    }
}
