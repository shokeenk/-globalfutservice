package com.globalfutservice.domain.orders;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The lifecycle rules, pinned.
 *
 * <p>Several of these look like they are testing the obvious. They are testing the
 * expensive: an order that can move backwards out of DELIVERED rewrites a contract the
 * customer has already been emailed about, and an operator who can set PAID by hand can
 * cause goods to be delivered against a payment that never arrived.
 */
class OrderStateMachineTest {

    @Test
    @DisplayName("the happy path for a player-auction order")
    void player_auction_path() {
        assertThat(OrderStateMachine.canTransition(OrderStatus.DRAFT, OrderStatus.AWAITING_PAYMENT)).isTrue();
        assertThat(OrderStateMachine.canTransition(OrderStatus.AWAITING_PAYMENT, OrderStatus.PAID)).isTrue();
        assertThat(OrderStateMachine.canTransition(OrderStatus.PAID, OrderStatus.READY_FOR_DELIVERY)).isTrue();
        assertThat(OrderStateMachine.canTransition(OrderStatus.READY_FOR_DELIVERY, OrderStatus.IN_PROGRESS)).isTrue();
        assertThat(OrderStateMachine.canTransition(OrderStatus.IN_PROGRESS, OrderStatus.DELIVERED)).isTrue();
        assertThat(OrderStateMachine.canTransition(OrderStatus.DELIVERED, OrderStatus.COMPLETED)).isTrue();
    }

    @Test
    @DisplayName("a comfort-trade order waits for the customer's sign-in first")
    void comfort_trade_path() {
        assertThat(OrderStateMachine.canTransition(OrderStatus.PAID, OrderStatus.CREDENTIALS_PENDING)).isTrue();
        assertThat(OrderStateMachine.canTransition(
                OrderStatus.CREDENTIALS_PENDING, OrderStatus.READY_FOR_DELIVERY)).isTrue();
    }

    @ParameterizedTest
    @EnumSource(OrderStatus.class)
    @DisplayName("DELIVERED is a one-way door")
    void delivered_is_terminal_for_fulfilment(OrderStatus target) {
        boolean allowed = OrderStateMachine.canTransition(OrderStatus.DELIVERED, target);
        if (target == OrderStatus.COMPLETED || target == OrderStatus.DISPUTED) {
            assertThat(allowed).isTrue();
        } else {
            assertThat(allowed)
                    .as("DELIVERED closed the refund window and started the guarantee "
                            + "clock; moving to %s would rewrite that", target)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("payment cannot be skipped")
    void cannot_skip_payment() {
        assertThat(OrderStateMachine.canTransition(OrderStatus.DRAFT, OrderStatus.PAID)).isFalse();
        assertThat(OrderStateMachine.canTransition(OrderStatus.DRAFT, OrderStatus.DELIVERED)).isFalse();
        assertThat(OrderStateMachine.canTransition(
                OrderStatus.AWAITING_PAYMENT, OrderStatus.READY_FOR_DELIVERY)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class,
            names = {"COMPLETED", "ABANDONED", "REFUNDED", "CREDITED"})
    @DisplayName("terminal states have no exits")
    void terminal_states(OrderStatus terminal) {
        assertThat(terminal.isTerminal()).isTrue();
        assertThat(OrderStateMachine.nextStates(terminal)).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(OrderStatus.class)
    @DisplayName("an operator can never fabricate a payment, a completion or a cancellation")
    void operator_cannot_move_money_states(OrderStatus from) {
        Set<OrderStatus> operatorMoves = OrderStateMachine.operatorTransitions(from);
        assertThat(operatorMoves).doesNotContain(
                OrderStatus.PAID, OrderStatus.COMPLETED, OrderStatus.ABANDONED);
        assertThat(OrderStateMachine.nextStates(from)).containsAll(operatorMoves);
    }

    @Test
    @DisplayName("a guarantee claim can be settled in cash or in store credit")
    void dispute_outcomes() {
        assertThat(OrderStateMachine.nextStates(OrderStatus.DISPUTED))
                .containsExactlyInAnyOrder(
                        OrderStatus.COMPLETED, OrderStatus.REFUNDED, OrderStatus.CREDITED);
    }

    @Test
    @DisplayName("nulls and self-transitions are refused rather than treated as no-ops")
    void defensive() {
        assertThat(OrderStateMachine.canTransition(null, OrderStatus.PAID)).isFalse();
        assertThat(OrderStateMachine.canTransition(OrderStatus.PAID, null)).isFalse();
        assertThat(OrderStateMachine.canTransition(OrderStatus.PAID, OrderStatus.PAID)).isFalse();
    }

    @Test
    @DisplayName("an illegal move throws with both states named")
    void assert_transition_throws() {
        assertThatThrownBy(() -> OrderStateMachine.assertTransition(
                OrderStatus.COMPLETED, OrderStatus.IN_PROGRESS))
                .isInstanceOf(IllegalTransitionException.class)
                .hasMessageContaining("COMPLETED")
                .hasMessageContaining("IN_PROGRESS");
    }

    @Test
    @DisplayName("credentials may only exist in states that need them")
    void credential_holding_states() {
        assertThat(OrderStatus.CREDENTIALS_PENDING.mayHoldCredentials()).isTrue();
        assertThat(OrderStatus.IN_PROGRESS.mayHoldCredentials()).isTrue();
        assertThat(OrderStatus.COMPLETED.mayHoldCredentials()).isFalse();
        assertThat(OrderStatus.REFUNDED.mayHoldCredentials()).isFalse();
        assertThat(OrderStatus.ABANDONED.mayHoldCredentials()).isFalse();
    }

    @Test
    @DisplayName("only coaching is fulfilled without a sign-in")
    void delivery_methods() {
        /*
         * PLAYER_AUCTION asserted `false` here until fulfilment moved to the futtransfer
         * supplier, which needs the sign-in. The flag is not cosmetic: declared false,
         * the state machine skips CREDENTIALS_PENDING and the order reaches an operator
         * with nothing attached, while the storefront promises the customer their
         * password is never needed. This assertion is what keeps that promise and the
         * code from drifting apart again.
         */
        assertThat(DeliveryMethod.PLAYER_AUCTION.requiresCredentials()).isTrue();
        assertThat(DeliveryMethod.COMFORT_TRADE.requiresCredentials()).isTrue();

        // Coaching is the genuine exception: the player plays their own account.
        assertThat(DeliveryMethod.SCHEDULED_SESSION.requiresCredentials()).isFalse();
    }
}
