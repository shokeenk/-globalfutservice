package com.globalfutservice.coaching.web;

import com.globalfutservice.coaching.CoachEntity;
import com.globalfutservice.coaching.CoachRepository;
import com.globalfutservice.coaching.CoachingService;
import com.globalfutservice.coaching.CoachingSessionEntity;
import com.globalfutservice.domain.coaching.CoachingPolicy;
import com.globalfutservice.domain.coaching.SessionStatus;
import com.globalfutservice.security.AccountPrincipal;
import com.globalfutservice.security.CurrentAccount;
import com.globalfutservice.web.ApiExceptions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Coaching: who teaches, when they are free, and the customer's own sessions.
 *
 * <p>The coach list and the slot calendar are public — they are a shop window, and putting
 * them behind a sign-in would mean asking someone to register before they can see whether
 * anybody is available on a Tuesday. Everything that spends a credit or touches a booking
 * is scoped to the authenticated account, in the query rather than in a check afterwards.
 */
@RestController
@RequestMapping("/api/v1/coaching")
@Tag(name = "Coaching", description = "Coaches, availability and session bookings")
public class CoachingController {

    private final CoachingService coaching;
    private final CoachRepository coaches;
    private final Clock clock;

    public CoachingController(CoachingService coaching, CoachRepository coaches, Clock clock) {
        this.coaching = coaching;
        this.coaches = coaches;
        this.clock = clock;
    }

    // ------------------------------------------------------------------- public -------

    @GetMapping("/coaches")
    @Operation(summary = "Coaches currently taking bookings")
    public List<CoachingDtos.CoachSummary> coaches() {
        return coaching.activeCoaches().stream().map(CoachingController::toSummary).toList();
    }

    /**
     * Free start times for one coach.
     *
     * <p>Defaults to the next fortnight when no range is given, which is what the calendar
     * opens on. The service clamps whatever is asked for to the bookable window, so a
     * caller cannot page into next year and get a wall of slots nobody could book.
     */
    @GetMapping("/coaches/{coachId}/slots")
    @Operation(summary = "Bookable start times for a coach")
    public ResponseEntity<CoachingDtos.SlotsResponse> slots(
            @PathVariable String coachId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {

        CoachEntity coach = coaching.requireCoach(coachId);
        Instant now = clock.instant();
        Instant start = from != null ? from : now;
        Instant end = to != null ? to : start.plus(Duration.ofDays(14));
        if (!end.isAfter(start)) {
            throw new ApiExceptions.BadRequestException("The end of the range must follow its start.");
        }

        List<Instant> slots = coaching.availableSlots(coach, start, end);
        return ResponseEntity.ok()
                // Availability goes stale the moment somebody books. A minute is enough to
                // absorb a burst of calendar paging without showing a taken slot.
                .header(HttpHeaders.CACHE_CONTROL, "public, max-age=60")
                .body(new CoachingDtos.SlotsResponse(
                        coach.getPublicId(),
                        coach.getTimezone(),
                        /*
                         * The single-session length, for everyone, even though a block
                         * customer's sessions are shorter.
                         *
                         * This response is public and shared-cached, so it cannot vary by
                         * caller without one customer's grid being served to another. The
                         * longer length is the safe one to publish: a start with room for
                         * sixty minutes has room for forty, and the busy set it was
                         * checked against is a superset of the forty-minute one, so every
                         * slot offered here is bookable by either customer. A block
                         * customer may see marginally fewer slots than they could have
                         * taken; nobody is ever shown one that will be refused.
                         *
                         * Booking re-derives legality against the length actually bought,
                         * so this number is a display hint, never the authority.
                         */
                        (int) coaching.policy().sessionLength().toMinutes(),
                        slots));
    }

    @GetMapping("/policy")
    @Operation(summary = "Booking rules, for rendering the page copy")
    public CoachingDtos.PolicyView policy() {
        return toPolicyView(coaching.policy());
    }

    // ----------------------------------------------------------------- customer -------

    @GetMapping("/me")
    @Operation(summary = "Session credits and upcoming bookings")
    public ResponseEntity<CoachingDtos.MyCoachingResponse> me(
            @CurrentAccount AccountPrincipal principal) {

        Long accountId = requireAccount(principal);
        List<CoachingSessionEntity> upcoming = coaching.upcomingFor(accountId);

        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new CoachingDtos.MyCoachingResponse(
                        coaching.creditBalance(accountId),
                        coaching.creditsExpireAt(accountId),
                        toViews(upcoming),
                        toPolicyView(coaching.policy())));
    }

    @PostMapping("/sessions")
    @Operation(summary = "Book a session, spending one credit")
    public ResponseEntity<CoachingDtos.SessionView> book(
            @CurrentAccount AccountPrincipal principal,
            @Valid @RequestBody CoachingDtos.BookRequest request) {

        Long accountId = requireAccount(principal);
        CoachingSessionEntity session = coaching.book(
                accountId, request.coachId(), request.startsAt(),
                CoachingService.normaliseZone(request.timezone()), request.note());

        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(toView(session, coachNameOf(session)));
    }

    @PostMapping("/sessions/{ref}/reschedule")
    @Operation(summary = "Move a session without spending another credit")
    public CoachingDtos.SessionView reschedule(
            @CurrentAccount AccountPrincipal principal,
            @PathVariable String ref,
            @Valid @RequestBody CoachingDtos.RescheduleRequest request) {

        Long accountId = requireAccount(principal);
        CoachingSessionEntity session = coaching.reschedule(accountId, ref, request.startsAt(),
                CoachingService.normaliseZone(request.timezone()));
        return toView(session, coachNameOf(session));
    }

    @PostMapping("/sessions/{ref}/cancel")
    @Operation(summary = "Cancel a session")
    public CoachingDtos.SessionView cancel(
            @CurrentAccount AccountPrincipal principal,
            @PathVariable String ref) {

        Long accountId = requireAccount(principal);
        CoachingSessionEntity session = coaching.cancelByCustomer(accountId, ref);
        return toView(session, coachNameOf(session));
    }

    @GetMapping("/credits")
    @Operation(summary = "Session-credit statement")
    public ResponseEntity<List<CoachingDtos.CreditEntry>> credits(
            @CurrentAccount AccountPrincipal principal) {

        Long accountId = requireAccount(principal);
        List<CoachingDtos.CreditEntry> entries = coaching.creditStatement(accountId).stream()
                .map(c -> new CoachingDtos.CreditEntry(c.getEntryType().name(), c.getAmount(),
                        c.getDescription(), c.getCreatedAt()))
                .toList();
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(entries);
    }

    // ------------------------------------------------------------------ mapping -------

    private Long requireAccount(AccountPrincipal principal) {
        if (principal == null) {
            throw new ApiExceptions.ForbiddenException("Please sign in.");
        }
        return principal.id();
    }

    private static CoachingDtos.CoachSummary toSummary(CoachEntity c) {
        return new CoachingDtos.CoachSummary(c.getPublicId(), c.getDisplayName(), c.getHeadline(),
                c.getBio(), c.getAvatarUrl(), c.getLanguages(), c.getCredentials(),
                c.getTimezone());
    }

    private static CoachingDtos.PolicyView toPolicyView(CoachingPolicy p) {
        return new CoachingDtos.PolicyView(
                (int) p.sessionLength().toMinutes(),
                (int) p.blockSessionLength().toMinutes(),
                p.changeCutoff().toHours(),
                p.maxReschedules(),
                p.minLeadTime().toHours(),
                p.creditValidity().toDays(),
                p.maxAdvance().toDays());
    }

    private String coachNameOf(CoachingSessionEntity session) {
        return coaches.findById(session.getCoachId())
                .map(CoachEntity::getDisplayName)
                .orElse("Your coach");
    }

    /** Batched so a list of sessions is one coach lookup, not one per row. */
    private List<CoachingDtos.SessionView> toViews(List<CoachingSessionEntity> sessions) {
        Map<Long, String> names = new HashMap<>();
        coaches.findAllById(sessions.stream().map(CoachingSessionEntity::getCoachId).toList())
                .forEach(c -> names.put(c.getId(), c.getDisplayName()));
        return sessions.stream()
                .map(s -> toView(s, names.getOrDefault(s.getCoachId(), "Your coach")))
                .toList();
    }

    private CoachingDtos.SessionView toView(CoachingSessionEntity s, String coachName) {
        Instant now = clock.instant();
        boolean scheduled = s.getStatus() == SessionStatus.SCHEDULED;
        CoachingPolicy policy = coaching.policy();
        return new CoachingDtos.SessionView(
                s.getPublicRef(),
                null,
                coachName,
                s.getStartsAt(),
                s.getEndsAt(),
                s.getStatus().name(),
                s.getCustomerTimezone(),
                s.getMeetingUrl(),
                s.getCustomerNote(),
                s.getRescheduleCount(),
                s.isCreditReturned(),
                // The same method the cancellation path uses, so the warning shown before
                // confirming cannot disagree with what confirming actually does.
                scheduled && policy.refundsCreditOnCustomerCancel(now, s.getStartsAt()),
                scheduled && policy.canReschedule(now, s.getStartsAt(), s.getRescheduleCount()));
    }
}
