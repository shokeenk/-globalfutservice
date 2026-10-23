package com.globalfutservice.notify.feed;

import com.globalfutservice.coaching.AfterCommit;
import com.globalfutservice.identity.AccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * The customer's notification feed: writing to it, and reading it back.
 *
 * <p><b>Writing never fails, or waits on, the thing that caused it.</b> A notification is
 * written only after the caller's transaction has committed, in a short transaction of its
 * own, and its errors -- a failed commit included -- are logged and dropped. An order must
 * not fail to be placed because a notification row could not be written.
 *
 * <p>It once wrote straight away in a transaction of its own. Inside checkout, which holds
 * the customer's account row locked while it spends store credit, that insert (whose foreign
 * key needs a share lock on the same row) waited on its own caller: forever, holding two
 * connections, until 2026-10-23; then for the 10 s lock timeout, after which the failed
 * commit rolled the order back.
 *
 * <p><b>A broadcast is fanned out, not shared.</b> One row per customer means the read
 * state lives on the row, the unread count is one indexed query, and the feed has one
 * shape rather than two. It costs a row per customer per announcement, which at this
 * scale is nothing and at ten thousand customers is still a single batch insert — worth
 * revisiting only if announcements become frequent and the customer list becomes large.
 */
@Service
public class CustomerFeedService {

    private static final Logger log = LoggerFactory.getLogger(CustomerFeedService.class);

    /** What the bell asks for. Deliberately small: a feed is read from the top. */
    private static final int MAX_FEED = 50;

    private final CustomerNotificationRepository repository;
    private final AccountRepository accounts;
    private final AfterCommit afterCommit;

    public CustomerFeedService(CustomerNotificationRepository repository, AccountRepository accounts,
                               AfterCommit afterCommit) {
        this.repository = repository;
        this.accounts = accounts;
        this.afterCommit = afterCommit;
    }

    /**
     * Records one notification for one customer.
     *
     * <p>A null account is a guest order — there is no bell to put it in, and the email
     * and the tracking page are how those customers are told.
     *
     * <p>Within a transaction: written once it commits, and not at all if it rolls back.
     * Outside one: written now. Either way in its own transaction, and never thrown.
     */
    public void record(Long accountId, NotificationKind kind, String title,
                       String body, String link, String orderRef) {
        if (accountId == null) {
            return;
        }
        // The order, the payment and the alert all matter more than the bell.
        afterCommit.run("notification for account " + accountId, () ->
                repository.save(new CustomerNotificationEntity(accountId, kind, title, body, link, orderRef)));
    }

    /** Sends one notice to every customer account. Returns how many were written. */
    @Transactional
    public int broadcast(String title, String body, String link) {
        List<Long> ids = accounts.findAll().stream()
                .filter(account -> account.getDisabledAt() == null)
                .map(com.globalfutservice.identity.AccountEntity::getId)
                .toList();
        List<CustomerNotificationEntity> rows = ids.stream()
                .map(id -> new CustomerNotificationEntity(
                        id, NotificationKind.ANNOUNCEMENT, title, body, link, null))
                .toList();
        repository.saveAll(rows);
        log.info("Broadcast \"{}\" to {} accounts", title, rows.size());
        return rows.size();
    }

    @Transactional(readOnly = true)
    public List<CustomerNotificationEntity> feed(Long accountId, int limit) {
        return repository.findByAccountIdOrderByCreatedAtDesc(
                accountId, PageRequest.of(0, Math.clamp(limit, 1, MAX_FEED)));
    }

    @Transactional(readOnly = true)
    public long unreadCount(Long accountId) {
        return repository.countByAccountIdAndReadAtIsNull(accountId);
    }

    @Transactional
    public int markAllRead(Long accountId) {
        return repository.markAllRead(accountId, Instant.now());
    }

    /** Marks one row, and only if it belongs to the customer asking. */
    @Transactional
    public void markRead(Long accountId, Long id) {
        repository.findById(id)
                .filter(row -> row.getAccountId().equals(accountId))
                .ifPresent(row -> {
                    row.markRead(Instant.now());
                    repository.save(row);
                });
    }
}
