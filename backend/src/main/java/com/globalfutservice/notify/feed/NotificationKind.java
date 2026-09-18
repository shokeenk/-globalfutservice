package com.globalfutservice.notify.feed;

/**
 * What a feed row is about.
 *
 * <p>Six of these follow an order through its life and one does not: {@link #ANNOUNCEMENT}
 * is sent to everybody by the business rather than caused by anything the customer did.
 * The distinction earns its place — it is the only kind that has no order behind it, and
 * the only one a person has to write by hand.
 *
 * <p>The names are repeated as a CHECK constraint in V23, so the database refuses a value
 * this enum would not accept.
 */
public enum NotificationKind {
    ORDER_PLACED,
    PAYMENT_SUBMITTED,
    PAYMENT_CONFIRMED,
    /** Something is waiting on the customer: a sign-in, a correction. */
    ACTION_NEEDED,
    STATUS_CHANGED,
    DELIVERED,
    ANNOUNCEMENT
}
