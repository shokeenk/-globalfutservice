package com.globalfutservice.identity;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface AccountRepository extends JpaRepository<AccountEntity, Long> {

    Optional<AccountEntity> findByEmailNormalised(String emailNormalised);

    Optional<AccountEntity> findByPublicId(String publicId);

    Optional<AccountEntity> findByOauthProviderAndOauthSubject(String provider, String subject);

    boolean existsByEmailNormalised(String emailNormalised);

    @Query("select count(a) from AccountEntity a where a.role in "
            + "(com.globalfutservice.identity.AccountRole.ADMIN, "
            + " com.globalfutservice.identity.AccountRole.OPERATOR)")
    long countStaff();
}
