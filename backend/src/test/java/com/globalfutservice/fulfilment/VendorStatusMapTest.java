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
    @DisplayName("finished short is partly delivered; finished with nothing, too much or no amounts is for an admin")
    void finishedWrong() {
        Outcome shortOne = map("finished", "finished", "finished", 500L, 420L, false);
        assertThat(shortOne.state()).isEqualTo(State.PARTIALLY_DELIVERED);
        assertThat(shortOne.reason()).isEqualTo("SHORT_DELIVERY");
        assertThat(shortOne.alert()).isTrue();

        assertThat(map("finished", "finished", "finished", 500L, 0L, false).reason()).isEqualTo("FINISHED_NOTHING_DELIVERED");
        assertThat(map("finished", "finished", "finished", 500L, 600L, false).reason()).isEqualTo("OVER_DELIVERY");
        assertThat(map("finished", "finished", "finished", null, null, false).reason()).isEqualTo("FINISHED_WITHOUT_AMOUNTS");
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
        assertThat(map("interrupted", "finished", "noSuitableSender", 500L, 0L, false).reason()).isEqualTo("SUPPLIER_SIDE");
        assertThat(map("interrupted", "loginFailed", null, 500L, 0L, false).reason()).isEqualTo("NEEDS_RETRY");
        assertThat(map("interrupted", "finished", null, 500L, 0L, false).reason()).isEqualTo("INTERRUPTED");
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
