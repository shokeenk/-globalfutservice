package com.globalfutservice.notify.discord;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;

public interface DiscordAttemptRepository extends JpaRepository<DiscordAttemptEntity, Long> {

    /** Failed tries by one account since a moment — the brute-force signal. */
    long countByDiscordUserIdAndOutcomeNotAndAttemptedAtAfter(
            String discordUserId, String outcome, Instant since);
}
