package com.globalfutservice.identity;

/**
 * Authorisation roles.
 *
 * <p>Staff share the {@code account} table with customers rather than living in a
 * separate one. The trade-off is deliberate and worth stating: a separate table gives
 * stronger isolation, but two auth flows, two password-reset paths and two session
 * stores is a large amount of surface for a business with a handful of operators. The
 * isolation is recovered by making every admin route require an explicit role check at
 * the URL level, giving staff a tighter rate-limit bucket, and never letting the
 * registration endpoint set this column.
 */
public enum AccountRole {
    /** Buyer. The default, and the only role self-registration can produce. */
    CUSTOMER,
    /** Fulfils orders: sees the queue, moves states, reads the vault. */
    OPERATOR,
    /** Everything an operator can do, plus prices, affiliates and other staff. */
    ADMIN;

    public boolean isStaff() {
        return this == OPERATOR || this == ADMIN;
    }

    public String authority() {
        return "ROLE_" + name();
    }
}
