package com.globalfutservice.payments;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Optional;

/**
 * Records webhook deliveries in their own transactions.
 *
 * <p><b>This is a separate bean on purpose, and the reason is subtle enough to be
 * worth writing down.</b> Spring's {@code @Transactional} is implemented with a
 * proxy, so a call from one method of a bean to another method of the <i>same</i>
 * bean bypasses it entirely — the annotation is silently ignored. Putting these
 * methods here means {@code REQUIRES_NEW} actually takes effect.
 *
 * <p>It matters here specifically. If applying an event fails, that transaction is
 * marked rollback-only; writing the failure record from inside it would then fail
 * too, and the delivery would vanish along with any evidence of what went wrong.
 * Recording in an independent transaction means the audit row survives the failure
 * it is describing.
 */
@Component
public class WebhookLedger {

    private static final Logger log = LoggerFactory.getLogger(WebhookLedger.class);

    private final WebhookEventRepository repository;
    private final Clock clock;

    public WebhookLedger(WebhookEventRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /**
     * @return the stored row's id, or empty if this delivery has been seen before.
     *         Gateways retry aggressively; losing the insert race is the normal
     *         outcome for a duplicate and is not an error.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<Long> record(String provider, String eventId, String eventType, String rawBody) {
        if (repository.existsByProviderAndProviderEventId(provider, eventId)) {
            log.debug("Webhook {} already seen; ignoring duplicate", eventId);
            return Optional.empty();
        }
        try {
            WebhookEventEntity saved = repository.save(
                    new WebhookEventEntity(provider, eventId, eventType, rawBody));
            return Optional.of(saved.getId());
        } catch (DataIntegrityViolationException e) {
            // Lost a race with a concurrent delivery of the same event. The winner
            // is doing the work.
            log.debug("Webhook {} raced a duplicate; ignoring", eventId);
            return Optional.empty();
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markProcessed(Long id) {
        repository.findById(id).ifPresent(event -> {
            event.markProcessed(clock.instant());
            repository.save(event);
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(Long id, String error) {
        repository.findById(id).ifPresent(event -> {
            event.markFailed(error);
            repository.save(event);
        });
    }
}
