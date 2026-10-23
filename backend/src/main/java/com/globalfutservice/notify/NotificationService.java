package com.globalfutservice.notify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * Fans one event out to every configured channel, off the request thread, and only once
 * the change it reports has committed.
 *
 * <p>Sent on a virtual thread, so a slow WhatsApp API costs notification latency and
 * nothing else -- in particular, it does not hold open the transaction that just took a
 * customer's money. And sent only after that transaction commits: an email about an order
 * that then rolled back is a lie, and a channel that writes to the database must never
 * wait on rows the caller still holds locked (2026-10-23). Called outside a transaction,
 * it sends straight away. A channel that fails is logged; nothing is thrown back.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final List<Notifier> notifiers;
    private final Executor executor;

    public NotificationService(List<Notifier> notifiers, @Qualifier("applicationTaskExecutor") Executor executor) {
        this.notifiers = notifiers;
        this.executor = executor;
        List<String> enabled = notifiers.stream()
                .filter(Notifier::isEnabled)
                .map(Notifier::channelName)
                .toList();

        /*
         * No channel is a operational failure, not a configuration detail.
         *
         * This used to log "Notification channels enabled: none" at INFO, which is
         * exactly the shape of the original bug: the system quietly not telling anyone
         * about paid orders, and quietly not telling anyone that it was not telling
         * anyone. A paid order that nobody is alerted to is an unfulfilled order, so
         * this gets a WARN and says what to do about it.
         */
        if (enabled.isEmpty()) {
            log.warn("NO notification channels are enabled — nobody will be alerted when an "
                    + "order is paid. Set GFS_EMAIL_ENABLED=true and GFS_OPERATOR_EMAILS to "
                    + "at least one address.");
        } else {
            log.info("Notification channels enabled: {}", String.join(", ", enabled));
        }
    }

    public void orderPlaced(OrderNotification n) {
        each(notifier -> notifier.orderPlaced(n));
    }

    public void credentialsNeeded(OrderNotification n) {
        each(notifier -> notifier.credentialsNeeded(n));
    }

    public void credentialsReminder(OrderNotification n) {
        each(notifier -> notifier.credentialsReminder(n));
    }

    public void supportReply(SupportReplyNotification n) {
        each(notifier -> notifier.supportReply(n));
    }

    public void fulfilmentAlert(FulfilmentAlert alert) {
        alert(notifier -> notifier.fulfilmentAlert(alert));
    }

    public void paymentAlert(PaymentAlert alert) {
        alert(notifier -> notifier.paymentAlert(alert));
    }

    public void customerActionNeeded(CustomerActionNotification n) {
        each(notifier -> notifier.customerActionNeeded(n));
    }

    public void readyToFulfil(OrderNotification n) {
        each(notifier -> notifier.readyToFulfil(n));
    }

    /**
     * Off the request thread: this runs inside the customer's own submit request, and a
     * slow SMTP server must not make submitting a payment reference look like it failed.
     */
    public void awaitingVerification(OrderNotification n) {
        each(notifier -> notifier.awaitingVerification(n));
    }

    /** Off the request thread too — this one runs inside an operator's verify click. */
    public void orderConfirmed(OrderNotification n) {
        each(notifier -> notifier.orderConfirmed(n));
    }

    public void coachingConfirmed(OrderNotification n) {
        each(notifier -> notifier.coachingConfirmed(n));
    }

    public void paymentClaimed(PaymentClaimNotification n) {
        each(notifier -> notifier.paymentClaimed(n));
    }

    /**
     * Off the request thread, like the claim. The image is already stored when this runs,
     * so a slow Discord costs the operator a few seconds and the customer's upload nothing
     * -- before this it was posted inside the upload request itself.
     */
    public void paymentProofAttached(PaymentProofNotification n) {
        each(notifier -> notifier.paymentProofAttached(n));
    }

    /** Off any request thread: the sweep that sends it must not wait on SMTP. */
    public void transferStarted(OrderNotification n) {
        each(notifier -> notifier.transferStarted(n));
    }

    /** Off any request or poll thread. The link is passed apart from the order, never put on it. */
    public void coinsOnTheWay(OrderNotification n, String trackingUrl) {
        each(notifier -> notifier.coinsOnTheWay(n, trackingUrl));
    }

    public void orderDelivered(OrderNotification n) {
        each(notifier -> notifier.orderDelivered(n));
    }

    /** Off the admin's request: raised after the settlement has committed. */
    public void settlementSettled(OrderNotification n) {
        each(notifier -> notifier.settlementSettled(n));
    }

    public void coachingReminder(String email, String sessionRef, Instant startsAt, String zone) {
        CoachingNotification n = new CoachingNotification(email, sessionRef, startsAt, zone);
        each(notifier -> notifier.coachingReminder(n));
    }

    /**
     * A session was booked, moved, or called off.
     *
     * <p>Off the request thread like the rest, so a slow Discord call cannot hold the booking
     * transaction open, and {@link #each} swallows a failing channel — a booking is
     * already committed by the time any of this runs and must never be undone by a
     * notification that did not send.
     */
    public void coachingBooked(CoachingBookingNotification n) {
        each(notifier -> notifier.coachingBooked(n));
    }

    public void coachingRescheduled(CoachingBookingNotification n) {
        each(notifier -> notifier.coachingRescheduled(n));
    }

    public void coachingCancelled(CoachingBookingNotification n) {
        each(notifier -> notifier.coachingCancelled(n));
    }

    /** A checkout hold became a booking: the payment was verified. */
    public void coachingSessionConfirmed(CoachingBookingNotification n) {
        each(notifier -> notifier.coachingSessionConfirmed(n));
    }

    /**
     * An operator alert: sent once the caller's transaction is over, committed or not. An
     * alert is often raised just before the change it reports is refused and rolled back,
     * and that is when the operator most needs it.
     */
    private void alert(java.util.function.Consumer<Notifier> action) {
        Runnable send = sender(action);
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            dispatch(send);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                dispatch(send);
            }
        });
    }

    private void each(java.util.function.Consumer<Notifier> action) {
        Runnable send = sender(action);
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            dispatch(send);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                dispatch(send);
            }
        });
    }

    private Runnable sender(java.util.function.Consumer<Notifier> action) {
        return () -> {
            for (Notifier notifier : notifiers) {
                try {
                    action.accept(notifier);
                } catch (Exception e) {
                    log.warn("Notifier {} failed: {}", notifier.channelName(), e.getMessage());
                }
            }
        };
    }

    private void dispatch(Runnable send) {
        try {
            executor.execute(send);
        } catch (RuntimeException e) {
            log.warn("Notifications not sent: {}", e.toString());
        }
    }
}
