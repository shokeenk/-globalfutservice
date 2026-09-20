package com.globalfutservice.identity;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface AccountRepository extends JpaRepository<AccountEntity, Long> {

    Optional<AccountEntity> findByEmailNormalised(String emailNormalised);

    Optional<AccountEntity> findByPublicId(String publicId);

    Optional<AccountEntity> findByOauthProviderAndOauthSubject(String provider, String subject);

    /**
     * Lookup by the opaque token an unsubscribe link carries.
     *
     * <p>The token exists so that link does not have to contain the address or the id —
     * an unsubscribe URL travels in plain text and sits in an inbox indefinitely.
     */
    Optional<AccountEntity> findByMarketingToken(java.util.UUID marketingToken);

    boolean existsByEmailNormalised(String emailNormalised);

    @Query("select count(a) from AccountEntity a where a.role in "
            + "(com.globalfutservice.identity.AccountRole.ADMIN, "
            + " com.globalfutservice.identity.AccountRole.OPERATOR)")
    long countStaff();
}
