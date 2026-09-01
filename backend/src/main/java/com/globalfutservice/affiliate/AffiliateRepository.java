package com.globalfutservice.affiliate;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AffiliateRepository extends JpaRepository<AffiliateEntity, Long> {

    Optional<AffiliateEntity> findByCodeNormalised(String codeNormalised);

    Optional<AffiliateEntity> findByCodeNormalisedAndStatus(String codeNormalised, AffiliateStatus status);

    List<AffiliateEntity> findByAccountId(Long accountId);

    boolean existsByCodeNormalised(String codeNormalised);

    List<AffiliateEntity> findByStatusOrderByCreatedAtDesc(AffiliateStatus status);
}
