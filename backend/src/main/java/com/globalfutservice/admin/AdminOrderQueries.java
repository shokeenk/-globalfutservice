package com.globalfutservice.admin;

import com.globalfutservice.credentials.CredentialVaultRepository;
import com.globalfutservice.domain.catalog.Platform;
import com.globalfutservice.domain.catalog.Sku;
import com.globalfutservice.domain.orders.OrderStateMachine;
import com.globalfutservice.domain.orders.OrderStatus;
import com.globalfutservice.identity.AccountEntity;
import com.globalfutservice.identity.AccountRepository;
import com.globalfutservice.orders.OrderEntity;
import com.globalfutservice.orders.OrderRepository;
import com.globalfutservice.orders.OrderService;
import com.globalfutservice.payments.ManualPaymentClaimEntity;
import com.globalfutservice.payments.ManualPaymentClaimRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The reads behind the Orders page: a filtered, paged queue with a real total, and the
 * counters above it.
 *
 * <p>Reads only. Every action the page offers goes through the endpoints that already
 * existed — transition, release, verify — so nothing here can change an order.
 *
 * <p>A page of rows costs four queries whatever its size: the orders, then their claims,
 * their vault rows and their accounts in one query each. The old queue asked the vault
 * once per row.
 */
@Service
public class AdminOrderQueries {

    /** Where "today" is. The business runs on Indian time, as its Discord tickets do. */
    static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Kolkata");

    private static final Sort NEWEST_FIRST =
            Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private final OrderRepository orders;
    private final ManualPaymentClaimRepository claims;
    private final CredentialVaultRepository vault;
    private final AccountRepository accounts;
    private final Clock clock;

    public AdminOrderQueries(OrderRepository orders, ManualPaymentClaimRepository claims,
                             CredentialVaultRepository vault, AccountRepository accounts,
                             Clock clock) {
        this.orders = orders;
        this.claims = claims;
        this.vault = vault;
        this.accounts = accounts;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public AdminOrderViews.Page search(AdminOrderFilter filter, int page, int size) {
        Page<OrderEntity> found = orders.findAll(AdminOrderSpecs.of(filter),
                PageRequest.of(page, size, NEWEST_FIRST));
        return new AdminOrderViews.Page(rows(found.getContent()), found.getTotalElements(),
                page, size);
    }

    /**
     * Rows for an export: the same filter, newest first, at most {@code cap} of them.
     * Read a page at a time so a large export does not load every order at once.
     */
    @Transactional(readOnly = true)
    public List<AdminOrderViews.Row> export(AdminOrderFilter filter, int cap) {
        List<AdminOrderViews.Row> out = new ArrayList<>();
        int chunk = 500;
        for (int page = 0; out.size() < cap; page++) {
            Page<OrderEntity> found = orders.findAll(AdminOrderSpecs.of(filter),
                    PageRequest.of(page, chunk, NEWEST_FIRST));
            List<OrderEntity> content = found.getContent();
            if (content.isEmpty()) {
                break;
            }
            List<OrderEntity> take = content.subList(0, Math.min(content.size(), cap - out.size()));
            out.addAll(rows(take));
            if (!found.hasNext()) {
                break;
            }
        }
        return out;
    }

    @Transactional(readOnly = true)
    public AdminOrderViews.Overview overview() {
        List<AdminOrderViews.StatusCount> counts = new ArrayList<>();
        long awaitingSignIn = 0;
        long disputed = 0;
        for (Object[] row : orders.countBySkuAndStatus()) {
            Sku sku = (Sku) row[0];
            OrderStatus status = (OrderStatus) row[1];
            long n = ((Number) row[2]).longValue();
            counts.add(new AdminOrderViews.StatusCount(sku.name(), status.name(), n));
            if (status == OrderStatus.CREDENTIALS_PENDING) {
                awaitingSignIn += n;
            } else if (status == OrderStatus.DISPUTED) {
                disputed += n;
            }
        }

        Instant now = clock.instant();
        ZonedDateTime local = now.atZone(BUSINESS_ZONE);
        Instant todayStarts = local.toLocalDate().atStartOfDay(BUSINESS_ZONE).toInstant();
        LocalDate yesterday = local.toLocalDate().minusDays(1);
        Instant yesterdayStarts = yesterday.atStartOfDay(BUSINESS_ZONE).toInstant();
        // The same wall-clock time yesterday. Built from the local time rather than by
        // subtracting 24 hours, which is the same thing in India but not everywhere.
        Instant yesterdaySameTime = local.minusDays(1).toInstant();

        return new AdminOrderViews.Overview(
                counts,
                orders.count(AdminOrderSpecs.paymentsToCheck()),
                orders.count(AdminOrderSpecs.signInsToWork()),
                disputed,
                awaitingSignIn,
                orders.count(deliveredBetween(todayStarts, now)),
                orders.count(deliveredBetween(yesterdayStarts, yesterdaySameTime)),
                vault.countHeld());
    }

    private static Specification<OrderEntity> deliveredBetween(Instant from, Instant before) {
        return (root, query, cb) -> cb.and(
                cb.greaterThanOrEqualTo(root.get("deliveredAt"), from),
                cb.lessThan(root.get("deliveredAt"), before));
    }

    List<AdminOrderViews.Row> rows(List<OrderEntity> page) {
        if (page.isEmpty()) {
            return List.of();
        }
        List<Long> ids = page.stream().map(OrderEntity::getId).toList();

        // Newest first, so the first claim seen for an order is its latest.
        Map<Long, ManualPaymentClaimEntity> latestClaim = new HashMap<>();
        for (ManualPaymentClaimEntity claim : claims.findByOrderIdInOrderBySubmittedAtDesc(ids)) {
            latestClaim.putIfAbsent(claim.getOrderId(), claim);
        }

        Set<Long> held = new HashSet<>(vault.heldAmong(ids));

        Set<Long> accountIds = new HashSet<>();
        for (OrderEntity order : page) {
            if (order.getAccountId() != null && blank(order.getGuestName())) {
                accountIds.add(order.getAccountId());
            }
        }
        Map<Long, String> accountNames = new HashMap<>();
        if (!accountIds.isEmpty()) {
            for (AccountEntity account : accounts.findAllById(accountIds)) {
                if (!blank(account.getDisplayName())) {
                    accountNames.put(account.getId(), account.getDisplayName().trim());
                }
            }
        }

        List<AdminOrderViews.Row> out = new ArrayList<>(page.size());
        for (OrderEntity order : page) {
            ManualPaymentClaimEntity claim = latestClaim.get(order.getId());
            String name = !blank(order.getGuestName())
                    ? order.getGuestName().trim()
                    : accountNames.get(order.getAccountId());
            out.add(new AdminOrderViews.Row(
                    order.getPublicRef(),
                    order.getStatus().name(),
                    order.getSku().name(),
                    OrderService.describe(order),
                    order.getVariant(),
                    order.getQuantity(),
                    platformOf(order),
                    order.getDeliveryMethod().name(),
                    held.contains(order.getId()),
                    order.getSupplierOrderId() != null,
                    name,
                    order.getGuestEmail(),
                    claim == null ? null : claim.getStatus().name(),
                    claim == null ? null : claim.getMethod().name(),
                    claim == null ? null : claim.getReference(),
                    order.getEaPlatformHandle(),
                    order.getTotalMinor(),
                    order.total().format(),
                    order.getCurrency().name(),
                    order.getCreatedAt(),
                    order.getDeliveredAt(),
                    OrderStateMachine.operatorTransitions(order.getStatus())
                            .stream().map(Enum::name).sorted().toList()));
        }
        return out;
    }

    private static String platformOf(OrderEntity order) {
        Platform platform = order.getPlatform() != null ? order.getPlatform() : order.getCoachingPlatform();
        return platform == null ? null : platform.name();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
