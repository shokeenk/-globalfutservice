package com.globalfutservice.fulfilment;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Whether customers are given FUT Transfer's tracking link, and which address.
 *
 * <p>{@code GFS_FUTTRANSFER_SHOW_PARTNER_LINK} (default true): their order page's Track order
 * button and the "on the way" email. Off: neither, and the email opens our own tracking page.
 *
 * <p>{@code GFS_FUTTRANSFER_PARTNER_LINK_STYLE}:
 * <ul>
 *   <li>{@code STATUS_PAGE} (default): {@value #STATUS_PAGE}?code=&lt;shortCode&gt;, with
 *       {@code &shop=<shop>} when the shop is not empty -- what FUT Transfer said to use on
 *       2026-10-15, while its short links answer 404. An order whose link came with
 *       specialParams gets the short link instead: nothing documents how to add them.</li>
 *   <li>{@code SHORT_LINK}: the link exactly as /generateOrderStatusLink returned it.</li>
 * </ul>
 * Either way it is a secret: it lets whoever holds it change the customer's EA sign-in.
 */
@Component
public class PartnerLinkVisibility {

    public enum Style { STATUS_PAGE, SHORT_LINK }

    /** FUT Transfer's own order status page. */
    static final String STATUS_PAGE = "https://futtransfer.top/orderStatus";

    /** For code built outside Spring that has always shown the stored link, such as older tests. */
    public static final PartnerLinkVisibility SHOWN = new PartnerLinkVisibility(true, Style.SHORT_LINK);
    public static final PartnerLinkVisibility HIDDEN = new PartnerLinkVisibility(false, Style.SHORT_LINK);

    private final boolean shown;
    private final Style style;

    @Autowired
    public PartnerLinkVisibility(@Value("${gfs.fut-transfer.show-partner-link:true}") boolean shown,
                                 @Value("${gfs.fut-transfer.partner-link-style:STATUS_PAGE}") Style style) {
        this.shown = shown;
        this.style = style;
    }

    public PartnerLinkVisibility(boolean shown) {
        this(shown, Style.STATUS_PAGE);
    }

    public boolean shown() {
        return shown;
    }

    public Style style() {
        return style;
    }

    /** The address customers are given for this stored link; null when they are given none. */
    public String customerUrl(VendorOrderLedger.PartnerLink link) {
        return shown && link != null ? address(link) : null;
    }

    /** The address the configured style gives for this link, shown or not (staff see it too). */
    public String address(VendorOrderLedger.PartnerLink link) {
        if (link == null) return null;
        boolean special = link.specialParams() != null && !link.specialParams().isBlank();
        if (style == Style.SHORT_LINK || link.shortCode() == null || special) {
            return link.fullUrl();
        }
        String url = STATUS_PAGE + "?code=" + URLEncoder.encode(link.shortCode(), StandardCharsets.UTF_8);
        if (link.shop() != null && !link.shop().isBlank()) {
            url += "&shop=" + URLEncoder.encode(link.shop(), StandardCharsets.UTF_8);
        }
        return url;
    }

    /** Why this link is given as the short link although the style says the status page; null if it is not. */
    public String shortLinkBecause(VendorOrderLedger.PartnerLink link) {
        if (link == null || style == Style.SHORT_LINK) return null;
        if (link.shortCode() == null) return "no short code was returned";
        if (link.specialParams() != null && !link.specialParams().isBlank()) {
            return "it came with specialParams, which the status page has no documented way to carry";
        }
        return null;
    }
}
