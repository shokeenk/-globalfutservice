package com.globalfutservice.domain.coaching;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The single place that knows which coaching-session transitions are legal.
 *
 * <p>Same reasoning as {@code OrderStateMachine}, and the same invariant that matters
 * most: <b>every terminal state settles a credit exactly once</b>. A session that could
 * move from CANCELLED back to SCHEDULED, or from NO_SHOW to COMPLETED, would either return
 * a credit twice or consume one twice — and since credits are what the customer paid for,
 * both are money bugs. Every state here is terminal except SCHEDULED, so the credit
 * settles on the one transition out of it and can never settle again.
 *
 * <p>Reschedules are deliberately absent: they keep a session SCHEDULED and are recorded
 * as events, so they never touch credit accounting.
 */
public final class SessionStateMachine {

    private static final Map<SessionStatus, Set<SessionStatus>> ALLOWED =
            new EnumMap<>(SessionStatus.class);

    static {
        ALLOWED.put(SessionStatus.SCHEDULED, EnumSet.of(
                SessionStatus.COMPLETED,
                SessionStatus.CANCELLED_BY_CUSTOMER,
                SessionStatus.CANCELLED_BY_COACH,
                SessionStatus.NO_SHOW));

        // Everything else is a one-way door. A mis-marked session is corrected by an
        // operator credit adjustment with a reason on it, not by rewriting history.
        ALLOWED.put(SessionStatus.COMPLETED, EnumSet.noneOf(SessionStatus.class));
        ALLOWED.put(SessionStatus.CANCELLED_BY_CUSTOMER, EnumSet.noneOf(SessionStatus.class));
        ALLOWED.put(SessionStatus.CANCELLED_BY_COACH, EnumSet.noneOf(SessionStatus.class));
        ALLOWED.put(SessionStatus.NO_SHOW, EnumSet.noneOf(SessionStatus.class));
    }

    private SessionStateMachine() {
    }

    public static boolean canTransition(SessionStatus from, SessionStatus to) {
        if (from == null || to == null || from == to) {
            return false;
        }
        return ALLOWED.getOrDefault(from, EnumSet.noneOf(SessionStatus.class)).contains(to);
    }

    public static void assertTransition(SessionStatus from, SessionStatus to) {
        if (!canTransition(from, to)) {
            throw new IllegalSessionTransitionException(from, to);
        }
    }

    public static Set<SessionStatus> nextStates(SessionStatus from) {
        return Set.copyOf(ALLOWED.getOrDefault(from, EnumSet.noneOf(SessionStatus.class)));
    }

    /**
     * What the customer may do themselves.
     *
     * <p>COMPLETED and NO_SHOW are coach-and-operator only. A customer who could mark
     * their own session complete could also mark it no-show, and neither of those is a
     * claim the person who owes the attendance should get to make.
     */
    public static Set<SessionStatus> customerTransitions(SessionStatus from) {
        return canTransition(from, SessionStatus.CANCELLED_BY_CUSTOMER)
                ? Set.of(SessionStatus.CANCELLED_BY_CUSTOMER)
                : Set.of();
    }
}
