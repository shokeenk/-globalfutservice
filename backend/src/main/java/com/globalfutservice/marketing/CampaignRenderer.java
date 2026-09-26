package com.globalfutservice.marketing;

import com.globalfutservice.config.AppProperties;
import com.globalfutservice.notify.email.EmailTemplate;
import com.globalfutservice.notify.email.PromotionalEmail;
import com.globalfutservice.notify.email.TransactionalEmails;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Turns a stored campaign into a message, and builds the links inside it.
 *
 * <p>Separated from the service because both the preview and the send have to produce
 * byte-identical output — an admin approving a preview that differs from what goes out is
 * worse than no preview at all — and because every URL in a campaign is built from
 * configuration rather than written down. A staging campaign cannot link to production,
 * and a change of public URL does not orphan the unsubscribe link in mail already sent.
 */
@Component
public class CampaignRenderer {

    private final AppProperties props;

    public CampaignRenderer(AppProperties props) {
        this.props = props;
    }

    /**
     * @param recipientToken the row this copy is addressed to, so its button can be
     *                       counted; null for a preview, whose button links straight to
     *                       the destination and records nothing
     */
    public TransactionalEmails.Rendered render(CampaignEntity c, String unsubscribeUrl,
                                               String pixelUrl, UUID recipientToken) {
        String ctaUrl = ctaUrl(c, recipientToken);
        return PromotionalEmail.render(
                new PromotionalEmail.Campaign(c.getSubject(), c.getHeroKicker(), c.getHeading(),
                        c.getHeroSubline(), c.getBody(), c.getOfferText(),
                        // Kept on the campaign but left out of the email when switched off.
                        c.isShowPromoCode() ? c.getPromoCode() : null,
                        c.getOfferValidUntil(), c.getCtaText(), ctaUrl, bannerUrl(c)),
                brand(),
                new PromotionalEmail.Delivery(unsubscribeUrl, pixelUrl));
    }

    /**
     * The copy one recipient receives, with the campaign's tracking switch applied.
     *
     * <p>The one place that decision is made. With tracking on, the message carries the
     * recipient's open pixel and its button goes through the click counter; with it off,
     * neither — the button links straight to the page and nothing reports back. Deciding
     * it here rather than at the call site means a new caller cannot forget the switch.
     */
    public TransactionalEmails.Rendered forRecipient(CampaignEntity c, String unsubscribeUrl,
                                                     UUID recipientToken) {
        boolean track = c.isTrackingEnabled();
        return render(c, unsubscribeUrl,
                track ? pixelUrl(recipientToken) : null,
                track ? recipientToken : null);
    }

    /**
     * The preview an admin approves.
     *
     * <p>Same renderer, inert links. Previewing must not be able to unsubscribe the person
     * doing the previewing, and must not record an open against a real recipient.
     */
    public TransactionalEmails.Rendered preview(CampaignEntity c) {
        return render(c, props.publicUrl() + "/unsubscribe?preview=1", null, null);
    }

    /**
     * The builder's live preview: what the fields currently on screen would send.
     *
     * <p>Rendered through the same template as a real send, so the preview cannot drift
     * from what goes out. Inert in the same ways {@link #preview} is — no pixel, the button
     * links straight to the page, the unsubscribe link is a dummy — because a preview must
     * never record anything against a real recipient.
     *
     * <p>Rendered while the admin is still typing, so an empty headline shows a
     * placeholder rather than an empty hero; every other empty field is simply absent,
     * exactly as it would be in the sent email.
     *
     * @param bannerUrl the draft's banner, if it has one yet, or null
     */
    public TransactionalEmails.Rendered previewOf(CampaignDetails d, String bannerUrl) {
        return previewOf(d, null, null, bannerUrl);
    }

    /** As above, with the second step's optional lines above and below the headline. */
    public TransactionalEmails.Rendered previewOf(CampaignDetails d, String kicker,
                                                  String subline, String bannerUrl) {
        CampaignType type = d.type() == null ? CampaignType.GENERAL : d.type();
        String heading = d.promoTitle() == null || d.promoTitle().isBlank()
                ? "Your promo title" : d.promoTitle();
        return PromotionalEmail.render(
                new PromotionalEmail.Campaign(nz(d.subject()), kicker, heading, subline,
                        d.description(), d.offerText(),
                        d.showPromoCode() ? d.promoCode() : null,
                        d.offerValidUntil(),
                        d.showButton() ? CampaignType.BUTTON_TEXT : null,
                        d.showButton() ? props.publicUrl() + type.buttonPath() : null,
                        bannerUrl),
                brand(),
                new PromotionalEmail.Delivery(props.publicUrl() + "/unsubscribe?preview=1", null));
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    public String unsubscribeUrl(UUID accountToken, String campaignPublicId) {
        return props.publicUrl() + "/unsubscribe?t=" + accountToken + "&c=" + campaignPublicId;
    }

    /** Served through the storefront origin, which proxies /api — see nginx.conf.template. */
    public String pixelUrl(UUID recipientToken) {
        return props.publicUrl() + "/api/v1/marketing/o/" + recipientToken + ".gif";
    }

    /**
     * Where a campaign button actually points.
     *
     * <p>For a real recipient it goes through the counter, which records the click and
     * then redirects; a click is the only one of the three tracked signals that means
     * something unambiguous, and it is worthless if the button bypasses it. A preview has
     * no recipient row to count against, so it links direct.
     */
    private String ctaUrl(CampaignEntity c, UUID recipientToken) {
        if (c.getCtaPath() == null) {
            return null;
        }
        return recipientToken == null
                ? props.publicUrl() + c.getCtaPath()
                : clickUrl(recipientToken);
    }

    /** Counts the click, then redirects to {@link #ctaUrlFor}. */
    public String clickUrl(UUID recipientToken) {
        return props.publicUrl() + "/api/v1/marketing/c/" + recipientToken;
    }

    /**
     * The banner's address, versioned by the campaign's last edit.
     *
     * <p>The banner endpoint is cached for 30 days, and it used to be addressed by the
     * campaign alone — so replacing a banner left every cache, including the admin's own
     * preview, showing the old one for a month. The version changes whenever the campaign
     * is edited, which is more often than the banner changes; a spare re-fetch is cheap and
     * a stale banner in a sent campaign is not.
     */
    public String bannerUrl(CampaignEntity c) {
        return c.hasBanner()
                ? props.publicUrl() + "/api/v1/marketing/campaigns/" + c.getPublicId() + "/banner"
                        + "?v=" + c.getUpdatedAt().toEpochMilli()
                : null;
    }

    public String ctaUrlFor(CampaignEntity c) {
        return c.getCtaPath() == null ? props.publicUrl() : props.publicUrl() + c.getCtaPath();
    }

    private EmailTemplate.Brand brand() {
        String website = props.publicUrl();
        String host = website.replaceFirst("^https?://", "")
                .replaceFirst("^www\\.", "")
                .replaceFirst("/.*$", "");
        String instagram = props.instagramUrl().replaceAll("/+$", "");
        return new EmailTemplate.Brand(
                website, host,
                props.discordInvite(), "Join the GFS Discord",
                props.instagramUrl(), "@" + instagram.substring(instagram.lastIndexOf('/') + 1));
    }
}
