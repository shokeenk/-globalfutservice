package com.globalfutservice.support;

import com.globalfutservice.coaching.AfterCommit;
import com.globalfutservice.config.AppProperties;
import com.globalfutservice.domain.crypto.Hmac;
import com.globalfutservice.domain.crypto.SecureIds;
import com.globalfutservice.notify.NotificationService;
import com.globalfutservice.notify.SupportReplyNotification;
import com.globalfutservice.notify.feed.CustomerFeedService;
import com.globalfutservice.notify.feed.NotificationKind;
import com.globalfutservice.web.ApiExceptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Support tickets as conversations.
 *
 * <p><b>Status follows whoever wrote last.</b> A customer's message makes a ticket OPEN,
 * waiting for staff, and reopens a closed one; a staff reply makes it ANSWERED, waiting for
 * the customer. A note changes nothing, and is never shown or sent to the customer.
 *
 * <p><b>Replies reach the customer by email,</b> with the message in full and a private link
 * to the conversation, and on their bell if they have an account. The link is signed, so
 * it cannot be guessed from a ticket reference; it works without signing in, which is how
 * a guest answers.
 */
@Service
public class SupportService {

    private static final Logger log = LoggerFactory.getLogger(SupportService.class);

    public static final String OPEN = "OPEN";
    public static final String ANSWERED = "ANSWERED";
    public static final String CLOSED = "CLOSED";

    public static final Set<String> CATEGORIES =
            Set.of("COINS", "BOOSTING", "COACHING", "PAYMENT", "ACCOUNT", "TECHNICAL", "OTHER");

    static final int MAX_BODY = 4000;

    /** Kept apart from the quote signatures made with the same secret. */
    private static final String LINK_PURPOSE = "support-ticket-link:";

    private final SupportTicketRepository tickets;
    private final SupportMessageRepository messages;
    private final NotificationService notifications;
    private final CustomerFeedService feed;
    private final AfterCommit afterCommit;
    private final AppProperties props;
    private final Clock clock;

    public SupportService(SupportTicketRepository tickets, SupportMessageRepository messages,
                          NotificationService notifications, CustomerFeedService feed, AfterCommit afterCommit,
                          AppProperties props, Clock clock) {
        this.tickets = tickets;
        this.messages = messages;
        this.notifications = notifications;
        this.feed = feed;
        this.afterCommit = afterCommit;
        this.props = props;
        this.clock = clock;
    }

    /** A category as sent, checked; null stays null. */
    public static String category(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String c = value.trim().toUpperCase(Locale.ROOT);
        if (!CATEGORIES.contains(c)) {
            throw new ApiExceptions.BadRequestException("Unknown category.");
        }
        return c;
    }

    static String body(String value) {
        String b = value == null ? "" : value.trim();
        if (b.isEmpty()) {
            throw new ApiExceptions.BadRequestException("Write a message first.");
        }
        if (b.length() > MAX_BODY) {
            throw new ApiExceptions.BadRequestException("Please keep it under 4000 characters.");
        }
        return b;
    }

    /** The contact form: a new ticket and its first message. */
    @Transactional
    public SupportTicketEntity openFromCustomer(Long accountId, String orderRef, String email, String subject,
                                                String message, String category) {
        SupportTicketEntity ticket = new SupportTicketEntity(newRef(), accountId, blankToNull(orderRef),
                email.trim(), subject.trim(), body(message));
        ticket.setCategory(category(category));
        ticket.setLastActivityAt(clock.instant());
        tickets.save(ticket);
        messages.save(new SupportMessageEntity(ticket.getId(), SupportMessageEntity.CUSTOMER,
                SupportMessageEntity.MESSAGE, ticket.getBody(), null, null));
        return ticket;
    }

    /**
     * Staff writing to a customer first, from their page. The ticket starts waiting for the
     * customer, and they are emailed at once, so their answer lands in this thread.
     */
    @Transactional
    public SupportTicketEntity openFromStaff(Long customerAccountId, String email, String orderRef, String category,
                                             String subject, String message, Long staffId, String staffLabel) {
        String s = subject == null ? "" : subject.trim();
        if (s.isEmpty() || s.length() > 120) {
            throw new ApiExceptions.BadRequestException("Give the message a subject of up to 120 characters.");
        }
        String b = body(message);
        SupportTicketEntity ticket = new SupportTicketEntity(newRef(), customerAccountId, blankToNull(orderRef),
                email.trim(), s, b);
        ticket.setCategory(category(category));
        ticket.setOpenedBy("STAFF");
        ticket.setStatus(ANSWERED);
        ticket.setLastActivityAt(clock.instant());
        tickets.save(ticket);
        messages.save(new SupportMessageEntity(ticket.getId(), SupportMessageEntity.STAFF,
                SupportMessageEntity.MESSAGE, b, staffId, staffLabel));
        tellCustomer(ticket, b, true);
        log.info("Staff {} opened support ticket {} to a customer", staffLabel, ticket.getPublicRef());
        return ticket;
    }

    /** A staff reply, which is sent, or a note, which is not. */
    @Transactional
    public SupportMessageEntity staffWrite(SupportTicketEntity ticket, String message, boolean note,
                                           Long staffId, String staffLabel) {
        String b = body(message);
        SupportMessageEntity saved = messages.save(new SupportMessageEntity(ticket.getId(), SupportMessageEntity.STAFF,
                note ? SupportMessageEntity.NOTE : SupportMessageEntity.MESSAGE, b, staffId, staffLabel));
        if (!note) {
            ticket.setStatus(ANSWERED);
            ticket.setResolvedAt(null);
            ticket.setLastActivityAt(clock.instant());
            tickets.save(ticket);
            tellCustomer(ticket, b, false);
        }
        return saved;
    }

    /** The customer answering, from their account or the emailed link. Reopens a closed ticket. */
    @Transactional
    public SupportMessageEntity customerWrite(SupportTicketEntity ticket, String message) {
        String b = body(message);
        SupportMessageEntity saved = messages.save(new SupportMessageEntity(ticket.getId(), SupportMessageEntity.CUSTOMER,
                SupportMessageEntity.MESSAGE, b, null, null));
        ticket.setStatus(OPEN);
        ticket.setResolvedAt(null);
        ticket.setLastActivityAt(clock.instant());
        tickets.save(ticket);
        return saved;
    }

    @Transactional
    public void close(SupportTicketEntity ticket) {
        Instant now = clock.instant();
        ticket.setStatus(CLOSED);
        ticket.setResolvedAt(now);
        ticket.setLastActivityAt(now);
        tickets.save(ticket);
    }

    @Transactional
    public void reopen(SupportTicketEntity ticket) {
        ticket.setStatus(OPEN);
        ticket.setResolvedAt(null);
        ticket.setLastActivityAt(clock.instant());
        tickets.save(ticket);
    }

    @Transactional
    public void setCategory(SupportTicketEntity ticket, String category) {
        ticket.setCategory(category(category));
        tickets.save(ticket);
    }

    public SupportTicketEntity require(String ref) {
        return tickets.findByPublicRef(ref).orElseThrow(() -> new ApiExceptions.NotFoundException("No such ticket."));
    }

    public List<SupportMessageEntity> thread(SupportTicketEntity ticket, boolean withNotes) {
        return withNotes
                ? messages.findByTicketIdOrderByCreatedAtAscIdAsc(ticket.getId())
                : messages.findByTicketIdAndKindOrderByCreatedAtAscIdAsc(ticket.getId(), SupportMessageEntity.MESSAGE);
    }

    /** The key in a customer's link to one ticket. The same every time for the same ticket. */
    public String linkKey(String ref) {
        return Hmac.base64UrlSha256(props.security().quoteSigningSecret(), LINK_PURPOSE + ref);
    }

    public boolean linkKeyMatches(String ref, String key) {
        return key != null && !key.isBlank() && Hmac.constantTimeEquals(linkKey(ref), key);
    }

    /** The customer's link to a ticket, key included, so it opens signed in or not. */
    public String link(String ref) {
        return props.publicUrl() + "/support/tickets/" + ref + "?key=" + linkKey(ref);
    }

    private void tellCustomer(SupportTicketEntity ticket, String message, boolean opened) {
        SupportReplyNotification n = new SupportReplyNotification(ticket.getEmail(), ticket.getPublicRef(),
                ticket.getSubject(), message, link(ticket.getPublicRef()), opened);
        Long account = ticket.getAccountId();
        String ref = ticket.getPublicRef();
        String subject = ticket.getSubject();
        afterCommit.run("support reply on " + ref, () -> {
            notifications.supportReply(n);
            feed.record(account, NotificationKind.SUPPORT_REPLY,
                    opened ? "A message from support" : "Support replied", truncate(subject, 480),
                    "/support/tickets/" + ref, null);
        });
    }

    private String newRef() {
        return "TKT-" + SecureIds.orderRef("26").substring(7);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }
}
