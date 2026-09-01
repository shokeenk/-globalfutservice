package com.globalfutservice.domain.loyalty;

import java.util.List;

/**
 * Balance arithmetic for the points wallet.
 *
 * <p>Kept as a pure function over ledger entries so that "what is this customer's
 * balance?" has exactly one answer, computed the same way in the account page, at
 * checkout, and in the admin console.
 */
public final class PointsWallet {

    private PointsWallet() {
    }

    /** @param entries signed amounts, already sign-corrected by {@link PointsEntryType} */
    public static long balance(List<Long> entries) {
        long sum = 0L;
        for (Long e : entries) {
            sum = Math.addExact(sum, e == null ? 0L : e);
        }
        return sum;
    }

    /**
     * Applies the sign convention. A caller that records a redemption passes a positive
     * magnitude and gets back the negative amount that belongs in the ledger, so no
     * caller ever has to remember which way round it goes.
     */
    public static long signedAmount(PointsEntryType type, long magnitude) {
        if (magnitude < 0) {
            throw new IllegalArgumentException("Magnitude must be non-negative; the type carries the sign");
        }
        return Math.multiplyExact(magnitude, type.sign());
    }
}
