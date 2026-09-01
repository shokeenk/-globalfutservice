package com.globalfutservice.domain.orders;

import com.globalfutservice.domain.orders.SupplierStatusMapper.CustomerAction;
import com.globalfutservice.domain.orders.SupplierStatusMapper.Outcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The supplier's vocabulary, translated.
 *
 * <p>This is the piece of the integration a wrong guess breaks silently: a status that
 * maps to nothing leaves a paid order sitting at the wrong state with no error anywhere,
 * and the customer finds out before we do. So the cases that matter here are not the happy
 * ones — they are the codes that must <em>not</em> be read as progress, and the unknown
 * ones that must not be read as anything at all.
 */
class SupplierStatusMapperTest {

    private static Outcome map(String status, String accountCheck, String economyState) {
        return SupplierStatusMapper.map(OrderStatus.IN_PROGRESS, status, accountCheck,
                economyState, false);
    }

    @Nested
    @DisplayName("progress")
    class Progress {

        @Test
        @DisplayName("finished delivers the order")
        void finished() {
            Outcome o = map("finished", "finished", "finished");
            assertThat(o.status()).isEqualTo(OrderStatus.DELIVERED);
            assertThat(o.action()).isEqualTo(CustomerAction.NONE);
            assertThat(o.needsOperator()).isFalse();
        }

        @Test
        @DisplayName("the three in-flight names all mean in progress")
        void inFlight() {
            for (String s : new String[] { "ready", "entered", "waitingForAssignment", "partlyDelivered" }) {
                assertThat(map(s, "finished", "transfersInProgress").status())
                        .describedAs(s)
                        .isEqualTo(OrderStatus.IN_PROGRESS);
            }
        }

        @Test
        @DisplayName("status names are matched case-insensitively")
        void caseInsensitive() {
            assertThat(map("FINISHED", null, null).status()).isEqualTo(OrderStatus.DELIVERED);
            assertThat(map("PartlyDelivered", null, null).status()).isEqualTo(OrderStatus.IN_PROGRESS);
        }
    }

    @Nested
    @DisplayName("things the customer can fix")
    class CustomerFixable {

        @Test
        @DisplayName("a rejected sign-in asks for it again")
        void wrongPassword() {
            Outcome o = map("entered", "wrongUserPass", null);
            assertThat(o.status()).isEqualTo(OrderStatus.ON_HOLD);
            assertThat(o.action()).isEqualTo(CustomerAction.RESUBMIT_SIGN_IN);
            // Not an operator's problem: the customer resubmits and the order resumes.
            assertThat(o.needsOperator()).isFalse();
        }

        @Test
        @DisplayName("each fixable code maps to its own instruction")
        void fixableCodes() {
            assertThat(map("entered", "wrongBA", null).action()).isEqualTo(CustomerAction.NEW_BACKUP_CODES);
            assertThat(map("entered", "console", null).action()).isEqualTo(CustomerAction.SIGN_OUT_CONSOLE);
            assertThat(map("entered", "unassignedItemsPresent", null).action()).isEqualTo(CustomerAction.CLEAR_UNASSIGNED_ITEMS);
            assertThat(map("entered", "tlFull", null).action()).isEqualTo(CustomerAction.FREE_TRANSFER_SLOTS);
            assertThat(map("entered", "notEnoughCoins", null).action()).isEqualTo(CustomerAction.ADD_COINS);
            assertThat(map("entered", "captcha", null).action()).isEqualTo(CustomerAction.SOLVE_CAPTCHA);
            assertThat(map("entered", "wrongPersona", null).action()).isEqualTo(CustomerAction.FIX_PERSONA);
        }

        @Test
        @DisplayName("the economyState spellings of the same problems agree with accountCheck")
        void economyStateSpellings() {
            assertThat(map("entered", null, "FailedWrongCredentialsTo").action())
                    .isEqualTo(CustomerAction.RESUBMIT_SIGN_IN);
            assertThat(map("entered", null, "FailedWrongBACodeTo").action())
                    .isEqualTo(CustomerAction.NEW_BACKUP_CODES);
            assertThat(map("entered", null, "FailLoggedInConsoleTo").action())
                    .isEqualTo(CustomerAction.SIGN_OUT_CONSOLE);
            assertThat(map("entered", null, "FailedTLfullReceiver").action())
                    .isEqualTo(CustomerAction.FREE_TRANSFER_SLOTS);
        }

        @Test
        @DisplayName("a blocking code outranks a healthy-looking status")
        void blockBeatsStatus() {
            /*
             * The order can sit at `entered` for hours while the real reason is a full
             * transfer list. Reading the overall status first would report "in progress"
             * to a customer who could have fixed it in thirty seconds.
             */
            Outcome o = map("entered", "tlFull", "transfersInProgress");
            assertThat(o.status()).isEqualTo(OrderStatus.ON_HOLD);
            assertThat(o.action()).isEqualTo(CustomerAction.FREE_TRANSFER_SLOTS);
        }
    }

    @Nested
    @DisplayName("things the customer cannot fix")
    class NotFixable {

        @Test
        @DisplayName("no transfer market or no club is a refund path, and a human sees it")
        void unusable() {
            for (String code : new String[] { "noTM", "noClub", "FailWebAppNotYetUnlocked", "wrongConsole" }) {
                Outcome o = map("entered", code, null);
                assertThat(o.action()).describedAs(code).isEqualTo(CustomerAction.ACCOUNT_UNUSABLE);
                assertThat(o.needsOperator()).describedAs(code).isTrue();
            }
        }

        @Test
        @DisplayName("a ban is a guarantee claim, not a retry")
        void banned() {
            assertThat(map("interrupted", "LoginFailedDeviceBan", null).action())
                    .isEqualTo(CustomerAction.BANNED);
            assertThat(map("interrupted", null, "FailedReceiverDeviceBan").action())
                    .isEqualTo(CustomerAction.BANNED);
        }

        @Test
        @DisplayName("supplier-side problems say nothing to the customer")
        void supplierSide() {
            for (String code : new String[] { "noSuitableSender", "insufficientFunds", "calcErrorMaintenance" }) {
                Outcome o = map("entered", null, code);
                assertThat(o.action()).describedAs(code).isEqualTo(CustomerAction.SUPPLIER_SIDE);
                assertThat(o.needsOperator()).describedAs(code).isTrue();
            }
        }
    }

    @Nested
    @DisplayName("the cases that protect a paid order")
    class Safety {

        @Test
        @DisplayName("an unknown status changes nothing and calls a human")
        void unknownStatusHoldsPosition() {
            /*
             * A vocabulary this large will grow. An unrecognised value means "we do not
             * know", which is not "nothing is wrong" — inventing a transition from it is
             * how a paid order silently becomes DELIVERED.
             */
            Outcome o = SupplierStatusMapper.map(OrderStatus.IN_PROGRESS,
                    "someStatusInventedNextYear", null, null, false);
            assertThat(o.status()).isEqualTo(OrderStatus.IN_PROGRESS);
            assertThat(o.needsOperator()).isTrue();
        }

        @Test
        @DisplayName("an unknown status does not drag a delivered order backwards")
        void unknownKeepsWhateverWeHad() {
            Outcome o = SupplierStatusMapper.map(OrderStatus.DELIVERED, "???", null, null, false);
            assertThat(o.status()).isEqualTo(OrderStatus.DELIVERED);
        }

        @Test
        @DisplayName("an abort outranks everything, including a finished status")
        void abortWins() {
            Outcome o = SupplierStatusMapper.map(OrderStatus.IN_PROGRESS,
                    "finished", "finished", "finished", true);
            assertThat(o.status()).isEqualTo(OrderStatus.ON_HOLD);
            assertThat(o.needsOperator()).isTrue();
        }

        @Test
        @DisplayName("nulls and blanks are survivable, not a crash")
        void nullsAreSafe() {
            Outcome o = SupplierStatusMapper.map(OrderStatus.IN_PROGRESS, null, null, null, false);
            assertThat(o.status()).isEqualTo(OrderStatus.IN_PROGRESS);
            assertThat(o.needsOperator()).isTrue();
        }

        @Test
        @DisplayName("interrupted with no sub-code is held for a human, never guessed")
        void interruptedIsHeld() {
            Outcome o = map("interrupted", null, null);
            assertThat(o.status()).isEqualTo(OrderStatus.ON_HOLD);
            assertThat(o.needsOperator()).isTrue();
        }
    }
}
