package com.globalfutservice.orders;

import com.globalfutservice.domain.orders.OrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
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
public interface OrderRepository extends JpaRepository<OrderEntity, Long>,
        JpaSpecificationExecutor<OrderEntity> {

    Optional<OrderEntity> findByPublicRefAndAccountId(String publicRef, Long accountId);

    /** Guest lookup: reference alone is not enough, the email must match too. */
    Optional<OrderEntity> findByPublicRefAndGuestEmail(String publicRef, String guestEmail);

    /** Staff and internal jobs only — never reachable from a customer-facing path. */
    Optional<OrderEntity> findByPublicRef(String publicRef);

    boolean existsByQuoteId(String quoteId);

    /**
     * Whether an order placed since {@code since} carries this coupon code: one whose
     * redemption was handed back (abandoned, refunded) still shows the discount it had.
     */
    boolean existsByCouponCodeAndCreatedAtGreaterThanEqual(String couponCode, java.time.Instant since);

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

    /**
     * Abandoned-checkout sweep: the candidates. {@link PayByDeadline} has the last word on
     * each, for an order whose payment was rejected after its deadline.
     *
     * <p>Skips an order while a Payop invoice for it can still be paid: Payop cannot cancel
     * an invoice, so abandoning the order under it would turn a customer's payment into a
     * refund. And skips one whose payment claim is waiting to be checked: the customer says
     * they have paid, and abandoning the order would leave a verified payment on a cancelled
     * order.
     */
    @Query("""
            select o from OrderEntity o
            where o.status in (com.globalfutservice.domain.orders.OrderStatus.DRAFT,
                               com.globalfutservice.domain.orders.OrderStatus.AWAITING_PAYMENT)
              and o.createdAt < :cutoff
              and not exists (select 1 from PayopInvoiceEntity i
                              where i.orderId = o.id and i.expiresAt > :now
                                and (i.invoiceId is not null or i.status = :creating))
              and not exists (select 1 from ManualPaymentClaimEntity c
                              where c.orderId = o.id and c.status = :submitted)
            """)
    List<OrderEntity> findStaleUnpaid(@Param("cutoff") Instant cutoff, @Param("now") Instant now,
                                      @Param("creating") com.globalfutservice.payments.payop.PayopInvoiceEntity.Status creating,
                                      @Param("submitted") com.globalfutservice.domain.payments.ClaimStatus submitted);

    @Query("select count(o) from OrderEntity o where o.status = :status")
    long countByStatus(@Param("status") OrderStatus status);

    /**
     * Every order counted by service and status, in one pass.
     *
     * <p>The Orders page's two rows of tabs are both read from this: the service tabs sum
     * across statuses, the status tabs sum within the chosen service. One grouped count is
     * a handful of rows however many orders there are, where a count per tab would be
     * thirty queries every twenty seconds.
     *
     * @return rows of {@code [Sku, OrderStatus, Long]}
     */
    @Query("select o.sku, o.status, count(o) from OrderEntity o group by o.sku, o.status")
    List<Object[]> countBySkuAndStatus();

    @Query("""
            select coalesce(sum(o.totalMinor), 0) from OrderEntity o
            where o.status in (com.globalfutservice.domain.orders.OrderStatus.DELIVERED,
                               com.globalfutservice.domain.orders.OrderStatus.COMPLETED)
              and o.createdAt >= :since
            """)
    long revenueSince(@Param("since") Instant since);

    /**
     * Revenue by the same rule as {@link #revenueSince}, over a window and per currency.
     *
     * <p>Delivered and completed orders, dated by when they were placed. Grouped by
     * currency rather than summed across them: there are no exchange rates in the system,
     * so a rupee total with pounds added into it would be a number nobody can use.
     *
     * @return rows of {@code [Currency, Long minor units]}
     */
    @Query("""
            select o.currency, coalesce(sum(o.totalMinor), 0) from OrderEntity o
            where o.status in (com.globalfutservice.domain.orders.OrderStatus.DELIVERED,
                               com.globalfutservice.domain.orders.OrderStatus.COMPLETED)
              and o.createdAt >= :from and o.createdAt < :before
            group by o.currency
            """)
    List<Object[]> revenueByCurrency(@Param("from") Instant from, @Param("before") Instant before);

}
