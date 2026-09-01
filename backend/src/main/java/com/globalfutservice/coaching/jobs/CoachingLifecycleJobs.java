package com.globalfutservice.coaching.jobs;

import com.globalfutservice.coaching.CoachingService;
import com.globalfutservice.coaching.CoachingSessionEntity;
import com.globalfutservice.coaching.CoachingSessionRepository;
import com.globalfutservice.coaching.SessionCreditEntity;
import com.globalfutservice.coaching.SessionCreditRepository;
import com.globalfutservice.domain.coaching.SessionActor;
import com.globalfutservice.domain.coaching.SessionCreditType;
import com.globalfutservice.domain.coaching.SessionStatus;
import com.globalfutservice.identity.AccountRepository;
import com.globalfutservice.notify.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * The clocks the coaching lifecycle depends on.
 *
 * <p><b>Deployment note.</b> Same caveat as {@code OrderLifecycleJobs}: these run on every
 * instance. Behind more than one replica, pin them to a node or add ShedLock. The credit
 * ledger's unique indexes would catch a double-settle, but relying on them as the primary
 * control is a hope, not a design.
 */
@Component
public class CoachingLifecycleJobs {

    private static final Logger log = LoggerFactory.getLogger(CoachingLifecycleJobs.class);

    private final CoachingSessionRepository sessions;
    private final SessionCreditRepository credits;
    private final CoachingService coaching;
    private final AccountRepository accounts;
    private final NotificationService notifications;
    private final Clock clock;

    public CoachingLifecycleJobs(CoachingSessionRepository sessions,
                                 SessionCreditRepository credits,
                                 CoachingService coaching,
                                 AccountRepository accounts,
                                 NotificationService notifications,
                                 Clock clock) {
        this.sessions = sessions;
        this.credits = credits;
        this.coaching = coaching;
        this.accounts = accounts;
        this.notifications = notifications;
        this.clock = clock;
    }

    /**
     * Closes out sessions nobody recorded an outcome for.
     *
     * <p><b>Not marked NO_SHOW.</b> That is the important decision here. A session still
     * sitting in SCHEDULED a day later usually means the coach forgot to click a button,
     * not that the customer failed to appear — and the two have opposite consequences for
     * the credit. Guessing "no-show" would quietly charge customers a session for the
     * coach's admin. So the sweep waits a generous window and then settles them as
     * COMPLETED, which is both the likelier truth and the error that fails in the
     * customer's favour. An operator who knows better can record a no-show at any point
     * before this runs.
     *
     * <p>The grace period is deliberately much longer than the policy's no-show grace: that
     * one governs when a <i>coach</i> may mark an absence, this one governs when the system
     * gives up waiting for anybody to say anything at all.
     */
    @Scheduled(cron = "0 25 * * * *")
    @Transactional
    public void settleUnrecordedSessions() {
        Instant cutoff = clock.instant().minus(Duration.ofDays(2));
        try {
            List<CoachingSessionEntity> stale =
                    sessions.findByStatusAndStartsAtBefore(SessionStatus.SCHEDULED, cutoff);
            for (CoachingSessionEntity session : stale) {
                try {
                    coaching.transition(session, SessionStatus.COMPLETED, SessionActor.SYSTEM,
                            null, "Auto-settled: no outcome recorded within 48 hours");
                } catch (Exception e) {
                    log.warn("Could not auto-settle session {}: {}",
                            session.getPublicRef(), e.getMessage());
                }
            }
            if (!stale.isEmpty()) {
                log.info("Auto-settled {} coaching sessions with no recorded outcome", stale.size());
            }
        } catch (Exception e) {
            log.error("Session settlement sweep failed", e);
        }
    }

    /**
     * Retires credits past their validity window.
     *
     * <p>Writes an EXPIRED entry for whatever is left rather than deleting the GRANTED row,
     * so the statement still explains where the credits went. A customer asking "I bought
     * six, I used two, where are the rest?" gets an answer with a date on it.
     *
     * <p>Only ever expires down to zero, never below: the balance is a sum over the whole
     * ledger, and a customer who has already used everything has nothing left to expire.
     */
    @Scheduled(cron = "0 40 3 * * *")
    @Transactional
    public void expireCredits() {
        Instant now = clock.instant();
        try {
            List<SessionCreditEntity> lapsed = credits.findAll().stream()
                    .filter(c -> c.getEntryType() == SessionCreditType.GRANTED)
                    .filter(c -> c.getExpiresAt() != null && !c.getExpiresAt().isAfter(now))
                    .toList();

            for (SessionCreditEntity grant : lapsed) {
                Long accountId = grant.getAccountId();
                int balance = credits.balanceOf(accountId);
                if (balance <= 0) {
                    continue;
                }
                // Expire only what is genuinely unused, and only once per account per run:
                // several grants can lapse on the same day and the balance is shared.
                credits.save(SessionCreditEntity.expired(accountId, balance,
                        "Session credits expired on " + now.toString().substring(0, 10)));
                log.info("Expired {} unused session credits for account {}", balance, accountId);
            }
        } catch (Exception e) {
            log.error("Credit expiry sweep failed", e);
        }
    }

    /**
     * Reminds customers about sessions starting in roughly a day.
     *
     * <p>The window is keyed to the job's own cadence so a session is picked up exactly
     * once: running hourly and selecting the hour that begins 24 hours out means no session
     * falls in two windows, and none falls in none.
     */
    @Scheduled(cron = "0 5 * * * *")
    @Transactional(readOnly = true)
    public void sendReminders() {
        Instant now = clock.instant();
        Instant from = now.plus(Duration.ofHours(24));
        Instant to = from.plus(Duration.ofHours(1));
        try {
            List<CoachingSessionEntity> upcoming = sessions.scheduledBetween(from, to);
            for (CoachingSessionEntity session : upcoming) {
                accounts.findById(session.getAccountId()).ifPresent(account ->
                        notifications.coachingReminder(
                                account.getEmail(),
                                session.getPublicRef(),
                                session.getStartsAt(),
                                // Render in the zone they booked in, falling back to the
                                // business zone. Someone who books at 19:00 IST and reads
                                // "13:30" in an email assumes we moved their session.
                                session.getCustomerTimezone()));
            }
            if (!upcoming.isEmpty()) {
                log.info("Sent {} coaching reminders", upcoming.size());
            }
        } catch (Exception e) {
            log.error("Coaching reminder sweep failed", e);
        }
    }
}
