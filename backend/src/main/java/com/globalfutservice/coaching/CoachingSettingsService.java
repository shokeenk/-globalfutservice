package com.globalfutservice.coaching;

import com.globalfutservice.config.AppProperties;
import com.globalfutservice.domain.coaching.CoachingPolicy;
import com.globalfutservice.web.ApiExceptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;

/**
 * The booking settings an admin sets from the Coaching diary, and the policy they make.
 *
 * <p>Minimum notice, the buffer between sessions, how long a checkout hold lasts, and the
 * two session lengths used to be environment variables. A setting the business changes
 * should not need a redeploy, so they are read from {@code coaching_settings} (V30). The
 * rest of the booking rules -- the slot grid, the booking horizon, the change cut-off --
 * are published promises in the Terms and stay in configuration.
 */
@Service
public class CoachingSettingsService {

    private static final Logger log = LoggerFactory.getLogger(CoachingSettingsService.class);
    private static final short ROW = 1;

    private final CoachingSettingsRepository repo;
    private final CoachingPolicy base;
    private final AppProperties props;
    private final Clock clock;

    public CoachingSettingsService(CoachingSettingsRepository repo, CoachingPolicy base,
                                   AppProperties props, Clock clock) {
        this.repo = repo;
        this.base = base;
        this.props = props;
        this.clock = clock;
    }

    /** The settings in force. */
    public record Settings(Duration minNotice, Duration buffer, Duration hold,
                           Duration singleSession, Duration blockSession) {
    }

    @Transactional(readOnly = true)
    public Settings current() {
        return repo.findById(ROW)
                .map(s -> new Settings(
                        Duration.ofMinutes(s.getMinNoticeMinutes()),
                        Duration.ofMinutes(s.getBufferMinutes()),
                        Duration.ofMinutes(s.getHoldMinutes()),
                        Duration.ofMinutes(s.getSingleSessionMinutes()),
                        Duration.ofMinutes(s.getBlockSessionMinutes())))
                // V30 seeds the row, so this is only reached on a database that was never
                // migrated past it -- and then the configured values are the right ones.
                .orElseGet(() -> new Settings(base.minLeadTime(), Duration.ZERO,
                        Duration.ofHours(2), base.sessionLength(), base.blockSessionLength()));
    }

    /** The configured policy, with the admin's notice and session lengths in force. */
    @Transactional(readOnly = true)
    public CoachingPolicy effectivePolicy() {
        Settings s = current();
        return base.withSettings(s.minNotice(), s.singleSession(), s.blockSession());
    }

    /**
     * How long a session bought under this rate-card variant runs.
     *
     * <p>Any variant granting more than one session is a block, the same rule as before,
     * so a future twelve-session pack is a block on the day it is priced.
     */
    @Transactional(readOnly = true)
    public Duration sessionLengthFor(String variant) {
        Settings s = current();
        return props.coaching().creditsFor(variant) > 1 ? s.blockSession() : s.singleSession();
    }

    /**
     * Change the settings. A change applies to slots offered from now on; sessions already
     * booked keep the length they were booked at, and credits keep the length they were
     * bought at.
     */
    @Transactional
    public Settings update(int minNoticeMinutes, int bufferMinutes, int holdMinutes,
                           int singleSessionMinutes, int blockSessionMinutes, Long adminId) {
        requireRange("Minimum notice", minNoticeMinutes, 0, 7 * 24 * 60);
        requireRange("Buffer between sessions", bufferMinutes, 0, 4 * 60);
        requireRange("Checkout hold", holdMinutes, 15, 48 * 60);
        requireRange("Single session length", singleSessionMinutes, 15, 4 * 60);
        requireRange("Package session length", blockSessionMinutes, 15, 4 * 60);
        if (Duration.ofMinutes(minNoticeMinutes).compareTo(base.maxAdvance()) >= 0) {
            throw new ApiExceptions.BadRequestException(
                    "Minimum notice must be shorter than how far ahead customers can book.");
        }
        CoachingSettingsEntity row = repo.findById(ROW).orElseThrow(() ->
                new IllegalStateException("coaching_settings has no row; V30 seeds one"));
        row.apply(minNoticeMinutes, bufferMinutes, holdMinutes, singleSessionMinutes,
                blockSessionMinutes, adminId, clock.instant());
        repo.save(row);
        log.info("Coaching settings changed by account {}: notice {}m, buffer {}m, hold {}m, "
                        + "single {}m, package {}m", adminId, minNoticeMinutes, bufferMinutes,
                holdMinutes, singleSessionMinutes, blockSessionMinutes);
        return current();
    }

    private static void requireRange(String what, int value, int min, int max) {
        if (value < min || value > max) {
            throw new ApiExceptions.BadRequestException(
                    what + " must be between " + min + " and " + max + " minutes.");
        }
    }
}
