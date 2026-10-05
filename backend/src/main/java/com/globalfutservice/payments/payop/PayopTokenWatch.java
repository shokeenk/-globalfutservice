package com.globalfutservice.payments.payop;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import com.globalfutservice.config.AppProperties;
import com.globalfutservice.notify.NotificationService;
import com.globalfutservice.notify.PaymentAlert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Tells staff before Payop's JWT stops working, and as soon as Payop refuses it.
 *
 * <p>The token lives until the expiry chosen when it was issued, and nothing on Payop's side
 * warns anyone. Without it the site cannot confirm a single Payop payment -- customers would
 * pay and their orders would sit unconfirmed -- so staff hear every morning of the last
 * week ({@code GFS_PAYOP_JWT_EXPIRES_AT}), and at once on any 401, at most twice a day.
 */
@Component
public class PayopTokenWatch {

    private static final Logger log = LoggerFactory.getLogger(PayopTokenWatch.class);

    /** A refused token is alerted at most this often: every payment would otherwise repeat it. */
    static final Duration REPEAT = Duration.ofHours(12);

    private final AppProperties props;
    private final NotificationService notifications;
    private final Clock clock;
    private final AtomicReference<Instant> lastRefusal = new AtomicReference<>();

    public PayopTokenWatch(AppProperties props, NotificationService notifications, Clock clock) {
        this.props = props;
        this.notifications = notifications;
        this.clock = clock;
    }

    /** Every morning, while Payop is on: is the token inside its last week? */
    @Scheduled(cron = "0 0 6 * * *", zone = "UTC")
    public void checkExpiry() {
        if (!props.payop().enabled()) {
            return;
        }
        Instant expires = PayopStartupCheck.parseExpiry(props.payop().jwtExpiresAt());
        if (expires == null) {
            return; // the startup check already says the expiry is unknown
        }
        Duration left = Duration.between(clock.instant(), expires);
        if (!left.isNegative() && left.compareTo(PayopStartupCheck.TOKEN_WARNING) > 0) {
            return;
        }
        String headline;
        String code;
        if (left.isNegative() || left.isZero()) {
            headline = "Payop token has expired";
            code = "TOKEN_EXPIRED";
        } else {
            long days = left.toDays();
            headline = days == 0 ? "Payop token expires today"
                    : "Payop token expires in " + days + (days == 1 ? " day" : " days");
            code = "TOKEN_EXPIRING";
        }
        log.warn("{} ({})", headline, expires);
        notifications.paymentAlert(new PaymentAlert(null, headline,
                "Payop's token expires at " + expires + ". Until it is replaced, Payop payments cannot be "
                        + "confirmed. Issue a new token in Payop's dashboard, then set GFS_PAYOP_JWT_TOKEN and "
                        + "GFS_PAYOP_JWT_EXPIRES_AT and redeploy.",
                code, null));
    }

    /** Payop answered 401: the token is wrong, revoked or expired. */
    public void refused(String call) {
        Instant now = clock.instant();
        Instant last = lastRefusal.get();
        if (last != null && Duration.between(last, now).compareTo(REPEAT) < 0) {
            return;
        }
        if (!lastRefusal.compareAndSet(last, now)) {
            return; // another thread is already raising it
        }
        notifications.paymentAlert(new PaymentAlert(null, "Payop refused our token",
                "Payop answered 401 to " + call + ". Until a valid token is set, Payop payments cannot be "
                        + "confirmed and new ones cannot start. Issue a new token in Payop's dashboard, then set "
                        + "GFS_PAYOP_JWT_TOKEN and GFS_PAYOP_JWT_EXPIRES_AT and redeploy.",
                "TOKEN_REJECTED", null));
    }
}
