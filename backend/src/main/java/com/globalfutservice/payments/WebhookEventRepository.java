package com.globalfutservice.payments;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface WebhookEventRepository extends JpaRepository<WebhookEventEntity, Long> {

    Optional<WebhookEventEntity> findByProviderAndProviderEventId(String provider, String providerEventId);

    boolean existsByProviderAndProviderEventId(String provider, String providerEventId);
}
