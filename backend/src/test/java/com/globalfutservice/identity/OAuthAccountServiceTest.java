package com.globalfutservice.identity;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import com.globalfutservice.identity.OAuthAccountService.ProviderIdentity;
import com.globalfutservice.identity.OAuthAccountService.Refusal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The rules that decide whose account a social sign-in lands on.
 *
 * <p>The refusal cases are the point of this file. It is easy to write a linker that
 * always finds an account and never says no, and it will pass every happy-path test
 * while handing one customer's order history to anybody who can put their address on a
 * Discord profile. The tests that assert a refusal are the ones protecting that.
 */
class OAuthAccountServiceTest {

    private FakeAccounts accounts;
    private OAuthAccountService service;

    @BeforeEach
    void setUp() {
        accounts = new FakeAccounts();
        service = new OAuthAccountService(accounts);
    }

    private static ProviderIdentity google(String sub, String email, boolean verified) {
        return new ProviderIdentity("google", sub, email, verified, "Ada");
    }

    @Nested
    @DisplayName("an identity already linked")
    class AlreadyLinked {

        @Test
        @DisplayName("returns that account without looking at the email at all")
        void returnsLinkedAccount() {
            AccountEntity existing = accounts.withOauth("google", "sub-1", "old@example.com");

            // A changed address at the provider must not produce a second account.
            AccountEntity got = service.resolve(google("sub-1", "brand-new@example.com", true));

            assertThat(got).isSameAs(existing);
            assertThat(accounts.saved).isEmpty();
        }
    }

    @Nested
    @DisplayName("no linked identity, but an account at that address")
    class EmailCollision {

        @Test
        @DisplayName("links when the provider verified the address")
        void linksVerified() {
            AccountEntity existing = accounts.withPassword("ada@example.com");

            AccountEntity got = service.resolve(google("sub-2", "ada@example.com", true));

            assertThat(got).isSameAs(existing);
            assertThat(got.getOauthProvider()).isEqualTo("google");
            assertThat(got.getOauthSubject()).isEqualTo("sub-2");
        }

        @Test
        @DisplayName("refuses when the provider did not verify it")
        void refusesUnverified() {
            accounts.withPassword("ada@example.com");

            assertThatThrownBy(() -> service.resolve(google("sub-3", "ada@example.com", false)))
                    .isInstanceOf(OAuthAccountService.RefusedException.class)
                    .extracting(e -> ((OAuthAccountService.RefusedException) e).refusal())
                    .isEqualTo(Refusal.UNVERIFIED_EMAIL_CONFLICT);
        }

        @Test
        @DisplayName("does not touch the existing password when it links")
        void keepsPassword() {
            AccountEntity existing = accounts.withPassword("ada@example.com");
            String before = existing.getPasswordHash();

            service.resolve(google("sub-4", "ada@example.com", true));

            assertThat(existing.getPasswordHash()).isEqualTo(before);
        }

        @Test
        @DisplayName("matches on a differently-cased address")
        void matchesCaseInsensitively() {
            AccountEntity existing = accounts.withPassword("ada@example.com");

            AccountEntity got = service.resolve(google("sub-5", "  Ada@Example.COM ", true));

            assertThat(got).isSameAs(existing);
        }
    }

    @Nested
    @DisplayName("nobody here yet")
    class NewAccount {

        @Test
        @DisplayName("creates a customer with no password")
        void createsPasswordless() {
            AccountEntity got = service.resolve(google("sub-6", "new@example.com", true));

            assertThat(got.getPasswordHash()).isNull();
            assertThat(got.getRole()).isEqualTo(AccountRole.CUSTOMER);
            assertThat(got.getOauthSubject()).isEqualTo("sub-6");
            assertThat(got.getEmailNormalised()).isEqualTo("new@example.com");
        }

        @Test
        @DisplayName("creates one even when the provider has not verified the address")
        void unverifiedIsFineWithNoCollision() {
            // Nothing to take over, so an unverified address is only a weaker claim about
            // an address nobody else holds — not a route into somebody's account.
            AccountEntity got = service.resolve(google("sub-7", "nobody@example.com", false));

            assertThat(got.getOauthSubject()).isEqualTo("sub-7");
        }

        @Test
        @DisplayName("refuses when the provider returned no address")
        void refusesWithoutEmail() {
            assertThatThrownBy(() -> service.resolve(google("sub-8", null, true)))
                    .isInstanceOf(OAuthAccountService.RefusedException.class)
                    .extracting(e -> ((OAuthAccountService.RefusedException) e).refusal())
                    .isEqualTo(Refusal.NO_EMAIL);
        }
    }

    /** Enough of the repository to exercise the branches, and nothing more. */
    private static final class FakeAccounts implements AccountRepository {
        private final Map<String, AccountEntity> byEmail = new HashMap<>();
        private final Map<String, AccountEntity> byOauth = new HashMap<>();
        private final java.util.List<AccountEntity> saved = new java.util.ArrayList<>();

        AccountEntity withPassword(String email) {
            AccountEntity a = new AccountEntity("acc_test", email, "hashed-secret", AccountRole.CUSTOMER);
            byEmail.put(AccountEntity.normalise(email), a);
            return a;
        }

        AccountEntity withOauth(String provider, String subject, String email) {
            AccountEntity a = new AccountEntity("acc_test", email, null, AccountRole.CUSTOMER);
            a.setOauthProvider(provider);
            a.setOauthSubject(subject);
            byOauth.put(provider + "|" + subject, a);
            return a;
        }

        @Override
        public Optional<AccountEntity> findByEmailNormalised(String email) {
            return Optional.ofNullable(byEmail.get(email));
        }

        @Override
        public Optional<AccountEntity> findByOauthProviderAndOauthSubject(String p, String s) {
            return Optional.ofNullable(byOauth.get(p + "|" + s));
        }

        @Override
        @SuppressWarnings("unchecked")
        public <S extends AccountEntity> S save(S entity) {
            saved.add(entity);
            return entity;
        }

        // ---- unused by these tests -------------------------------------------------
        @Override public Optional<AccountEntity> findByPublicId(String publicId) { return Optional.empty(); }
        @Override public long countStaff() { return 0; }
        @Override public boolean existsByEmailNormalised(String email) { return byEmail.containsKey(email); }
        @Override public java.util.List<AccountEntity> findAll() { return java.util.List.of(); }
        @Override public java.util.List<AccountEntity> findAllById(Iterable<Long> ids) { return java.util.List.of(); }
        @Override public <S extends AccountEntity> java.util.List<S> saveAll(Iterable<S> e) { return java.util.List.of(); }
        @Override public Optional<AccountEntity> findById(Long id) { return Optional.empty(); }
        @Override public boolean existsById(Long id) { return false; }
        @Override public long count() { return byEmail.size(); }
        @Override public void deleteById(Long id) { }
        @Override public void delete(AccountEntity entity) { }
        @Override public void deleteAllById(Iterable<? extends Long> ids) { }
        @Override public void deleteAll(Iterable<? extends AccountEntity> entities) { }
        @Override public void deleteAll() { }
        @Override public void flush() { }
        @Override public <S extends AccountEntity> S saveAndFlush(S entity) { return save(entity); }
        @Override public <S extends AccountEntity> java.util.List<S> saveAllAndFlush(Iterable<S> e) { return java.util.List.of(); }
        @Override public void deleteAllInBatch(Iterable<AccountEntity> entities) { }
        @Override public void deleteAllByIdInBatch(Iterable<Long> ids) { }
        @Override public void deleteAllInBatch() { }
        @Override public AccountEntity getOne(Long id) { return null; }
        @Override public AccountEntity getById(Long id) { return null; }
        @Override public AccountEntity getReferenceById(Long id) { return null; }
        @Override public <S extends AccountEntity> Optional<S> findOne(org.springframework.data.domain.Example<S> e) { return Optional.empty(); }
        @Override public <S extends AccountEntity> java.util.List<S> findAll(org.springframework.data.domain.Example<S> e) { return java.util.List.of(); }
        @Override public <S extends AccountEntity> java.util.List<S> findAll(org.springframework.data.domain.Example<S> e, org.springframework.data.domain.Sort s) { return java.util.List.of(); }
        @Override public <S extends AccountEntity> org.springframework.data.domain.Page<S> findAll(org.springframework.data.domain.Example<S> e, org.springframework.data.domain.Pageable p) { return org.springframework.data.domain.Page.empty(); }
        @Override public <S extends AccountEntity> long count(org.springframework.data.domain.Example<S> e) { return 0; }
        @Override public <S extends AccountEntity> boolean exists(org.springframework.data.domain.Example<S> e) { return false; }
        @Override public <S extends AccountEntity, R> R findBy(org.springframework.data.domain.Example<S> e, java.util.function.Function<org.springframework.data.repository.query.FluentQuery.FetchableFluentQuery<S>, R> f) { return null; }
        @Override public java.util.List<AccountEntity> findAll(org.springframework.data.domain.Sort sort) { return java.util.List.of(); }
        @Override public org.springframework.data.domain.Page<AccountEntity> findAll(org.springframework.data.domain.Pageable pageable) { return org.springframework.data.domain.Page.empty(); }
    }
}
