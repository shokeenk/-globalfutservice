package com.globalfutservice.identity;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import com.globalfutservice.domain.crypto.SecureIds;
import com.globalfutservice.notify.AccountMailer;
import com.globalfutservice.notify.email.AccountEmails;
import com.globalfutservice.security.AttemptThrottle;
import com.globalfutservice.security.JwtService;
import com.globalfutservice.web.ApiExceptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * "Please verify your email", for accounts made with an email and a password.
 *
 * <p>The account works from the moment it is made; verifying only proves the address is
 * theirs, and the storefront asks for it with a banner and a Resend button until it is.
 * The link's token is random, stored as its SHA-256, works once, and lapses after 24 hours;
 * sending another voids the older one. Accounts made with Google or Discord are verified
 * when they are made -- the provider has already checked the address.
 */
@Service
public class EmailVerificationService {

    private static final Logger log = LoggerFactory.getLogger(EmailVerificationService.class);

    static final Duration LINK_TTL = Duration.ofHours(24);
    static final String LINK_INVALID = "verify_link_invalid";

    /** Resend: three an hour per account. Sign-up's own link is not counted. */
    private final AttemptThrottle resends = new AttemptThrottle(3, Duration.ofHours(1));

    private final AccountRepository accounts;
    private final EmailVerificationTokenRepository tokens;
    private final JwtService jwtService;
    private final AccountMailer mailer;
    private final Clock clock;

    public EmailVerificationService(AccountRepository accounts, EmailVerificationTokenRepository tokens,
                                    JwtService jwtService, AccountMailer mailer, Clock clock) {
        this.accounts = accounts;
        this.tokens = tokens;
        this.jwtService = jwtService;
        this.mailer = mailer;
        this.clock = clock;
    }

    /** At sign-up: the first link. Nothing for an account already verified. */
    @Transactional
    public void sendFor(AccountEntity account, String language) {
        if (account.isEmailVerified()) {
            return;
        }
        Instant now = clock.instant();
        tokens.revokeOpen(account.getId(), now);
        String raw = SecureIds.token(32);
        tokens.save(new EmailVerificationTokenEntity(account.getId(), jwtService.hashRefreshToken(raw),
                now.plus(LINK_TTL)));
        mailer.sendVerification(account.getEmail(), account.getPublicId(), AccountEmails.Lang.of(language), raw,
                LINK_TTL.toHours());
    }

    /**
     * The Resend button. Limited per account; already verified is answered as done, not as
     * an error.
     *
     * @return true when the address is already verified and nothing was sent
     */
    @Transactional
    public boolean resend(Long accountId, String language) {
        AccountEntity account = accounts.findById(accountId)
                .filter(AccountEntity::isActive)
                .orElseThrow(() -> new ApiExceptions.ForbiddenException("Please sign in."));
        if (account.isEmailVerified()) {
            return true;
        }
        String key = "acct:" + accountId;
        if (resends.exhausted(key)) {
            throw new ApiExceptions.TooManyRequestsException("too_many_attempts",
                    "We have sent a few links already. Please wait a while before asking again.",
                    resends.retryAt(clock.instant()));
        }
        resends.record(key);
        sendFor(account, language);
        return false;
    }

    /** The link was opened: the address is theirs. One refusal for any link that is no good. */
    @Transactional
    public AccountEntity verify(String rawToken) {
        Instant now = clock.instant();
        EmailVerificationTokenEntity token = tokens.findByTokenHash(jwtService.hashRefreshToken(rawToken))
                .orElseThrow(EmailVerificationService::linkInvalid);
        if (tokens.spend(token.getId(), now) != 1) {
            throw linkInvalid();
        }
        AccountEntity account = accounts.findById(token.getAccountId())
                .filter(AccountEntity::isActive)
                .orElseThrow(EmailVerificationService::linkInvalid);
        if (account.getEmailVerifiedAt() == null) {
            account.setEmailVerifiedAt(now);
            accounts.save(account);
        }
        tokens.revokeOpen(account.getId(), now);
        log.info("Email verified for account {}", account.getPublicId());
        return account;
    }

    private static ApiExceptions.BadRequestException linkInvalid() {
        return new ApiExceptions.BadRequestException(LINK_INVALID,
                "This verification link has expired or has already been used. Ask for a new one.");
    }
}
