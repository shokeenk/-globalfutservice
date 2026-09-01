package com.globalfutservice.identity;

import java.util.Locale;
import java.util.Optional;

import com.globalfutservice.domain.crypto.SecureIds;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns a provider identity into an account here.
 *
 * <p>Three cases, in the order they are tried. <b>Already linked</b> — a row already
 * carries this provider and subject, so it is that customer and nothing else is
 * checked. <b>Same verified email</b> — a password account exists at the address the
 * provider asserts, and the provider says it verified it, so the two are the same
 * person and the identity is attached to the existing account. <b>Neither</b> — a new
 * account, with no password set.
 *
 * <p><b>Why the verified flag decides everything.</b> Linking on a bare email match is
 * an account-takeover route: anyone who can put {@code someone@example.com} on a
 * provider account inherits the order history, the reward balance and the saved
 * details of whoever registered here with that address. Google reports
 * {@code email_verified} and Discord reports {@code verified}, and an unverified
 * assertion is treated as no assertion at all — the customer is asked to sign in with
 * their password first, which proves they hold both sides before anything is merged.
 *
 * <p><b>The subject is the key, not the email.</b> Addresses change; a Google {@code sub}
 * and a Discord snowflake do not. Keying on the subject means a customer who changes
 * their email at the provider still lands on their own account rather than a new one.
 */
@Service
public class OAuthAccountService {

    private final AccountRepository accounts;

    public OAuthAccountService(AccountRepository accounts) {
        this.accounts = accounts;
    }

    /** What a provider told us about the person who just came back from the redirect. */
    public record ProviderIdentity(
            String provider,
            String subject,
            String email,
            boolean emailVerified,
            String displayName) {
    }

    /** Why a sign-in could not be completed. Carried to the frontend as a query param. */
    public enum Refusal {
        /** The provider returned no address, so there is nothing to create an account on. */
        NO_EMAIL,
        /**
         * An account exists at this address but the provider did not verify it. Resolved by
         * signing in with the password once; the identity can be linked from the account
         * page afterwards.
         */
        UNVERIFIED_EMAIL_CONFLICT,
    }

    /** Thrown when a sign-in is refused, so the handler can redirect with a reason. */
    public static class RefusedException extends RuntimeException {
        private final transient Refusal refusal;

        public RefusedException(Refusal refusal) {
            super(refusal.name());
            this.refusal = refusal;
        }

        public Refusal refusal() {
            return refusal;
        }
    }

    @Transactional
    public AccountEntity resolve(ProviderIdentity identity) {
        Optional<AccountEntity> linked =
                accounts.findByOauthProviderAndOauthSubject(identity.provider(), identity.subject());
        if (linked.isPresent()) {
            return linked.get();
        }

        if (identity.email() == null || identity.email().isBlank()) {
            throw new RefusedException(Refusal.NO_EMAIL);
        }
        String normalised = identity.email().trim().toLowerCase(Locale.ROOT);

        Optional<AccountEntity> byEmail = accounts.findByEmailNormalised(normalised);
        if (byEmail.isPresent()) {
            AccountEntity existing = byEmail.get();
            if (!identity.emailVerified()) {
                throw new RefusedException(Refusal.UNVERIFIED_EMAIL_CONFLICT);
            }
            /*
             * Attach, do not overwrite. The display name and password on the existing
             * account are the customer's own; a provider's idea of their name is not an
             * improvement on the one they typed, and it is certainly not a reason to touch
             * a password hash.
             */
            existing.setOauthProvider(identity.provider());
            existing.setOauthSubject(identity.subject());
            return accounts.save(existing);
        }

        /*
         * Same constructor the password flow uses, with a null hash. `setEmail` is what
         * maintains the normalised column, so the address goes through it rather than
         * being written to both fields by hand.
         */
        AccountEntity created = new AccountEntity(
                "acc_" + SecureIds.token(12),
                identity.email().trim(),
                null,
                // Never from the provider. A social sign-in produces customers, exactly as
                // self-registration does; there is no claim that can make somebody staff.
                AccountRole.CUSTOMER);
        created.setDisplayName(identity.displayName());
        created.setOauthProvider(identity.provider());
        created.setOauthSubject(identity.subject());
        /*
         * No password hash. The column is nullable precisely so an account can exist
         * without one, and inventing a random password to fill it would leave a
         * credential nobody holds and the reset flow could still target.
         */
        return accounts.save(created);
    }
}
