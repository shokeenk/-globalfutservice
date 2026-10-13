package com.globalfutservice.domain.catalog;

/** How a rate-card row turns a quantity into a base price. */
public enum PriceUnit {
    /** price × quantity, where quantity is in millions of coins (0.5 steps). */
    PER_MILLION,
    /** price × 1, regardless of quantity. Boost tiers are flat rows keyed by variant. */
    FLAT,
    /** price × whole hours, the hours chosen by the customer. A listing priced by the hour. */
    PER_HOUR
}
