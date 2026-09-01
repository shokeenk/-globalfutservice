package com.globalfutservice.orders;

import com.globalfutservice.domain.orders.OrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Order lookups.
 *
 * <p>Note that there is <b>no</b> plain {@code findByPublicRef} exposed to customer-facing
 * code. Ownership is part of the query, not an {@code if} after the fetch: broken
 * object-level authorisation is the most commonly exploited API flaw, and the reliable
 * defence is to make the unauthorised row impossible to load rather than remembering to
 * check it every time.
 */
public interface OrderRepository extends JpaRepository<OrderEntity, Long> {

    Optional<OrderEntity> findByPublicRefAndAccountId(String publicRef, Long accountId);

    /** Guest lookup: reference alone is not enough, the email must match too. */
    Optional<OrderEntity> findByPublicRefAndGuestEmail(String publicRef, String guestEmail);

    /** Staff and internal jobs only — never reachable from a customer-facing path. */
    Optional<OrderEntity> findByPublicRef(String publicRef);

    boolean existsByQuoteId(String quoteId);

    Page<OrderEntity> findByAccountIdOrderByCreatedAtDesc(Long accountId, Pageable pageable);

    /**
     * The operations queue, with both filters optional.
     *
     * <p><b>The casts are load-bearing.</b> When {@code search} is null the driver sends an
     * untyped NULL, and PostgreSQL has to guess its type from context. Inside
     * {@code lower(concat('%', ?, '%'))} it guesses {@code bytea} and the statement dies on
     * {@code function lower(bytea) does not exist} — so the whole queue 500s whenever
     * nobody has typed a search term, which is almost always. Casting to string tells the
     * planner what the parameter is before it has to infer.
     *
     * <p>{@code :status} needs no cast: it is compared against a typed column, so the type
     * is inferable. The difference is worth remembering — a null parameter is only a
     * problem where nothing around it pins the type down.
     */
    @Query("""
            select o from OrderEntity o
            where (:status is null or o.status = :status)
              and (cast(:search as string) is null
                   or lower(o.publicRef) like lower(concat('%', cast(:search as string), '%'))
                   or lower(coalesce(o.guestEmail, '')) like lower(concat('%', cast(:search as string), '%')))
            order by o.createdAt desc
            """)
    Page<OrderEntity> findForAdmin(@Param("status") OrderStatus status,
                                   @Param("search") String search,
                                   Pageable pageable);

    /** Backs the job that settles orders whose guarantee window has elapsed. */
    @Query("""
            select o from OrderEntity o
            where o.status = com.globalfutservice.domain.orders.OrderStatus.DELIVERED
              and o.guaranteeExpiresAt < :now
            """)
    List<OrderEntity> findGuaranteeElapsed(@Param("now") Instant now);

    /** Abandoned-checkout sweep. */
    @Query("""
            select o from OrderEntity o
            where o.status in (com.globalfutservice.domain.orders.OrderStatus.DRAFT,
                               com.globalfutservice.domain.orders.OrderStatus.AWAITING_PAYMENT)
              and o.createdAt < :cutoff
            """)
    List<OrderEntity> findStaleUnpaid(@Param("cutoff") Instant cutoff);

    @Query("select count(o) from OrderEntity o where o.status = :status")
    long countByStatus(@Param("status") OrderStatus status);

    @Query("""
            select coalesce(sum(o.totalMinor), 0) from OrderEntity o
            where o.status in (com.globalfutservice.domain.orders.OrderStatus.DELIVERED,
                               com.globalfutservice.domain.orders.OrderStatus.COMPLETED)
              and o.createdAt >= :since
            """)
    long revenueSince(@Param("since") Instant since);

    /**
     * Orders the supplier is still working.
     *
     * <p>Bounded by status rather than by "has a supplier id", so a delivered or refunded
     * order stops being polled the moment it settles — otherwise every order ever
     * dispatched would be re-read every minute for the life of the system.
     *
     * <p>Ordered oldest-poll-first so that when there are more open orders than one bulk
     * call can carry, the ones waiting longest are the ones that get read.
     */
    @Query("""
           SELECT o FROM OrderEntity o
            WHERE o.supplierOrderId IS NOT NULL
              AND o.status IN (com.globalfutservice.domain.orders.OrderStatus.READY_FOR_DELIVERY,
                               com.globalfutservice.domain.orders.OrderStatus.IN_PROGRESS,
                               com.globalfutservice.domain.orders.OrderStatus.ON_HOLD)
            ORDER BY o.supplierPolledAt ASC NULLS FIRST
           """)
    List<OrderEntity> findOpenSupplierOrders();
}
