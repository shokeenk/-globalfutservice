package com.globalfutservice.admin;

import com.globalfutservice.credentials.CredentialVaultEntity;
import com.globalfutservice.domain.orders.OrderStatus;
import com.globalfutservice.domain.payments.ClaimStatus;
import com.globalfutservice.identity.AccountEntity;
import com.globalfutservice.orders.OrderEntity;
import com.globalfutservice.payments.ManualPaymentClaimEntity;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The Orders page's filters as a query.
 *
 * <p>Built with the criteria API rather than one JPQL string with a dozen
 * {@code (:x is null or ...)} clauses. Seven optional filters written that way need a cast
 * on every nullable parameter — see {@code OrderRepository.findForAdmin} for what happens
 * without one — and a predicate that is simply left out cannot be mis-typed.
 *
 * <p>"Needs attention" is defined once, here, and both the table filter and the counters
 * on the page use it, so the number on the card is always the number of rows the card
 * shows when clicked.
 */
final class AdminOrderSpecs {

    private AdminOrderSpecs() {
    }

    static Specification<OrderEntity> of(AdminOrderFilter filter) {
        return (root, query, cb) -> {
            List<Predicate> where = new ArrayList<>();
            if (!filter.skus().isEmpty()) {
                where.add(root.get("sku").in(filter.skus()));
            }
            if (!filter.statuses().isEmpty()) {
                where.add(root.get("status").in(filter.statuses()));
            }
            if (filter.platform() != null) {
                // Coaching records the player's platform in a column of its own.
                where.add(cb.or(
                        cb.equal(root.get("platform"), filter.platform()),
                        cb.equal(root.get("coachingPlatform"), filter.platform())));
            }
            if (filter.createdFrom() != null) {
                where.add(cb.greaterThanOrEqualTo(root.get("createdAt"), filter.createdFrom()));
            }
            if (filter.createdBefore() != null) {
                where.add(cb.lessThan(root.get("createdAt"), filter.createdBefore()));
            }
            if (filter.search() != null) {
                where.add(search(root, query, cb, filter.search()));
            }
            if (filter.attention()) {
                where.add(cb.or(
                        paymentToCheck(root, query, cb),
                        signInToWork(root, query, cb),
                        cb.equal(root.get("status"), OrderStatus.DISPUTED)));
            }
            return cb.and(where.toArray(Predicate[]::new));
        };
    }

    /** Waiting for payment, and the customer says they have paid. */
    static Specification<OrderEntity> paymentsToCheck() {
        return AdminOrderSpecs::paymentToCheck;
    }

    /** Paid, sign-in on file, and nobody has started it. */
    static Specification<OrderEntity> signInsToWork() {
        return AdminOrderSpecs::signInToWork;
    }

    private static Predicate paymentToCheck(Root<OrderEntity> root, CriteriaQuery<?> query,
                                            CriteriaBuilder cb) {
        Subquery<Long> claim = query.subquery(Long.class);
        Root<ManualPaymentClaimEntity> c = claim.from(ManualPaymentClaimEntity.class);
        claim.select(c.get("id")).where(
                cb.equal(c.get("orderId"), root.get("id")),
                cb.equal(c.get("status"), ClaimStatus.SUBMITTED));
        return cb.and(
                cb.equal(root.get("status"), OrderStatus.AWAITING_PAYMENT),
                cb.exists(claim));
    }

    private static Predicate signInToWork(Root<OrderEntity> root, CriteriaQuery<?> query,
                                          CriteriaBuilder cb) {
        Subquery<Long> vault = query.subquery(Long.class);
        Root<CredentialVaultEntity> v = vault.from(CredentialVaultEntity.class);
        vault.select(v.get("orderId")).where(
                cb.equal(v.get("orderId"), root.get("id")),
                cb.isNull(v.get("purgedAt")));
        return cb.and(
                cb.equal(root.get("status"), OrderStatus.READY_FOR_DELIVERY),
                cb.exists(vault));
    }

    /**
     * Anything an operator might have in front of them: the reference, the contact email,
     * the name, the EA ID, the Discord name, the account's display name, or the reference
     * on a bank statement line.
     */
    private static Predicate search(Root<OrderEntity> root, CriteriaQuery<?> query,
                                    CriteriaBuilder cb, String term) {
        String pattern = "%" + escapeLike(term.toLowerCase(Locale.ROOT)) + "%";

        Subquery<Long> claim = query.subquery(Long.class);
        Root<ManualPaymentClaimEntity> c = claim.from(ManualPaymentClaimEntity.class);
        claim.select(c.get("id")).where(
                cb.equal(c.get("orderId"), root.get("id")),
                cb.like(cb.lower(c.get("reference")), pattern, '\\'));

        Subquery<Long> account = query.subquery(Long.class);
        Root<AccountEntity> a = account.from(AccountEntity.class);
        account.select(a.get("id")).where(
                cb.equal(a.get("id"), root.get("accountId")),
                cb.like(cb.lower(a.get("displayName")), pattern, '\\'));

        return cb.or(
                cb.like(cb.lower(root.get("publicRef")), pattern, '\\'),
                cb.like(cb.lower(root.get("guestEmail")), pattern, '\\'),
                cb.like(cb.lower(root.get("guestName")), pattern, '\\'),
                cb.like(cb.lower(root.get("eaPlatformHandle")), pattern, '\\'),
                cb.like(cb.lower(root.get("discordUsername")), pattern, '\\'),
                cb.exists(claim),
                cb.exists(account));
    }

    /** A typed % or _ is a character to find, not a wildcard. */
    static String escapeLike(String term) {
        return term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
