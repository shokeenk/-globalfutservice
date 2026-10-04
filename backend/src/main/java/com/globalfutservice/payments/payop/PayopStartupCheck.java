package com.globalfutservice.payments.payop;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

import com.globalfutservice.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * What Payop needs before the checkout may offer it, checked once at startup.
 *
 * <ul>
 *   <li>Switched on without its four credentials, the application refuses to start, naming
 *       the missing variables -- never their values. A half-configured gateway would send a
 *       customer to a payment that cannot be confirmed.</li>
 *   <li>Switched on, it says that Payop's commission must stay "merchant pays" in Payop's
 *       panel. The checkout adds each method's fee to the total itself, so "payer pays"
 *       would charge every customer twice, and nothing in Payop's API reports the setting.</li>
 *   <li>The API token's expiry, when given, must be a date it can read. Past, or within the
 *       warning window, is logged as an error; {@link PayopTokenWatch} alerts staff.</li>
 *   <li>International points switched on stops the start: what a point is worth outside
 *       INR has not been decided, and guessing it would be a pricing decision.</li>
 * </ul>
 */
@Component
public class PayopStartupCheck {

    private static final Logger log = LoggerFactory.getLogger(PayopStartupCheck.class);

    /** How long before the token expires staff are warned. */
    public static final Duration TOKEN_WARNING = Duration.ofDays(7);

    static final String MERCHANT_PAYS = "Payop is on. Its commission must stay \"merchant pays\" in "
            + "Payop's panel: the checkout already adds each method's fee to the customer's total, "
            + "so \"payer pays\" would charge every customer twice.";

    private final AppProperties props;
    private final Clock clock;

    public PayopStartupCheck(AppProperties props, Clock clock) {
        this.props = props;
        this.clock = clock;
    }

    public void verify() {
        if (props.loyalty().internationalPoints()) {
            throw new IllegalStateException("GFS_LOYALTY_INTERNATIONAL_POINTS is on, but what a point is "
                    + "worth outside INR has not been decided. Turn it off until it has.");
        }
        AppProperties.Payop payop = props.payop();
        if (!payop.enabled()) {
            return;
        }
        List<String> missing = new ArrayList<>();
        if (blank(payop.publicKey())) missing.add("GFS_PAYOP_PUBLIC_KEY");
        if (blank(payop.secretKey())) missing.add("GFS_PAYOP_SECRET_KEY");
        if (blank(payop.jwtToken())) missing.add("GFS_PAYOP_JWT_TOKEN");
        if (blank(payop.applicationId())) missing.add("GFS_PAYOP_APPLICATION_ID");
        if (!missing.isEmpty()) {
            throw new IllegalStateException("GFS_PAYOP_ENABLED is on but " + String.join(", ", missing)
                    + (missing.size() == 1 ? " is" : " are") + " not set. Set them, or turn Payop off.");
        }
        Instant expires = parseExpiry(payop.jwtExpiresAt());
        if (expires == null) {
            log.warn("GFS_PAYOP_JWT_EXPIRES_AT is not set: staff will only hear about the Payop token "
                    + "expiring when Payop starts refusing it.");
        } else if (!expires.isAfter(clock.instant().plus(TOKEN_WARNING))) {
            log.error("The Payop API token expires {} (GFS_PAYOP_JWT_EXPIRES_AT). Create a new one in "
                    + "Payop's dashboard and update GFS_PAYOP_JWT_TOKEN and GFS_PAYOP_JWT_EXPIRES_AT.", expires);
        }
        log.warn(MERCHANT_PAYS);
    }

    /**
     * The token's expiry as Payop's dashboard sets it, or null when not given.
     *
     * <p>Takes a date (2027-03-31, read as that day's start in UTC, the earlier reading) or
     * an instant (2027-03-31T00:00:00Z). Anything else stops the start: a mistyped expiry
     * would otherwise silently disable the warning it exists for.
     */
    public static Instant parseExpiry(String value) {
        if (blank(value)) {
            return null;
        }
        String v = value.trim();
        try {
            return v.length() == 10 ? LocalDate.parse(v).atStartOfDay(ZoneOffset.UTC).toInstant() : Instant.parse(v);
        } catch (DateTimeParseException e) {
            throw new IllegalStateException("GFS_PAYOP_JWT_EXPIRES_AT must be a date such as 2027-03-31 "
                    + "or an instant such as 2027-03-31T00:00:00Z; it is not.");
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
