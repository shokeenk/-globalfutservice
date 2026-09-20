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
                new PromotionalEmail.Campaign(c.getSubject(), c.getHeading(), c.getBody(),
                        c.getPromoCode(), c.getCtaText(), ctaUrl, bannerUrl(c)),
                brand(),
                new PromotionalEmail.Delivery(unsubscribeUrl, pixelUrl));
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

    public String bannerUrl(CampaignEntity c) {
        return c.hasBanner()
                ? props.publicUrl() + "/api/v1/marketing/campaigns/" + c.getPublicId() + "/banner"
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
