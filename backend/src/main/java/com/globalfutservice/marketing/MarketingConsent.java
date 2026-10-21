package com.globalfutservice.marketing;

import java.time.Clock;

import com.globalfutservice.identity.AccountEntity;
import com.globalfutservice.identity.AccountRepository;
import com.globalfutservice.identity.ConsentSource;
import org.springframework.stereotype.Component;

/**
 * The one place a marketing choice is written: the account's current answer, how it was
 * given, and a line in the consent log. See V58.
 *
 * <p>Sign-up records the answer either way -- a box left unticked is a choice too, with a
 * time and a source. Account settings and the unsubscribe link record only a change, so the
 * timestamp says when they decided, not when a settings page was last saved.
 *
 * <p>Only ever about promotional email. Order, payment, password and security email is not
 * a preference and nothing here or anywhere else consults it before sending one.
 */
@Component
public class MarketingConsent {

    private final AccountRepository accounts;
    private final MarketingConsentEventRepository events;
    private final Clock clock;

    public MarketingConsent(AccountRepository accounts, MarketingConsentEventRepository events, Clock clock) {
        this.accounts = accounts;
        this.events = events;
        this.clock = clock;
    }

    /**
     * Writes the choice. The account must already be saved.
     *
     * @param campaignId the campaign whose unsubscribe link was used, or null
     */
    public void record(AccountEntity account, boolean optIn, ConsentSource source, Long campaignId) {
        if (optIn) {
            account.optInToMarketing();
        } else {
            account.optOutOfMarketing(campaignId);
        }
        account.setMarketingConsentSource(source);
        accounts.save(account);
        events.save(new MarketingConsentEventEntity(account.getId(), optIn, source, campaignId, clock.instant()));
    }
}
