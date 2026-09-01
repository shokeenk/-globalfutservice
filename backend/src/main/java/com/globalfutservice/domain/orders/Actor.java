package com.globalfutservice.domain.orders;

/** Who caused a state transition. Every {@code order_event} row carries one. */
public enum Actor {
    /** The buyer, acting through the storefront. */
    CUSTOMER,
    /** A staff member in the admin console. */
    OPERATOR,
    /** Razorpay webhook, scheduled job, or another automated path. */
    SYSTEM
}
