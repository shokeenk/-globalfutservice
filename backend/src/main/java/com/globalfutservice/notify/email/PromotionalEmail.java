package com.globalfutservice.notify.email;

import java.util.List;

/**
 * A campaign, rendered into the same shell the order emails use.
 *
 * <p>Header, footer links and trust badges are identical to a transactional message —
 * that is the point of one template. What differs is everything below the banner, and one
 * thing below the footer: a promotional email carries an unsubscribe link and says plainly
 * that it is marketing. An order email carries neither, because there is nothing to
 * unsubscribe from.
 *
 * <p><b>The tracking pixel.</b> Rendered last, one transparent pixel, with the recipient's
 * own token. It is worth knowing what it can and cannot report: images are blocked by
 * default in most clients, so a missing open means nothing, and providers that pre-fetch
 * images on the customer's behalf produce an open the customer never caused. Clicks are
 * the honest signal. The pixel is included because the specification asks for opens, not
 * because the number deserves much weight.
 */
public final class PromotionalEmail {

    public record Campaign(String subject, String heading, String body, String promoCode,
                           String ctaText, String ctaUrl, String bannerUrl) {
    }

    /**
     * @param unsubscribeUrl where the footer's opt-out link goes. Required — a promotional
     *                       send without one is not something this class will render.
     * @param pixelUrl       open-tracking pixel, or null to omit tracking entirely
     */
    public record Delivery(String unsubscribeUrl, String pixelUrl) {
    }

    private PromotionalEmail() {
    }

    public static TransactionalEmails.Rendered render(Campaign campaign,
                                                      EmailTemplate.Brand brand,
                                                      Delivery delivery) {
        if (delivery.unsubscribeUrl() == null || delivery.unsubscribeUrl().isBlank()) {
            // Refused rather than defaulted. A campaign that reaches customers without a
            // way out is the one failure in this system that cannot be undone afterwards.
            throw new IllegalArgumentException(
                    "A promotional email must carry an unsubscribe link.");
        }

        StringBuilder body = new StringBuilder();
        if (campaign.bannerUrl() != null && !campaign.bannerUrl().isBlank()) {
            // Width-constrained and with alt text, because this is the one image in the
            // mail and a blocked image must still leave the message readable.
            body.append("<div style=\"padding:4px 0 18px 0;\">")
                .append("<img src=\"").append(EmailTemplate.esc(campaign.bannerUrl()))
                .append("\" alt=\"\" width=\"552\" ")
                .append("style=\"display:block;width:100%;max-width:552px;height:auto;")
                .append("border-radius:10px;\"/></div>\n");
        }
        body.append(EmailTemplate.paragraphs(campaign.body().split("\n\n")));

        if (campaign.promoCode() != null && !campaign.promoCode().isBlank()) {
            body.append("\n<div style=\"margin:18px 0 6px 0;padding:16px;border-radius:10px;")
                .append("border:2px dashed #E10B2E;text-align:center;\">")
                .append("<div style=\"font-size:11px;letter-spacing:2px;font-weight:700;")
                .append("color:#5A6070;\">USE CODE</div>")
                .append("<div style=\"font-size:24px;font-weight:800;letter-spacing:2px;")
                .append("color:#E10B2E;padding-top:4px;\">")
                .append(EmailTemplate.esc(campaign.promoCode().trim().toUpperCase()))
                .append("</div></div>\n");
        }

        EmailTemplate.Content content = new EmailTemplate.Content(
                preheaderFrom(campaign),
                null,
                campaign.heading(),
                null,
                List.of(),
                body.toString(),
                null,
                List.of(),
                null,
                campaign.ctaText(),
                campaign.ctaUrl(),
                footerNote(delivery));

        String html = EmailTemplate.render(content, brand);
        if (delivery.pixelUrl() != null && !delivery.pixelUrl().isBlank()) {
            html = html.replace("</body>",
                    "<img src=\"" + EmailTemplate.esc(delivery.pixelUrl())
                            + "\" width=\"1\" height=\"1\" alt=\"\" "
                            + "style=\"display:block;width:1px;height:1px;border:0;\"/>\n</body>");
        }

        return new TransactionalEmails.Rendered(campaign.subject(), html, text(campaign, delivery));
    }

    /**
     * The inbox preview line.
     *
     * <p>Taken from the opening of the body rather than repeating the heading, which the
     * reader can already see in the subject. Trimmed to something that fits the preview
     * strip rather than spilling the whole first paragraph into it.
     */
    private static String preheaderFrom(Campaign campaign) {
        String first = campaign.body().strip().split("\n")[0].strip();
        return first.length() <= 120 ? first : first.substring(0, 117).strip() + "…";
    }

    /**
     * Why this email arrived, and how to stop it.
     *
     * <p>Raw HTML rather than escaped text because it carries a link. It is the only
     * unescaped string in either renderer, and everything interpolated into it is either a
     * constant or a URL this application built.
     */
    private static String footerNote(Delivery delivery) {
        return "You are receiving this because you opted in to promotional email from "
                + "Global FUT Services. This is a marketing message — order updates are "
                + "sent separately and are not affected. "
                + "<a href=\"" + EmailTemplate.esc(delivery.unsubscribeUrl())
                + "\" style=\"color:#A7ADBB;text-decoration:underline;\">Unsubscribe</a>.";
    }

    private static String text(Campaign campaign, Delivery delivery) {
        StringBuilder t = new StringBuilder();
        t.append(campaign.heading()).append("\n\n").append(campaign.body().strip()).append("\n");
        if (campaign.promoCode() != null && !campaign.promoCode().isBlank()) {
            t.append("\nUse code: ").append(campaign.promoCode().trim().toUpperCase()).append("\n");
        }
        if (campaign.ctaText() != null && campaign.ctaUrl() != null) {
            t.append("\n").append(campaign.ctaText()).append(": ")
             .append(campaign.ctaUrl()).append("\n");
        }
        t.append("\n— Global FUT Services\n")
         .append("\nYou are receiving this because you opted in to promotional email. ")
         .append("Order updates are sent separately and are not affected.\n")
         .append("Unsubscribe: ").append(delivery.unsubscribeUrl()).append("\n");
        return t.toString();
    }
}
