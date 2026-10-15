package com.globalfutservice.fulfilment;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Whether customers are given FUT Transfer's tracking link ({@code GFS_FUTTRANSFER_SHOW_PARTNER_LINK}).
 *
 * <p>Off by default: the links FUT Transfer returns (transferstatus.top) answered 404 when
 * checked on 2026-10-15. While off, the order page has no partner Track order button and the
 * "on the way" email's TRACK ORDER button opens our own tracking page. The link is still asked
 * for, stored exactly as returned and shown to admins -- marked hidden from customers -- so
 * switching this on needs no backfill.
 */
@Component
public class PartnerLinkVisibility {

    /** For code built outside Spring that has always shown the link, such as older tests. */
    public static final PartnerLinkVisibility SHOWN = new PartnerLinkVisibility(true);
    public static final PartnerLinkVisibility HIDDEN = new PartnerLinkVisibility(false);

    private final boolean shown;

    @Autowired
    public PartnerLinkVisibility(@Value("${gfs.fut-transfer.show-partner-link:false}") boolean shown) {
        this.shown = shown;
    }

    public boolean shown() {
        return shown;
    }
}
