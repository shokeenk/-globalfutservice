package com.globalfutservice.domain.coaching;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The hold's place in a session's life: picked at checkout, then either booked or let go.
 */
class SessionStateMachineTest {

    @Test
    @DisplayName("a hold becomes a booking when paid, or is released -- nothing else")
    void holdMoves() {
        assertThat(SessionStateMachine.nextStates(SessionStatus.PENDING))
                .containsExactlyInAnyOrder(SessionStatus.SCHEDULED, SessionStatus.RELEASED);
    }

    @ParameterizedTest
    @EnumSource(value = SessionStatus.class, names = {"COMPLETED", "NO_SHOW",
            "CANCELLED_BY_CUSTOMER", "CANCELLED_BY_COACH"})
    @DisplayName("a hold cannot be settled as if the session had happened or been cancelled")
    void holdIsNotASession(SessionStatus to) {
        assertThatThrownBy(() -> SessionStateMachine.assertTransition(SessionStatus.PENDING, to))
                .isInstanceOf(IllegalSessionTransitionException.class);
    }

    @Test
    @DisplayName("a released hold is final, and frees its slot")
    void releasedIsFinal() {
        assertThat(SessionStateMachine.nextStates(SessionStatus.RELEASED)).isEmpty();
        assertThat(SessionStatus.RELEASED.isTerminal()).isTrue();
        assertThat(SessionStatus.RELEASED.releasesSlot()).isTrue();
    }

    @Test
    @DisplayName("a customer cannot release a hold by cancelling it")
    void customerCannotCancelAHold() {
        assertThat(SessionStateMachine.customerTransitions(SessionStatus.PENDING)).isEmpty();
    }

    @Test
    @DisplayName("nothing goes back to a hold")
    void noWayBack() {
        for (SessionStatus from : SessionStatus.values()) {
            assertThat(SessionStateMachine.canTransition(from, SessionStatus.PENDING))
                    .as("%s -> PENDING", from).isFalse();
        }
    }

    @Test
    @DisplayName("a booking's own moves are unchanged")
    void scheduledUnchanged() {
        assertThat(SessionStateMachine.nextStates(SessionStatus.SCHEDULED))
                .containsExactlyInAnyOrder(SessionStatus.COMPLETED, SessionStatus.NO_SHOW,
                        SessionStatus.CANCELLED_BY_CUSTOMER, SessionStatus.CANCELLED_BY_COACH);
    }
}
