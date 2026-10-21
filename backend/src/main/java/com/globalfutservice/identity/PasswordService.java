package com.globalfutservice.identity;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

import com.globalfutservice.config.AppProperties;
import com.globalfutservice.domain.crypto.SecureIds;
import com.globalfutservice.notify.AccountMailer;
import com.globalfutservice.notify.email.AccountEmails;
import com.globalfutservice.security.JwtService;
import com.globalfutservice.web.ApiExceptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Forgot password, reset password, change password, and "Set a password" for an account
 * made with Google or Discord.
 *
 * <ul>
 *   <li><b>One answer to "Forgot password?"</b>, account or not, and the email goes out off
 *       the request thread so the timing does not tell either.</li>
 *   <li><b>The link's token is random and stored hashed</b>, works once, and lapses after
 *       {@code passwordResetTtl} (30 minutes). Asking again voids the older link.</li>
 *   <li><b>A new password signs the account out.</b> After a reset, everywhere; after a
 *       change from Account settings, everywhere but the device that made it. Either way a
 *       "your password was changed" email follows.</li>
 * </ul>
 *
 * <p>An account made with Google or Discord has no password. The reset link is how it gets
 * one, which is also what proves the person setting it holds the mailbox.
 */
@Service
public class PasswordService {

    private static final Logger log = LoggerFactory.getLogger(PasswordService.class);

    /** What "Forgot password?" says every time. The storefront words it in each language. */
    public static final String FORGOT_REPLY = "If an account exists for that email, we've sent a reset link.";

    static final String LINK_INVALID = "reset_link_invalid";

    private final AccountRepository accounts;
    private final PasswordResetTokenRepository resetTokens;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final SignInThrottle throttle;
    private final AccountMailer mailer;
    private final AppProperties props;
    private final Clock clock;

    public PasswordService(AccountRepository accounts, PasswordResetTokenRepository resetTokens,
                           RefreshTokenRepository refreshTokens, PasswordEncoder passwordEncoder,
                           JwtService jwtService, SignInThrottle throttle, AccountMailer mailer,
                           AppProperties props, Clock clock) {
        this.accounts = accounts;
        this.resetTokens = resetTokens;
        this.refreshTokens = refreshTokens;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.throttle = throttle;
        this.mailer = mailer;
        this.props = props;
        this.clock = clock;
    }

    /**
     * "Forgot password?": mails a reset link if the address has an active account, and
     * otherwise does nothing. The caller answers {@link #FORGOT_REPLY} either way.
     */
    @Transactional
    public void requestReset(String email, String language, String ip) {
        String normalised = AccountEntity.normalise(email);
        throttle.spendResetRequest(normalised, ip);
        accounts.findByEmailNormalised(normalised)
                .filter(AccountEntity::isActive)
                .ifPresent(account -> sendLink(account, AccountEmails.Lang.of(language)));
    }

    /**
     * "Set a password", signed in: the same link, to the account's own address. Counted
     * against the same per-address budget as "Forgot password?".
     */
    @Transactional
    public void sendSetPasswordLink(Long accountId, String language, String ip) {
        AccountEntity account = accounts.findById(accountId)
                .filter(AccountEntity::isActive)
                .orElseThrow(() -> new ApiExceptions.ForbiddenException("Please sign in."));
        throttle.spendResetRequest(account.getEmailNormalised(), ip);
        sendLink(account, AccountEmails.Lang.of(language));
    }

    /**
     * Sets the password a link was sent for, and signs the account out everywhere.
     *
     * <p>One refusal for every way a link can be no good -- unknown, used, voided or out of
     * time -- because the person holding it can do the same thing about all four: ask for a
     * new one.
     */
    @Transactional
    public void resetPassword(String rawToken, String newPassword, String language) {
        Instant now = clock.instant();
        PasswordResetTokenEntity token = resetTokens.findByTokenHash(jwtService.hashRefreshToken(rawToken))
                .orElseThrow(PasswordService::linkInvalid);
        if (resetTokens.spend(token.getId(), now) != 1) {
            throw linkInvalid();
        }
        AccountEntity account = accounts.findById(token.getAccountId())
                .filter(AccountEntity::isActive)
                .orElseThrow(PasswordService::linkInvalid);

        account.setPasswordHash(passwordEncoder.encode(newPassword));
        // They have just proved they hold the mailbox: a lock from somebody else's guessing
        // should not keep them out of the account they recovered.
        account.setFailedLoginCount(0);
        account.setLockedUntil(null);
        accounts.save(account);

        resetTokens.revokeOpen(account.getId(), now);
        refreshTokens.revokeAllForAccount(account.getId(), now);
        log.info("Password reset for account {}; every session signed out", account.getPublicId());
        mailer.sendPasswordChanged(account.getEmail(), account.getPublicId(), AccountEmails.Lang.of(language), now);
    }

    /**
     * Account settings: the current password, then the new one. Every other device is signed
     * out; this one keeps its session, identified by the refresh cookie it holds.
     *
     * <p>A wrong current password counts as a failed sign-in for the address, so a borrowed
     * session cannot be used to guess it any faster than the sign-in form allows.
     */
    @Transactional
    public void changePassword(Long accountId, String currentPassword, String newPassword,
                               String language, String currentRefreshToken, String ip) {
        AccountEntity account = accounts.findById(accountId)
                .filter(AccountEntity::isActive)
                .orElseThrow(() -> new ApiExceptions.ForbiddenException("Please sign in."));
        if (account.getPasswordHash() == null) {
            throw new ApiExceptions.BadRequestException("no_password",
                    "This account signs in with Google or Discord. Use \"Set a password\" to add one.");
        }
        throttle.checkSignIn(account.getEmailNormalised(), ip);
        if (!passwordEncoder.matches(currentPassword, account.getPasswordHash())) {
            throttle.recordFailure(account.getEmailNormalised(), ip);
            throw new ApiExceptions.BadRequestException("current_password_incorrect",
                    "Your current password is incorrect.");
        }

        Instant now = clock.instant();
        account.setPasswordHash(passwordEncoder.encode(newPassword));
        accounts.save(account);
        resetTokens.revokeOpen(account.getId(), now);

        Optional<String> keep = currentFamily(currentRefreshToken, account.getId());
        if (keep.isPresent()) {
            refreshTokens.revokeAllForAccountExcept(account.getId(), keep.get(), now);
        } else {
            refreshTokens.revokeAllForAccount(account.getId(), now);
        }
        log.info("Password changed for account {}; other sessions signed out", account.getPublicId());
        mailer.sendPasswordChanged(account.getEmail(), account.getPublicId(), AccountEmails.Lang.of(language), now);
    }

    // ----------------------------------------------------------------- private

    private void sendLink(AccountEntity account, AccountEmails.Lang lang) {
        Instant now = clock.instant();
        resetTokens.revokeOpen(account.getId(), now);
        String raw = SecureIds.token(32);
        resetTokens.save(new PasswordResetTokenEntity(account.getId(), jwtService.hashRefreshToken(raw),
                now.plus(props.security().passwordResetTtl())));
        log.info("Password reset link issued for account {}", account.getPublicId());
        mailer.sendResetLink(account.getEmail(), account.getPublicId(), lang, raw,
                props.security().passwordResetTtl().toMinutes());
    }

    /** The session family the request's own refresh cookie belongs to, if it is this account's. */
    private Optional<String> currentFamily(String rawRefreshToken, Long accountId) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            return Optional.empty();
        }
        return refreshTokens.findByTokenHash(jwtService.hashRefreshToken(rawRefreshToken))
                .filter(t -> t.getAccountId().equals(accountId))
                .map(RefreshTokenEntity::getFamilyId);
    }

    private static ApiExceptions.BadRequestException linkInvalid() {
        return new ApiExceptions.BadRequestException(LINK_INVALID,
                "This reset link has expired or has already been used. Ask for a new one.");
    }
}
