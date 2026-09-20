package com.globalfutservice.notify.email;

import java.util.ArrayList;
import java.util.List;

/**
 * The branded shell every GFS customer email is rendered into.
 *
 * <p>One template, two kinds of content. The transactional emails fill it with an order
 * status and its next step; a promotional campaign fills the same shell with a headline,
 * a message and a CTA. Building two designs that have to be kept in visual sync by hand is
 * how the footer ends up saying different things in different emails.
 *
 * <p><b>Why tables and inline styles.</b> This is HTML email, not a web page. Outlook
 * renders through Word, Gmail strips {@code <style>} blocks in some contexts, and flexbox
 * is not reliably available anywhere. Every layout decision here is a table cell with
 * inline CSS because that is the only thing that renders the same in Gmail, Outlook,
 * Apple Mail and a phone.
 *
 * <p><b>Why the wordmark is text, not an image.</b> Most clients block remote images until
 * the reader allows them, and an email whose entire header is one blocked image opens as a
 * grey box. The mark is set in type so the email is legible the instant it opens, with no
 * asset hosting to keep alive and nothing to break when a CDN path changes.
 *
 * <p><b>Content the reference image carries that this does not.</b> "SINCE 2019" is not
 * claimed anywhere on the site and is not repeated here — an unverified founding year is
 * not something to publish to customers. "YOUR ULTIMATE FC PARTNER" is also left out,
 * which the specification explicitly allows for a line the site does not already use.
 *
 * <p>The header tagline and the service list <i>are</i> carried, because neither is
 * that kind of statement: "PLAY MORE · WORRY LESS" is a slogan already printed in the
 * trust bar at the foot of this same email, and the list names the three things the
 * site sells. The trust badges and the Instagram handle below are the wording the site
 * actually uses.
 *
 * <p>Pure string building, no Spring — so it can be rendered and eyeballed in a test
 * without booting anything.
 */
public final class EmailTemplate {

    // ---- palette, taken from the reference artwork --------------------------
    private static final String INK = "#0A0A0C";   // header and trust bar
    private static final String RED = "#E10B2E";   // accent, banner, buttons
    private static final String RED_DARK = "#A50920";
    private static final String PAPER = "#FFFFFF";
    private static final String MUTED_BG = "#F6F7F9";
    private static final String BORDER = "#E6E8EC";
    private static final String TEXT = "#1B1D23";
    private static final String TEXT_MUTED = "#5A6070";
    private static final String TEXT_ON_DARK = "#FFFFFF";
    private static final String TEXT_ON_DARK_MUTED = "#A7ADBB";

    private static final String FONT =
            "-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif";

    private EmailTemplate() {
    }

    /**
     * One row of the order-detail strip: an icon, a label, and a value.
     *
     * <p>The icon is a character, not an image, for the reason the wordmark is: a client
     * that blocks remote images would otherwise show a row of empty boxes. These are
     * chosen from the ranges that render in Outlook's font stack as well as on a phone,
     * which rules out most of the pictograms the reference artwork uses.
     */
    public record InfoCard(String icon, String label, String value, boolean accent) {
        public static InfoCard of(String icon, String label, String value) {
            return new InfoCard(icon, label, value, false);
        }

        /** Renders the value in brand red — for the status, which is the point of the email. */
        public static InfoCard accented(String icon, String label, String value) {
            return new InfoCard(icon, label, value, true);
        }
    }

    /** One numbered card in the "NEXT STEPS" block. */
    public record Step(String title, String body, String buttonText, String buttonUrl) {
        public static Step of(String title, String body) {
            return new Step(title, body, null, null);
        }
    }

    /** Where the footer points. Sourced from configuration, never hardcoded at the call site. */
    public record Brand(String websiteUrl, String websiteLabel,
                        String discordUrl, String discordLabel,
                        String instagramUrl, String instagramLabel) {
    }

    /**
     * Everything that varies between one email and the next.
     *
     * @param preheader   the grey line clients show beside the subject in the inbox list.
     *                    Worth setting: left empty, clients scrape the first text they find,
     *                    which is usually the wordmark.
     * @param statusIcon  a single character shown in a badge above the headline — a tick
     *                    for a confirmation, something quieter for a state that is still
     *                    in progress. Null for no badge.
     * @param eyebrow     small caps line above the headline, or null
     * @param headline    the main statement, rendered across two weights
     * @param intro       a sentence under the headline, or null
     * @param cards       the order-detail strip, may be empty
     * @param bodyHtml    free content under the strip, already escaped, or null
     * @param steps       the numbered "NEXT STEPS" block, may be empty
     * @param note        the grey info note under the steps, or null
     * @param ctaText     primary button label, or null for no button
     * @param ctaUrl      primary button destination
     * @param footerNote  a line above the footer links — used to say why this email arrived
     */
    public record Content(String preheader, String statusIcon, String eyebrow,
                          String headline, String intro,
                          List<InfoCard> cards, String bodyHtml, String stepsHeading,
                          List<Step> steps, String note, String ctaText, String ctaUrl,
                          String footerNote) {
    }

    public static String render(Content c, Brand brand) {
        StringBuilder b = new StringBuilder(8192);
        b.append("<!DOCTYPE html PUBLIC \"-//W3C//DTD XHTML 1.0 Transitional//EN\" ")
         .append("\"http://www.w3.org/TR/xhtml1/DTD/xhtml1-transitional.dtd\">\n")
         .append("<html xmlns=\"http://www.w3.org/1999/xhtml\" lang=\"en\">\n<head>\n")
         .append("<meta charset=\"utf-8\"/>\n")
         .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"/>\n")
         .append("<meta name=\"x-apple-disable-message-reformatting\"/>\n")
         .append("<meta name=\"color-scheme\" content=\"light\"/>\n")
         .append("<title>Global FUT Services</title>\n")
         // The one <style> block. Everything structural is inline; this only carries the
         // mobile stacking rules, which cannot be expressed inline at all. Clients that
         // drop it still get the desktop layout, which is readable on a phone.
         .append("<style>\n")
         .append("@media only screen and (max-width:600px){\n")
         .append("  .gfs-stack{display:block !important;width:100% !important;}\n")
         .append("  .gfs-stack td{display:block !important;width:100% !important;")
         .append("border-right:0 !important;}\n")
         .append("  .gfs-pad{padding-left:20px !important;padding-right:20px !important;}\n")
         .append("  .gfs-h1{font-size:28px !important;line-height:34px !important;}\n")
         .append("}\n</style>\n</head>\n");

        b.append("<body style=\"margin:0;padding:0;background:").append(INK)
         .append(";-webkit-font-smoothing:antialiased;\">\n");

        // Preheader: present in the DOM, invisible on screen, read by the inbox list.
        b.append("<div style=\"display:none;max-height:0;overflow:hidden;opacity:0;\">")
         .append(esc(nz(c.preheader()))).append("</div>\n");

        b.append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" ")
         .append("border=\"0\" style=\"background:").append(INK).append(";\"><tr><td align=\"center\" ")
         .append("style=\"padding:24px 12px;\">\n")
         .append("<table role=\"presentation\" width=\"600\" cellpadding=\"0\" cellspacing=\"0\" ")
         .append("border=\"0\" style=\"width:600px;max-width:600px;\">\n");

        header(b);
        banner(b, c);
        if (!c.cards().isEmpty()) {
            cards(b, c.cards());
        }
        if (c.bodyHtml() != null && !c.bodyHtml().isBlank()) {
            bodyBlock(b, c.bodyHtml());
        }
        if (c.ctaText() != null && c.ctaUrl() != null && c.steps().isEmpty()) {
            cta(b, c.ctaText(), c.ctaUrl());
        }
        if (!c.steps().isEmpty()) {
            steps(b, c.stepsHeading(), c.steps());
        }
        if (c.note() != null && !c.note().isBlank()) {
            note(b, c.note());
        }
        footerLinks(b, brand);
        trustBadges(b);
        signOff(b, c.footerNote());

        b.append("</table>\n</td></tr></table>\n</body>\n</html>");
        return b.toString();
    }

    // ---- sections -----------------------------------------------------------

    private static void header(StringBuilder b) {
        b.append("<tr><td align=\"center\" style=\"background:").append(INK)
         .append(";padding:28px 24px 22px 24px;border-radius:14px 14px 0 0;\">\n")
         .append("<div style=\"font-family:").append(FONT)
         .append(";font-size:38px;line-height:40px;font-weight:800;letter-spacing:-1px;color:")
         .append(TEXT_ON_DARK).append(";\">")
         .append("<span style=\"color:").append(RED).append(";\">G</span>FS</div>\n")
         .append("<div style=\"font-family:").append(FONT)
         .append(";font-size:11px;line-height:16px;letter-spacing:4px;font-weight:600;color:")
         .append(TEXT_ON_DARK_MUTED).append(";padding-top:6px;\">GLOBAL FUT SERVICES</div>\n")
         /*
          * The tagline and the service list, both from the reference artwork.
          *
          * Unlike "SINCE 2019" neither is a claim that could turn out to be untrue: one
          * is a slogan already printed in the trust bar at the foot of this same email,
          * and the other names the three things the site actually sells. A founding year
          * is checkable and was therefore left out; these are not the same kind of
          * statement.
          */
         .append("<div style=\"font-family:").append(FONT)
         .append(";font-size:12px;line-height:17px;letter-spacing:2.5px;font-weight:700;")
         .append("color:").append(RED).append(";padding-top:14px;\">")
         .append("PLAY MORE &#183; WORRY LESS</div>\n")
         .append("<div style=\"font-family:").append(FONT)
         .append(";font-size:10px;line-height:15px;letter-spacing:1.6px;font-weight:600;")
         .append("color:").append(TEXT_ON_DARK_MUTED).append(";padding-top:8px;\">")
         .append("EA FC COINS &#183; CHAMPS BOOSTING &#183; 1-TO-1 COACHING &amp; MORE</div>\n")
         .append("</td></tr>\n");
    }

    private static void banner(StringBuilder b, Content c) {
        b.append("<tr><td style=\"background:").append(RED)
         .append(";background-image:linear-gradient(135deg,").append(RED).append(" 0%,")
         .append(RED_DARK).append(" 100%);padding:34px 32px;\" class=\"gfs-pad\">\n");

        /*
         * The status badge: one character in a white disc.
         *
         * A table cell rather than a styled div, because Outlook drops border-radius on
         * a div and would render a white square. The character is centred with
         * line-height for the same reason -- there is no flexbox here.
         */
        if (c.statusIcon() != null && !c.statusIcon().isBlank()) {
            b.append("<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\"")
             .append(" style=\"margin-bottom:16px;\"><tr>")
             .append("<td align=\"center\" valign=\"middle\" width=\"46\" height=\"46\"")
             .append(" style=\"width:46px;height:46px;background:#FFFFFF;border-radius:23px;")
             .append("font-family:").append(FONT)
             .append(";font-size:24px;line-height:46px;font-weight:800;color:").append(RED)
             .append(";\">").append(esc(c.statusIcon()))
             .append("</td></tr></table>\n");
        }
        if (c.eyebrow() != null && !c.eyebrow().isBlank()) {
            b.append("<div style=\"font-family:").append(FONT)
             .append(";font-size:11px;letter-spacing:3px;font-weight:700;color:#FFD7DE;")
             .append("padding-bottom:10px;\">").append(esc(c.eyebrow())).append("</div>\n");
        }
        b.append("<div class=\"gfs-h1\" style=\"font-family:").append(FONT)
         .append(";font-size:32px;line-height:38px;font-weight:800;color:").append(TEXT_ON_DARK)
         .append(";margin:0;\">").append(esc(c.headline())).append("</div>\n");
        if (c.intro() != null && !c.intro().isBlank()) {
            b.append("<div style=\"font-family:").append(FONT)
             .append(";font-size:15px;line-height:23px;color:#FFE3E8;padding-top:12px;\">")
             .append(esc(c.intro())).append("</div>\n");
        }
        b.append("</td></tr>\n");
    }

    private static void cards(StringBuilder b, List<InfoCard> cards) {
        b.append("<tr><td style=\"background:").append(PAPER)
         .append(";padding:24px 24px 8px 24px;\" class=\"gfs-pad\">\n")
         .append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" ")
         .append("border=\"0\" class=\"gfs-stack\"><tr>\n");
        for (int i = 0; i < cards.size(); i++) {
            InfoCard card = cards.get(i);
            boolean last = i == cards.size() - 1;
            b.append("<td valign=\"top\" style=\"padding:6px 14px 14px 0;")
             .append(last ? "" : "border-right:1px solid " + BORDER + ";")
             .append("\">\n")
             .append("<div style=\"font-family:").append(FONT)
             .append(";font-size:12px;letter-spacing:.5px;color:").append(TEXT_MUTED)
             .append(";\">")
             // The icon sits inside the label line so the two never wrap apart.
             .append(card.icon() == null ? "" : "<span style=\"color:" + RED
                     + ";font-weight:700;padding-right:6px;\">" + esc(card.icon()) + "</span>")
             .append(esc(card.label())).append("</div>\n")
             .append("<div style=\"font-family:").append(FONT)
             .append(";font-size:17px;line-height:24px;font-weight:700;padding-top:4px;color:")
             .append(card.accent() ? RED : TEXT).append(";\">")
             .append(esc(card.value())).append("</div>\n</td>\n");
        }
        b.append("</tr></table>\n</td></tr>\n");
    }

    private static void bodyBlock(StringBuilder b, String html) {
        b.append("<tr><td style=\"background:").append(PAPER)
         .append(";padding:8px 24px 4px 24px;font-family:").append(FONT)
         .append(";font-size:15px;line-height:24px;color:").append(TEXT)
         .append(";\" class=\"gfs-pad\">\n").append(html).append("\n</td></tr>\n");
    }

    private static void cta(StringBuilder b, String text, String url) {
        b.append("<tr><td align=\"center\" style=\"background:").append(PAPER)
         .append(";padding:14px 24px 30px 24px;\" class=\"gfs-pad\">\n")
         .append(button(text, url))
         .append("</td></tr>\n");
    }

    /**
     * A button that is a table cell, not an {@code <a>} with padding.
     *
     * <p>Outlook ignores padding on inline elements, so a styled anchor collapses to
     * underlined text there. The link still wraps the label so the whole thing is
     * clickable everywhere else.
     */
    private static String button(String text, String url) {
        return "<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\">"
                + "<tr><td align=\"center\" style=\"background:" + RED + ";border-radius:8px;\">"
                + "<a href=\"" + esc(url) + "\" style=\"display:inline-block;padding:15px 34px;"
                + "font-family:" + FONT + ";font-size:14px;font-weight:700;letter-spacing:1px;"
                + "color:#FFFFFF;text-decoration:none;border-radius:8px;\">"
                + esc(text) + "</a></td></tr></table>";
    }

    private static void steps(StringBuilder b, String heading, List<Step> steps) {
        b.append("<tr><td style=\"background:").append(PAPER)
         .append(";padding:8px 24px 6px 24px;\" class=\"gfs-pad\">\n")
         .append("<div style=\"border-top:1px solid ").append(BORDER)
         .append(";padding-top:24px;\"></div>\n")
         .append("<div align=\"center\" style=\"font-family:").append(FONT)
         .append(";font-size:26px;font-weight:800;color:").append(TEXT).append(";\">")
         .append(esc(nz(heading))).append("</div>\n")
         .append("<div align=\"center\" style=\"font-family:").append(FONT)
         .append(";font-size:14px;color:").append(TEXT_MUTED)
         .append(";padding:6px 0 18px 0;\">Please follow the steps below to complete your order.")
         .append("</div>\n")
         .append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" ")
         .append("border=\"0\" class=\"gfs-stack\"><tr>\n");

        for (int i = 0; i < steps.size(); i++) {
            Step s = steps.get(i);
            b.append("<td valign=\"top\" width=\"33%\" style=\"padding:0 6px 12px 6px;\">\n")
             .append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" ")
             .append("border=\"0\" style=\"background:").append(MUTED_BG)
             .append(";border-radius:12px;\"><tr><td style=\"padding:18px 16px;\">\n")
             .append("<div style=\"font-family:").append(FONT)
             .append(";font-size:13px;font-weight:800;color:#FFFFFF;background:").append(RED)
             .append(";width:26px;height:26px;line-height:26px;border-radius:13px;")
             .append("text-align:center;\">").append(i + 1).append("</div>\n")
             .append("<div style=\"font-family:").append(FONT)
             .append(";font-size:15px;line-height:21px;font-weight:700;color:").append(TEXT)
             .append(";padding:12px 0 6px 0;\">").append(esc(s.title())).append("</div>\n")
             .append("<div style=\"font-family:").append(FONT)
             .append(";font-size:13px;line-height:20px;color:").append(TEXT_MUTED).append(";\">")
             .append(esc(s.body())).append("</div>\n");
            if (s.buttonText() != null && s.buttonUrl() != null) {
                b.append("<div style=\"padding-top:14px;\">")
                 .append(button(s.buttonText(), s.buttonUrl())).append("</div>\n");
            }
            b.append("</td></tr></table>\n</td>\n");
        }
        b.append("</tr></table>\n</td></tr>\n");
    }

    private static void note(StringBuilder b, String text) {
        b.append("<tr><td style=\"background:").append(PAPER)
         .append(";padding:6px 24px 28px 24px;\" class=\"gfs-pad\">\n")
         .append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" ")
         .append("border=\"0\" style=\"background:").append(MUTED_BG)
         .append(";border-radius:10px;\"><tr><td style=\"padding:14px 16px;font-family:")
         .append(FONT).append(";font-size:13px;line-height:20px;color:").append(TEXT_MUTED)
         .append(";\">").append(esc(text)).append("</td></tr></table>\n</td></tr>\n");
    }

    private static void footerLinks(StringBuilder b, Brand brand) {
        b.append("<tr><td style=\"background:").append(PAPER)
         .append(";border-top:1px solid ").append(BORDER)
         .append(";padding:20px 24px;\" class=\"gfs-pad\">\n")
         .append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" ")
         .append("border=\"0\" class=\"gfs-stack\"><tr>\n");
        footerLink(b, "◆", "WEBSITE", brand.websiteLabel(), brand.websiteUrl(), false);
        footerLink(b, "●", "DISCORD (SUPPORT)", brand.discordLabel(), brand.discordUrl(),
                false);
        footerLink(b, "■", "INSTAGRAM", brand.instagramLabel(), brand.instagramUrl(), true);
        b.append("</tr></table>\n</td></tr>\n");
    }

    /**
     * One footer row: a mark, a label, and the link itself.
     *
     * <p>Geometric marks rather than brand glyphs. There is no Discord or Instagram
     * character in any font a mail client can be relied on to have, and the alternatives
     * -- a remote image, or an emoji that renders as a coloured picture on a phone and a
     * hollow box in older Outlook -- both fail in a way the reader sees.
     */
    private static void footerLink(StringBuilder b, String icon, String label, String value,
                                   String url, boolean last) {
        b.append("<td valign=\"top\" style=\"padding:4px 12px 8px 0;")
         .append(last ? "" : "border-right:1px solid " + BORDER + ";").append("\">\n")
         .append("<div style=\"font-family:").append(FONT)
         .append(";font-size:12px;font-weight:800;letter-spacing:.6px;color:").append(TEXT)
         .append(";\">")
         .append("<span style=\"color:").append(RED).append(";padding-right:6px;\">")
         .append(esc(icon)).append("</span>")
         .append(esc(label)).append("</div>\n")
         .append("<a href=\"").append(esc(url)).append("\" style=\"font-family:").append(FONT)
         .append(";font-size:13px;color:").append(TEXT_MUTED).append(";text-decoration:underline;\">")
         .append(esc(value)).append("</a>\n</td>\n");
    }

    /**
     * The four claims along the bottom.
     *
     * <p>"100% SAFETY POLICY" is the wording the site uses in nineteen places; the older
     * "guarantee" phrasing is deliberately not reintroduced here.
     */
    private static void trustBadges(StringBuilder b) {
        String[][] badges = {
                {"100%", "SAFETY POLICY"},
                {"FAST &", "RELIABLE SERVICE"},
                {"TRUSTED BY", "THOUSANDS"},
                {"PLAY MORE", "WORRY LESS"},
        };
        b.append("<tr><td style=\"background:").append(INK).append(";padding:22px 18px;\">\n")
         .append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" ")
         .append("border=\"0\" class=\"gfs-stack\"><tr>\n");
        for (int i = 0; i < badges.length; i++) {
            b.append("<td align=\"center\" valign=\"middle\" style=\"padding:6px 8px;")
             .append(i == badges.length - 1 ? "" : "border-right:1px solid #24262E;")
             .append("\">\n")
             .append("<div style=\"font-family:").append(FONT)
             .append(";font-size:12px;line-height:17px;font-weight:800;color:").append(RED)
             .append(";\">").append(esc(badges[i][0])).append("</div>\n")
             .append("<div style=\"font-family:").append(FONT)
             .append(";font-size:11px;line-height:16px;font-weight:700;letter-spacing:.4px;color:")
             .append(TEXT_ON_DARK).append(";\">").append(esc(badges[i][1])).append("</div>\n")
             .append("</td>\n");
        }
        b.append("</tr></table>\n</td></tr>\n");
    }

    private static void signOff(StringBuilder b, String footerNote) {
        b.append("<tr><td align=\"center\" style=\"background:").append(INK)
         .append(";padding:6px 24px 26px 24px;border-radius:0 0 14px 14px;\">\n")
         .append("<div style=\"font-family:").append(FONT)
         .append(";font-size:13px;letter-spacing:3px;font-weight:700;color:").append(TEXT_ON_DARK)
         .append(";\">GLOBAL FUT SERVICES</div>\n");
        if (footerNote != null && !footerNote.isBlank()) {
            b.append("<div style=\"font-family:").append(FONT)
             .append(";font-size:11px;line-height:17px;color:").append(TEXT_ON_DARK_MUTED)
             .append(";padding-top:10px;max-width:420px;\">").append(footerNote).append("</div>\n");
        }
        b.append("</td></tr>\n");
    }

    // ---- helpers ------------------------------------------------------------

    /** Builder for the free-body block, so callers never hand-write paragraph markup. */
    public static String paragraphs(String... paragraphs) {
        List<String> out = new ArrayList<>(paragraphs.length);
        for (String p : paragraphs) {
            if (p != null && !p.isBlank()) {
                out.add("<p style=\"margin:0 0 12px 0;\">" + esc(p) + "</p>");
            }
        }
        return String.join("\n", out);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /**
     * Escapes text for HTML.
     *
     * <p>Applied to every interpolated value without exception — including ones that look
     * safe, like an order reference. Promotional copy is typed by an admin into a form and
     * lands in this same template, so "the caller will pass something sensible" is not a
     * property this class can rely on.
     */
    static String esc(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(ch);
            }
        }
        return out.toString();
    }
}
