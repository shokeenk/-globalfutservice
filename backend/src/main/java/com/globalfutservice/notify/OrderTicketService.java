package com.globalfutservice.notify;

import com.globalfutservice.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;

/**
 * A Discord channel per payment submission, matching the tickets already in the server.
 *
 * <p><b>Why a channel rather than another line in a feed.</b> Verifying a payment is a
 * conversation with a customer as often as it is a lookup -- the reference does not match,
 * the screenshot shows a different amount, the money arrived from a name nobody
 * recognises. A ticket gives that conversation somewhere to live and keeps it attached to
 * the order. A shared alerts channel gives you one line that scrolls away.
 *
 * <p><b>It never fails an order.</b> Every path here falls back to the webhook and, if
 * that is also unavailable, to a log line. A customer who has sent money must not have
 * their submission rejected because Discord was rate-limiting us.
 */
@Service
public class OrderTicketService {

    private static final Logger log = LoggerFactory.getLogger(OrderTicketService.class);

    /**
     * "8 Sept 2026, 4:12 PM".
     *
     * <p>In the business's own timezone rather than UTC. An operator comparing this against
     * a bank statement is reading local times on both, and a ticket that says 10:42 for a
     * payment their statement puts at 16:12 is a ticket that costs five minutes every time.
     */
    private static final DateTimeFormatter STAMP = new java.time.format.DateTimeFormatterBuilder()
            .appendPattern("d MMM yyyy, h:mm ")
            /*
             * AM/PM spelled out rather than left to the pattern's `a`. CLDR renders the
             * en-GB meridiem lowercase, so `a` produces "4:12 pm" -- correct for the
             * locale, and not the format this ticket was specified in. Mapping the field
             * explicitly pins it regardless of which CLDR the JDK ships.
             */
            .appendText(java.time.temporal.ChronoField.AMPM_OF_DAY,
                    java.util.Map.of(0L, "AM", 1L, "PM"))
            .toFormatter(Locale.UK);

    private static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");

    private final DiscordBotClient bot;
    private final AppProperties props;

    public OrderTicketService(DiscordBotClient bot, AppProperties props) {
        this.bot = bot;
        this.props = props;
    }

    public boolean isEnabled() {
        return bot.isEnabled();
    }

    /**
     * Opens the ticket and posts the submission into it.
     *
     * @return the channel id, or empty if a ticket could not be opened -- in which case
     *         the caller should fall back to the webhook rather than drop the alert
     */
    public Optional<String> openTicket(PaymentClaimNotification n) {
        if (!isEnabled()) {
            return Optional.empty();
        }
        try {
            String channelId = bot.createTicketChannel(n.publicRef());
            bot.postMessage(channelId, compose(n));
            return Optional.of(channelId);
        } catch (RuntimeException e) {
            // Reason only. A 403 here is almost always the bot missing Manage Channels in
            // the configured category, which is granted in Discord, not in this codebase.
            log.warn("Could not open a Discord ticket for {} — falling back to the webhook: {}",
                    n.publicRef(), e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Posts the screenshot into an order's existing ticket.
     *
     * <p>Separate from {@link #openTicket} because the two events genuinely are separate:
     * the storefront records the reference, then uploads the image. Best effort throughout
     * -- a screenshot that does not reach Discord is still in the admin console, which is
     * where an operator can always go.
     */
    public void attachScreenshot(String publicRef, byte[] image, String contentType) {
        if (!isEnabled()) {
            return;
        }
        try {
            Optional<String> channelId = bot.findTicketChannel(publicRef);
            if (channelId.isEmpty()) {
                log.debug("No Discord ticket for {}; screenshot stays in the console only",
                        publicRef);
                return;
            }
            bot.postImage(channelId.get(), image, filenameFor(publicRef, contentType),
                    "📸 Payment screenshot for " + publicRef);
            log.info("Posted the payment screenshot into the Discord ticket for {}", publicRef);
        } catch (RuntimeException e) {
            log.warn("Could not post the screenshot for {} to Discord: {}",
                    publicRef, e.getMessage());
        }
    }

    /* ---------------------------------------------------------------- message --- */

    /**
     * The ticket body.
     *
     * <p>Every field is real or is absent. Nothing here is a placeholder and nothing claims
     * something the data does not support -- in particular the screenshot line reports what
     * is actually attached at this moment rather than always saying "attached".
     *
     * <p>The EA sign-in is not here and has no field to travel in. See
     * {@link PaymentClaimNotification}: an operator is told <em>whether</em> it arrived and
     * goes to the audited reveal for the rest.
     */
    String compose(PaymentClaimNotification n) {
        String mention = mention();
        return (mention.isEmpty() ? "" : mention + "\n")
                + "💰 **NEW PAYMENT SUBMISSION**\n\n"
                + "🆔 **Order ID:** #" + n.publicRef() + "\n\n"
                + "👤 **Customer:** " + orDash(n.customerName()) + "\n"
                + "📧 **Email:** " + orDash(n.customerEmail()) + "\n\n"
                + "📦 **Product:** " + orDash(n.serviceLabel()) + "\n"
                + "💵 **Amount:** " + orDash(n.amountFormatted()) + "\n"
                + "💳 **Payment Method:** " + readableMethod(n.method())
                + " → `" + orDash(n.destination()) + "`\n\n"
                + "🔢 **UTR / Transaction ID:** `" + orDash(n.reference()) + "`\n\n"
                + "📸 **Payment Screenshot:** " + screenshotLine(n) + "\n\n"
                + "🔐 **Account details:** " + (n.credentialsHeld()
                        ? "submitted ✓ (open them in the console — never here)"
                        : "not required for this order") + "\n\n"
                + "🕐 **Submitted:** " + stamp(n.submittedAt()) + "\n\n"
                + "⚠️ **Status:** PAYMENT VERIFICATION PENDING\n\n"
                + "👉 **Action Required:** Verify the payment from the account above and "
                + "update the order status.\n\n"
                + "🔗 **View Order:** " + orDash(n.adminDeepLink());
    }

    /**
     * What the screenshot line may say.
     *
     * <p>Three states, not two. "Not provided" and "uploading" are different facts, and
     * telling them apart is the difference between an operator waiting a moment and an
     * operator going to ask the customer for something they already sent.
     */
    private static String screenshotLine(PaymentClaimNotification n) {
        return n.hasProof()
                ? "attached below ✓"
                : "not attached yet — it is uploaded a moment after the reference, "
                        + "and will be posted here when it arrives";
    }

    private String mention() {
        String id = props.notifications().discordAdminId();
        return (id == null || id.isBlank()) ? "" : "<@" + id.trim() + ">";
    }

    private static String stamp(Instant at) {
        return at == null ? "—" : STAMP.format(at.atZone(ZONE));
    }

    private static String readableMethod(String method) {
        if (method == null) return "—";
        return switch (method) {
            case "UPI" -> "UPI";
            case "PAYPAL" -> "PayPal";
            case "CRYPTO" -> "Crypto (USDT, TRON)";
            default -> method;
        };
    }

    private static String filenameFor(String publicRef, String contentType) {
        String ext = switch (contentType == null ? "" : contentType) {
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            default -> ".jpg";
        };
        // Built from the order reference and the stored type, never from anything the
        // customer supplied -- an uploaded filename in a header is how a download ends up
        // named "../../x".
        return DiscordBotClient.channelNameFor(publicRef) + ext;
    }

    private static String orDash(String value) {
        return (value == null || value.isBlank()) ? "—" : value;
    }
}
