package com.globalfutservice.payments;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WebhookEventRepository extends JpaRepository<WebhookEventEntity, Long> {

    Optional<WebhookEventEntity> findByProviderAndProviderEventId(String provider, String providerEventId);

    boolean existsByProviderAndProviderEventId(String provider, String providerEventId);

    /** The newest deliveries of one kind from one provider, for an admin to look through. */
    List<WebhookEventEntity> findTop100ByProviderAndEventTypeOrderByReceivedAtDesc(String provider, String eventType);
}
