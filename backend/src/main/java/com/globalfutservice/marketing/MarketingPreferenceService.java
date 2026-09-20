package com.globalfutservice.marketing;

import com.globalfutservice.identity.AccountEntity;
import com.globalfutservice.identity.AccountRepository;
import com.globalfutservice.web.ApiExceptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Marketing consent for somebody who is signed in.
 *
 * <p>Separate from the unsubscribe path in {@link CampaignService}, because the two
 * answer different questions. That one is reached from a link in an inbox and identifies
 * the customer by an opaque token, since the person clicking it usually is not logged in
 * and cannot be asked to be. This one is reached from the account page by a session that
 * has already proved who it is, and is the only path that can turn consent back <i>on</i>
 * — a token found in an old email must never be able to do that, or a leaked link becomes
 * a resubscribe.
 */
@Service
public class MarketingPreferenceService {

    private static final Logger log = LoggerFactory.getLogger(MarketingPreferenceService.class);

    private final AccountRepository accounts;

    public MarketingPreferenceService(AccountRepository accounts) {
        this.accounts = accounts;
    }

    @Transactional(readOnly = true)
    public boolean isOptedIn(Long accountId) {
        return require(accountId).isMarketingOptIn();
    }

    /**
     * Set the preference and return what it now is.
     *
     * <p>Touches the marketing columns only. Order email is not a preference and is not
     * reachable from here.
     */
    @Transactional
    public boolean set(Long accountId, boolean optIn) {
        AccountEntity account = require(accountId);
        if (account.isMarketingOptIn() == optIn) {
            // Already there. Returning early keeps the consent timestamp honest: it should
            // say when they agreed, not when they last re-saved a settings page.
            return optIn;
        }
        if (optIn) {
            account.optInToMarketing();
        } else {
            account.optOutOfMarketing(null);
        }
        accounts.save(account);
        log.info("Account {} marketing consent set to {}", account.getPublicId(), optIn);
        return optIn;
    }

    private AccountEntity require(Long accountId) {
        return accounts.findById(accountId).orElseThrow(
                () -> new ApiExceptions.NotFoundException("No such account."));
    }
}
