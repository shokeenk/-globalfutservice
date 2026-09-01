package com.globalfutservice.domain.coaching;

/** Thrown when something asks for a session transition {@link SessionStateMachine} forbids. */
public class IllegalSessionTransitionException extends RuntimeException {

    private final SessionStatus from;
    private final SessionStatus to;

    public IllegalSessionTransitionException(SessionStatus from, SessionStatus to) {
        super("Cannot move a coaching session from " + from + " to " + to);
        this.from = from;
        this.to = to;
    }

    public SessionStatus from() {
        return from;
    }

    public SessionStatus to() {
        return to;
    }
}
