package com.globalfutservice.affiliate;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AffiliateLedgerRepository extends JpaRepository<AffiliateLedgerEntity, Long> {

    boolean existsByOrderId(Long orderId);

    Page<AffiliateLedgerEntity> findByAffiliateIdOrderByCreatedAtDesc(Long affiliateId, Pageable pageable);

    @Query("""
            select coalesce(sum(l.commissionMinor), 0) from AffiliateLedgerEntity l
            where l.affiliateId = :affiliateId and l.status in ('PENDING', 'PAYABLE')
            """)
    long unpaidCommission(@Param("affiliateId") Long affiliateId);

    @Query("""
            select coalesce(sum(l.commissionMinor), 0) from AffiliateLedgerEntity l
            where l.affiliateId = :affiliateId and l.status = 'PAID'
            """)
    long paidCommission(@Param("affiliateId") Long affiliateId);

    @Query("select count(l) from AffiliateLedgerEntity l where l.affiliateId = :affiliateId")
    long orderCount(@Param("affiliateId") Long affiliateId);

    @Query("""
            select coalesce(sum(l.grossMinor), 0) from AffiliateLedgerEntity l
            where l.affiliateId = :affiliateId
            """)
    long grossReferred(@Param("affiliateId") Long affiliateId);
}
