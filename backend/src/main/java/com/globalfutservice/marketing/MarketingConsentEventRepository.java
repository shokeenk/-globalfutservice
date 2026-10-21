package com.globalfutservice.marketing;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface MarketingConsentEventRepository extends JpaRepository<MarketingConsentEventEntity, Long> {

    List<MarketingConsentEventEntity> findByAccountIdOrderByIdAsc(Long accountId);
}
