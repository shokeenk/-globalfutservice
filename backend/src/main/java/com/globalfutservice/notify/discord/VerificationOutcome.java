package com.globalfutservice.notify.discord;

/**
 * How a {@code /verify} attempt ended.
 *
 * <p>Each one carries the sentence the customer sees. They are written to be read by
 * somebody who is stuck rather than by somebody debugging: no order reference is echoed
 * back on a miss, because an endpoint that confirms which references exist is a way to
 * find one.
 */
public enum VerificationOutcome {

    GRANTED(true,
            "You're in. Your ticket is open below — everything about this order goes there."),

    /**
     * Also what a customer sees when the reference is real but belongs to somebody else.
     * Deliberately identical to a genuine miss: telling the difference apart is exactly
     * the signal somebody guessing references is looking for.
     */
    NOT_FOUND(false,
            "We couldn't match that order reference. Check it against your confirmation "
                    + "email — it looks like GFS-26-XXXXXXXX. If it still doesn't work, "
                    + "message support and we'll sort it out."),

    ALREADY_CLAIMED(false,
            "That order is already linked to a different Discord account. If that wasn't "
                    + "you, or you've changed accounts, message support and we'll move it "
                    + "across."),

    NO_TICKET(false,
            "We found your order, but its ticket isn't open yet. That usually means the "
                    + "payment is still being checked. Try again in a few minutes, or "
                    + "message support if it's been longer."),

    RATE_LIMITED(false,
            "That's a few tries in a row. Wait a minute and try again — and if you're not "
                    + "sure of your order reference, message support rather than guessing."),

    ERROR(false,
            "Something went wrong on our side, not yours. Message support with your order "
                    + "reference and we'll get you in.");

    private final boolean granted;
    private final String message;

    VerificationOutcome(boolean granted, String message) {
        this.granted = granted;
        this.message = message;
    }

    public boolean granted() {
        return granted;
    }

    public String message() {
        return message;
    }
}
