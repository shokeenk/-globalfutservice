package com.globalfutservice.marketing;

/**
 * Where a campaign is in its life.
 *
 * <p>SENDING exists as a distinct state rather than being inferred from timestamps
 * because it is what makes the send claimable: a conditional update into SENDING is how
 * one instance takes ownership and every other one is refused. See V27.
 */
public enum CampaignStatus {
    /** Being composed. The only state in which content can still be edited. */
    DRAFT,
    /** Waiting for its send time. */
    SCHEDULED,
    /** Claimed by a sender and working through its recipient rows. */
    SENDING,
    /** Every recipient row reached a terminal state. */
    SENT,
    /** Withdrawn before sending. Kept, never deleted — see the repository. */
    CANCELLED,
    /**
     * The send broke part-way, or it reached nobody: the relay refused every message it
     * tried. Individual failures in a send that reached somebody leave it SENT.
     */
    FAILED;

    /** Content is frozen the moment a campaign is queued to go out. */
    public boolean editable() {
        return this == DRAFT;
    }

    public boolean terminal() {
        return this == SENT || this == CANCELLED || this == FAILED;
    }
}
