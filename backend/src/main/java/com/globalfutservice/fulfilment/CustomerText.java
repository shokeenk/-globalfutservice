package com.globalfutservice.fulfilment;

import com.globalfutservice.domain.orders.CustomerAction;

/**
 * The one place a fulfilment state becomes words a customer reads: the order timeline,
 * the email, the Discord ticket.
 *
 * <p>Customers see GFS Transfer Method 3.0 and plain progress -- onboarding, delivering,
 * delivered, something to do. They never see the partner's name for a method or any of
 * its codes: the input here is our own state and our own {@link CustomerAction}, so there
 * is nothing of the partner's to leak. A test scans every sentence below for the partner's
 * vocabulary.
 */
public final class CustomerText {

    private CustomerText() {
    }

    /** The sentence for an order that has moved to {@code state}; null if the customer is told nothing. */
    public static String forState(VendorStatusMap.State state, CustomerAction action) {
        return switch (state) {
            case SUBMITTED -> "We're setting up your transfer with GFS Transfer Method 3.0.";
            case IN_DELIVERY -> "Your coins are being delivered.";
            case DELIVERED -> "All your coins have been delivered.";
            case AWAITING_CUSTOMER -> forAction(action);
            // Our team is looking into it; there is nothing for the customer to do.
            case PARTIALLY_DELIVERED, NEEDS_REVIEW -> null;
        };
    }

    /** What the customer has to do, as one or two sentences they can act on. */
    public static String forAction(CustomerAction action) {
        return switch (action == null ? CustomerAction.NONE : action) {
            case RESUBMIT_SIGN_IN -> "Your EA sign-in was not accepted. Please enter your details again on your "
                    + "order page so we can carry on.";
            case NEW_BACKUP_CODES -> "Your backup codes have been used or were not accepted. Please create new ones "
                    + "in your EA account and enter them on your order page.";
            case SIGN_OUT_CONSOLE -> "Please sign out of EA FC on your console and everywhere else, then let us know "
                    + "so we can carry on.";
            case CLEAR_UNASSIGNED_ITEMS -> "Please clear your unassigned items, so fewer than 50 are left, then let "
                    + "us know so we can carry on.";
            case FREE_TRANSFER_SLOTS -> "Please free at least 3 spaces on your transfer list and in your transfer "
                    + "targets, then let us know so we can carry on.";
            case ADD_COINS -> "Your club needs more than 1,500 coins before we can start. Please add some, then let "
                    + "us know.";
            case SOLVE_CAPTCHA -> "Please sign in to the EA FC Web App once and complete the security check, then let "
                    + "us know so we can carry on.";
            case FIX_PERSONA -> "Please check you are using the right EA account for EA FC, then let us know so we "
                    + "can carry on.";
            case ACCOUNT_UNUSABLE, BANNED, SUPPLIER_SIDE, NONE -> "We're looking into your order and will be in "
                    + "touch.";
        };
    }
}
