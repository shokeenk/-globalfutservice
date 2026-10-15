package com.globalfutservice.fulfilment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.globalfutservice.domain.orders.CustomerAction;
import com.globalfutservice.fulfilment.VendorStatusMap.Outcome;
import com.globalfutservice.fulfilment.VendorStatusMap.Report;
import com.globalfutservice.fulfilment.VendorStatusMap.State;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Every documented FUT Transfer value, mapped explicitly; everything else goes to an admin. */
class VendorStatusMapTest {

    private static final long OURS = 500;

    private static Outcome map(String status, String accountCheck, String economyState, Long ordered, Long delivered,
                               boolean aborted) {
        return VendorStatusMap.map(new Report(status, accountCheck, economyState, ordered, delivered, aborted), OURS);
    }

    /** The "Possible Values" column of the Query Order Status documentation, for one field. */
    private static List<String> documented(String field) throws Exception {
        String collection = Files.readString(Path.of("..", "docs", "vendor", "fut-transfer.postman_collection.json"));
        Matcher m = Pattern.compile("\\| " + field + " \\| [^|]+\\| ([^|]+) \\|").matcher(collection);
        assertThat(m.find()).as(field + " is documented in the collection").isTrue();
        List<String> values = new ArrayList<>();
        for (String v : m.group(1).split(",")) {
            if (!v.isBlank()) values.add(v.trim());
        }
        return values;
    }

    @Test
    @DisplayName("every value the vendor's collection documents is named in the map")
    void exhaustiveAgainstTheCollection() throws Exception {
        List<String> status = documented("Status");
        List<String> accountCheck = documented("AccountCheck");
        List<String> economyState = documented("EconomyState");
        assertThat(status).hasSize(6);
        assertThat(accountCheck).hasSizeGreaterThan(15);
        assertThat(economyState).hasSizeGreaterThan(15);

        for (String v : status) {
            assertThat(VendorStatusMap.STATUS).as("status " + v).containsKey(v.toLowerCase(Locale.ROOT));
        }
        for (String v : accountCheck) {
            assertThat(VendorStatusMap.ACCOUNT_CHECK).as("accountCheck " + v).containsKey(v.toLowerCase(Locale.ROOT));
        }
        for (String v : economyState) {
            assertThat(VendorStatusMap.ECONOMY_STATE).as("economyState " + v).containsKey(v.toLowerCase(Locale.ROOT));
        }
    }

    @Test
    @DisplayName("delivered only when finished, not aborted, and delivered = ordered = paid for")
    void delivered() {
        assertThat(map("finished", "finished", "finished", 500L, 500L, false).state()).isEqualTo(State.DELIVERED);
        assertThat(map("Finished", "finished", null, 500L, 500L, false).state()).isEqualTo(State.DELIVERED);

        Outcome aborted = map("finished", "finished", "finished", 500L, 500L, true);
        assertThat(aborted.state()).isEqualTo(State.PARTIALLY_DELIVERED);
        assertThat(aborted.alert()).isTrue();
    }

    @Test
    @DisplayName("finished short is for an admin, with an alert; finished with nothing or no amounts, too")
    void finishedWrong() {
        Outcome shortOne = map("finished", "finished", "finished", 500L, 420L, false);
        assertThat(shortOne.state()).isEqualTo(State.NEEDS_REVIEW);
        assertThat(shortOne.reason()).isEqualTo(VendorStatusMap.PARTIAL_DELIVERY);
        assertThat(shortOne.alert()).isTrue();

        assertThat(map("finished", "finished", "finished", 500L, 0L, false).reason()).isEqualTo("FINISHED_NOTHING_DELIVERED");
        assertThat(map("finished", "finished", "finished", null, null, false).reason()).isEqualTo("FINISHED_WITHOUT_AMOUNTS");
    }

    @Test
    @DisplayName("observed 2026-10-14, public pool: economyState's words in status -- transfersInProgress is delivering")
    void publicPoolStatusWords() {
        // The reply FUT Transfer gave for a public pool order, 40 seconds after it was accepted.
        Outcome observed = VendorStatusMap.map(new Report("transfersInProgress", "started", "transfersInProgress",
                OURS, 0L, false, false, "pending"), OURS);
        assertThat(observed).isEqualTo(new Outcome(State.IN_DELIVERY, CustomerAction.NONE, null, false));

        assertThat(map("TransfersInProgress", "finished", "transfersInProgress", 500L, 110L, false).state())
                .isEqualTo(State.IN_DELIVERY);
        assertThat(map("transferCycleComplete", "finished", "transferCycleComplete", 500L, 0L, false).state())
                .isEqualTo(State.IN_DELIVERY);
        assertThat(map("started", "started", "started", 500L, 0L, false).state()).isEqualTo(State.SUBMITTED);
        assertThat(map("entered", "entered", "entered", 500L, 0L, false).state()).isEqualTo(State.SUBMITTED);
        // Any other failure word in status keeps its meaning: not a status we know.
        assertThat(map("insufficientFunds", "finished", "insufficientFunds", 500L, 0L, false).reason())
                .isEqualTo("UNKNOWN_STATUS");
        // And none of it delivers: finished is still the only way.
        assertThat(map("transfersInProgress", "finished", "transfersInProgress", 500L, 500L, false).state())
                .isEqualTo(State.IN_DELIVERY);
    }

    @Test
    @DisplayName("a refused sign-in, wherever FUT Transfer reports it -- status included -- asks the customer to fix it")
    void signInRefusalsInStatus() {
        Outcome pw = map("FailedWrongCredentialsTo", "finished", "FailedWrongCredentialsTo", 500L, 0L, false);
        assertThat(pw.state()).isEqualTo(State.AWAITING_CUSTOMER);
        assertThat(pw.action()).isEqualTo(CustomerAction.RESUBMIT_SIGN_IN);
        Outcome ba = map("FailedWrongBACodeTo", "started", "transfersInProgress", 500L, 0L, false);
        assertThat(ba.state()).isEqualTo(State.AWAITING_CUSTOMER);
        assertThat(ba.action()).isEqualTo(CustomerAction.NEW_BACKUP_CODES);
        assertThat(map("wrongUserPass", "started", "transfersInProgress", 500L, 0L, false).action())
                .isEqualTo(CustomerAction.RESUBMIT_SIGN_IN);
        assertThat(map("transfersInProgress", "wrongBA", "transfersInProgress", 500L, 30L, false).action())
                .isEqualTo(CustomerAction.NEW_BACKUP_CODES);
    }

    @Test
    @DisplayName("a send refused for the sign-in names the part to fix: the password, or the backup codes")
    void signInActionFromCode() {
        assertThat(VendorStatusMap.signInAction("InvalidPassword")).isEqualTo(CustomerAction.RESUBMIT_SIGN_IN);
        for (int i = 1; i <= 5; i++) {
            assertThat(VendorStatusMap.signInAction("InvalidBA" + i)).isEqualTo(CustomerAction.NEW_BACKUP_CODES);
        }
        assertThat(VendorStatusMap.signInAction("NoBackupCode")).isEqualTo(CustomerAction.NEW_BACKUP_CODES);
        assertThat(VendorStatusMap.signInAction("MissingData")).isNull();
        assertThat(VendorStatusMap.signInAction(null)).isNull();
    }

    @Test
    @DisplayName("FUT Transfer's status list of 2026-10-15: every word maps as agreed, and none but finished delivers")
    void everyStatusWord() {
        for (String w : List.of("entered", "ready", "waitingForAssignment")) {
            assertThat(map(w, "started", "entered", 500L, 0L, false).state()).as(w).isEqualTo(State.SUBMITTED);
            assertThat(map(w, "started", "entered", 500L, 20L, false).state()).as(w).isEqualTo(State.IN_DELIVERY);
        }
        for (String w : List.of("transfersInProgress", "partlyDelivered")) {
            assertThat(map(w, "finished", "transfersInProgress", 500L, 0L, false).state()).as(w)
                    .isEqualTo(State.IN_DELIVERY);
            assertThat(map(w, "finished", "transfersInProgress", 500L, 500L, false).state()).as(w + ", all delivered")
                    .isEqualTo(State.IN_DELIVERY);
        }
        assertThat(map("finished", "finished", "finished", 500L, 500L, false).state()).isEqualTo(State.DELIVERED);

        Outcome paused = map("paused", "finished", "transfersInProgress", 500L, 120L, false);
        assertThat(paused.state()).isEqualTo(State.NEEDS_REVIEW);
        assertThat(paused.reason()).isEqualTo(VendorStatusMap.PAUSED);
        assertThat(VendorStatusMap.RECOVERABLE_REASONS).contains(VendorStatusMap.PAUSED);

        for (String w : List.of("cancelled", "canceled", "Cancelled")) {
            assertThat(map(w, "finished", "transfersInProgress", 500L, 120L, false).reason()).as(w)
                    .isEqualTo(VendorStatusMap.CANCELLED_PARTIAL);
            assertThat(map(w, "finished", "transfersInProgress", 500L, 0L, false).reason()).as(w)
                    .isEqualTo(VendorStatusMap.CANCELLED_NOTHING);
        }
        assertThat(map("archived", "finished", "finished", 500L, 500L, false).reason()).isEqualTo("ARCHIVED");
        assertThat(map("interrupted", "finished", "entered", 500L, 0L, false).reason()).isEqualTo("INTERRUPTED");
        assertThat(map("somethingBrandNew", "finished", "finished", 500L, 500L, false).reason())
                .isEqualTo("UNKNOWN_STATUS");
    }

    @Test
    @DisplayName("simplifiedStatus: completed is finished; error, interrupted and cancelled go to an admin")
    void simplifiedStatuses() {
        java.util.function.Function<String, Outcome> with = s -> VendorStatusMap.map(
                new Report("transfersInProgress", "finished", "transfersInProgress", 500L, 120L, false, false, s), OURS);
        assertThat(with.apply("pending").state()).isEqualTo(State.IN_DELIVERY);
        assertThat(with.apply("error").reason()).isEqualTo("PARTNER_ERROR");
        assertThat(with.apply("interrupted").reason()).isEqualTo("INTERRUPTED");
        assertThat(with.apply("cancelled").reason()).isEqualTo(VendorStatusMap.CANCELLED_PARTIAL);
        assertThat(with.apply("somethingNew").state()).as("an unknown summary is never completion")
                .isEqualTo(State.IN_DELIVERY);
        for (String s : List.of("error", "interrupted", "cancelled", "somethingNew")) {
            assertThat(with.apply(s).state()).isNotEqualTo(State.DELIVERED);
        }
    }

    @Test
    @DisplayName("the word behind an unknown-word review, as field:word; null for any other outcome")
    void unknownWord() {
        Report r = new Report("SomethingNew", "finished", "transfersInProgress", 500L, 0L, false);
        assertThat(VendorStatusMap.unknownWord(VendorStatusMap.map(r, OURS), r)).isEqualTo("status:somethingnew");
        Report ac = new Report("partlyDelivered", "brandNewCheck", "transfersInProgress", 500L, 0L, false);
        assertThat(VendorStatusMap.unknownWord(VendorStatusMap.map(ac, OURS), ac)).isEqualTo("accountCheck:brandnewcheck");
        Report es = new Report("partlyDelivered", "finished", "newEconomy", 500L, 0L, false);
        assertThat(VendorStatusMap.unknownWord(VendorStatusMap.map(es, OURS), es)).isEqualTo("economyState:neweconomy");
        Report fine = new Report("partlyDelivered", "finished", "transfersInProgress", 500L, 10L, false);
        assertThat(VendorStatusMap.unknownWord(VendorStatusMap.map(fine, OURS), fine)).isNull();
        Report interrupted = new Report("interrupted", "finished", "entered", 500L, 0L, false);
        assertThat(VendorStatusMap.unknownWord(VendorStatusMap.map(interrupted, OURS), interrupted)).isNull();
    }

    @Test
    @DisplayName("finished with more than ordered is delivered -- amount >= amountOrdered -- and an admin is told")
    void overDelivered() {
        Outcome over = map("finished", "finished", "finished", 500L, 600L, false);
        assertThat(over.state()).isEqualTo(State.DELIVERED);
        assertThat(over.reason()).isEqualTo(VendorStatusMap.OVER_DELIVERY);
        assertThat(over.alert()).isTrue();
    }

    @Test
    @DisplayName("accountCheck \"finished\" is the account check finishing, never the delivery")
    void accountCheckFinishedIsNotDelivered() {
        for (String status : List.of("waitingForAssignment", "partlyDelivered", "interrupted")) {
            for (Long delivered : new Long[]{0L, 250L, 500L}) {
                Outcome o = map(status, "finished", "transfersInProgress", 500L, delivered, false);
                assertThat(o.state()).as(status + " " + delivered).isNotEqualTo(State.DELIVERED);
            }
        }
        // Nor with a summary that is not "completed".
        for (String simplified : new String[]{null, "pending", "error"}) {
            Outcome o = VendorStatusMap.map(new Report("partlyDelivered", "finished", "transfersInProgress",
                    500L, 500L, false, false, simplified), OURS);
            assertThat(o.state()).as(String.valueOf(simplified)).isNotEqualTo(State.DELIVERED);
        }
    }

    @Test
    @DisplayName("simplifiedStatus \"completed\" counts as finished: delivered in full, or for an admin when short")
    void simplifiedCompleted() {
        assertThat(VendorStatusMap.map(new Report("partlyDelivered", "finished", "finished", 500L, 500L, false, false,
                "completed"), OURS).state()).isEqualTo(State.DELIVERED);
        Outcome shortOne = VendorStatusMap.map(new Report("partlyDelivered", "finished", "finished", 500L, 250L,
                false, false, "Completed"), OURS);
        assertThat(shortOne.state()).isEqualTo(State.NEEDS_REVIEW);
        assertThat(shortOne.reason()).isEqualTo(VendorStatusMap.PARTIAL_DELIVERY);
    }

    @Test
    @DisplayName("the vendor's ordered amount must be ours, in any state")
    void amountMismatch() {
        assertThat(map("partlyDelivered", "finished", "transfersInProgress", 300L, 10L, false).reason())
                .isEqualTo("AMOUNT_MISMATCH");
        assertThat(map("finished", "finished", "finished", 300L, 300L, false).state()).isEqualTo(State.NEEDS_REVIEW);
    }

    @Test
    @DisplayName("an undocumented value anywhere goes to an admin, whatever else the report says")
    void unknownValues() {
        assertThat(map("almostDone", "finished", "finished", 500L, 500L, false).reason()).isEqualTo("UNKNOWN_STATUS");
        assertThat(map("finished", "somethingNew", "finished", 500L, 500L, false).reason()).isEqualTo("UNKNOWN_ACCOUNT_CHECK");
        assertThat(map("finished", "finished", "newFailure", 500L, 500L, false).reason()).isEqualTo("UNKNOWN_ECONOMY_STATE");
        assertThat(map(null, "finished", "finished", 500L, 500L, false).reason()).isEqualTo("NO_STATUS");
        assertThat(map("  ", null, null, null, null, false).state()).isEqualTo(State.NEEDS_REVIEW);
    }

    @Test
    @DisplayName("codes the customer can fix wait for them, with the action to ask of them")
    void customerFixable() {
        Outcome signIn = map("interrupted", "wrongUserPass", null, 500L, 0L, false);
        assertThat(signIn.state()).isEqualTo(State.AWAITING_CUSTOMER);
        assertThat(signIn.action()).isEqualTo(CustomerAction.RESUBMIT_SIGN_IN);

        assertThat(map("interrupted", "wrongBA", null, 500L, 0L, false).action()).isEqualTo(CustomerAction.NEW_BACKUP_CODES);
        assertThat(map("interrupted", "console", null, 500L, 0L, false).action()).isEqualTo(CustomerAction.SIGN_OUT_CONSOLE);
        assertThat(map("interrupted", "tlFull", null, 500L, 0L, false).action()).isEqualTo(CustomerAction.FREE_TRANSFER_SLOTS);
        assertThat(map("interrupted", "captcha", null, 500L, 0L, false).action()).isEqualTo(CustomerAction.SOLVE_CAPTCHA);
        assertThat(map("interrupted", "unassignedItemsPresent", null, 500L, 0L, false).action())
                .isEqualTo(CustomerAction.CLEAR_UNASSIGNED_ITEMS);
        assertThat(map("interrupted", "notEnoughCoins", null, 500L, 0L, false).action()).isEqualTo(CustomerAction.ADD_COINS);
        assertThat(map("interrupted", "wrongPersona", null, 500L, 0L, false).action()).isEqualTo(CustomerAction.FIX_PERSONA);
        // The economyState spellings of the same problems agree.
        assertThat(map("interrupted", "finished", "FailedWrongBACodeTo", 500L, 0L, false).action())
                .isEqualTo(CustomerAction.NEW_BACKUP_CODES);
        assertThat(map("partlyDelivered", "finished", "FailedTLfullReceiver", 500L, 100L, false).state())
                .isEqualTo(State.AWAITING_CUSTOMER);
    }

    @Test
    @DisplayName("codes the customer cannot fix go to an admin")
    void notFixable() {
        assertThat(map("interrupted", "noTM", null, 500L, 0L, false).reason()).isEqualTo("ACCOUNT_UNUSABLE");
        assertThat(map("interrupted", "LoginFailedDeviceBan", null, 500L, 0L, false).reason()).isEqualTo("BANNED");
        assertThat(map("interrupted", "finished", "FailPlayerLostToTempban", 500L, 0L, false).reason()).isEqualTo("BANNED");
        assertThat(map("interrupted", "finished", "insufficientFunds", 500L, 0L, false).reason()).isEqualTo("SUPPLIER_SIDE");
        assertThat(map("interrupted", "loginFailed", null, 500L, 0L, false).reason()).isEqualTo("NEEDS_RETRY");
        assertThat(map("interrupted", "finished", null, 500L, 0L, false).reason()).isEqualTo("INTERRUPTED");
    }

    @Test
    @DisplayName("no suitable sender: its own reason, to an admin with an alert, whatever has been delivered so far")
    void noSuitableSender() {
        for (Outcome o : List.of(
                map("interrupted", "finished", "noSuitableSender", 500L, 0L, false),
                map("partlyDelivered", "finished", "noSuitableSender", 500L, 150L, false),
                map("interrupted", null, "NoSuitableSender", 500L, 150L, false))) {
            assertThat(o.state()).isEqualTo(State.NEEDS_REVIEW);
            assertThat(o.reason()).isEqualTo(VendorStatusMap.NO_SUITABLE_SENDER);
            assertThat(o.action()).isEqualTo(CustomerAction.NONE);
            assertThat(o.alert()).isTrue();
        }
        // Something the customer can fix still comes first: they are asked, not left waiting on an admin.
        assertThat(map("interrupted", "wrongBA", "noSuitableSender", 500L, 0L, false).state())
                .isEqualTo(State.AWAITING_CUSTOMER);
    }

    @Test
    @DisplayName("working orders: onboarding stays submitted, moving coins is in delivery, and nobody is alerted")
    void working() {
        assertThat(map("ready", "entered", null, 500L, 0L, false)).isEqualTo(new Outcome(State.SUBMITTED, CustomerAction.NONE, null, false));
        assertThat(map("waitingForAssignment", "finished", "entered", 500L, 0L, false).state()).isEqualTo(State.SUBMITTED);
        assertThat(map("partlyDelivered", "finished", "transfersInProgress", 500L, 107L, false))
                .isEqualTo(new Outcome(State.IN_DELIVERY, CustomerAction.NONE, null, false));
    }

    @Test
    @DisplayName("a mother order is never DELIVERED, even reporting the full amount finished: an admin looks")
    void motherOrderToReview() {
        Outcome o = VendorStatusMap.map(new Report("finished", "finished", "finished", OURS, OURS, false, true), OURS);
        assertThat(o.state()).isEqualTo(VendorStatusMap.State.NEEDS_REVIEW);
        assertThat(o.reason()).isEqualTo("MOTHER_ORDER");
        assertThat(o.alert()).isTrue();

        // The same report about a single order is delivered, as before.
        assertThat(map("finished", "finished", "finished", OURS, OURS, false).state())
                .isEqualTo(VendorStatusMap.State.DELIVERED);
    }
}
