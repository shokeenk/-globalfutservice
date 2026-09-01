package com.globalfutservice.notify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * Fans one event out to every configured channel, off the request thread.
 *
 * <p>{@code @Async} on a virtual-thread executor means a slow WhatsApp API costs
 * notification latency and nothing else — in particular, it does not hold open the
 * transaction that just took a customer's money.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final List<Notifier> notifiers;

    public NotificationService(List<Notifier> notifiers) {
        this.notifiers = notifiers;
        List<String> enabled = notifiers.stream()
                .filter(Notifier::isEnabled)
                .map(Notifier::channelName)
                .toList();

        /*
         * No channel is a operational failure, not a configuration detail.
         *
         * This used to log "Notification channels enabled: none" at INFO, which is
         * exactly the shape of the original bug: the system quietly not telling anyone
         * about paid orders, and quietly not telling anyone that it was not telling
         * anyone. A paid order that nobody is alerted to is an unfulfilled order, so
         * this gets a WARN and says what to do about it.
         */
        if (enabled.isEmpty()) {
            log.warn("NO notification channels are enabled — nobody will be alerted when an "
                    + "order is paid. Set GFS_EMAIL_ENABLED=true and GFS_OPERATOR_EMAILS to "
                    + "at least one address.");
        } else {
            log.info("Notification channels enabled: {}", String.join(", ", enabled));
        }
    }

    @Async
    public void orderPlaced(OrderNotification n) {
        each(notifier -> notifier.orderPlaced(n));
    }

    @Async
    public void credentialsNeeded(OrderNotification n) {
        each(notifier -> notifier.credentialsNeeded(n));
    }

    @Async
    public void readyToFulfil(OrderNotification n) {
        each(notifier -> notifier.readyToFulfil(n));
    }

    public void orderDelivered(OrderNotification n) {
        each(notifier -> notifier.orderDelivered(n));
    }

    @Async
    public void coachingReminder(String email, String sessionRef, Instant startsAt, String zone) {
        CoachingNotification n = new CoachingNotification(email, sessionRef, startsAt, zone);
        each(notifier -> notifier.coachingReminder(n));
    }

    private void each(java.util.function.Consumer<Notifier> action) {
        for (Notifier notifier : notifiers) {
            try {
                action.accept(notifier);
            } catch (Exception e) {
                log.warn("Notifier {} failed: {}", notifier.channelName(), e.getMessage());
            }
        }
    }
}
