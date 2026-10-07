package com.globalfutservice.payments.payop;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.globalfutservice.domain.catalog.Sku;
import com.globalfutservice.domain.money.Currency;
import com.globalfutservice.domain.orders.DeliveryMethod;
import com.globalfutservice.domain.orders.OrderStatus;
import com.globalfutservice.orders.OrderEntity;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Shared stand-ins for the Payop service tests. */
final class PayopFakes {

    private PayopFakes() {
    }

    /** Runs the callback straight through: these tests are about what happens, not about commits. */
    static PlatformTransactionManager noTransactions() {
        return new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                return new SimpleTransactionStatus();
            }

            @Override
            public void commit(TransactionStatus status) {
            }

            @Override
            public void rollback(TransactionStatus status) {
            }
        };
    }

    /**
     * The invoice table in memory, holding the one rule the database holds with a partial
     * unique index: at most one CREATING or OPEN attempt per order.
     */
    static final class Invoices {
        final Map<Long, PayopInvoiceEntity> rows = new LinkedHashMap<>();
        final PayopInvoiceRepository repo = mock(PayopInvoiceRepository.class);
        /** Which account each order belongs to, for the per-account limit. */
        final Map<Long, Long> accountOf = new java.util.HashMap<>();
        private long nextId = 100;

        Invoices() {
            when(repo.save(any(PayopInvoiceEntity.class))).thenAnswer(inv -> store(inv.getArgument(0)));
            when(repo.saveAndFlush(any(PayopInvoiceEntity.class))).thenAnswer(inv -> store(inv.getArgument(0)));
            when(repo.findById(anyLong())).thenAnswer(inv -> Optional.ofNullable(rows.get((Long) inv.getArgument(0))));
            when(repo.lockById(anyLong())).thenAnswer(inv -> Optional.ofNullable(rows.get((Long) inv.getArgument(0))));
            when(repo.findUnsettledSince(any(), anyCollection())).thenAnswer(inv -> {
                Collection<?> settled = inv.getArgument(1);
                return rows.values().stream()
                        .filter(r -> r.getInvoiceId() != null && r.getCreatedAt().isAfter(inv.getArgument(0))
                                && !settled.contains(r.getStatus()))
                        .sorted(Comparator.comparing(PayopInvoiceEntity::getCreatedAt)).toList();
            });
            when(repo.findByInvoiceId(anyString())).thenAnswer(inv -> rows.values().stream()
                    .filter(r -> inv.getArgument(0).equals(r.getInvoiceId())).findFirst());
            when(repo.findFirstByOrderIdAndStatusInOrderByCreatedAtDesc(anyLong(), anyCollection())).thenAnswer(inv -> {
                Collection<?> statuses = inv.getArgument(1);
                return rows.values().stream()
                        .filter(r -> r.getOrderId().equals(inv.getArgument(0)) && statuses.contains(r.getStatus()))
                        .max(Comparator.comparing(PayopInvoiceEntity::getCreatedAt));
            });
            when(repo.findByStatusAndExpiresAtLessThanEqual(any(), any())).thenAnswer(inv -> rows.values().stream()
                    .filter(r -> r.getStatus() == inv.getArgument(0)
                            && !r.getExpiresAt().isAfter(inv.getArgument(1))).toList());
            when(repo.findByStatusAndUpdatedAtBefore(any(), any())).thenAnswer(inv -> rows.values().stream()
                    .filter(r -> r.getStatus() == inv.getArgument(0)
                            && r.getUpdatedAt().isBefore(inv.getArgument(1))).toList());
            when(repo.findByOrderIdOrderByCreatedAtDesc(anyLong())).thenAnswer(inv -> rows.values().stream()
                    .filter(r -> r.getOrderId().equals(inv.getArgument(0)))
                    .sorted(Comparator.comparing(PayopInvoiceEntity::getCreatedAt).reversed()
                            .thenComparing(PayopInvoiceEntity::getId, Comparator.reverseOrder()))
                    .toList());
            when(repo.countByOrderIdAndCreatedAtAfter(anyLong(), any())).thenAnswer(inv -> rows.values().stream()
                    .filter(r -> r.getOrderId().equals(inv.getArgument(0))
                            && r.getCreatedAt().isAfter(inv.getArgument(1))).count());
            when(repo.firstForOrderSince(anyLong(), any())).thenAnswer(inv -> rows.values().stream()
                    .filter(r -> r.getOrderId().equals(inv.getArgument(0))
                            && r.getCreatedAt().isAfter(inv.getArgument(1)))
                    .map(PayopInvoiceEntity::getCreatedAt).min(Comparator.naturalOrder()));
            when(repo.countForAccountSince(anyLong(), any())).thenAnswer(inv -> rows.values().stream()
                    .filter(r -> inv.getArgument(0).equals(accountOf.get(r.getOrderId()))
                            && r.getCreatedAt().isAfter(inv.getArgument(1))).count());
            when(repo.firstForAccountSince(anyLong(), any())).thenAnswer(inv -> rows.values().stream()
                    .filter(r -> inv.getArgument(0).equals(accountOf.get(r.getOrderId()))
                            && r.getCreatedAt().isAfter(inv.getArgument(1)))
                    .map(PayopInvoiceEntity::getCreatedAt).min(Comparator.naturalOrder()));
            when(repo.payableUntil(anyLong(), any(), any())).thenAnswer(inv -> rows.values().stream()
                    .filter(r -> r.getOrderId().equals(inv.getArgument(0))
                            && r.getExpiresAt().isAfter(inv.getArgument(1))
                            && (r.getInvoiceId() != null || r.getStatus() == PayopInvoiceEntity.Status.CREATING))
                    .map(PayopInvoiceEntity::getExpiresAt).max(Comparator.naturalOrder()));
        }

        private PayopInvoiceEntity store(PayopInvoiceEntity row) {
            if (row.getId() == null) {
                ReflectionTestUtils.setField(row, "id", nextId++);
            }
            if (row.isActive() && rows.values().stream().anyMatch(r -> r != row && r.isActive()
                    && r.getOrderId().equals(row.getOrderId()) && !r.getId().equals(row.getId()))) {
                throw new DataIntegrityViolationException("payop_invoice_one_active_uk");
            }
            rows.put(row.getId(), row);
            return row;
        }

        List<PayopInvoiceEntity> forOrder(long orderId) {
            return new ArrayList<>(rows.values().stream().filter(r -> r.getOrderId() == orderId).toList());
        }

        /** An attempt as the checkout leaves it once Payop has made the invoice. */
        PayopInvoiceEntity open(long orderId, String invoiceId, long methodId, Currency currency, long net, long fee,
                                String amountSent, Instant createdAt) {
            PayopInvoiceEntity a = new PayopInvoiceEntity(orderId, UUID.randomUUID(), method(methodId, "Bank transfer"),
                    new FxRateService.RateUsed(BigDecimal.ONE, FxRateService.NONE, java.time.LocalDate.of(2026, 10, 4)),
                    currency, new PayopFeeCalculator.FeeQuote(net, fee, net + fee), amountSent, "DE", createdAt,
                    createdAt.plusSeconds(24 * 3600));
            a.opened(invoiceId, createdAt);
            return store(a);
        }
    }

    static PayopFeeMethodEntity method(long id, String name) {
        return method(id, name, "0.30", "4.0");
    }

    static PayopFeeMethodEntity method(long id, String name, String fixedEur, String percent) {
        return new PayopFeeMethodEntity(id, name, "bank_transfer", "Europe", new BigDecimal(fixedEur),
                new BigDecimal(percent), List.of("DE", "AT"), List.of("EUR"), null, Instant.EPOCH);
    }

    /**
     * An order as placed: base 100.00, a 10.00 coupon, the flat 2.5% card fee of 2.25, so a
     * total of 92.25 and a price without the card fee of 90.00.
     */
    static OrderEntity order(long id, String ref, Currency currency, OrderStatus status) {
        String breakdown = """
                {"quoteId":"q_1","currency":"%s","lines":[
                  {"code":"BASE","label":"Coins","amountMinor":10000,"amountFormatted":"100.00"},
                  {"code":"COUPON_DISCOUNT","label":"Coupon","amountMinor":-1000,"amountFormatted":"-10.00"},
                  {"code":"GATEWAY_FEE","label":"Payment processing (2.5%%)","amountMinor":225,"amountFormatted":"2.25"}
                ],"total":{"minor":9225}}
                """.formatted(currency.name());
        OrderEntity order = new OrderEntity(ref, "q_" + id, "FC27", Sku.TRADING_SERVICE, null, null,
                BigDecimal.ONE, DeliveryMethod.PLAYER_AUCTION, currency, 10000, 9225, breakdown);
        ReflectionTestUtils.setField(order, "id", id);
        ReflectionTestUtils.setField(order, "status", status);
        order.setGuestEmail("buyer@example.com");
        return order;
    }
}
