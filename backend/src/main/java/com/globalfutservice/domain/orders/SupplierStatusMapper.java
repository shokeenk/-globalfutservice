package com.globalfutservice.domain.orders;

import java.util.Locale;

/**
 * Turns the supplier's three status vocabularies into one order status and, where there is
 * one, an instruction the customer can act on.
 *
 * <p>The supplier reports {@code status} (the order overall), {@code accountCheck} (getting
 * on to the account) and {@code economyState} (moving the coins). Most of the interesting
 * values are not errors at all — they name something <em>the customer</em> must fix: clear
 * unassigned items, free transfer-list slots, sign out of the console, generate a fresh
 * backup code. Collapsing them all to ON_HOLD would throw away the only information that
 * makes a stalled order self-serviceable, and turn each one into a support thread.
 *
 * <p>Deliberately in the domain and free of Spring, JPA and any HTTP type: this is the
 * piece most likely to be wrong, it is the piece a wrong guess silently strands orders on,
 * and it should be testable without a database or a running supplier.
 *
 * <p><b>Unknown codes never invent a transition.</b> A vocabulary this large will grow, and
 * an unrecognised value means "we do not know", which is not the same as "nothing is
 * wrong". Those hold the current status and route to an operator.
 */
public final class SupplierStatusMapper {

    private SupplierStatusMapper() {
    }

    /** What, if anything, the customer can do about it. */
    public enum CustomerAction {
        /** Nothing to do — it is progressing, or it is finished. */
        NONE,
        /** The sign-in was rejected; collect it again and send a correction. */
        RESUBMIT_SIGN_IN,
        /** Backup codes used or wrong; the customer must generate new ones. */
        NEW_BACKUP_CODES,
        SIGN_OUT_CONSOLE,
        CLEAR_UNASSIGNED_ITEMS,
        FREE_TRANSFER_SLOTS,
        ADD_COINS,
        SOLVE_CAPTCHA,
        FIX_PERSONA,
        /** Not fixable by the customer: this account cannot be used. Refund path. */
        ACCOUNT_UNUSABLE,
        /** EA has banned the account or device. Guarantee claim. */
        BANNED,
        /** Supplier-side and nothing to tell the customer. Operators only. */
        SUPPLIER_SIDE
    }

    /**
     * @param status      where the order should now sit
     * @param action      what to tell the customer, if anything
     * @param needsOperator whether a human should look at it regardless
     */
    public record Outcome(OrderStatus status, CustomerAction action, boolean needsOperator) {
    }

    /**
     * @param current what we already had, returned unchanged when the supplier says
     *                nothing we recognise
     */
    public static Outcome map(OrderStatus current,
                              String status,
                              String accountCheck,
                              String economyState,
                              boolean aborted) {

        // An abort is the supplier's own decision and outranks everything below it.
        if (aborted) {
            return new Outcome(OrderStatus.ON_HOLD, CustomerAction.SUPPLIER_SIDE, true);
        }

        /*
         * Blocking conditions are read before the overall status, because the order can
         * sit at `entered` for hours while the real reason is a full transfer list. The
         * specific complaint is the useful half.
         */
        CustomerAction blocked = blockingAction(accountCheck, economyState);
        if (blocked != null) {
            boolean terminal = blocked == CustomerAction.ACCOUNT_UNUSABLE
                    || blocked == CustomerAction.BANNED
                    || blocked == CustomerAction.SUPPLIER_SIDE;
            return new Outcome(OrderStatus.ON_HOLD, blocked, terminal);
        }

        return switch (norm(status)) {
            case "finished" -> new Outcome(OrderStatus.DELIVERED, CustomerAction.NONE, false);

            // All three mean "accepted and moving". They differ in how far along, which the
            // amount fields carry far better than a status name does.
            case "ready", "entered", "waitingforassignment", "partlydelivered" ->
                    new Outcome(OrderStatus.IN_PROGRESS, CustomerAction.NONE, false);

            // Interrupted with no recognised sub-code: something is wrong and we cannot say
            // what, which is precisely when a human should look.
            case "interrupted" ->
                    new Outcome(OrderStatus.ON_HOLD, CustomerAction.SUPPLIER_SIDE, true);

            default -> new Outcome(current, CustomerAction.NONE, true);
        };
    }

    /**
     * The blocking complaint, if either detail vocabulary names one.
     *
     * <p>Returns {@code null} when both are progress values, which is the common case.
     */
    private static CustomerAction blockingAction(String accountCheck, String economyState) {
        CustomerAction a = fromCode(norm(accountCheck));
        return a != null ? a : fromCode(norm(economyState));
    }

    private static CustomerAction fromCode(String code) {
        return switch (code) {
            // -- the customer can fix these -------------------------------------------
            case "wronguserpass", "failedwrongcredentialsto" -> CustomerAction.RESUBMIT_SIGN_IN;
            case "wrongba", "failedwrongbacodeto"            -> CustomerAction.NEW_BACKUP_CODES;
            case "console", "failloggedinconsoleto",
                 "failedsessionexpiredcustomerloggedin?"     -> CustomerAction.SIGN_OUT_CONSOLE;
            case "unassigneditemspresent"                    -> CustomerAction.CLEAR_UNASSIGNED_ITEMS;
            case "tlfull", "failedtlfullreceiver"            -> CustomerAction.FREE_TRANSFER_SLOTS;
            case "notenoughcoins"                            -> CustomerAction.ADD_COINS;
            case "captcha"                                   -> CustomerAction.SOLVE_CAPTCHA;
            case "wrongpersona"                              -> CustomerAction.FIX_PERSONA;

            // -- the account cannot be used at all ------------------------------------
            case "notm", "failwebappnotyetunlocked", "noclub",
                 "wrongconsole", "failwebappcustomerlocked"  -> CustomerAction.ACCOUNT_UNUSABLE;

            // -- EA has acted ---------------------------------------------------------
            case "loginfaileddeviceban", "failedreceiverdeviceban" -> CustomerAction.BANNED;

            // -- ours to deal with, not the customer's --------------------------------
            case "nosuitablesender", "insufficientfunds", "calcerrormaintenance",
                 "nnoplayer", "noplayer", "belowmintransfer"  -> CustomerAction.SUPPLIER_SIDE;

            /*
             * Everything else is progress, a transient the supplier says to retry, or a
             * code we have not seen. `loginFailed` and `FailedProxyConnectionError` are
             * both documented as "the user can retry", so they are not surfaced as a
             * customer instruction — the next poll usually clears them.
             */
            default -> null;
        };
    }

    private static String norm(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }
}
