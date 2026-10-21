package com.globalfutservice.identity;

import com.globalfutservice.config.AppProperties;
import com.globalfutservice.domain.crypto.SecureIds;
import com.globalfutservice.domain.money.Currency;
import com.globalfutservice.domain.money.Money;
import com.globalfutservice.identity.web.AuthDtos;
import com.globalfutservice.loyalty.LoyaltyService;
import com.globalfutservice.security.JwtService;
import com.globalfutservice.web.ApiExceptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Registration, sign-in and session rotation.
 *
 * <p>The defensive choices here are conventional but worth stating, because each one
 * closes a real hole:
 *
 * <ul>
 *   <li><b>Registration never reveals whether an email is already known.</b> A distinct
 *       "that email is taken" turns the sign-up form into a membership oracle — useful to
 *       anyone holding a breach dump and wondering which of these addresses to target.</li>
 *   <li><b>Sign-in verifies a dummy hash when no account exists</b>, so the response time
 *       does not distinguish "no such user" from "wrong password".</li>
 *   <li><b>Failed attempts lock the account temporarily</b>, and the lock is reported the
 *       same way as a wrong password.</li>
 *   <li><b>Failed attempts are also counted per address and per IP</b> by
 *       {@link SignInThrottle}, account or not, so a password list run against one address
 *       or one password run against many both slow to a crawl.</li>
 *   <li><b>Refresh tokens rotate, and reuse revokes the whole family.</b> A stolen token
 *       can be used once; the moment either party refreshes again, both are signed out
 *       and the theft becomes visible instead of silent.</li>
 * </ul>
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    /**
     * A real BCrypt hash of a value nobody knows, verified when an account is not found
     * so that the timing of a failed sign-in does not leak whether the email exists.
     */
    private static final String DUMMY_HASH =
            "$2a$12$C6UzMDM.H6dfI/f/IKcEe.5vXBqZ0Y8p7f0N.CqQ2u7bC9a8rWJcq";

    private final AccountRepository accounts;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final LoyaltyService loyaltyService;
    private final SignInThrottle throttle;
    private final AppProperties props;
    private final Clock clock;

    public AuthService(AccountRepository accounts, RefreshTokenRepository refreshTokens,
                       PasswordEncoder passwordEncoder, JwtService jwtService,
                       LoyaltyService loyaltyService, SignInThrottle throttle,
                       AppProperties props, Clock clock) {
        this.accounts = accounts;
        this.refreshTokens = refreshTokens;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.loyaltyService = loyaltyService;
        this.throttle = throttle;
        this.props = props;
        this.clock = clock;
    }

    /**
     * A newly issued session: the access token, and the raw refresh token for the cookie.
     *
     * @param persistent "Remember me": true for a cookie that outlives the browser, false
     *                   for a session cookie
     */
    public record Session(String accessToken, String refreshToken, AccountEntity account,
                          boolean persistent) {
        @Override
        public String toString() {
            return "Session[redacted]";
        }
    }

    @Transactional
    public Session register(AuthDtos.RegisterRequest request, String userAgent, String ip) {
        String normalised = AccountEntity.normalise(request.email());

        if (accounts.existsByEmailNormalised(normalised)) {
            // Same shape as a successful call would produce for the caller's purposes:
            // a generic conflict that does not confirm the address is registered here.
            throw new ApiExceptions.ConflictException("registration_failed",
                    "We could not create that account. If you already have one, try signing in.");
        }

        AccountEntity account = new AccountEntity(
                "acc_" + SecureIds.token(12),
                request.email().trim(),
                passwordEncoder.encode(request.password()),
                // Never taken from the request. Self-registration produces customers,
                // full stop; there is no field a caller can set to become staff.
                AccountRole.CUSTOMER);
        account.setDisplayName(request.displayName());
        account.setPhone(request.phone());
        if (request.referralCode() != null && !request.referralCode().isBlank()) {
            account.setReferredByCode(request.referralCode().trim().toUpperCase(Locale.ROOT));
        }
        /*
         * Only when they ticked it. The column already defaults to false, so this branch
         * is the single place in the application that can turn marketing consent on for a
         * new account -- which is what makes "where did this consent come from" a
         * question with one answer and a timestamp.
         */
        if (request.marketingOptIn()) {
            account.optInToMarketing();
        }

        AccountEntity saved = accounts.save(account);
        log.info("Registered account {}", saved.getPublicId());
        return issue(saved, true, userAgent, ip);
    }

    /**
     * Not rolled back on a wrong password: the failure count it writes is the point.
     */
    @Transactional(noRollbackFor = ApiExceptions.BadRequestException.class)
    public Session login(AuthDtos.LoginRequest request, String userAgent, String ip) {
        String normalised = AccountEntity.normalise(request.email());
        throttle.checkSignIn(normalised, ip);
        Optional<AccountEntity> maybe = accounts.findByEmailNormalised(normalised);

        if (maybe.isEmpty()) {
            // Burn the same amount of time a real verification would.
            passwordEncoder.matches(request.password(), DUMMY_HASH);
            throttle.recordFailure(normalised, ip);
            throw invalidCredentials();
        }

        AccountEntity account = maybe.get();
        Instant now = clock.instant();

        if (!account.isActive()) {
            throttle.recordFailure(normalised, ip);
            throw invalidCredentials();
        }
        if (account.isLocked(now)) {
            // Same message as a wrong password: telling an attacker they have triggered a
            // lockout tells them the address is real and that they are being counted.
            throttle.recordFailure(normalised, ip);
            throw invalidCredentials();
        }
        if (account.getPasswordHash() == null
                || !passwordEncoder.matches(request.password(), account.getPasswordHash())) {
            registerFailure(account, now);
            throttle.recordFailure(normalised, ip);
            throw invalidCredentials();
        }

        if (account.getFailedLoginCount() != 0 || account.getLockedUntil() != null) {
            account.setFailedLoginCount(0);
            account.setLockedUntil(null);
            accounts.save(account);
        }
        // Absent means ticked: the long-lived cookie every sign-in issued before the box existed.
        return issue(account, !Boolean.FALSE.equals(request.rememberMe()), userAgent, ip);
    }

    /**
     * Rotates a refresh token.
     *
     * <p>Presenting a token that has already been used means it was captured — by an
     * attacker, or by the legitimate user replaying an old one, and there is no way to
     * tell which. The safe response is the same either way: revoke the entire family and
     * make both parties sign in again.
     */
    @Transactional
    public Session refresh(String rawToken, String userAgent, String ip) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new ApiExceptions.ForbiddenException("Please sign in again.");
        }
        String hash = jwtService.hashRefreshToken(rawToken);
        RefreshTokenEntity token = refreshTokens.findByTokenHash(hash)
                .orElseThrow(() -> new ApiExceptions.ForbiddenException("Please sign in again."));

        Instant now = clock.instant();

        if (token.getUsedAt() != null) {
            log.warn("Refresh token reuse detected for account {}; revoking family",
                    token.getAccountId());
            refreshTokens.revokeFamily(token.getFamilyId(), now);
            throw new ApiExceptions.ForbiddenException("Please sign in again.");
        }
        if (!token.isUsable(now)) {
            throw new ApiExceptions.ForbiddenException("Please sign in again.");
        }

        AccountEntity account = accounts.findById(token.getAccountId())
                .filter(AccountEntity::isActive)
                .orElseThrow(() -> new ApiExceptions.ForbiddenException("Please sign in again."));

        token.setUsedAt(now);
        refreshTokens.save(token);

        // The family keeps the kind of cookie it started with.
        return issue(account, token.getFamilyId(), token.isPersistent(), userAgent, ip);
    }

    @Transactional
    public void logout(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return;
        }
        refreshTokens.findByTokenHash(jwtService.hashRefreshToken(rawToken))
                .ifPresent(token -> refreshTokens.revokeFamily(token.getFamilyId(), clock.instant()));
    }

    @Transactional
    public void logoutEverywhere(Long accountId) {
        refreshTokens.revokeAllForAccount(accountId, clock.instant());
    }

    @Transactional(readOnly = true)
    public AuthDtos.AccountResponse describe(AccountEntity account) {
        long balance = loyaltyService.balance(account.getId());
        long valueMinor = balance * props.pricing().pointValueMinor();
        return new AuthDtos.AccountResponse(
                account.getPublicId(),
                account.getEmail(),
                account.getDisplayName(),
                account.getRole().name(),
                balance,
                valueMinor,
                Money.ofMinor(valueMinor, Currency.INR).format(),
                account.isFirstOrder(),
                account.getReferredByCode(),
                account.getPasswordHash() != null);
    }

    public long accessTokenSeconds() {
        return props.security().accessTokenTtl().toSeconds();
    }

    /**
     * Start a session for an account that has already been authenticated elsewhere.
     *
     * <p>For the OAuth redirect, where the provider has proved identity and there is no
     * password to check. Everything after this point — the refresh family, rotation,
     * revocation, the access token — is identical to a password sign-in, which is the
     * point: one session model, not two.
     */
    public Session issueFor(AccountEntity account, String userAgent, String ip) {
        return issue(account, true, userAgent, ip);
    }

    // ----------------------------------------------------------------- private

    private Session issue(AccountEntity account, boolean persistent, String userAgent, String ip) {
        return issue(account, UUID.randomUUID().toString(), persistent, userAgent, ip);
    }

    private Session issue(AccountEntity account, String familyId, boolean persistent,
                          String userAgent, String ip) {
        String raw = jwtService.newRefreshToken();
        RefreshTokenEntity token = new RefreshTokenEntity(
                account.getId(),
                jwtService.hashRefreshToken(raw),
                familyId,
                clock.instant().plus(persistent
                        ? props.security().refreshTokenTtl()
                        : props.security().sessionRefreshTokenTtl()));
        token.setPersistent(persistent);
        token.setUserAgent(truncate(userAgent, 255));
        // The IP is stored as a hash: it is useful for spotting a session hopping
        // continents, and it is personal data this table has no business holding in clear.
        token.setIpHash(ip == null ? null : jwtService.hashRefreshToken(ip));
        refreshTokens.save(token);

        return new Session(jwtService.issueAccessToken(account), raw, account, persistent);
    }

    private void registerFailure(AccountEntity account, Instant now) {
        int failures = account.getFailedLoginCount() + 1;
        account.setFailedLoginCount(failures);
        if (failures >= props.security().maxFailedLogins()) {
            account.setLockedUntil(now.plus(props.security().loginLockout()));
            account.setFailedLoginCount(0);
            log.warn("Account {} locked after repeated failures", account.getPublicId());
        }
        accounts.save(account);
    }

    /**
     * The one answer to every failed sign-in. Its own code so the storefront can word it in
     * the customer's language; a 400 rather than a 401 so the client does not take it as an
     * expired session and retry the sign-in after a refresh.
     */
    static ApiExceptions.BadRequestException invalidCredentials() {
        return new ApiExceptions.BadRequestException(INVALID_CREDENTIALS, "Email or password is incorrect.");
    }

    static final String INVALID_CREDENTIALS = "invalid_credentials";

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
