package com.globalfutservice.marketing;

/**
 * What a campaign did, counted from its recipient rows.
 *
 * <p><b>On what these numbers can honestly claim.</b> {@code sent} is what this
 * application handed to the SMTP server and it accepted; it is not proof of delivery to an
 * inbox, which only a bounce webhook from the mail provider could tell us and none is
 * wired in. {@code opened} counts recipients whose tracking pixel was fetched at least
 * once, which undercounts anybody with images off and overcounts anybody whose provider
 * pre-fetches images on their behalf. {@code clicked} is the only one of the three that
 * requires a deliberate human action, and is the number worth trusting.
 *
 * <p>{@code unsubscribed} is counted from the account table rather than from the recipient
 * rows, because consent lives on the account and a customer has only one answer to give
 * regardless of how many campaigns they have been sent. It attributes somebody to the
 * campaign whose unsubscribe link they actually used, so a campaign that drives people
 * away can be identified as the one that did it.
 */
public record CampaignStats(long total, long sent, long failed, long opened, long clicked,
                            long unsubscribed) {

    /**
     * The five numbers the recipient rows can answer on their own.
     *
     * <p>This is the constructor the stats query targets; unsubscribes are not in that
     * table and are folded in afterwards by {@link #withUnsubscribed}.
     */
    public CampaignStats(long total, long sent, long failed, long opened, long clicked) {
        this(total, sent, failed, opened, clicked, 0);
    }

    public CampaignStats withUnsubscribed(long count) {
        return new CampaignStats(total, sent, failed, opened, clicked, count);
    }

    public static CampaignStats empty() {
        return new CampaignStats(0, 0, 0, 0, 0, 0);
    }

    public long pending() {
        return total - sent - failed;
    }
}
