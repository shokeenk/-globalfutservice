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
 */
public record CampaignStats(long total, long sent, long failed, long opened, long clicked) {

    public static CampaignStats empty() {
        return new CampaignStats(0, 0, 0, 0, 0);
    }

    public long pending() {
        return total - sent - failed;
    }
}
