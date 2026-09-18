package com.globalfutservice.notify.feed;

import com.globalfutservice.identity.AccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * The customer's notification feed: writing to it, and reading it back.
 *
 * <p><b>Writing never fails the thing that caused it.</b> Every write runs in its own
 * transaction and swallows its own errors — an order must not fail to be placed because a
 * notification row could not be written. This is the same rule the Discord and email
 * notifiers already follow, for the same reason.
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

    public CustomerFeedService(CustomerNotificationRepository repository, AccountRepository accounts) {
        this.repository = repository;
        this.accounts = accounts;
    }

    /**
     * Records one notification for one customer.
     *
     * <p>A null account is a guest order — there is no bell to put it in, and the email
     * and the tracking page are how those customers are told.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(Long accountId, NotificationKind kind, String title,
                       String body, String link, String orderRef) {
        if (accountId == null) {
            return;
        }
        try {
            repository.save(new CustomerNotificationEntity(accountId, kind, title, body, link, orderRef));
        } catch (RuntimeException e) {
            // The order, the payment and the alert all matter more than the bell.
            log.warn("Could not record a notification for account {}: {}", accountId, e.toString());
        }
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
