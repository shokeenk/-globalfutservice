package com.globalfutservice.coaching;

import com.globalfutservice.identity.AccountRepository;
import com.globalfutservice.notify.CoachingBookingNotification;
import com.globalfutservice.notify.NotificationService;
import com.globalfutservice.orders.OrderEntity;
import com.globalfutservice.orders.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Turns a session into something worth telling somebody about.
 *
 * <p>Split out of {@link CoachingService} rather than added to it, because assembling
 * this payload needs orders, accounts, coaches and the credit ledger — four dependencies
 * the booking logic itself has no use for. The service stays about booking rules; this is
 * about describing what happened.
 *
 * <p>Every method here is best effort. A booking is committed before any of this runs and
 * must never be undone by a description of it failing to send, so nothing throws:
 * the fan-out is {@code @Async} and already isolates per-channel failures, and the
 * lookups below are wrapped for the case where the data is simply not there.
 */
@Component
public class CoachingAnnouncer {

    private static final Logger log = LoggerFactory.getLogger(CoachingAnnouncer.class);

    private final CoachingSessionRepository sessions;
    private final SessionCreditRepository credits;
    private final CoachRepository coaches;
    private final OrderRepository orders;
    private final AccountRepository accounts;
    private final NotificationService notifications;

    public CoachingAnnouncer(CoachingSessionRepository sessions,
                             SessionCreditRepository credits,
                             CoachRepository coaches,
                             OrderRepository orders,
                             AccountRepository accounts,
                             NotificationService notifications) {
        this.sessions = sessions;
        this.credits = credits;
        this.coaches = coaches;
        this.orders = orders;
        this.accounts = accounts;
        this.notifications = notifications;
    }

    @Transactional(readOnly = true)
    public void booked(CoachingSessionEntity session) {
        describe(session, null).ifPresent(notifications::coachingBooked);
    }

    @Transactional(readOnly = true)
    public void rescheduled(CoachingSessionEntity session, Instant previousStart) {
        describe(session, previousStart).ifPresent(notifications::coachingRescheduled);
    }

    @Transactional(readOnly = true)
    public void cancelled(CoachingSessionEntity session) {
        describe(session, null).ifPresent(notifications::coachingCancelled);
    }

    /**
     * Assemble the payload, or give up quietly.
     *
     * <p>Returns empty rather than throwing when something cannot be read. A session that
     * cannot be described is a missing Discord message; an exception here would be a
     * booking that failed after the customer had already been charged a credit.
     */
    private java.util.Optional<CoachingBookingNotification> describe(
            CoachingSessionEntity session, Instant previousStart) {
        try {
            OrderEntity order = session.getOrderId() == null ? null
                    : orders.findById(session.getOrderId()).orElse(null);

            return java.util.Optional.of(new CoachingBookingNotification(
                    session.getPublicRef(),
                    session.getStartsAt(),
                    session.getEndsAt(),
                    previousStart,
                    session.getCustomerTimezone(),
                    accounts.findById(session.getAccountId())
                            .map(a -> a.getEmail()).orElse(null),
                    customerName(order, session),
                    coaches.findById(session.getCoachId())
                            .map(CoachEntity::getDisplayName).orElse(null),
                    positionInPack(session),
                    packSize(order),
                    order == null ? null : order.getPublicRef(),
                    order == null ? null : order.getStatus().name()));
        } catch (RuntimeException e) {
            log.warn("Could not describe session {} for notification: {}",
                    session.getPublicRef(), e.getMessage());
            return java.util.Optional.empty();
        }
    }

    /**
     * Which session of the pack this is.
     *
     * <p>Counted from the order's own sessions in the order they were created, not from
     * the credit ledger. The ledger moves when a session is cancelled and its credit
     * comes back, so numbering from it would renumber sessions that had already been
     * announced — "session 3 of 6" becoming "session 2 of 6" in a later message about
     * the same booking.
     *
     * @return the position counting from one, or 0 when the session has no order behind
     *         it and there is nothing to count within
     */
    private int positionInPack(CoachingSessionEntity session) {
        if (session.getOrderId() == null) {
            return 0;
        }
        List<CoachingSessionEntity> forOrder =
                sessions.findByOrderIdOrderByIdAsc(session.getOrderId());
        for (int i = 0; i < forOrder.size(); i++) {
            if (forOrder.get(i).getId().equals(session.getId())) {
                return i + 1;
            }
        }
        return 0;
    }

    /** How many sessions the order bought, from the grant it created. */
    private int packSize(OrderEntity order) {
        if (order == null) {
            return 0;
        }
        return credits.findGrantForOrder(order.getId())
                .map(SessionCreditEntity::getAmount)
                .orElse(0);
    }

    private static String customerName(OrderEntity order, CoachingSessionEntity session) {
        if (order != null && order.getGuestName() != null && !order.getGuestName().isBlank()) {
            return order.getGuestName();
        }
        return null;
    }
}
