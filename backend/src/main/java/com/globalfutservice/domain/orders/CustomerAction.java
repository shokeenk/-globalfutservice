package com.globalfutservice.domain.orders;

/**
 * What the customer has to do for a held order, in our own words.
 *
 * <p>The API sends these names, never the fulfilment partner's codes, and the storefront
 * turns each one into a sentence in the customer's language. The partner's vocabulary
 * stops at the backend.
 */
public enum CustomerAction {
    NONE,
    RESUBMIT_SIGN_IN,
    NEW_BACKUP_CODES,
    SIGN_OUT_CONSOLE,
    CLEAR_UNASSIGNED_ITEMS,
    FREE_TRANSFER_SLOTS,
    ADD_COINS,
    SOLVE_CAPTCHA,
    FIX_PERSONA,
    ACCOUNT_UNUSABLE,
    BANNED,
    SUPPLIER_SIDE
}
