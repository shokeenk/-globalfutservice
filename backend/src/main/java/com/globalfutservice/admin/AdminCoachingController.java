package com.globalfutservice.admin;

import com.globalfutservice.coaching.CoachAvailabilityEntity;
import com.globalfutservice.coaching.CoachAvailabilityRepository;
import com.globalfutservice.coaching.CoachEntity;
import com.globalfutservice.coaching.CoachRepository;
import com.globalfutservice.coaching.CoachTimeOffEntity;
import com.globalfutservice.coaching.CoachTimeOffRepository;
import com.globalfutservice.coaching.CoachingService;
import com.globalfutservice.coaching.CoachingSessionEntity;
import com.globalfutservice.coaching.CoachingSessionRepository;
import com.globalfutservice.coaching.SessionCreditEntity;
import com.globalfutservice.coaching.SessionCreditRepository;
import com.globalfutservice.identity.AccountRepository;
import com.globalfutservice.orders.OrderEntity;
import com.globalfutservice.orders.OrderRepository;
import com.globalfutservice.domain.coaching.SessionActor;
import com.globalfutservice.domain.coaching.SessionStateMachine;
import com.globalfutservice.domain.coaching.SessionStatus;
import com.globalfutservice.domain.crypto.SecureIds;
import com.globalfutservice.security.AccountPrincipal;
import com.globalfutservice.security.CurrentAccount;
import com.globalfutservice.web.ApiExceptions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Coach and session administration.
 *
 * <p>Availability is replaced wholesale rather than patched row by row. A weekly schedule
 * is read as a single thing — "Tuesdays and Thursdays, 6 to 10" — and editing it as a set
 * of independent rows invites the half-applied state where somebody adds Thursday, the
 * second call fails, and the coach is now bookable on a day they never agreed to.
 *
 * <p>Session outcomes are OPERATOR-level; creating and pricing coaches is ADMIN, on the
 * same reasoning the rate cards use. An operator marks who turned up. Deciding who teaches
 * for the business is a different authority.
 */
@RestController
@RequestMapping("/api/v1/admin/coaching")
@Tag(name = "Admin · Coaching", description = "Coaches, availability and session outcomes")
public class AdminCoachingController {

    private static final Logger log = LoggerFactory.getLogger(AdminCoachingController.class);

    private final CoachRepository coaches;
    private final CoachAvailabilityRepository availability;
    private final CoachTimeOffRepository timeOff;
    private final CoachingSessionRepository sessions;
    private final CoachingService coaching;
    /* Only for enriching the diary: who the customer is, and what they paid for. */
    private final SessionCreditRepository credits;
    private final OrderRepository orders;
    private final AccountRepository accounts;

    public AdminCoachingController(CoachRepository coaches,
                                   CoachAvailabilityRepository availability,
                                   CoachTimeOffRepository timeOff,
                                   CoachingSessionRepository sessions,
                                   CoachingService coaching,
                                   SessionCreditRepository credits,
                                   OrderRepository orders,
                                   AccountRepository accounts) {
        this.coaches = coaches;
        this.availability = availability;
        this.timeOff = timeOff;
        this.sessions = sessions;
        this.coaching = coaching;
        this.credits = credits;
        this.orders = orders;
        this.accounts = accounts;
    }

    // ------------------------------------------------------------------- records ------

    public record CoachRequest(
            @NotBlank String displayName,
            String headline,
            String bio,
            String avatarUrl,
            /** IANA zone. Validated here — a bad zone silently breaks slot generation. */
            @NotBlank String timezone,
            String languages,
            String credentials,
            Boolean active,
            Integer sortOrder) {
    }

    public record AvailabilityWindow(
            /** ISO-8601 weekday: Monday is 1, Sunday is 7. */
            @NotNull Integer dayOfWeek,
            @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime start,
            @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime end) {
    }

    public record AvailabilityRequest(@NotNull List<AvailabilityWindow> windows) {
    }

    public record TimeOffRequest(
            @NotNull Instant startsAt,
            @NotNull Instant endsAt,
            String reason) {
    }

    public record OutcomeRequest(@NotBlank String status, String note) {
    }

    public record CoachAdminView(
            String id, String displayName, String headline, String timezone,
            boolean active, int sortOrder, List<AvailabilityWindow> availability) {
    }

    /**
     * One row of the diary.
     *
     * <p>The last four fields are what turn a list of times into something an operator
     * can act on: who it is, which order paid, where it sits in their pack, and whether
     * the money arrived. Without them a coach looking at a busy week cannot tell a
     * confirmed session from one on an unpaid order.
     */
    public record SessionAdminView(
            String ref, String coachName, String customerTimezone,
            Instant startsAt, Instant endsAt, String status,
            boolean creditReturned, int rescheduleCount,
            String customerNote, String meetingUrl, List<String> allowedTransitions,
            String customerEmail, String orderRef, String sessionLabel, String paymentStatus) {
    }

    // -------------------------------------------------------------------- coaches -----

    @GetMapping("/coaches")
    @PreAuthorize("hasRole('OPERATOR')")
    @Operation(summary = "Every coach, active or not")
    @Transactional(readOnly = true)
    public List<CoachAdminView> list() {
        return coaches.findAll().stream().map(this::toAdminView).toList();
    }

    @PostMapping("/coaches")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Add a coach")
    @Transactional
    public CoachAdminView create(@CurrentAccount AccountPrincipal principal,
                                 @Valid @RequestBody CoachRequest request) {
        CoachEntity coach = new CoachEntity(
                SecureIds.token(9), request.displayName(), requireZone(request.timezone()));
        apply(coach, request);
        coaches.save(coach);
        log.info("Coach {} created by {}", coach.getPublicId(), principal.id());
        return toAdminView(coach);
    }

    @PostMapping("/coaches/{coachId}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Update a coach")
    @Transactional
    public CoachAdminView update(@PathVariable String coachId,
                                 @Valid @RequestBody CoachRequest request) {
        CoachEntity coach = requireCoach(coachId);
        coach.setDisplayName(request.displayName());
        coach.setTimezone(requireZone(request.timezone()));
        apply(coach, request);
        coach.touch();
        coaches.save(coach);
        return toAdminView(coach);
    }

    /**
     * Replace a coach's weekly availability.
     *
     * <p>Wholesale, and deliberately so — see the class comment. Note what this does
     * <b>not</b> do: it does not touch sessions already booked. Narrowing a schedule stops
     * new bookings in the removed hours but leaves existing ones standing, because a
     * customer who booked in good faith should hear about a change from a person, not by
     * finding their session silently gone.
     */
    @PostMapping("/coaches/{coachId}/availability")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Replace a coach's weekly availability")
    @Transactional
    public CoachAdminView setAvailability(@PathVariable String coachId,
                                          @Valid @RequestBody AvailabilityRequest request) {
        CoachEntity coach = requireCoach(coachId);

        for (AvailabilityWindow w : request.windows()) {
            if (w.dayOfWeek() < 1 || w.dayOfWeek() > 7) {
                throw new ApiExceptions.BadRequestException(
                        "Day of week must be 1 (Monday) through 7 (Sunday).");
            }
            if (!w.end().isAfter(w.start())) {
                throw new ApiExceptions.BadRequestException(
                        "Each window must end after it starts. A window crossing midnight "
                                + "is two windows on two days.");
            }
        }

        availability.deleteByCoachId(coach.getId());
        availability.flush();
        for (AvailabilityWindow w : request.windows()) {
            availability.save(new CoachAvailabilityEntity(
                    coach.getId(), DayOfWeek.of(w.dayOfWeek()), w.start(), w.end()));
        }
        return toAdminView(coach);
    }

    @PostMapping("/coaches/{coachId}/time-off")
    @PreAuthorize("hasRole('OPERATOR')")
    @Operation(summary = "Block a period of absence")
    @Transactional
    public CoachTimeOffEntity addTimeOff(@PathVariable String coachId,
                                         @Valid @RequestBody TimeOffRequest request) {
        CoachEntity coach = requireCoach(coachId);
        if (!request.endsAt().isAfter(request.startsAt())) {
            throw new ApiExceptions.BadRequestException("Time off must end after it starts.");
        }
        return timeOff.save(new CoachTimeOffEntity(
                coach.getId(), request.startsAt(), request.endsAt(), request.reason()));
    }

    @DeleteMapping("/time-off/{id}")
    @PreAuthorize("hasRole('OPERATOR')")
    @Operation(summary = "Remove an absence")
    @Transactional
    public void removeTimeOff(@PathVariable Long id) {
        timeOff.deleteById(id);
    }

    // ------------------------------------------------------------------- sessions -----

    @GetMapping("/sessions")
    @PreAuthorize("hasRole('OPERATOR')")
    @Operation(summary = "A coach's diary for a window")
    @Transactional(readOnly = true)
    public List<SessionAdminView> diary(
            @RequestParam String coachId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {

        CoachEntity coach = requireCoach(coachId);
        Instant start = from != null ? from : Instant.now().minus(Duration.ofDays(7));
        Instant end = to != null ? to : start.plus(Duration.ofDays(30));
        List<CoachingSessionEntity> found = sessions.forCoachBetween(coach.getId(), start, end);
        return enrich(found, coach.getDisplayName());
    }

    /**
     * Adds the customer, order and pack position to every row, in a fixed number of
     * queries.
     *
     * <p>Four extra reads for the whole window rather than four per session. A coach's
     * diary is a screen whose entire job is to show a lot of rows at once, so resolving
     * each one individually is the difference between a page and a page that times out.
     */
    private List<SessionAdminView> enrich(List<CoachingSessionEntity> found, String coachName) {
        Set<Long> orderIds = found.stream()
                .map(CoachingSessionEntity::getOrderId)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
        Set<Long> accountIds = found.stream()
                .map(CoachingSessionEntity::getAccountId)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());

        Map<Long, OrderEntity> ordersById = orderIds.isEmpty() ? Map.of()
                : orders.findAllById(orderIds).stream()
                        .collect(java.util.stream.Collectors.toMap(OrderEntity::getId, o -> o));
        Map<Long, String> emailsById = accountIds.isEmpty() ? Map.of()
                : accounts.findAllById(accountIds).stream()
                        .collect(java.util.stream.Collectors.toMap(
                                a -> a.getId(), a -> a.getEmail()));
        Map<Long, Integer> packByOrder = orderIds.isEmpty() ? Map.of()
                : credits.findGrantsForOrders(orderIds).stream()
                        .collect(java.util.stream.Collectors.toMap(
                                SessionCreditEntity::getOrderId,
                                SessionCreditEntity::getAmount,
                                (first, second) -> first));

        /*
         * Position is counted from the order's own sessions in creation order, matching
         * CoachingAnnouncer. Numbering from the credit ledger instead would renumber a
         * session whenever a sibling was cancelled and its credit came back.
         */
        Map<Long, List<Long>> sessionIdsByOrder = orderIds.isEmpty() ? Map.of()
                : sessions.findByOrderIdInOrderByIdAsc(orderIds).stream()
                        .collect(java.util.stream.Collectors.groupingBy(
                                CoachingSessionEntity::getOrderId,
                                java.util.stream.Collectors.mapping(
                                        CoachingSessionEntity::getId,
                                        java.util.stream.Collectors.toList())));

        List<SessionAdminView> out = new java.util.ArrayList<>(found.size());
        for (CoachingSessionEntity s : found) {
            OrderEntity order = s.getOrderId() == null ? null : ordersById.get(s.getOrderId());
            int position = s.getOrderId() == null ? 0
                    : sessionIdsByOrder.getOrDefault(s.getOrderId(), List.of())
                            .indexOf(s.getId()) + 1;
            int pack = s.getOrderId() == null ? 0
                    : packByOrder.getOrDefault(s.getOrderId(), 0);

            out.add(new SessionAdminView(
                    s.getPublicRef(), coachName, s.getCustomerTimezone(),
                    s.getStartsAt(), s.getEndsAt(), s.getStatus().name(),
                    s.isCreditReturned(), s.getRescheduleCount(),
                    s.getCustomerNote(), s.getMeetingUrl(),
                    SessionStateMachine.nextStates(s.getStatus()).stream()
                            .map(Enum::name).sorted().toList(),
                    emailsById.get(s.getAccountId()),
                    order == null ? null : order.getPublicRef(),
                    label(position, pack),
                    order == null ? null : order.getStatus().name()));
        }
        return out;
    }

    /** "3 of 6", "3" where the pack size is unknown, or nothing at all. */
    private static String label(int position, int pack) {
        if (position <= 0) {
            return null;
        }
        return pack > 0 ? position + " of " + pack : String.valueOf(position);
    }

    /**
     * Record what happened to a session.
     *
     * <p>Goes through {@code CoachingService.transition} like every other status change, so
     * the credit settles exactly once and the event row is written. There is no path in
     * this controller that writes a status directly.
     */
    @PostMapping("/sessions/{ref}/outcome")
    @PreAuthorize("hasRole('OPERATOR')")
    @Operation(summary = "Mark a session completed, no-show, or cancelled by the coach")
    @Transactional
    public SessionAdminView outcome(@CurrentAccount AccountPrincipal principal,
                                    @PathVariable String ref,
                                    @Valid @RequestBody OutcomeRequest request) {

        CoachingSessionEntity session = sessions.findByPublicRef(ref)
                .orElseThrow(() -> new ApiExceptions.NotFoundException("No such session."));

        SessionStatus target;
        try {
            target = SessionStatus.valueOf(request.status().trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ApiExceptions.BadRequestException("Unknown session outcome.");
        }

        // A customer cancellation is the customer's to make. An operator recording one
        // would bypass the notice-period rule and silently hand back a credit the policy
        // says is spent.
        Set<SessionStatus> operatorSafe = Set.of(
                SessionStatus.COMPLETED, SessionStatus.NO_SHOW, SessionStatus.CANCELLED_BY_COACH);
        if (!operatorSafe.contains(target)) {
            throw new ApiExceptions.BadRequestException(
                    "Operators may mark a session completed, no-show, or cancelled by the coach.");
        }

        coaching.transition(session, target, SessionActor.OPERATOR, principal.id(), request.note());

        String coachName = coaches.findById(session.getCoachId())
                .map(CoachEntity::getDisplayName).orElse("—");
        // Same enrichment as the diary, so a row does not lose its customer and order
        // the moment an operator acts on it.
        return enrich(List.of(session), coachName).get(0);
    }

    // -------------------------------------------------------------------- helpers -----

    private CoachEntity requireCoach(String publicId) {
        return coaches.findByPublicId(publicId)
                .orElseThrow(() -> new ApiExceptions.NotFoundException("No such coach."));
    }

    /** Rejects a malformed zone here, where a human can fix it, not at slot-generation time. */
    private static String requireZone(String timezone) {
        try {
            return ZoneId.of(timezone.trim()).getId();
        } catch (RuntimeException e) {
            throw new ApiExceptions.BadRequestException(
                    "Unknown timezone. Use an IANA name such as Asia/Kolkata.");
        }
    }

    private static void apply(CoachEntity coach, CoachRequest request) {
        coach.setHeadline(request.headline());
        coach.setBio(request.bio());
        coach.setAvatarUrl(request.avatarUrl());
        coach.setLanguages(request.languages());
        coach.setCredentials(request.credentials());
        if (request.active() != null) {
            coach.setActive(request.active());
        }
        if (request.sortOrder() != null) {
            coach.setSortOrder(request.sortOrder());
        }
    }

    private CoachAdminView toAdminView(CoachEntity coach) {
        List<AvailabilityWindow> windows = availability.findByCoachId(coach.getId()).stream()
                .map(a -> new AvailabilityWindow(
                        a.getDay().getValue(), a.getStartTime(), a.getEndTime()))
                .toList();
        return new CoachAdminView(coach.getPublicId(), coach.getDisplayName(), coach.getHeadline(),
                coach.getTimezone(), coach.isActive(), coach.getSortOrder(), windows);
    }

}
