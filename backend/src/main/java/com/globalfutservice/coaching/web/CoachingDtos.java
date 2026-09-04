package com.globalfutservice.coaching.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/**
 * The coaching module's wire contract.
 *
 * <p>Times cross the wire as instants, never as local strings. A booking is an agreement
 * about a moment, and the moment is the thing both sides have to agree on — the timezone a
 * customer wants it rendered in is presentation, and it travels separately.
 */
public final class CoachingDtos {

    private CoachingDtos() {
    }

    public record CoachSummary(
            String id,
            String displayName,
            String headline,
            String bio,
            String avatarUrl,
            String languages,
            String credentials,
            String timezone) {
    }

    /** Bookable start times, plus the length so the UI can render an end without guessing. */
    public record SlotsResponse(
            String coachId,
            String coachTimezone,
            int sessionMinutes,
            List<Instant> slots) {
    }

    public record SessionView(
            String ref,
            String coachId,
            String coachName,
            Instant startsAt,
            Instant endsAt,
            String status,
            String customerTimezone,
            String meetingUrl,
            String customerNote,
            int rescheduleCount,
            boolean creditReturned,
            /**
             * Whether cancelling right now returns the credit.
             *
             * <p>Computed server-side from the same method the cancellation path uses, so
             * the warning the customer sees before confirming cannot disagree with what
             * actually happens when they do.
             */
            boolean cancelRefundsCredit,
            boolean canReschedule) {
    }

    /** Everything the account page needs about coaching, in one round trip. */
    public record MyCoachingResponse(
            int creditBalance,
            Instant creditsExpireAt,
            List<SessionView> upcoming,
            PolicyView policy) {
    }

    /**
     * The booking rules, rendered from configuration rather than hard-coded in the UI.
     *
     * <p>Same discipline as the catalogue's policy endpoint: the storefront's copy about
     * notice periods and reschedule limits comes from the engine that enforces them, so the
     * page and the rules cannot drift apart.
     */
    public record PolicyView(
            /** Length of a single purchased session. */
            int sessionMinutes,
            /**
             * Length of one session out of a multi-session block.
             *
             * <p>Served separately because the two products genuinely differ, and the
             * storefront prices them side by side: a length panel showing one number for
             * both is how the block came to be advertised at a duration it was not sold
             * at.
             */
            int blockSessionMinutes,
            long changeCutoffHours,
            int maxReschedules,
            long minLeadTimeHours,
            long creditValidityDays,
            /**
             * How far ahead the calendar is open, in days.
             *
             * <p>Served because the storefront draws a month grid and has to know when to
             * stop letting someone page forward. Without it the frontend would hardcode a
             * horizon, and the day the owner changes {@code maxAdvance} the calendar would
             * quietly start offering months that return nothing.
             */
            long maxAdvanceDays) {
    }

    public record BookRequest(
            @NotBlank String coachId,
            @NotNull Instant startsAt,
            /** IANA zone, e.g. {@code Asia/Kolkata}. Presentation only. */
            String timezone,
            @Size(max = 500) String note) {
    }

    public record RescheduleRequest(
            @NotNull Instant startsAt,
            String timezone) {
    }

    public record CreditEntry(String type, int amount, String description, Instant at) {
    }
}
