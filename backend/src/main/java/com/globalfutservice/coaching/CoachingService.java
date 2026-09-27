package com.globalfutservice.coaching;

import com.globalfutservice.domain.coaching.AvailabilityRule;
import com.globalfutservice.domain.coaching.CoachingPolicy;
import com.globalfutservice.domain.coaching.SessionActor;
import com.globalfutservice.domain.coaching.SessionCreditType;
import com.globalfutservice.domain.coaching.SessionStateMachine;
import com.globalfutservice.domain.coaching.SessionStatus;
import com.globalfutservice.domain.coaching.SlotPlanner;
import com.globalfutservice.domain.coaching.TimeRange;
import com.globalfutservice.domain.crypto.SecureIds;
import com.globalfutservice.web.ApiExceptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Booking, rescheduling and settling coaching sessions.
 *
 * <p>Two rules run through everything here, and they are the coaching equivalents of the
 * two that hold the orders module together:
 *
 * <ol>
 *   <li><b>Every status change goes through {@link #transition}.</b> Nothing anywhere calls
 *       a setter on the status. That single path validates against
 *       {@link SessionStateMachine}, settles the credit exactly once and writes an event
 *       row, in one transaction — which is what makes it possible to say that every change
 *       is legal, every change is audited, and no credit is ever settled twice.
 *   <li><b>The client's chosen slot is never trusted.</b> The storefront picks from a list
 *       this service generated, but booking re-derives that list from the coach's rules
 *       against a freshly-read busy set, inside the transaction. A hand-crafted request can
 *       name any instant at all.
 * </ol>
 *
 * <p>The last line of defence is neither of those: it is an exclusion constraint in
 * Postgres. Two customers confirming adjacent overlapping slots in the same instant is a
 * race the application cannot win by checking first, so the database settles it and this
 * service translates the resulting violation into a civil "someone just took that slot".
 */
@Service
public class CoachingService {

    private static final Logger log = LoggerFactory.getLogger(CoachingService.class);

    private final CoachRepository coaches;
    private final CoachAvailabilityRepository availability;
    private final CoachTimeOffRepository timeOff;
    private final CoachingSessionRepository sessions;
    private final CoachingSessionEventRepository events;
    private final SessionCreditRepository credits;
    private final CoachExtraSlotRepository extraSlots;
    /**
     * Where the policy comes from. The admin's notice, buffer and session lengths are
     * read per call, so a change in the Coaching diary applies to the next slot offered.
     */
    private final CoachingSettingsService settings;
    private final Clock clock;
    /**
     * Telling people. Deliberately the last thing every path does, and deliberately
     * incapable of failing one: see {@link CoachingAnnouncer}.
     */
    private final CoachingAnnouncer announcer;

    public CoachingService(CoachRepository coaches,
                           CoachAvailabilityRepository availability,
                           CoachTimeOffRepository timeOff,
                           CoachingSessionRepository sessions,
                           CoachingSessionEventRepository events,
                           SessionCreditRepository credits,
                           CoachExtraSlotRepository extraSlots,
                           CoachingSettingsService settings,
                           Clock clock,
                           CoachingAnnouncer announcer) {
        this.coaches = coaches;
        this.availability = availability;
        this.timeOff = timeOff;
        this.sessions = sessions;
        this.events = events;
        this.credits = credits;
        this.extraSlots = extraSlots;
        this.settings = settings;
        this.clock = clock;
        this.announcer = announcer;
    }

    /**
     * The session an order holds or has booked -- the first one still live -- for telling
     * the customer when it is. Empty for an order with no slot, or whose hold let go.
     */
    @Transactional(readOnly = true)
    public Optional<CoachingSessionEntity> activeSessionForOrder(Long orderId) {
        return sessions.findByOrderIdOrderByIdAsc(orderId).stream()
                .filter(s -> s.getStatus() == SessionStatus.PENDING
                        || s.getStatus() == SessionStatus.SCHEDULED)
                .findFirst();
    }

    /** How long a session bought under a rate-card variant runs, per the admin's settings. */
    public Duration sessionLengthForVariant(String variant) {
        return settings.sessionLengthFor(variant);
    }

    /** The booking rules in force: configuration, with the admin's settings applied. */
    public CoachingPolicy policy() {
        return settings.effectivePolicy();
    }

    // ---------------------------------------------------------------- availability -----

    @Transactional(readOnly = true)
    public List<CoachEntity> activeCoaches() {
        return coaches.findByActiveTrueOrderBySortOrderAscDisplayNameAsc();
    }

    @Transactional(readOnly = true)
    public CoachEntity requireCoach(String publicId) {
        return coaches.findByPublicId(publicId)
                .filter(CoachEntity::isActive)
                .orElseThrow(() -> new ApiExceptions.NotFoundException("That coach is not available."));
    }

    /**
     * Free start times for a coach, clamped to the bookable window.
     *
     * <p>The caller may ask for any range; what comes back is the intersection of that
     * range with {@code [now + minLeadTime, now + maxAdvance)}. Clamping here rather than
     * validating and rejecting means a storefront paging through a calendar cannot
     * accidentally ask for something that 400s — it simply gets nothing for the days that
     * are out of bounds.
     */
    @Transactional(readOnly = true)
    public List<Instant> availableSlots(CoachEntity coach, Instant from, Instant to) {
        return availableSlots(coach, from, to, policy().sessionLength());
    }

    /**
     * Bookable starts for a session of a given length -- the length of the product being
     * bought, so a checkout for the six-pack lays out forty-minute sessions, not hours.
     */
    @Transactional(readOnly = true)
    public List<Instant> availableSlots(CoachEntity coach, Instant from, Instant to,
                                        Duration length) {
        CoachingPolicy p = policy();
        Instant now = clock.instant();
        Instant windowStart = max(from, p.earliestBookableStart(now));
        Instant windowEnd = min(to, p.latestBookableStart(now));
        if (!windowEnd.isAfter(windowStart)) {
            return List.of();
        }
        TimeRange window = new TimeRange(windowStart, windowEnd);
        return SlotPlanner.bookableStarts(
                coach.zone(), rulesFor(coach.getId()), extrasFor(coach.getId(), window),
                busyFor(coach.getId(), window, null), window, p, length);
    }

    private List<AvailabilityRule> rulesFor(Long coachId) {
        return availability.findByCoachId(coachId).stream()
                .map(CoachAvailabilityEntity::toRule)
                .toList();
    }

    /** One-off extra windows the admin has opened, for slot generation. */
    private List<TimeRange> extrasFor(Long coachId, TimeRange window) {
        return extraSlots.overlapping(coachId, window.start(), window.end()).stream()
                .map(CoachExtraSlotEntity::toRange)
                .toList();
    }

    /**
     * Booked sessions, holds and declared absences, as one busy set.
     *
     * <p>Sessions and holds are widened by the buffer on both sides, so a slot cannot start
     * until the buffer after one session has passed, nor run into the buffer before the
     * next. Blocked time is not widened: it is already the time the coach is away.
     *
     * @param exclude a session to leave out, so a session being moved does not block itself
     */
    private List<TimeRange> busyFor(Long coachId, TimeRange window, Long exclude) {
        Duration buffer = settings.current().buffer();
        List<TimeRange> busy = new ArrayList<>();
        sessions.busyBetween(coachId, window.start().minus(buffer), window.end().plus(buffer))
                .stream()
                .filter(s -> exclude == null || !exclude.equals(s.getId()))
                .forEach(s -> busy.add(new TimeRange(
                        s.getStartsAt().minus(buffer), s.getEndsAt().plus(buffer))));
        timeOff.overlapping(coachId, window.start(), window.end())
                .forEach(t -> busy.add(t.toRange()));
        return busy;
    }

    // ---------------------------------------------------------------------- booking ----

    /**
     * Book one session, spending one credit.
     *
     * <p>Ordered so the customer cannot lose a credit to a slot they did not get: the slot
     * is validated, the session is written and flushed — which is where the exclusion
     * constraint fires if someone beat them to it — and only then is the credit consumed.
     */
    @Transactional
    public CoachingSessionEntity book(Long accountId, String coachPublicId, Instant startsAt,
                                      String customerTimezone, String note) {
        if (accountId == null) {
            throw new ApiExceptions.ForbiddenException(
                    "Please sign in to book a session.");
        }
        CoachEntity coach = requireCoach(coachPublicId);

        int balance = credits.balanceOf(accountId);
        if (balance <= 0) {
            throw new ApiExceptions.ConflictException("no_session_credits",
                    "You have no coaching sessions left. Buy a session or a pack to book.");
        }

        Instant now = clock.instant();
        CoachingPolicy p = policy();
        TimeRange window = new TimeRange(
                p.earliestBookableStart(now), p.latestBookableStart(now));

        // Re-derived, never trusted. The list the browser was shown is a suggestion.
        // The length the customer actually bought, not the configured default. It decides
        // both whether the slot fits and when the session ends, and those two must be the
        // same number or a booking can be accepted into a window it does not fit.
        Duration length = sessionLengthFor(accountId);

        boolean legal = SlotPlanner.isBookable(startsAt, coach.zone(), rulesFor(coach.getId()),
                extrasFor(coach.getId(), window), busyFor(coach.getId(), window, null), window,
                p, length);
        if (!legal) {
            throw new ApiExceptions.ConflictException("slot_unavailable",
                    "That time is no longer available. Please pick another slot.");
        }

        /*
         * Linked to the order whose credits are paying for it.
         *
         * The column has always been here and was always null. Which grant a booking
         * spends is already decided by sessionLengthFor's oldest-first walk, so the order
         * behind that grant is the order behind this session -- no new rule, just the
         * existing one written down. It is what lets a notification say which order a
         * session belongs to, and which of six it is.
         */
        Long fundingOrderId = fundingGrant(accountId)
                .map(SessionCreditEntity::getOrderId)
                .orElse(null);

        CoachingSessionEntity session = new CoachingSessionEntity(
                SecureIds.sessionRef(), accountId, coach.getId(), fundingOrderId,
                startsAt, startsAt.plus(length), customerTimezone);
        session.setCustomerNote(note);

        try {
            sessions.saveAndFlush(session);
        } catch (DataIntegrityViolationException raced) {
            // The exclusion constraint caught a genuine tie. Nothing has been spent.
            log.info("Booking race lost for coach {} at {}", coach.getPublicId(), startsAt);
            throw new ApiExceptions.ConflictException("slot_unavailable",
                    "Someone just took that slot. Please pick another.");
        }

        credits.save(SessionCreditEntity.consumed(accountId, session.getId(),
                session.getPublicRef()));
        events.save(CoachingSessionEventEntity.booked(
                session.getId(), SessionActor.CUSTOMER, accountId, now));

        log.info("Session {} booked with coach {} at {}",
                session.getPublicRef(), coach.getPublicId(), startsAt);
        announcer.booked(session);
        return session;
    }

    /**
     * Move a session without spending another credit.
     *
     * <p>Deliberately not cancel-and-rebook: that would refund a credit and consume one,
     * which nets to nothing but leaves a ledger implying the customer used two sessions —
     * and it would drop the reschedule count, which is the only thing stopping a session
     * being moved indefinitely.
     */
    @Transactional
    public CoachingSessionEntity reschedule(Long accountId, String publicRef, Instant newStart,
                                            String customerTimezone) {
        CoachingSessionEntity session = requireOwnSession(accountId, publicRef);
        Instant now = clock.instant();

        if (session.getStatus() != SessionStatus.SCHEDULED) {
            throw new ApiExceptions.ConflictException("session_not_scheduled",
                    "That session has already finished or been cancelled.");
        }
        CoachingPolicy p = policy();
        if (!p.canReschedule(now, session.getStartsAt(), session.getRescheduleCount())) {
            throw new ApiExceptions.ConflictException("reschedule_not_allowed",
                    "Sessions can be moved up to " + hours(p.changeCutoff())
                            + " hours before they start, at most "
                            + p.maxReschedules() + " times.");
        }

        CoachEntity coach = coaches.findById(session.getCoachId())
                .orElseThrow(() -> new ApiExceptions.NotFoundException("That coach is no longer available."));

        TimeRange window = new TimeRange(
                p.earliestBookableStart(now), p.latestBookableStart(now));

        // Exclude this session from its own busy set, or it blocks its own move.
        List<TimeRange> busy = busyFor(coach.getId(), window, session.getId());

        // Checked at the length the session already has. This used the default length, so
        // a forty-minute package session was tested as if it ran an hour.
        Duration booked = Duration.between(session.getStartsAt(), session.getEndsAt());
        if (!SlotPlanner.isBookable(newStart, coach.zone(), rulesFor(coach.getId()),
                extrasFor(coach.getId(), window), busy, window, p, booked)) {
            throw new ApiExceptions.ConflictException("slot_unavailable",
                    "That time is not available. Please pick another slot.");
        }

        Instant was = session.getStartsAt();
        // The length the session already has, not today's configuration and not the
        // customer's current credit batch. Moving a session must not silently change how
        // long it runs -- and by now the credit that paid for it has been consumed, so
        // re-deriving it from the pool would read the wrong batch entirely.
        session.applyReschedule(newStart, newStart.plus(booked),
                customerTimezone, now);
        try {
            sessions.saveAndFlush(session);
        } catch (DataIntegrityViolationException raced) {
            throw new ApiExceptions.ConflictException("slot_unavailable",
                    "Someone just took that slot. Please pick another.");
        }
        events.save(CoachingSessionEventEntity.rescheduled(
                session.getId(), was, newStart, SessionActor.CUSTOMER, accountId));
        announcer.rescheduled(session, was);
        return session;
    }

    @Transactional
    public CoachingSessionEntity cancelByCustomer(Long accountId, String publicRef) {
        CoachingSessionEntity session = requireOwnSession(accountId, publicRef);
        CoachingSessionEntity cancelled = transition(session,
                SessionStatus.CANCELLED_BY_CUSTOMER, SessionActor.CUSTOMER, accountId, null);
        /*
         * Announced here rather than inside transition(), which is also the path an
         * operator marking a no-show or a completion takes. Those are not news the coach
         * needs pushed at them -- they are usually the person who just did it. A customer
         * dropping a session, on the other hand, frees a slot somebody should know about.
         */
        announcer.cancelled(cancelled);
        return cancelled;
    }

    @Transactional(readOnly = true)
    public CoachingSessionEntity requireOwnSession(Long accountId, String publicRef) {
        if (accountId == null) {
            throw new ApiExceptions.ForbiddenException("Please sign in.");
        }
        // Authorisation in the query. A reference alone turns up in screenshots.
        return sessions.findByPublicRefAndAccountId(publicRef, accountId)
                .orElseThrow(() -> new ApiExceptions.NotFoundException("We could not find that session."));
    }

    // ------------------------------------------------------------------------- holds ---

    /**
     * Hold a slot for a coaching order being placed, until its payment is verified.
     *
     * <p>Runs inside order creation, so a slot that cannot be held fails the whole order:
     * nothing is charged, no coupon or points are spent, and the customer picks again. The
     * hold spends no credit -- the customer has not paid -- and lasts the admin's hold
     * length; submitting payment proof extends it until the proof is reviewed.
     *
     * <p>The slot is re-derived at the length of the product being bought, exactly as a
     * booking is, and the overlap constraint settles the race between two customers who
     * picked the same slot at once: one order goes through, the other is told the slot was
     * just taken.
     */
    @Transactional
    public CoachingSessionEntity holdForOrder(Long accountId, Long orderId, String variant,
                                              String coachPublicId, Instant startsAt,
                                              String customerTimezone) {
        if (accountId == null) {
            throw new ApiExceptions.ForbiddenException("Please sign in to book a session.");
        }
        if (startsAt == null || coachPublicId == null || coachPublicId.isBlank()) {
            throw new ApiExceptions.BadRequestException("Pick a date and time for your session.");
        }
        String zone = validZone(customerTimezone);
        CoachEntity coach = requireCoach(coachPublicId);
        CoachingPolicy p = policy();
        Instant now = clock.instant();
        TimeRange window = new TimeRange(p.earliestBookableStart(now), p.latestBookableStart(now));
        Duration length = settings.sessionLengthFor(variant);
        boolean legal = SlotPlanner.isBookable(startsAt, coach.zone(), rulesFor(coach.getId()),
                extrasFor(coach.getId(), window), busyFor(coach.getId(), window, null),
                window, p, length);
        if (!legal) {
            throw new ApiExceptions.ConflictException("slot_unavailable",
                    "That slot was just taken, please pick another.");
        }
        CoachingSessionEntity hold = CoachingSessionEntity.hold(SecureIds.sessionRef(), accountId,
                coach.getId(), orderId, startsAt, startsAt.plus(length), zone,
                now.plus(settings.current().hold()));
        try {
            sessions.saveAndFlush(hold);
        } catch (DataIntegrityViolationException raced) {
            // The overlap constraint caught two checkouts for the same slot. This order is
            // rolled back with the exception; the other one keeps the slot.
            log.info("Checkout hold race lost for coach {} at {}", coach.getPublicId(), startsAt);
            throw new ApiExceptions.ConflictException("slot_unavailable",
                    "That slot was just taken, please pick another.");
        }
        events.save(CoachingSessionEventEntity.held(hold.getId(), accountId, now));
        log.info("Session {} held for order {} with coach {} at {} until {}", hold.getPublicRef(),
                orderId, coach.getPublicId(), startsAt, hold.getHoldExpiresAt());
        announcer.held(hold);
        return hold;
    }

    /**
     * The order has been paid: its hold becomes a booking, spending one of the credits the
     * payment has just granted.
     *
     * <p>Nothing to do when the order holds no slot -- it was bought without one, or the
     * hold had already expired, in which case the customer books with their credit like
     * any other.
     */
    @Transactional
    public Optional<CoachingSessionEntity> confirmHoldForOrder(Long orderId, SessionActor actor,
                                                             Long actorId) {
        Optional<CoachingSessionEntity> found =
                sessions.findFirstByOrderIdAndStatus(orderId, SessionStatus.PENDING);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        CoachingSessionEntity hold = found.get();
        if (credits.balanceOf(hold.getAccountId()) <= 0) {
            // The payment path grants before it confirms, so this means the grant failed;
            // leave the hold for an admin rather than book a session nobody paid for.
            log.warn("Order {} is paid but account {} has no credit to confirm session {}",
                    orderId, hold.getAccountId(), hold.getPublicRef());
            return Optional.empty();
        }
        transition(hold, SessionStatus.SCHEDULED, actor, actorId, "payment verified");
        credits.save(SessionCreditEntity.consumed(hold.getAccountId(), hold.getId(),
                hold.getPublicRef()));
        announcer.confirmed(hold);
        return Optional.of(hold);
    }

    /**
     * Let an order's hold go: the payment was rejected, the order was abandoned, or an
     * admin released it. The slot is free again the moment this commits.
     */
    @Transactional
    public Optional<CoachingSessionEntity> releaseHoldForOrder(Long orderId, SessionActor actor,
                                                             Long actorId, String why) {
        Optional<CoachingSessionEntity> found =
                sessions.findFirstByOrderIdAndStatus(orderId, SessionStatus.PENDING);
        found.ifPresent(hold -> release(hold, actor, actorId, why));
        return found;
    }

    /**
     * Payment proof is in: keep the slot until someone has looked at it. Only ever
     * lengthens a hold, so a second submission cannot shorten the first.
     */
    @Transactional
    public void extendHoldForOrder(Long orderId, Instant until) {
        sessions.findFirstByOrderIdAndStatus(orderId, SessionStatus.PENDING).ifPresent(hold -> {
            if (hold.getHoldExpiresAt() == null || until.isAfter(hold.getHoldExpiresAt())) {
                hold.extendHold(until, clock.instant());
                sessions.save(hold);
                log.info("Hold {} for order {} extended to {} while the payment is reviewed",
                        hold.getPublicRef(), orderId, until);
            }
        });
    }

    /** Release every hold whose time is up. Run by the scheduler. */
    @Transactional
    public int releaseExpiredHolds() {
        List<CoachingSessionEntity> expired = sessions.expiredHolds(clock.instant());
        expired.forEach(hold -> release(hold, SessionActor.SYSTEM, null, "hold expired"));
        return expired.size();
    }

    private void release(CoachingSessionEntity hold, SessionActor actor, Long actorId, String why) {
        transition(hold, SessionStatus.RELEASED, actor, actorId, why);
        announcer.released(hold, why);
    }

    private static String validZone(String zone) {
        if (zone == null || zone.isBlank()) {
            return null;
        }
        try {
            return ZoneId.of(zone.trim()).getId();
        } catch (RuntimeException e) {
            throw new ApiExceptions.BadRequestException("That time zone was not recognised.");
        }
    }

    // ------------------------------------------------------------------ admin actions ---

    /**
     * An admin moves a session or hold to another free slot.
     *
     * <p>Not bound by the customer's rules: no minimum notice, no change cut-off, and it does
     * not use up one of the customer's own moves. It is bound by everything that makes a slot
     * real -- the coach's hours or an extra window, nothing booked or held in the way, no
     * blocked time -- and by the overlap constraint underneath.
     */
    @Transactional
    public CoachingSessionEntity rescheduleByAdmin(CoachingSessionEntity session, Instant newStart,
                                                   Long actorId) {
        if (session.getStatus() != SessionStatus.PENDING
                && session.getStatus() != SessionStatus.SCHEDULED) {
            throw new ApiExceptions.ConflictException("session_not_movable",
                    "Only a pending or confirmed session can be moved.");
        }
        CoachEntity coach = coaches.findById(session.getCoachId())
                .orElseThrow(() -> new ApiExceptions.NotFoundException("That coach no longer exists."));
        CoachingPolicy p = policy();
        Instant now = clock.instant();
        TimeRange window = new TimeRange(now, p.latestBookableStart(now));
        Duration length = Duration.between(session.getStartsAt(), session.getEndsAt());
        if (!SlotPlanner.isBookable(newStart, coach.zone(), rulesFor(coach.getId()),
                extrasFor(coach.getId(), window), busyFor(coach.getId(), window, session.getId()),
                window, p, length)) {
            throw new ApiExceptions.ConflictException("slot_unavailable",
                    "That time is not free. Pick another slot.");
        }
        Instant was = session.getStartsAt();
        session.applyMove(newStart, newStart.plus(length), now);
        try {
            sessions.saveAndFlush(session);
        } catch (DataIntegrityViolationException raced) {
            throw new ApiExceptions.ConflictException("slot_unavailable",
                    "Someone just took that slot. Pick another.");
        }
        events.save(CoachingSessionEventEntity.rescheduled(
                session.getId(), was, newStart, SessionActor.OPERATOR, actorId));
        log.info("Session {} moved from {} to {} by account {}", session.getPublicRef(), was,
                newStart, actorId);
        announcer.rescheduled(session, was);
        return session;
    }

    /**
     * An admin cancels: a hold is released, a booking is cancelled by the coach and its
     * credit returned -- the customer did nothing wrong.
     */
    @Transactional
    public CoachingSessionEntity cancelByAdmin(CoachingSessionEntity session, Long actorId,
                                               String note) {
        String why = note == null || note.isBlank() ? "cancelled by an admin" : note.trim();
        switch (session.getStatus()) {
            case PENDING -> release(session, SessionActor.OPERATOR, actorId, why);
            case SCHEDULED -> {
                transition(session, SessionStatus.CANCELLED_BY_COACH, SessionActor.OPERATOR,
                        actorId, why);
                announcer.cancelled(session);
            }
            default -> throw new ApiExceptions.ConflictException("session_not_cancellable",
                    "That session has already finished or been cancelled.");
        }
        return session;
    }

    // ------------------------------------------------------------------- transitions ---

    /**
     * The single path by which a session's status changes.
     *
     * <p>Validates the move, decides whether the credit comes back, writes the credit entry
     * and the event, and stamps the session — all inside one transaction. The credit rules
     * live here rather than at the call sites so that "who cancelled, and how much notice
     * did they give" is answered once:
     *
     * <ul>
     *   <li><b>Coach cancelled</b> — always refunded. The customer did nothing wrong.
     *   <li><b>Customer cancelled</b> — refunded only with enough notice, per
     *       {@link CoachingPolicy#refundsCreditOnCustomerCancel}. Same method the
     *       storefront calls to warn them beforehand, so the warning is never wrong.
     *   <li><b>Completed or no-show</b> — consumed. The coach held the slot either way.
     * </ul>
     */
    @Transactional
    public CoachingSessionEntity transition(CoachingSessionEntity session, SessionStatus to,
                                            SessionActor actor, Long actorId, String detail) {
        SessionStatus from = session.getStatus();
        SessionStateMachine.assertTransition(from, to);

        Instant now = clock.instant();
        boolean refund = switch (to) {
            case CANCELLED_BY_COACH -> true;
            case CANCELLED_BY_CUSTOMER ->
                    policy().refundsCreditOnCustomerCancel(now, session.getStartsAt());
            case COMPLETED, NO_SHOW -> false;
            // A hold spent nothing, so confirming or releasing one has nothing to return.
            case SCHEDULED, PENDING, RELEASED -> false;
        };

        if (from == SessionStatus.PENDING && to == SessionStatus.SCHEDULED) {
            session.applyConfirmation(now);
        } else {
            session.applyTransition(to, refund, now);
        }
        sessions.save(session);

        if (refund) {
            // The unique index on (session_id, entry_type) is the real guard; this check
            // only keeps the common case out of the exception path.
            if (!credits.existsBySessionIdAndEntryType(
                    session.getId(), SessionCreditType.RETURNED)) {
                credits.save(SessionCreditEntity.returned(
                        session.getAccountId(), session.getId(), session.getPublicRef(),
                        to == SessionStatus.CANCELLED_BY_COACH
                                ? "cancelled by coach"
                                : "cancelled with notice"));
            }
        }

        events.save(CoachingSessionEventEntity.transitioned(
                session.getId(), from, to, actor, actorId, detail));

        log.info("Session {} moved {} -> {} by {} (credit returned: {})",
                session.getPublicRef(), from, to, actor, refund);
        return session;
    }

    // ----------------------------------------------------------------------- credits ---

    @Transactional(readOnly = true)
    public int creditBalance(Long accountId) {
        return accountId == null ? 0 : credits.balanceOf(accountId);
    }

    @Transactional(readOnly = true)
    public Instant creditsExpireAt(Long accountId) {
        return accountId == null ? null : credits.nextExpiryFor(accountId, clock.instant());
    }

    /**
     * How long the customer's next booked session runs.
     *
     * <p>Length is a property of what was bought, and the credit is the only thing that
     * still knows: a single session is an hour, a session from the six-pack is forty
     * minutes, and the price difference between the two products <em>is</em> that
     * difference. Reading it back from configuration at booking time would give every
     * session the same length again, which is the bug this exists to close.
     *
     * <p>Credits are spent oldest first, so the batch being drawn on is found by walking
     * the grants in purchase order and stopping at the one that covers the next unit.
     * Cheap enough to do inline: a customer holds a handful of grants, not thousands, and
     * the alternative -- a remaining-count column per batch -- is a second source of
     * truth for a ledger whose whole design is that the sum is right by construction.
     *
     * <p>Falls back to the single-session length when there is nothing to go on: an
     * account with no grants (an operator booking, a manual adjustment) or a grant
     * predating the column. The fallback is the longer of the two on purpose. Guessing
     * short would quietly sell someone forty minutes of an hour they paid for; guessing
     * long costs the coach twenty minutes and shows up in a calendar rather than in a
     * complaint.
     */
    @Transactional(readOnly = true)
    public Duration sessionLengthFor(Long accountId) {
        if (accountId == null) {
            return policy().sessionLength();
        }
        return fundingGrant(accountId)
                // Null minutes means a grant from before the column, which falls through
                // to the default exactly as it did when this walk was written inline.
                .map(SessionCreditEntity::getSessionMinutes)
                .map(Duration::ofMinutes)
                .orElse(policy().sessionLength());
    }

    /**
     * The grant the next booking will spend.
     *
     * <p>Oldest first, skipping whatever has already been consumed: the packs a customer
     * bought are used in the order they bought them. Two things read this — how long the
     * session is, and which order it belongs to — and they must agree, which is why it is
     * one method rather than the same loop written twice.
     */
    @Transactional(readOnly = true)
    public Optional<SessionCreditEntity> fundingGrant(Long accountId) {
        if (accountId == null) {
            return Optional.empty();
        }
        int alreadySpent = credits.netConsumedBy(accountId);
        int seen = 0;
        for (SessionCreditEntity grant : credits.grantsOldestFirst(accountId)) {
            seen += grant.getAmount();
            if (seen > alreadySpent) {
                return Optional.of(grant);
            }
        }
        return Optional.empty();
    }

    /**
     * Grant the credits a paid coaching order bought.
     *
     * <p>Called when the order reaches PAID rather than COMPLETED — unlike loyalty points,
     * which wait for the guarantee window. The customer has paid for sessions and needs to
     * book them now; making them wait a week for the credits would make the product
     * unusable. The refund path returns the credits, and the number of coaching orders that
     * get reversed is small enough that this is the right trade.
     *
     * <p>Idempotent at the database level via a unique index on {@code (order_id)} where
     * the entry type is GRANTED, so a retried webhook cannot mint a second pack.
     */
    @Transactional
    public void grantCredits(Long accountId, Long orderId, int sessionCount, String orderRef,
                             Duration sessionLength) {
        if (accountId == null || orderId == null || sessionCount <= 0) {
            return;
        }
        if (credits.existsByOrderIdAndEntryType(orderId, SessionCreditType.GRANTED)) {
            log.debug("Credits already granted for order {}; skipping", orderRef);
            return;
        }
        try {
            credits.saveAndFlush(SessionCreditEntity.granted(accountId, orderId, sessionCount,
                    policy().creditsExpireAt(clock.instant()), orderRef,
                    (int) sessionLength.toMinutes()));
            log.info("Granted {} session credits to account {} from order {}",
                    sessionCount, accountId, orderRef);
        } catch (DataIntegrityViolationException duplicate) {
            log.debug("Concurrent grant for order {} collapsed by the unique index", orderRef);
        }
    }

    @Transactional(readOnly = true)
    public List<SessionCreditEntity> creditStatement(Long accountId) {
        return credits.findByAccountIdOrderByCreatedAtDesc(accountId);
    }

    @Transactional(readOnly = true)
    public List<CoachingSessionEntity> upcomingFor(Long accountId) {
        return sessions.findByAccountIdAndStatusOrderByStartsAtAsc(
                accountId, SessionStatus.SCHEDULED);
    }

    @Transactional(readOnly = true)
    public List<CoachingSessionEventEntity> timeline(Long sessionId) {
        return events.findBySessionIdOrderByCreatedAtAsc(sessionId);
    }

    // ------------------------------------------------------------------------ helpers --

    private static Instant max(Instant a, Instant b) {
        return a.isAfter(b) ? a : b;
    }

    private static Instant min(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }

    private static long hours(java.time.Duration d) {
        return d.toHours();
    }

    /**
     * Resolves a customer-supplied zone, returning null rather than failing a booking.
     *
     * <p>The zone is presentation only — it decides how a reminder renders the time, not
     * when the session is. A browser reporting something java.time does not recognise is
     * not a reason to refuse the booking; it is a reason to fall back to rendering in the
     * coach's zone.
     */
    public static String normaliseZone(String timezone) {
        if (timezone == null || timezone.isBlank()) {
            return null;
        }
        try {
            return ZoneId.of(timezone).getId();
        } catch (RuntimeException malformed) {
            return null;
        }
    }
}
