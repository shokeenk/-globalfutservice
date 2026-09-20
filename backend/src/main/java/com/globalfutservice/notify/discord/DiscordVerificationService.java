package com.globalfutservice.notify.discord;

import com.globalfutservice.identity.AccountEntity;
import com.globalfutservice.identity.AccountRepository;
import com.globalfutservice.notify.DiscordBotClient;
import com.globalfutservice.orders.OrderEntity;
import com.globalfutservice.orders.OrderRepository;
import io.github.bucket4j.Bucket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Granting a customer access to their own ticket, once they are in the server.
 *
 * <p>Most customers sign in with Google, so when a ticket is opened the application has
 * no idea what their Discord account is and cannot let them in. They join the server and
 * run {@code /verify GFS-26-XXXXXXXX}, and the id arrives for the first time attached to
 * that command. This is what happens next.
 *
 * <p><b>An order reference is not a secret and is not treated as one.</b> It is printed
 * in emails, on receipts, and in the screenshots customers post publicly when asking for
 * help. Anybody who has seen one can type it here, so two things stand between a stranger
 * and a ticket: the first account to claim an order keeps it, and guessing is rate
 * limited and written down. That is weaker than a password and stronger than nothing,
 * which is the honest description of what an order reference can support.
 */
@Service
public class DiscordVerificationService {

    private static final Logger log = LoggerFactory.getLogger(DiscordVerificationService.class);

    /**
     * Five tries a minute per Discord account.
     *
     * <p>Enough that somebody mistyping their reference three times never notices, and
     * little enough that working through the reference space is not a thing you can do
     * from a Discord client. The references are eight random characters, so this is not
     * the only defence — it is the one that makes the attempt visible rather than fast.
     */
    private static final int ATTEMPTS_PER_MINUTE = 5;

    /** Above this many failures in an hour, an operator should be looking at the account. */
    private static final int SUSPICIOUS_FAILURES_PER_HOUR = 20;

    private final OrderRepository orders;
    private final DiscordVerificationRepository verifications;
    private final DiscordAttemptRepository attempts;
    private final DiscordBotClient bot;
    private final AccountRepository accounts;

    /*
     * In memory, and therefore per instance — the same caveat RateLimitFilter documents.
     * On more than one replica each would grant its own budget; the durable attempt log
     * is what stops that being the whole story.
     */
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    public DiscordVerificationService(OrderRepository orders,
                                      DiscordVerificationRepository verifications,
                                      DiscordAttemptRepository attempts,
                                      DiscordBotClient bot,
                                      AccountRepository accounts) {
        this.orders = orders;
        this.verifications = verifications;
        this.attempts = attempts;
        this.bot = bot;
        this.accounts = accounts;
    }

    /** What the command should say back, and the channel to link if there is one. */
    public record Result(VerificationOutcome outcome, String channelId) {

        static Result of(VerificationOutcome outcome) {
            return new Result(outcome, null);
        }

        public boolean granted() {
            return outcome.granted();
        }

        public String message() {
            return outcome.message();
        }
    }

    /**
     * Handle one {@code /verify}.
     *
     * <p>Not {@code @Transactional} as a whole, deliberately: the Discord call in the
     * middle is a network round trip that can take seconds, and holding a transaction
     * across it would pin a connection for the duration. The two writes are each their
     * own unit, and the order they happen in matters — the row that claims the order is
     * written <i>after</i> the grant succeeds, so a failed grant leaves the order
     * claimable rather than locked to somebody who never got in.
     */
    public Result verify(String discordUserId, String discordUsername, String rawOrderRef) {
        if (!allow(discordUserId)) {
            record(discordUserId, rawOrderRef, VerificationOutcome.RATE_LIMITED);
            return Result.of(VerificationOutcome.RATE_LIMITED);
        }

        String ref = normalise(rawOrderRef);
        if (ref.isEmpty()) {
            record(discordUserId, rawOrderRef, VerificationOutcome.NOT_FOUND);
            return Result.of(VerificationOutcome.NOT_FOUND);
        }

        Optional<OrderEntity> found = orders.findByPublicRef(ref);
        if (found.isEmpty()) {
            record(discordUserId, ref, VerificationOutcome.NOT_FOUND);
            return Result.of(VerificationOutcome.NOT_FOUND);
        }
        OrderEntity order = found.get();

        Optional<DiscordVerificationEntity> existing = verifications.findByOrderId(order.getId());
        if (existing.isPresent()) {
            DiscordVerificationEntity claim = existing.get();
            if (claim.claimedBySomeoneOtherThan(discordUserId)) {
                record(discordUserId, ref, VerificationOutcome.ALREADY_CLAIMED);
                log.warn("Order {} is claimed by {} and {} tried to claim it too",
                        ref, claim.getDiscordUserId(), discordUserId);
                return Result.of(VerificationOutcome.ALREADY_CLAIMED);
            }
            /*
             * Same person again. Re-granting rather than refusing: somebody who lost the
             * channel, or who was removed from the server and came back, should be able
             * to run the command a second time and get back in.
             */
            return regrant(discordUserId, ref, claim);
        }

        if (!bot.isEnabled()) {
            record(discordUserId, ref, VerificationOutcome.ERROR);
            log.error("A customer ran /verify but the Discord bot is not configured");
            return Result.of(VerificationOutcome.ERROR);
        }

        Optional<String> channelId;
        try {
            channelId = bot.findTicketChannel(ref);
        } catch (RuntimeException e) {
            record(discordUserId, ref, VerificationOutcome.ERROR);
            log.error("Could not look up the ticket channel for {}", ref, e);
            return Result.of(VerificationOutcome.ERROR);
        }
        if (channelId.isEmpty()) {
            // The order is real but no ticket has been opened — payment not submitted yet.
            record(discordUserId, ref, VerificationOutcome.NO_TICKET);
            return Result.of(VerificationOutcome.NO_TICKET);
        }

        try {
            bot.grantChannelAccess(channelId.get(), discordUserId);
        } catch (RuntimeException e) {
            record(discordUserId, ref, VerificationOutcome.ERROR);
            log.error("Could not grant {} access to {}", discordUserId, channelId.get(), e);
            return Result.of(VerificationOutcome.ERROR);
        }

        claim(order.getId(), discordUserId, discordUsername, channelId.get());
        record(discordUserId, ref, VerificationOutcome.GRANTED);
        log.info("Discord account {} verified against order {}", discordUserId, ref);
        return new Result(VerificationOutcome.GRANTED, channelId.get());
    }

    /**
     * The other path: a customer who signed in with Discord in the first place.
     *
     * <p>Their Discord id has been on the account since they authenticated, so there is
     * nothing to prove and nothing to ask them for — the grant happens as the ticket is
     * opened and they are inside it before they read the email. Everyone else falls
     * through to {@code /verify}.
     *
     * <p>Best effort throughout. A failure here costs the shortcut, not the ticket: the
     * customer can still run the command, and a ticket that opened is worth more than a
     * permission that did not.
     */
    public void grantAtTicketCreation(String publicRef, String channelId) {
        if (channelId == null || !bot.isEnabled()) {
            return;
        }
        try {
            Optional<OrderEntity> order = orders.findByPublicRef(publicRef);
            if (order.isEmpty() || order.get().getAccountId() == null) {
                return; // A guest checkout has no account and therefore no Discord id.
            }
            Optional<String> discordUserId = accounts.findById(order.get().getAccountId())
                    .filter(a -> "discord".equalsIgnoreCase(a.getOauthProvider()))
                    .map(AccountEntity::getOauthSubject)
                    .filter(id -> id != null && !id.isBlank());
            if (discordUserId.isEmpty()) {
                return; // Signed in with Google, or with a password. /verify is their path.
            }
            bot.grantChannelAccess(channelId, discordUserId.get());
            claim(order.get().getId(), discordUserId.get(), null, channelId);
            log.info("Granted Discord-authenticated customer {} access to {} at ticket creation",
                    discordUserId.get(), channelId);
        } catch (RuntimeException e) {
            log.warn("Could not pre-grant access to {}; /verify still works: {}",
                    channelId, e.getMessage());
        }
    }

    /**
     * A deep link into the ticket, for a customer already inside it.
     *
     * <p>Read from the verification row rather than asked of Discord: the channel id was
     * written down when access was granted, so this costs one indexed lookup instead of
     * an API call on every page load. Empty when nobody has been granted anything yet,
     * which is the correct answer — a link to a channel the reader cannot see looks like
     * a broken site.
     */
    @Transactional(readOnly = true)
    public Optional<String> ticketUrl(Long orderId, String guildId) {
        if (orderId == null || guildId == null || guildId.isBlank()) {
            return Optional.empty();
        }
        return verifications.findByOrderId(orderId)
                .map(DiscordVerificationEntity::getChannelId)
                .filter(id -> id != null && !id.isBlank())
                .map(id -> "https://discord.com/channels/" + guildId + "/" + id);
    }

    /**
     * Whether this order's customer will be let in automatically.
     *
     * <p>Read by the storefront and the confirmation email so they can show a direct
     * channel link instead of an invite and a set of instructions.
     */
    @Transactional(readOnly = true)
    public boolean isDiscordAuthenticated(Long accountId) {
        if (accountId == null) {
            return false;
        }
        return accounts.findById(accountId)
                .filter(a -> "discord".equalsIgnoreCase(a.getOauthProvider()))
                .map(a -> a.getOauthSubject() != null && !a.getOauthSubject().isBlank())
                .orElse(false);
    }

    // ---- internals ---------------------------------------------------------

    private Result regrant(String discordUserId, String ref, DiscordVerificationEntity claim) {
        String channelId = claim.getChannelId();
        if (channelId == null || !bot.isEnabled()) {
            record(discordUserId, ref, VerificationOutcome.GRANTED);
            return Result.of(VerificationOutcome.GRANTED);
        }
        try {
            bot.grantChannelAccess(channelId, discordUserId);
        } catch (RuntimeException e) {
            log.warn("Could not re-grant {} access to {}: {}",
                    discordUserId, channelId, e.getMessage());
        }
        record(discordUserId, ref, VerificationOutcome.GRANTED);
        return new Result(VerificationOutcome.GRANTED, channelId);
    }

    /**
     * Write the claim.
     *
     * <p><b>Not annotated {@code @Transactional}, on purpose.</b> This is called from
     * {@link #verify} on the same object, and Spring's transaction proxy is only involved
     * when a call arrives from outside — the annotation would be inert and would read as
     * a guarantee that was not there. It does not need one: a single repository {@code
     * save} carries its own transaction.
     *
     * <p>The unique constraint on {@code order_id} is the real guard, not the check above
     * it: two commands arriving at once both see no existing row, and the database is what
     * decides which of them wins. Losing that race is not an error worth surfacing — the
     * grant has already happened.
     */
    private void claim(Long orderId, String discordUserId, String discordUsername,
                       String channelId) {
        try {
            verifications.save(new DiscordVerificationEntity(
                    orderId, discordUserId, discordUsername, channelId));
        } catch (RuntimeException e) {
            log.debug("Order {} was claimed concurrently: {}", orderId, e.getMessage());
        }
    }

    private void record(String discordUserId, String orderRef, VerificationOutcome outcome) {
        try {
            attempts.save(new DiscordAttemptEntity(discordUserId, clamp(orderRef), outcome));
        } catch (RuntimeException e) {
            // The log is evidence, not control flow. Never fail a verification over it.
            log.warn("Could not record a verification attempt: {}", e.getMessage());
            return;
        }
        if (outcome == VerificationOutcome.GRANTED) {
            return;
        }
        long failures = attempts.countByDiscordUserIdAndOutcomeNotAndAttemptedAtAfter(
                discordUserId, VerificationOutcome.GRANTED.name(),
                Instant.now().minus(Duration.ofHours(1)));
        if (failures >= SUSPICIOUS_FAILURES_PER_HOUR) {
            log.warn("Discord account {} has failed /verify {} times in an hour",
                    discordUserId, failures);
        }
    }

    private boolean allow(String discordUserId) {
        return buckets.computeIfAbsent(discordUserId, k -> Bucket.builder()
                // Greedy refill, for the reason RateLimitFilter spells out: an interval
                // refill lets somebody spend a whole budget either side of the boundary.
                .addLimit(limit -> limit
                        .capacity(ATTEMPTS_PER_MINUTE)
                        .refillGreedy(ATTEMPTS_PER_MINUTE, Duration.ofMinutes(1)))
                .build()).tryConsume(1);
    }

    /**
     * What the customer typed, turned into what the reference actually looks like.
     *
     * <p>People paste with whitespace, in lower case, wrapped in backticks, and sometimes
     * with a leading {@code #} copied off the email. None of that is a wrong answer worth
     * failing on.
     */
    static String normalise(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.trim()
                .replace("`", "")
                .replaceFirst("^#", "")
                .trim()
                .toUpperCase(Locale.ROOT);
    }

    private static String clamp(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.length() <= 64 ? t : t.substring(0, 64);
    }
}
