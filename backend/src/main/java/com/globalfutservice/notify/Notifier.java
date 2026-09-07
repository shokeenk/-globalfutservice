package com.globalfutservice.notify;

/**
 * One outbound channel.
 *
 * <p>Every implementation must be failure-tolerant: a notification that cannot be sent is
 * logged and dropped, never retried into the caller's transaction. An order that has been
 * paid for must not roll back because Meta's API was slow.
 */
public interface Notifier {

    void orderPlaced(OrderNotification notification);

    void orderDelivered(OrderNotification notification);

    void credentialsNeeded(OrderNotification notification);

    /**
     * Payment has settled and the order is in the operator queue.
     *
     * <p>This is the one alert an owner-operator actually needs on their phone:
     * money has arrived and somebody has to go and do the work. It fires on entry
     * to {@code READY_FOR_DELIVERY} rather than on {@code PAID}, because a comfort
     * trade reaches PAID while still waiting on the customer's sign-in — alerting
     * then would be telling the operator to start a job they cannot start.
     *
     * <p>Defaulted to a no-op so a channel that has not been given a template for
     * it stays quiet rather than failing.
     */
    default void readyToFulfil(OrderNotification notification) {
        // Channels opt in by overriding.
    }

    /**
     * A customer has reported paying outside the gateway.
     *
     * <p>The most time-critical alert here, and the only one with nothing behind it. Every
     * other event in this interface follows something the system observed for itself -- a
     * gateway callback, a state transition. This one follows a customer typing a reference
     * into a box, and until a person checks it against an account the order does not move.
     * No timer picks it up and no queue drains on its own; if nobody is told, the customer
     * waits indefinitely having already paid.
     *
     * <p>Defaulted to a no-op like the others, so a channel that has nowhere sensible to
     * put it stays quiet rather than failing.
     */
    default void paymentClaimed(PaymentClaimNotification notification) {
        // Channels opt in by overriding.
    }

    /**
     * A coaching session starting tomorrow.
     *
     * <p>Defaulted to a no-op rather than added to the interface proper, because not every
     * channel should carry it. A reminder is a scheduled courtesy; the WhatsApp template
     * approval process makes adding one a piece of paperwork rather than a piece of code,
     * and a channel that has not done that paperwork should stay quiet rather than fail.
     */
    default void coachingReminder(CoachingNotification notification) {
        // Channels opt in by overriding.
    }

    /** Whether this channel is configured; used to keep startup logs honest. */
    boolean isEnabled();

    String channelName();
}
