package com.globalfutservice.notify.discord;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DiscordVerificationRepository
        extends JpaRepository<DiscordVerificationEntity, Long> {

    Optional<DiscordVerificationEntity> findByOrderId(Long orderId);
}
