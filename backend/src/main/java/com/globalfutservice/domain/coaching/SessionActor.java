package com.globalfutservice.domain.coaching;

/**
 * Who caused a change to a coaching session. Every {@code coaching_session_event} row
 * carries one.
 *
 * <p>Separate from {@code orders.Actor} rather than sharing it, because of the extra
 * constant: a COACH is not an OPERATOR. They act on their own sessions and nobody else's,
 * and the difference is exactly what a customer disputing "the coach cancelled on me"
 * needs the timeline to show. Widening the orders enum instead would have meant loosening
 * the {@code order_event} check constraint to admit an actor no order can ever have.
 */
public enum SessionActor {
    /** The customer, acting through the storefront. */
    CUSTOMER,
    /** The coach, acting on their own calendar. */
    COACH,
    /** A staff member in the admin console. */
    OPERATOR,
    /** A scheduled job — reminders, no-show sweeps, credit expiry. */
    SYSTEM
}
