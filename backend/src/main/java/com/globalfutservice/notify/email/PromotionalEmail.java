package com.globalfutservice.notify.email;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A promotional campaign, in the layout of the redesigned campaign builder.
 *
 * <p><b>Its own layout, not the order emails' shell.</b> The two used to share one
 * template. The reference design for promotions is a different object — a white header
 * with the mark and slogan, a hero with the headline beside the banner, the offer and code
 * beside the button — and forcing it through the transactional shell would have meant
 * redesigning every order email as a side effect. The order emails are untouched;
 * {@link EmailTemplate#esc} is shared so both escape identically.
 *
 * <p><b>Built for mail clients, not browsers.</b> Tables and inline styles throughout;
 * the single {@code <style>} block holds only the phone-width stacking, which cannot be
 * written inline, and a client that drops it still gets a readable desktop layout. No web
 * fonts — Gmail does not load them — so the heavy headline asks for Arial Black and falls
 * back to bold Arial. Rounded corners are decoration: Outlook squares them and nothing
 * depends on them.
 *
 * <p><b>Images, and what happens when they are blocked.</b> The mark, the badge icons and
 * the social icons are PNGs served from the storefront, because no mail client renders
 * SVG. Outlook blocks remote images until the reader allows them, so every image either
 * sits beside the words it illustrates or carries alt text that stands in for it, and the
 * mark has the business's name set in type beneath it.
 *
 * <p><b>What it will not say.</b> The trust badges use wording the storefront already
 * publishes. The reference's "100% Safe and Secure" and "Your Ultimate FC Partner" are
 * stronger claims than the site makes, and a promotional email is not the place to
 * introduce one.
 */
public final class PromotionalEmail {

    private static final String RED = "#DB1825";
    private static final String INK = "#111318";
    private static final String BODY_TEXT = "#3A3D44";
    private static final String MUTED = "#5B606A";
    private static final String LINE = "#E7E9EE";
    private static final String PAGE = "#F4F6FA";
    private static final String HERO = "#F6F6F8";
    private static final String PINK = "#FDE8EA";
    private static final String PINK_EDGE = "#F3CDD2";

    private static final String FONT = "Arial,Helvetica,sans-serif";
    /** Asked for, not relied on: Gmail and most phones fall back to bold Arial. */
    private static final String HEAVY = "'Arial Black','Arial Bold',Arial,Helvetica,sans-serif";

    private static final DateTimeFormatter DAY =
            DateTimeFormatter.ofPattern("d MMM uuuu", Locale.ENGLISH);

    /** "15% OFF" → "15%" large and "OFF" beneath it, as the reference draws it. */
    private static final Pattern ENDS_IN_OFF = Pattern.compile("(?i)^(.*\\S)\\s+(off)$");

    /** The storefront's own wording for the three badges; see the class comment. */
    private static final String[][] BADGES = {
            {"trust-shield.png", "100%", "Safety policy"},
            {"trust-bolt.png", "Fast", "Delivery"},
            {"trust-users.png", "Trusted by", "Thousands"},
    };

    /**
     * What the admin wrote, resolved against the campaign's switches.
     *
     * @param kicker          short line above the headline, or null
     * @param heading         the headline; its last word is set in red
     * @param subline         line beneath the headline, or null
     * @param body            the offer description; blank lines separate paragraphs
     * @param offerText       e.g. "15% OFF", or null
     * @param promoCode       the code to show, or null when there is none or it is hidden
     * @param offerValidUntil the offer's last day, or null
     * @param ctaText         button label, or null for no button
     * @param ctaUrl          button destination
     * @param bannerUrl       the hero image, or null
     */
    public record Campaign(String subject, String kicker, String heading, String subline,
                           String body, String offerText, String promoCode,
                           LocalDate offerValidUntil, String ctaText, String ctaUrl,
                           String bannerUrl) {
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

    public static TransactionalEmails.Rendered render(Campaign c, EmailTemplate.Brand brand,
                                                      Delivery delivery) {
        if (delivery.unsubscribeUrl() == null || delivery.unsubscribeUrl().isBlank()) {
            // Refused rather than defaulted. A campaign that reaches customers without a
            // way out is the one failure in this system that cannot be undone afterwards.
            throw new IllegalArgumentException(
                    "A promotional email must carry an unsubscribe link.");
        }
        String assets = trimSlash(brand.websiteUrl());

        StringBuilder b = new StringBuilder(12_288);
        head(b);
        b.append("<body style=\"margin:0;padding:0;background:").append(PAGE)
         .append(";-webkit-font-smoothing:antialiased;\">\n");
        // Preheader: in the DOM, invisible on screen, read by the inbox list.
        b.append("<div style=\"display:none;max-height:0;overflow:hidden;opacity:0;\">")
         .append(EmailTemplate.esc(preheaderFrom(c))).append("</div>\n");

        b.append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" ")
         .append("border=\"0\" style=\"background:").append(PAGE).append(";\"><tr>")
         .append("<td align=\"center\" class=\"gfs-outer\" style=\"padding:24px 12px;\">\n")
         // width="600" (below) is the width Outlook reads; the style is what every other
         // client reads. A fixed width:600px made the email 624px wide inside a 375px
         // phone, so it had to be scrolled sideways or shrunk to fit.
         .append("<table role=\"presentation\" width=\"600\" cellpadding=\"0\" cellspacing=\"0\" ")
         .append("border=\"0\" style=\"width:100%;max-width:600px;background:#FFFFFF;")
         .append("border:1px solid ").append(LINE).append(";border-radius:12px;\">\n");

        header(b, assets);
        hero(b, c, assets);
        body(b, c.body());
        offer(b, c);
        validUntil(b, c.offerValidUntil());
        socials(b, brand, assets);
        signature(b, brand);
        legal(b, delivery.unsubscribeUrl());

        b.append("</table>\n</td></tr></table>\n");
        if (delivery.pixelUrl() != null && !delivery.pixelUrl().isBlank()) {
            b.append("<img src=\"").append(EmailTemplate.esc(delivery.pixelUrl()))
             .append("\" width=\"1\" height=\"1\" alt=\"\" ")
             .append("style=\"display:block;width:1px;height:1px;border:0;\"/>\n");
        }
        b.append("</body>\n</html>");

        return new TransactionalEmails.Rendered(c.subject(), b.toString(), text(c, delivery));
    }

    // ---- sections -----------------------------------------------------------

    private static void head(StringBuilder b) {
        b.append("<!DOCTYPE html PUBLIC \"-//W3C//DTD XHTML 1.0 Transitional//EN\" ")
         .append("\"http://www.w3.org/TR/xhtml1/DTD/xhtml1-transitional.dtd\">\n")
         .append("<html xmlns=\"http://www.w3.org/1999/xhtml\" lang=\"en\">\n<head>\n")
         .append("<meta charset=\"utf-8\"/>\n")
         .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"/>\n")
         .append("<meta name=\"x-apple-disable-message-reformatting\"/>\n")
         .append("<meta name=\"color-scheme\" content=\"light\"/>\n")
         .append("<title>Global FUT Services</title>\n")
         .append("<style>\n@media only screen and (max-width:600px){\n")
         .append("  .gfs-col{display:block !important;width:100% !important;")
         .append("padding-left:0 !important;padding-right:0 !important;}\n")
         .append("  .gfs-gap{padding-top:14px !important;}\n")
         .append("  .gfs-pad{padding-left:20px !important;padding-right:20px !important;}\n")
         .append("  .gfs-hide{display:none !important;}\n")
         .append("  .gfs-h1{font-size:34px !important;line-height:38px !important;}\n")
         .append("  .gfs-outer{padding:12px 6px !important;}\n")
         // Three badges have to fit a column about 310px wide on a small phone.
         .append("  .gfs-badge-icon{padding-right:4px !important;}\n")
         .append("  .gfs-badge-icon img{width:18px !important;height:18px !important;}\n")
         .append("  .gfs-badge-label{font-size:9px !important;padding-right:8px !important;}\n")
         .append("}\n</style>\n</head>\n");
    }

    /**
     * The mark in the middle and the slogan on the right, with an empty cell on the left
     * of the same width so the mark sits on the centre line. The slogan is dropped on a
     * phone, where there is no room beside the mark.
     */
    private static void header(StringBuilder b, String assets) {
        b.append("<tr><td style=\"padding:16px 24px 12px 24px;border-bottom:1px solid #EEF0F3;\" ")
         .append("class=\"gfs-pad\">\n")
         .append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" ")
         .append("border=\"0\"><tr>\n")
         .append("<td width=\"30%\" class=\"gfs-hide\">&nbsp;</td>\n")
         .append("<td align=\"center\" valign=\"middle\">")
         .append("<img src=\"").append(EmailTemplate.esc(assets)).append("/brand/gfs-mark-128.png\" ")
         .append("width=\"44\" height=\"44\" alt=\"Global FUT Services\" ")
         .append("style=\"display:block;margin:0 auto;border:0;width:44px;height:44px;")
         .append("font-family:").append(FONT).append(";font-size:12px;color:").append(RED).append(";\"/>")
         .append("<div style=\"font-family:").append(FONT).append(";font-size:9px;font-weight:700;")
         .append("letter-spacing:3px;color:").append(INK).append(";padding-top:6px;\">")
         .append("GLOBAL FUT SERVICES</div></td>\n")
         .append("<td width=\"30%\" align=\"right\" valign=\"middle\" class=\"gfs-hide\">")
         .append("<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" ")
         .append("align=\"right\"><tr>")
         .append("<td style=\"font-family:").append(FONT).append(";font-size:10px;line-height:17px;")
         .append("letter-spacing:2.5px;color:").append(MUTED).append(";text-align:right;")
         .append("padding-right:10px;\">PLAY MORE<br/>WORRY LESS</td>")
         .append("<td width=\"2\" style=\"width:2px;background:").append(RED)
         .append(";font-size:0;line-height:0;\">&nbsp;</td>")
         .append("</tr></table></td>\n")
         .append("</tr></table>\n</td></tr>\n");
    }

    /**
     * Kicker, headline, subline and badges on the left; the banner on the right. Without a
     * banner the copy takes the full width rather than leaving half the hero empty.
     */
    private static void hero(StringBuilder b, Campaign c, String assets) {
        boolean banner = present(c.bannerUrl());
        b.append("<tr><td class=\"gfs-pad\" style=\"background:").append(HERO)
         .append(";padding:26px 24px 24px 24px;\">\n")
         .append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" ")
         .append("border=\"0\"><tr>\n")
         .append("<td class=\"gfs-col\" valign=\"middle\"")
         .append(banner ? " width=\"60%\" style=\"padding-right:14px;\"" : "").append(">\n");

        if (present(c.kicker())) {
            b.append("<div style=\"font-family:").append(FONT).append(";font-size:12px;font-weight:700;")
             .append("letter-spacing:3px;color:").append(RED).append(";padding-bottom:8px;\">")
             .append(EmailTemplate.esc(upper(c.kicker()))).append("</div>\n");
        }
        b.append("<div class=\"gfs-h1\" style=\"font-family:").append(HEAVY)
         .append(";font-size:40px;line-height:44px;font-weight:900;color:").append(INK).append(";\">")
         .append(twoTone(c.heading())).append("</div>\n");
        if (present(c.subline())) {
            b.append("<div style=\"font-family:").append(FONT).append(";font-size:12px;font-weight:700;")
             .append("letter-spacing:3.5px;color:").append(INK).append(";padding-top:10px;\">")
             .append(EmailTemplate.esc(upper(c.subline()))).append("</div>\n");
        }
        badges(b, assets);
        b.append("</td>\n");

        if (banner) {
            b.append("<td class=\"gfs-col gfs-gap\" valign=\"middle\" align=\"center\" width=\"40%\">")
             .append("<img src=\"").append(EmailTemplate.esc(c.bannerUrl())).append("\" width=\"220\" alt=\"\" ")
             .append("style=\"display:block;width:100%;max-width:220px;height:auto;border:0;")
             .append("border-radius:8px;margin:0 auto;\"/></td>\n");
        }
        b.append("</tr></table>\n</td></tr>\n");
    }

    private static void badges(StringBuilder b, String assets) {
        b.append("<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" ")
         .append("style=\"margin-top:20px;\"><tr>\n");
        for (String[] badge : BADGES) {
            b.append("<td valign=\"middle\" class=\"gfs-badge-icon\" style=\"padding-right:7px;\">")
             .append("<img src=\"").append(EmailTemplate.esc(assets)).append("/email/").append(badge[0])
             .append("\" width=\"22\" height=\"22\" alt=\"\" style=\"display:block;border:0;")
             .append("width:22px;height:22px;\"/></td>\n")
             .append("<td valign=\"middle\" class=\"gfs-badge-label\" style=\"font-family:").append(FONT)
             .append(";font-size:10px;line-height:13px;font-weight:700;color:").append(INK)
             .append(";padding-right:14px;white-space:nowrap;\">")
             .append(upper(badge[1])).append("<br/>").append(upper(badge[2])).append("</td>\n");
        }
        b.append("</tr></table>\n");
    }

    private static void body(StringBuilder b, String body) {
        if (!present(body)) {
            return;
        }
        b.append("<tr><td class=\"gfs-pad\" style=\"padding:20px 24px 2px 24px;font-family:")
         .append(FONT).append(";font-size:14px;line-height:22px;color:").append(BODY_TEXT).append(";\">\n");
        for (String paragraph : body.strip().split("\\n\\s*\\n")) {
            if (paragraph.isBlank()) {
                continue;
            }
            // Single line breaks inside a paragraph are kept, as the admin typed them.
            b.append("<p style=\"margin:0 0 12px 0;\">")
             .append(EmailTemplate.esc(paragraph.strip()).replace("\n", "<br/>"))
             .append("</p>\n");
        }
        b.append("</td></tr>\n");
    }

    /**
     * The offer and code as one pill, and the button beside it — or whichever of the three
     * the campaign actually has. Nothing is drawn as an empty box.
     */
    private static void offer(StringBuilder b, Campaign c) {
        boolean offer = present(c.offerText());
        boolean code = present(c.promoCode());
        boolean button = present(c.ctaText()) && present(c.ctaUrl());
        if (!offer && !code && !button) {
            return;
        }
        boolean pill = offer || code;

        b.append("<tr><td class=\"gfs-pad\" style=\"padding:16px 24px 4px 24px;\">\n")
         .append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" ")
         .append("border=\"0\"><tr>\n");

        if (pill) {
            b.append("<td class=\"gfs-col\" valign=\"middle\"")
             .append(button ? " width=\"58%\" style=\"padding-right:12px;\"" : "").append(">\n")
             .append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" ")
             .append("border=\"0\" style=\"border:1px solid ").append(PINK_EDGE)
             .append(";border-radius:10px;\"><tr>\n");
            if (offer) {
                b.append("<td align=\"center\" valign=\"middle\"").append(code ? " width=\"42%\"" : "")
                 .append(" style=\"background:").append(PINK).append(";border-radius:10px;padding:12px 10px;\">")
                 .append(offerFigure(c.offerText())).append("</td>\n");
            }
            if (code) {
                b.append("<td valign=\"middle\" style=\"padding:12px 16px;\">")
                 .append("<div style=\"font-family:").append(FONT).append(";font-size:11px;font-weight:700;")
                 .append("letter-spacing:1px;color:").append(INK).append(";\">USE CODE</div>")
                 .append("<div style=\"font-family:").append(HEAVY).append(";font-size:22px;line-height:26px;")
                 .append("font-weight:900;letter-spacing:0.5px;color:").append(INK).append(";padding-top:2px;\">")
                 .append(EmailTemplate.esc(upper(c.promoCode()))).append("</div></td>\n");
            }
            b.append("</tr></table>\n</td>\n");
        }

        if (button) {
            b.append("<td class=\"gfs-col").append(pill ? " gfs-gap" : "").append("\" valign=\"middle\"")
             .append(pill ? " width=\"42%\"" : " align=\"center\"").append(">\n")
             .append("<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" ")
             .append(pill ? "width=\"100%\"" : "width=\"280\" align=\"center\"").append("><tr>")
             .append("<td align=\"center\" bgcolor=\"").append(RED).append("\" style=\"background:")
             .append(RED).append(";border-radius:8px;\">")
             .append("<a href=\"").append(EmailTemplate.esc(c.ctaUrl())).append("\" ")
             .append("style=\"display:block;padding:18px 12px;font-family:").append(FONT)
             .append(";font-size:15px;font-weight:700;letter-spacing:1px;color:#FFFFFF;")
             .append("text-decoration:none;\">")
             .append(EmailTemplate.esc(upper(c.ctaText()))).append(" &rarr;</a>")
             .append("</td></tr></table>\n</td>\n");
        }
        b.append("</tr></table>\n</td></tr>\n");
    }

    private static String offerFigure(String offerText) {
        String text = offerText.strip();
        Matcher m = ENDS_IN_OFF.matcher(text);
        if (m.matches()) {
            return "<div style=\"font-family:" + HEAVY + ";font-size:30px;line-height:32px;"
                    + "font-weight:900;color:" + RED + ";\">" + EmailTemplate.esc(upper(m.group(1)))
                    + "</div><div style=\"font-family:" + FONT + ";font-size:13px;font-weight:700;"
                    + "letter-spacing:1px;color:" + RED + ";padding-top:2px;\">OFF</div>";
        }
        // Anything else is shown as written, smaller when it is long.
        int size = text.length() <= 8 ? 26 : 18;
        return "<div style=\"font-family:" + HEAVY + ";font-size:" + size + "px;line-height:"
                + (size + 4) + "px;font-weight:900;color:" + RED + ";\">"
                + EmailTemplate.esc(upper(text)) + "</div>";
    }

    private static void validUntil(StringBuilder b, LocalDate until) {
        if (until == null) {
            return;
        }
        b.append("<tr><td class=\"gfs-pad\" style=\"padding:8px 24px 0 24px;font-family:").append(FONT)
         .append(";font-size:12px;line-height:18px;color:").append(MUTED).append(";\">")
         .append("Offer valid till ").append(DAY.format(until)).append(".</td></tr>\n");
    }

    /**
     * Only the accounts the business actually has. The reference also draws YouTube and X;
     * there are no such accounts to link to, and an icon that goes nowhere is worse than
     * no icon.
     */
    private static void socials(StringBuilder b, EmailTemplate.Brand brand, String assets) {
        StringBuilder icons = new StringBuilder();
        if (present(brand.instagramUrl())) {
            icons.append(socialIcon(brand.instagramUrl(), assets + "/email/social-instagram.png",
                    "Instagram", icons.isEmpty()));
        }
        if (present(brand.discordUrl())) {
            icons.append(socialIcon(brand.discordUrl(), assets + "/email/social-discord.png",
                    "Discord", icons.isEmpty()));
        }
        if (icons.isEmpty()) {
            return;
        }
        String rule = "<td width=\"36%\" valign=\"middle\"><div style=\"height:1px;line-height:1px;"
                + "font-size:0;background:" + LINE + ";\">&nbsp;</div></td>\n";
        b.append("<tr><td style=\"padding:28px 24px 6px 24px;\">\n")
         .append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" ")
         .append("border=\"0\"><tr>\n").append(rule)
         .append("<td align=\"center\" valign=\"middle\" style=\"padding:0 12px;white-space:nowrap;\">")
         .append(icons).append("</td>\n").append(rule)
         .append("</tr></table>\n</td></tr>\n");
    }

    private static String socialIcon(String href, String src, String name, boolean first) {
        return "<a href=\"" + EmailTemplate.esc(href) + "\" style=\"display:inline-block;"
                + (first ? "" : "margin-left:16px;") + "text-decoration:none;color:" + INK + ";\">"
                + "<img src=\"" + EmailTemplate.esc(src) + "\" width=\"20\" height=\"20\" alt=\""
                + name + "\" style=\"display:block;border:0;width:20px;height:20px;font-family:"
                + FONT + ";font-size:11px;color:" + INK + ";\"/></a>";
    }

    private static void signature(StringBuilder b, EmailTemplate.Brand brand) {
        b.append("<tr><td align=\"center\" style=\"padding:10px 24px 0 24px;font-family:").append(FONT)
         .append(";font-size:14px;font-weight:700;color:").append(INK).append(";\">")
         .append("Global FUT Services</td></tr>\n");
        if (present(brand.websiteUrl())) {
            b.append("<tr><td align=\"center\" style=\"padding:4px 24px 0 24px;font-family:").append(FONT)
             .append(";font-size:13px;\"><a href=\"").append(EmailTemplate.esc(brand.websiteUrl()))
             .append("\" style=\"color:").append(MUTED).append(";text-decoration:none;\">")
             .append(EmailTemplate.esc(brand.websiteLabel())).append("</a></td></tr>\n");
        }
    }

    /**
     * Why this email arrived, and how to stop it.
     *
     * <p>Raw HTML rather than escaped text because it carries a link. Everything
     * interpolated into it is a constant or a URL this application built.
     */
    private static void legal(StringBuilder b, String unsubscribeUrl) {
        b.append("<tr><td align=\"center\" class=\"gfs-pad\" style=\"padding:18px 36px 26px 36px;")
         .append("font-family:").append(FONT).append(";font-size:11px;line-height:17px;color:#6B7079;\">")
         .append("You are receiving this because you opted in to promotional email from ")
         .append("Global FUT Services. This is a marketing message — order updates are ")
         .append("sent separately and are not affected. ")
         .append("<a href=\"").append(EmailTemplate.esc(unsubscribeUrl))
         .append("\" style=\"color:#6B7079;text-decoration:underline;\">Unsubscribe</a>.")
         .append("</td></tr>\n");
    }

    // ---- text part ------------------------------------------------------------

    private static String text(Campaign c, Delivery delivery) {
        StringBuilder t = new StringBuilder();
        if (present(c.kicker())) {
            t.append(c.kicker().strip()).append("\n");
        }
        t.append(c.heading()).append("\n");
        if (present(c.subline())) {
            t.append(c.subline().strip()).append("\n");
        }
        if (present(c.body())) {
            t.append("\n").append(c.body().strip()).append("\n");
        }
        if (present(c.offerText())) {
            t.append("\n").append(c.offerText().strip()).append("\n");
        }
        if (present(c.promoCode())) {
            t.append(present(c.offerText()) ? "" : "\n")
             .append("Use code: ").append(c.promoCode().trim().toUpperCase(Locale.ROOT)).append("\n");
        }
        if (c.offerValidUntil() != null) {
            t.append("Offer valid till ").append(DAY.format(c.offerValidUntil())).append(".\n");
        }
        if (present(c.ctaText()) && present(c.ctaUrl())) {
            t.append("\n").append(c.ctaText()).append(": ").append(c.ctaUrl()).append("\n");
        }
        t.append("\n— Global FUT Services\n")
         .append("\nYou are receiving this because you opted in to promotional email. ")
         .append("Order updates are sent separately and are not affected.\n")
         .append("Unsubscribe: ").append(delivery.unsubscribeUrl()).append("\n");
        return t.toString();
    }

    // ---- helpers ----------------------------------------------------------------

    /**
     * The headline with its last word in red — "COINS <red>SALE</red>". A one-word
     * headline is red throughout.
     */
    static String twoTone(String heading) {
        String h = upper(heading == null ? "" : heading.strip());
        int split = h.lastIndexOf(' ');
        if (split < 0) {
            return "<span style=\"color:" + RED + ";\">" + EmailTemplate.esc(h) + "</span>";
        }
        return EmailTemplate.esc(h.substring(0, split)) + " <span style=\"color:" + RED + ";\">"
                + EmailTemplate.esc(h.substring(split + 1)) + "</span>";
    }

    /**
     * The inbox preview line, from the opening of the description rather than the
     * headline the reader can already see in the subject.
     */
    private static String preheaderFrom(Campaign c) {
        String source = present(c.body()) ? c.body() : c.heading();
        String first = source.strip().split("\n")[0].strip();
        return first.length() <= 120 ? first : first.substring(0, 117).strip() + "…";
    }

    private static String upper(String s) {
        return s.strip().toUpperCase(Locale.ROOT);
    }

    private static boolean present(String s) {
        return s != null && !s.isBlank();
    }

    private static String trimSlash(String url) {
        if (url == null) {
            return "";
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
