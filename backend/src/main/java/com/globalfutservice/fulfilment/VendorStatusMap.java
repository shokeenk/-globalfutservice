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
 *   <li>Any value not in the documentation: NEEDS_REVIEW -- except an {@code economyState} we
 *       do not know while {@code status} says coins are moving ({@code transfersInProgress},
 *       {@code partlyDelivered}) and the delivered amount is not going down: that order is still
 *       delivering. It stays IN_DELIVERY, and an admin is told once about the word
 *       ({@link #UNKNOWN_ECONOMY_STATE_DELIVERING}).</li>
 *   <li>The vendor's ordered amount differs from ours: NEEDS_REVIEW.</li>
 *   <li>Aborted: PARTIALLY_DELIVERED if anything arrived, NEEDS_REVIEW if not.</li>
 *   <li>Finished -- {@code status} "finished", or {@code simplifiedStatus} "completed" --
 *       is DELIVERED only when nothing was aborted and at least what the customer paid for
 *       arrived (more is delivered, with an alert). Less arrived: NEEDS_REVIEW, as a partial
 *       delivery, with an alert. {@code accountCheck} "finished" means only that the account
 *       check finished, and never makes an order delivered.</li>
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

    /** Finished with less delivered than ordered. */
    public static final String PARTIAL_DELIVERY = "PARTIAL_DELIVERY";

    /** Finished with more delivered than ordered: delivered, and an admin is told. */
    public static final String OVER_DELIVERY = "OVER_DELIVERY";

    /**
     * An {@code economyState} we do not know, reported while the order is delivering: it stays
     * IN_DELIVERY, and an admin is told once about the word. Never a review.
     */
    public static final String UNKNOWN_ECONOMY_STATE_DELIVERING = "UNKNOWN_ECONOMY_STATE_DELIVERING";

    /** Our states for a vendor order, as vendor_order stores them. STOPPED is an admin's, never a report's. */
    public enum State { SUBMITTED, IN_DELIVERY, AWAITING_CUSTOMER, DELIVERED, PARTIALLY_DELIVERED, NEEDS_REVIEW,
        STOPPED }

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

    /**
     * The {@code status} values: the six the collection documents, and the progress words
     * public pool (/buyCoinsAPI) orders were seen putting in {@code status} on 2026-10-14 --
     * economyState's words, e.g. {@code transfersInProgress} (docs/vendor/futtransfer-observed.md).
     * A failure word there is still not a status we know.
     */
    static final Map<String, String> STATUS = Map.ofEntries(
            Map.entry("ready", "onboarding"), Map.entry("entered", "onboarding"),
            Map.entry("waitingforassignment", "onboarding"), Map.entry("partlydelivered", "delivering"),
            Map.entry("finished", "finished"), Map.entry("interrupted", "interrupted"),
            // Observed, public pool: economyState's progress words.
            Map.entry("started", "onboarding"), Map.entry("transfersinprogress", "delivering"),
            Map.entry("transfercyclecomplete", "delivering"),
            // FUT Transfer's answer of 2026-10-15: the other overall status values (the list is not closed).
            Map.entry("paused", "paused"), Map.entry("cancelled", "cancelled"), Map.entry("canceled", "cancelled"),
            Map.entry("archived", "archived"),
            // The sign-in refusals, wherever they appear: the customer is asked to fix it.
            Map.entry("wronguserpass", "stopped"), Map.entry("wrongba", "stopped"),
            Map.entry("failedwrongcredentialsto", "stopped"), Map.entry("failedwrongbacodeto", "stopped"));

    /**
     * What a send refused for the customer's sign-in asks them to fix, from the partner's
     * documented code: their EA email or password, or their backup codes. Null for any other
     * code -- including ours for a missing backup code, which the form never lets through.
     */
    public static CustomerAction signInAction(String code) {
        String c = norm(code);
        if (c.equals("invalidpassword")) return CustomerAction.RESUBMIT_SIGN_IN;
        if (c.matches("invalidba[1-5]") || c.equals("nobackupcode")) return CustomerAction.NEW_BACKUP_CODES;
        return null;
    }

    /** The review reasons that are a word we did not know: read again, and recovered once understood. */
    public static final java.util.Set<String> UNKNOWN_WORD_REASONS = java.util.Set.of(
            "UNKNOWN_STATUS", "UNKNOWN_ACCOUNT_CHECK", "UNKNOWN_ECONOMY_STATE", "NO_STATUS");

    /** Paused at FUT Transfer: an admin looks, the order keeps being read, and it picks up by itself when resumed. */
    public static final String PAUSED = "PAUSED";
    /** Cancelled at FUT Transfer, with some coins delivered, or none. */
    public static final String CANCELLED_PARTIAL = "CANCELLED_PARTIAL";
    public static final String CANCELLED_NOTHING = "CANCELLED_NOTHING_DELIVERED";

    /** Reviews undone by themselves once a later report is understood and says otherwise. */
    public static final java.util.Set<String> RECOVERABLE_REASONS = java.util.Set.of(
            "UNKNOWN_STATUS", "UNKNOWN_ACCOUNT_CHECK", "UNKNOWN_ECONOMY_STATE", "NO_STATUS", PAUSED);

    /**
     * The word behind an unknown-word outcome, as {@code field:word} -- what an admin is
     * alerted about once per order. Null for any other outcome.
     */
    public static String unknownWord(Outcome o, Report r) {
        if (o.reason() == null) return null;
        return switch (o.reason()) {
            case "UNKNOWN_STATUS", "NO_STATUS" -> "status:" + norm(r.status());
            case "UNKNOWN_ACCOUNT_CHECK" -> "accountCheck:" + norm(r.accountCheck());
            case "UNKNOWN_ECONOMY_STATE", UNKNOWN_ECONOMY_STATE_DELIVERING -> "economyState:" + norm(r.economyState());
            default -> null;
        };
    }

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
            // Observed 2026-10-15 on GFS-26-ZTE3A759 with status partlyDelivered and coins arriving.
            Map.entry("transferinserted", k(Meaning.PROGRESS)),
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
                         Long vendorOrderedK, Long deliveredK, boolean aborted, boolean motherOrder,
                         /** pending, completed or error; null when the partner does not say. */
                         String simplifiedStatus) {

        /** A report about one order. */
        public Report(String status, String accountCheck, String economyState,
                      Long vendorOrderedK, Long deliveredK, boolean aborted) {
            this(status, accountCheck, economyState, vendorOrderedK, deliveredK, aborted, false, null);
        }

        public Report(String status, String accountCheck, String economyState,
                      Long vendorOrderedK, Long deliveredK, boolean aborted, boolean motherOrder) {
            this(status, accountCheck, economyState, vendorOrderedK, deliveredK, aborted, motherOrder, null);
        }
    }

    /** What the report means for an order we sent for {@code ourOrderedK} thousand coins. */
    public static Outcome map(Report r, long ourOrderedK) {
        return map(r, ourOrderedK, null);
    }

    /**
     * As {@link #map(Report, long)}, knowing what the last report said was delivered: an
     * economyState we do not know while coins are moving, and not going down, is delivering.
     */
    public static Outcome map(Report r, long ourOrderedK, Long previousDeliveredK) {
        String status = norm(r.status());
        String accountCheck = norm(r.accountCheck());
        String economyState = norm(r.economyState());

        if (r.motherOrder()) return Outcome.review("MOTHER_ORDER", CustomerAction.NONE);
        if (status.isEmpty()) return Outcome.review("NO_STATUS", CustomerAction.NONE);
        if (!STATUS.containsKey(status)) return Outcome.review("UNKNOWN_STATUS", CustomerAction.NONE);
        if (!accountCheck.isEmpty() && !ACCOUNT_CHECK.containsKey(accountCheck)) {
            return Outcome.review("UNKNOWN_ACCOUNT_CHECK", CustomerAction.NONE);
        }
        boolean unknownWhileDelivering = false;
        if (!economyState.isEmpty() && !ECONOMY_STATE.containsKey(economyState)) {
            boolean moving = "delivering".equals(STATUS.get(status)) && !"transfercyclecomplete".equals(status);
            boolean notGoingDown = r.deliveredK() == null || previousDeliveredK == null
                    || r.deliveredK() >= previousDeliveredK;
            if (!moving || !notGoingDown) {
                return Outcome.review("UNKNOWN_ECONOMY_STATE", CustomerAction.NONE);
            }
            unknownWhileDelivering = true;
            economyState = "";
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
        // Finished is the order's status, or its one-word summary -- never accountCheck.
        if (status.equals("finished") || norm(r.simplifiedStatus()).equals("completed")) {
            if (r.deliveredK() == null || r.vendorOrderedK() == null) {
                return Outcome.review("FINISHED_WITHOUT_AMOUNTS", CustomerAction.NONE);
            }
            if (delivered == ourOrderedK) {
                return Outcome.working(State.DELIVERED);
            }
            if (delivered > ourOrderedK) {
                return new Outcome(State.DELIVERED, CustomerAction.NONE, OVER_DELIVERY, true);
            }
            return delivered > 0
                    ? Outcome.review(PARTIAL_DELIVERY, CustomerAction.NONE)
                    : Outcome.review("FINISHED_NOTHING_DELIVERED", CustomerAction.NONE);
        }

        String meaning = STATUS.get(status);
        String simplified = norm(r.simplifiedStatus());
        // Closed at FUT Transfer without finishing: an admin decides, knowing what was delivered.
        if ("cancelled".equals(meaning) || simplified.equals("cancelled") || simplified.equals("canceled")) {
            return Outcome.review(delivered > 0 ? CANCELLED_PARTIAL : CANCELLED_NOTHING, CustomerAction.NONE);
        }
        if ("archived".equals(meaning)) {
            return Outcome.review("ARCHIVED", CustomerAction.NONE);
        }

        Known ac = ACCOUNT_CHECK.get(accountCheck);
        Known es = ECONOMY_STATE.get(economyState);
        // A sign-in refusal reported in status itself, as public pool orders mirror economyState there.
        Known st = ECONOMY_STATE.containsKey(status) ? ECONOMY_STATE.get(status) : ACCOUNT_CHECK.get(status);
        for (Known known : new Known[]{ac, es, st}) {
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
        if ("paused".equals(meaning)) {
            return Outcome.review(PAUSED, CustomerAction.NONE);
        }
        if (status.equals("interrupted") || simplified.equals("interrupted")) {
            return Outcome.review("INTERRUPTED", CustomerAction.NONE);
        }
        if (simplified.equals("error")) {
            return Outcome.review("PARTNER_ERROR", CustomerAction.NONE);
        }
        if (unknownWhileDelivering) {
            return new Outcome(State.IN_DELIVERY, CustomerAction.NONE, UNKNOWN_ECONOMY_STATE_DELIVERING, true);
        }
        return Outcome.working("delivering".equals(meaning) || delivered > 0 ? State.IN_DELIVERY : State.SUBMITTED);
    }

    private static String norm(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }
}
