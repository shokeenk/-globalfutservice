package com.globalfutservice.fulfilment;

import java.util.Locale;
import java.util.Map;

import com.globalfutservice.domain.orders.CustomerAction;

/**
 * What a FUT Transfer status report means for our order: every value the vendor documents,
 * named explicitly, and nothing guessed.
 *
 * <p>The report has three vocabularies -- {@code status}, {@code accountCheck} and
 * {@code economyState} -- and each documented value below says what it is. A value that is
 * not here is UNKNOWN, and an unknown value never moves an order anywhere but to an admin.
 *
 * <p>The rules, in the order they are applied:
 * <ol>
 *   <li>A report about a mother order: NEEDS_REVIEW. Its amounts would be a total over
 *       child orders, which the collection documents only for its deprecated auto-buy;
 *       nothing here sends one, so nothing is concluded from one.</li>
 *   <li>Any value not in the documentation: NEEDS_REVIEW.</li>
 *   <li>The vendor's ordered amount differs from ours: NEEDS_REVIEW.</li>
 *   <li>Aborted: PARTIALLY_DELIVERED if anything arrived, NEEDS_REVIEW if not.</li>
 *   <li>{@code finished}: DELIVERED only when nothing was aborted and delivered equals
 *       ordered equals what the customer paid for. Less arrived: PARTIALLY_DELIVERED (or
 *       NEEDS_REVIEW if none). More, or anything else: NEEDS_REVIEW.</li>
 *   <li>A code the customer can fix (a wrong password, a full transfer list...):
 *       AWAITING_CUSTOMER, with the action to ask of them.</li>
 *   <li>Any other failure code (no transfer market, a ban, the vendor's side):
 *       NEEDS_REVIEW. {@code noSuitableSender} has its own reason, NO_SUITABLE_SENDER:
 *       nobody is left to deliver the rest, which an admin needs to see as such.</li>
 *   <li>{@code interrupted} with no code to explain it: NEEDS_REVIEW.</li>
 *   <li>Otherwise it is working: IN_DELIVERY once coins are moving, SUBMITTED before.</li>
 * </ol>
 */
public final class VendorStatusMap {

    private VendorStatusMap() {
    }

    /** The reason for a report of {@code noSuitableSender}: no sender, or seller, can deliver the rest. */
    public static final String NO_SUITABLE_SENDER = "NO_SUITABLE_SENDER";

    /** Our states for a vendor order, as vendor_order stores them. */
    public enum State { SUBMITTED, IN_DELIVERY, AWAITING_CUSTOMER, DELIVERED, PARTIALLY_DELIVERED, NEEDS_REVIEW }

    /**
     * @param reason a short code for the admin and the audit, e.g. {@code UNKNOWN_STATUS} or
     *               {@code SHORT_DELIVERY}; null when all is well
     * @param alert  whether an admin should be told
     */
    public record Outcome(State state, CustomerAction action, String reason, boolean alert) {
        static Outcome working(State state) {
            return new Outcome(state, CustomerAction.NONE, null, false);
        }

        static Outcome review(String reason, CustomerAction action) {
            return new Outcome(State.NEEDS_REVIEW, action, reason, true);
        }
    }

    /** What a documented value means. */
    private enum Meaning { PROGRESS, FIXABLE, UNUSABLE, BANNED, SUPPLIER, NO_SENDER, RETRY }

    private record Known(Meaning meaning, CustomerAction action) {
    }

    private static Known k(Meaning meaning) {
        return new Known(meaning, CustomerAction.NONE);
    }

    private static Known fix(CustomerAction action) {
        return new Known(Meaning.FIXABLE, action);
    }

    /** The documented {@code status} values. */
    static final Map<String, String> STATUS = Map.of(
            "ready", "onboarding", "entered", "onboarding", "waitingforassignment", "onboarding",
            "partlydelivered", "delivering", "finished", "finished", "interrupted", "interrupted");

    /** The documented {@code accountCheck} values. */
    static final Map<String, Known> ACCOUNT_CHECK = Map.ofEntries(
            Map.entry("finished", k(Meaning.PROGRESS)),
            Map.entry("entered", k(Meaning.PROGRESS)),
            Map.entry("started", k(Meaning.PROGRESS)),
            Map.entry("userpassverified", k(Meaning.PROGRESS)),
            Map.entry("correctba", k(Meaning.PROGRESS)),
            Map.entry("nounassigneditemspresent", k(Meaning.PROGRESS)),
            Map.entry("wronguserpass", fix(CustomerAction.RESUBMIT_SIGN_IN)),
            Map.entry("wrongba", fix(CustomerAction.NEW_BACKUP_CODES)),
            Map.entry("console", fix(CustomerAction.SIGN_OUT_CONSOLE)),
            Map.entry("unassigneditemspresent", fix(CustomerAction.CLEAR_UNASSIGNED_ITEMS)),
            Map.entry("tlfull", fix(CustomerAction.FREE_TRANSFER_SLOTS)),
            Map.entry("notenoughcoins", fix(CustomerAction.ADD_COINS)),
            Map.entry("captcha", fix(CustomerAction.SOLVE_CAPTCHA)),
            Map.entry("wrongpersona", fix(CustomerAction.FIX_PERSONA)),
            Map.entry("notm", new Known(Meaning.UNUSABLE, CustomerAction.ACCOUNT_UNUSABLE)),
            Map.entry("noclub", new Known(Meaning.UNUSABLE, CustomerAction.ACCOUNT_UNUSABLE)),
            Map.entry("wrongconsole", new Known(Meaning.UNUSABLE, CustomerAction.ACCOUNT_UNUSABLE)),
            Map.entry("loginfaileddeviceban", new Known(Meaning.BANNED, CustomerAction.BANNED)),
            // The vendor says these may simply be retried; an admin resumes the order.
            Map.entry("loginfailed", k(Meaning.RETRY)),
            Map.entry("failedproxyconnectionerror", k(Meaning.RETRY)),
            Map.entry("failedproxypoolexhausted", k(Meaning.RETRY)));

    /** The documented {@code economyState} values, including the two temp-ban codes. */
    static final Map<String, Known> ECONOMY_STATE = Map.ofEntries(
            Map.entry("entered", k(Meaning.PROGRESS)),
            Map.entry("started", k(Meaning.PROGRESS)),
            Map.entry("transfersinprogress", k(Meaning.PROGRESS)),
            Map.entry("transfercyclecomplete", k(Meaning.PROGRESS)),
            Map.entry("customerhasplayer", k(Meaning.PROGRESS)),
            Map.entry("customerlistedplayer", k(Meaning.PROGRESS)),
            Map.entry("finished", k(Meaning.PROGRESS)),
            Map.entry("failedwrongcredentialsto", fix(CustomerAction.RESUBMIT_SIGN_IN)),
            Map.entry("failedwrongbacodeto", fix(CustomerAction.NEW_BACKUP_CODES)),
            Map.entry("failloggedinconsoleto", fix(CustomerAction.SIGN_OUT_CONSOLE)),
            Map.entry("failedsessionexpiredcustomerloggedin?", fix(CustomerAction.SIGN_OUT_CONSOLE)),
            Map.entry("failedtlfullreceiver", fix(CustomerAction.FREE_TRANSFER_SLOTS)),
            Map.entry("failnoclubtocanbeeaerrortryagain", new Known(Meaning.UNUSABLE, CustomerAction.ACCOUNT_UNUSABLE)),
            Map.entry("failwebappcustomerlocked", new Known(Meaning.UNUSABLE, CustomerAction.ACCOUNT_UNUSABLE)),
            Map.entry("failwebappnotyetunlocked", new Known(Meaning.UNUSABLE, CustomerAction.ACCOUNT_UNUSABLE)),
            Map.entry("failedreceiverdeviceban", new Known(Meaning.BANNED, CustomerAction.BANNED)),
            Map.entry("failplayerlosttotempban", new Known(Meaning.BANNED, CustomerAction.BANNED)),
            Map.entry("faillisttempbancustomer", new Known(Meaning.BANNED, CustomerAction.BANNED)),
            Map.entry("insufficientfunds", k(Meaning.SUPPLIER)),
            Map.entry("calcerrormaintenance", k(Meaning.SUPPLIER)),
            Map.entry("noplayer", k(Meaning.SUPPLIER)),
            Map.entry("nosuitablesender", k(Meaning.NO_SENDER)),
            Map.entry("belowmintransfer", k(Meaning.SUPPLIER)));

    /** One status report, in our units: thousands of coins throughout. */
    public record Report(String status, String accountCheck, String economyState,
                         Long vendorOrderedK, Long deliveredK, boolean aborted, boolean motherOrder) {

        /** A report about one order. */
        public Report(String status, String accountCheck, String economyState,
                      Long vendorOrderedK, Long deliveredK, boolean aborted) {
            this(status, accountCheck, economyState, vendorOrderedK, deliveredK, aborted, false);
        }
    }

    /** What the report means for an order we sent for {@code ourOrderedK} thousand coins. */
    public static Outcome map(Report r, long ourOrderedK) {
        String status = norm(r.status());
        String accountCheck = norm(r.accountCheck());
        String economyState = norm(r.economyState());

        if (r.motherOrder()) return Outcome.review("MOTHER_ORDER", CustomerAction.NONE);
        if (status.isEmpty()) return Outcome.review("NO_STATUS", CustomerAction.NONE);
        if (!STATUS.containsKey(status)) return Outcome.review("UNKNOWN_STATUS", CustomerAction.NONE);
        if (!accountCheck.isEmpty() && !ACCOUNT_CHECK.containsKey(accountCheck)) {
            return Outcome.review("UNKNOWN_ACCOUNT_CHECK", CustomerAction.NONE);
        }
        if (!economyState.isEmpty() && !ECONOMY_STATE.containsKey(economyState)) {
            return Outcome.review("UNKNOWN_ECONOMY_STATE", CustomerAction.NONE);
        }
        if (r.vendorOrderedK() != null && r.vendorOrderedK() != ourOrderedK) {
            return Outcome.review("AMOUNT_MISMATCH", CustomerAction.NONE);
        }

        long delivered = r.deliveredK() == null ? 0 : r.deliveredK();
        if (r.aborted()) {
            return delivered > 0
                    ? new Outcome(State.PARTIALLY_DELIVERED, CustomerAction.NONE, "ABORTED", true)
                    : Outcome.review("ABORTED", CustomerAction.NONE);
        }
        if (status.equals("finished")) {
            if (r.deliveredK() == null || r.vendorOrderedK() == null) {
                return Outcome.review("FINISHED_WITHOUT_AMOUNTS", CustomerAction.NONE);
            }
            if (delivered == ourOrderedK) {
                return Outcome.working(State.DELIVERED);
            }
            if (delivered < ourOrderedK) {
                return delivered > 0
                        ? new Outcome(State.PARTIALLY_DELIVERED, CustomerAction.NONE, "SHORT_DELIVERY", true)
                        : Outcome.review("FINISHED_NOTHING_DELIVERED", CustomerAction.NONE);
            }
            return Outcome.review("OVER_DELIVERY", CustomerAction.NONE);
        }

        Known ac = ACCOUNT_CHECK.get(accountCheck);
        Known es = ECONOMY_STATE.get(economyState);
        for (Known known : new Known[]{ac, es}) {
            if (known != null && known.meaning() == Meaning.FIXABLE) {
                return new Outcome(State.AWAITING_CUSTOMER, known.action(), "CUSTOMER_ACTION", true);
            }
        }
        for (Known known : new Known[]{ac, es}) {
            if (known == null || known.meaning() == Meaning.PROGRESS) continue;
            String reason = switch (known.meaning()) {
                case UNUSABLE -> "ACCOUNT_UNUSABLE";
                case BANNED -> "BANNED";
                case SUPPLIER -> "SUPPLIER_SIDE";
                case NO_SENDER -> NO_SUITABLE_SENDER;
                default -> "NEEDS_RETRY";
            };
            return Outcome.review(reason, known.action());
        }
        if (status.equals("interrupted")) {
            return Outcome.review("INTERRUPTED", CustomerAction.NONE);
        }
        return Outcome.working(status.equals("partlydelivered") || delivered > 0 ? State.IN_DELIVERY : State.SUBMITTED);
    }

    private static String norm(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }
}
