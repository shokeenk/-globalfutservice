package com.globalfutservice.pricing;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface CouponRepository extends JpaRepository<CouponEntity, Long> {

    Optional<CouponEntity> findByCode(String code);

    boolean existsByCode(String code);

    Page<CouponEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /**
     * Claim one redemption, atomically.
     *
     * <p><b>This single statement is the redemption limit.</b> Reading the count and then
     * writing count+1 is a lost update waiting to happen: two customers checking out in
     * the same second both read 99 of 100 and both write 100, and the coupon is redeemed
     * 101 times. Here the check and the increment are one UPDATE, so the database decides
     * who gets the last one and the loser sees zero rows affected.
     *
     * <p>The caller must treat a return of 0 as "somebody else took the last one" and
     * refuse the order — not retry, and not proceed.
     *
     * @return 1 when a redemption was claimed, 0 when the coupon was exhausted
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update CouponEntity c
               set c.redeemedCount = c.redeemedCount + 1
             where c.id = :id
               and c.active = true
               and (c.maxRedemptions is null or c.redeemedCount < c.maxRedemptions)
            """)
    int claimRedemption(@Param("id") Long id);

    /**
     * Hand one back, for an order that never completed.
     *
     * <p>Floored at zero so a double release cannot drive the count negative and quietly
     * grant an extra redemption. The unique index on {@code coupon_redemption.order_id} is
     * what actually prevents the double release; this is the belt to its braces.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update CouponEntity c
               set c.redeemedCount = c.redeemedCount - 1
             where c.id = :id
               and c.redeemedCount > 0
            """)
    int releaseRedemption(@Param("id") Long id);

    /** Locked read, for the admin screens that edit a coupon's limits. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from CouponEntity c where c.id = :id")
    Optional<CouponEntity> findForUpdate(@Param("id") Long id);
}
