package com.globalfutservice.support.chat;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.globalfutservice.coaching.CoachEntity;
import com.globalfutservice.coaching.CoachRepository;
import com.globalfutservice.coaching.CoachingSessionEntity;
import com.globalfutservice.coaching.CoachingSessionRepository;
import com.globalfutservice.config.AppProperties;
import com.globalfutservice.domain.catalog.CoinAmount;
import com.globalfutservice.domain.catalog.Sku;
import com.globalfutservice.domain.coaching.SessionStatus;
import com.globalfutservice.identity.AccountEntity;
import com.globalfutservice.identity.AccountRepository;
import com.globalfutservice.orders.OrderEntity;
import com.globalfutservice.orders.OrderService;
import com.globalfutservice.orders.web.OrderMapper;
import com.globalfutservice.web.ApiExceptions;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What an order's support page shows, and the little it tells the live chat.
 *
 * <p><b>The owner only.</b> The order is loaded with its owner in the query. Anyone else,
 * and any reference that does not exist, gets the same "not found", so the page cannot be
 * used to learn whether an order exists.
 *
 * <p><b>The mode is the order's.</b> Boosting, coins or coaching comes from the order's
 * service here, never from the address the customer opened.
 *
 * <p><b>An allowlist, on purpose.</b> The chat is a third party, tawk.to. It is told the
 * order's reference, service, platform and status in the words the customer already sees,
 * the customer's name and email, and the coin amount or the coaching session and coach.
 * Those keys are fixed in {@link #ATTRIBUTE_KEYS}. This class is not given the credential
 * vault or anything about payment, so it cannot leak them.
 *
 * <p><b>Every key, every time.</b> tawk.to keeps attributes on the visitor and has no way to
 * remove one, so a coin order's amount would still be showing when the same customer opens
 * a coaching order. Keys that do not apply are sent as {@value #NOT_APPLICABLE}.
 */
@Service
public class SupportChatService {

    /** Everything the chat is ever told about the order, in tawk.to's key rules (lower case, dashes). */
    public static final List<String> ATTRIBUTE_KEYS =
            List.of("order-id", "service", "platform", "order-status", "coin-amount", "session-id", "coach");

    static final String NOT_APPLICABLE = "n/a";

    /** tawk.to refuses a value longer than this. */
    static final int MAX_VALUE = 255;

    /** Which support page this is: the copy, the button and the chat fields follow it. */
    public enum Mode { BOOSTING, COINS, COACHING }

    /** The order at the top of the support page, in the customer's own words. */
    public record Summary(String reference, String service, String platform, String status, String coins,
                          String session, Instant sessionStartsAt, String sessionTimezone, String coach) {
    }

    /**
     * What the chat is told.
     *
     * @param hash HMAC-SHA256 of {@code email} under the Secure Mode key, so tawk.to can trust
     *             the name and email came from us; null when Secure Mode is not configured
     */
    public record Chat(String name, String email, String hash, Map<String, String> attributes) {
    }

    public record SupportContext(Mode mode, Summary summary, Chat chat) {
    }

    private final OrderService orders;
    private final AccountRepository accounts;
    private final CoachingSessionRepository sessions;
    private final CoachRepository coaches;
    private final AppProperties props;
    private final Clock clock;

    public SupportChatService(OrderService orders, AccountRepository accounts, CoachingSessionRepository sessions,
                              CoachRepository coaches, AppProperties props, Clock clock) {
        this.orders = orders;
        this.accounts = accounts;
        this.sessions = sessions;
        this.coaches = coaches;
        this.props = props;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public SupportContext contextFor(String publicRef, long accountId) {
        OrderEntity order = orders.requireOwned(publicRef, accountId);
        AccountEntity account = accounts.findById(accountId)
                .orElseThrow(() -> new ApiExceptions.NotFoundException("No such order."));

        Mode mode = modeOf(order.getSku());
        String service = OrderService.describe(order);
        String platform = platform(order);
        String status = OrderMapper.statusLabel(order);
        String coins = mode == Mode.COINS ? CoinAmount.describe(order.getQuantity()) : null;
        CoachingSessionEntity session = mode == Mode.COACHING ? sessionFor(order) : null;
        String coach = session == null ? null
                : coaches.findById(session.getCoachId()).map(CoachEntity::getDisplayName).orElse(null);

        Summary summary = new Summary(order.getPublicRef(), service, platform, status, blankToNull(coins),
                session == null ? null : session.getPublicRef(),
                session == null ? null : session.getStartsAt(),
                session == null ? null : session.getCustomerTimezone(),
                coach);

        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("order-id", value(order.getPublicRef()));
        attributes.put("service", value(service));
        attributes.put("platform", value(platform));
        attributes.put("order-status", value(status));
        attributes.put("coin-amount", value(coins));
        attributes.put("session-id", value(summary.session()));
        attributes.put("coach", value(coach));

        String email = account.getEmail();
        return new SupportContext(mode, summary,
                new Chat(blankToNull(account.getDisplayName()), email, hash(email),
                        java.util.Collections.unmodifiableMap(attributes)));
    }

    static Mode modeOf(Sku sku) {
        if (sku == Sku.COACHING) return Mode.COACHING;
        if (sku.isCoinTransfer()) return Mode.COINS;
        return Mode.BOOSTING;
    }

    private static String platform(OrderEntity order) {
        if (order.getPlatform() != null) return order.getPlatform().displayName();
        if (order.getCoachingPlatform() != null) return order.getCoachingPlatform().displayName();
        return null;
    }

    /** The next session still to come; failing that, the latest one booked. */
    private CoachingSessionEntity sessionFor(OrderEntity order) {
        List<CoachingSessionEntity> all = sessions.findByOrderIdOrderByIdAsc(order.getId());
        Instant now = clock.instant();
        return all.stream()
                .filter(s -> s.getStatus() == SessionStatus.SCHEDULED && s.getStartsAt() != null
                        && s.getStartsAt().isAfter(now))
                .min(Comparator.comparing(CoachingSessionEntity::getStartsAt))
                .orElseGet(() -> all.stream()
                        .filter(s -> s.getStartsAt() != null)
                        .max(Comparator.comparing(CoachingSessionEntity::getStartsAt))
                        .orElse(null));
    }

    /** A value tawk.to will take: never empty, never longer than it allows. */
    static String value(String raw) {
        String v = raw == null ? "" : raw.trim();
        if (v.isEmpty()) return NOT_APPLICABLE;
        return v.length() <= MAX_VALUE ? v : v.substring(0, MAX_VALUE);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    /** Secure Mode: the email signed with the property's key, as tawk.to checks it. Null when not configured. */
    String hash(String email) {
        AppProperties.Tawk tawk = props.tawk();
        if (tawk == null || !tawk.secureModeConfigured() || email == null) return null;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(tawk.secureKey().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(email.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HmacSHA256 is unavailable on this JVM");
        }
    }
}
