package com.globalfutservice.fulfilment;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.springframework.stereotype.Component;

/**
 * The balance FUT Transfer reports, for an admin looking at an order's vendor section.
 *
 * <p>Read live and kept for a minute, so a page open in several tabs does not ask the vendor
 * each time. A read that fails is kept for the minute too -- "unavailable" -- rather than
 * retried on every refresh against a vendor that is already struggling.
 *
 * <p>Never on the send path: a purchase keeps its own reading on the vendor order, taken just
 * before it is sent, and nothing here can delay or stop a send.
 */
@Component
public class VendorBalance {

    static final Duration TTL = Duration.ofSeconds(60);

    /** @param balance as reported, in no confirmed currency; null when it could not be read */
    public record Reading(BigDecimal balance, Instant readAt) {
    }

    private final FutTransferClient client;
    private final Clock clock;
    private Reading cached;

    public VendorBalance(FutTransferClient client, Clock clock) {
        this.client = client;
        this.clock = clock;
    }

    public synchronized Reading current() {
        Instant now = clock.instant();
        if (cached != null && now.isBefore(cached.readAt().plus(TTL))) {
            return cached;
        }
        BigDecimal value = client.balance(null) instanceof FutTransferClient.ReadOk<BigDecimal> ok ? ok.value() : null;
        cached = new Reading(value, now);
        return cached;
    }
}
