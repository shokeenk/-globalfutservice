package com.globalfutservice.admin;

import com.globalfutservice.domain.money.Currency;
import com.globalfutservice.domain.money.Money;
import com.globalfutservice.domain.orders.OrderStatus;
import com.globalfutservice.orders.OrderEntity;
import com.globalfutservice.orders.OrderEventRepository;
import com.globalfutservice.orders.OrderRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The figures and lists on the console's dashboard.
 *
 * <p>Every "vs yesterday" compares today so far with yesterday up to the same time of day,
 * as the Orders page's Completed Today does, so a figure is not "down" every morning just
 * because the day has barely started. Days are India days.
 */
@Service
public class AdminDashboardQueries {

    /**
     * Pending: paid, and not delivered yet. The work the business owes customers.
     * Waiting for payment is not here: nothing is owed until somebody pays.
     */
    static final Set<OrderStatus> PENDING = EnumSet.of(
            OrderStatus.PAID, OrderStatus.CREDENTIALS_PENDING, OrderStatus.READY_FOR_DELIVERY,
            OrderStatus.IN_PROGRESS, OrderStatus.ON_HOLD);

    static final int LIST_SIZE = 5;

    private final OrderRepository orders;
    private final OrderEventRepository events;
    private final AdminOrderQueries rows;
    private final Clock clock;

    public AdminDashboardQueries(OrderRepository orders, OrderEventRepository events,
                                 AdminOrderQueries rows, Clock clock) {
        this.orders = orders;
        this.events = events;
        this.rows = rows;
        this.clock = clock;
    }

    /** Today so far, and yesterday up to the same wall-clock time, in India. */
    record Days(Instant todayStarts, Instant now, Instant yesterdayStarts, Instant yesterdaySameTime) {

        static Days at(Instant now) {
            ZonedDateTime local = now.atZone(AdminOrderQueries.BUSINESS_ZONE);
            return new Days(
                    local.toLocalDate().atStartOfDay(AdminOrderQueries.BUSINESS_ZONE).toInstant(),
                    now,
                    local.toLocalDate().minusDays(1).atStartOfDay(AdminOrderQueries.BUSINESS_ZONE).toInstant(),
                    local.minusDays(1).toInstant());
        }
    }

    public record Activity(AdminOrderViews.Row order, Instant changedAt) {
    }

    public record Dashboard(
            long newToday,
            long newYesterdaySoFar,
            long pending,
            /** Pending at this time yesterday, rebuilt from each order's status history. */
            long pendingYesterday,
            long deliveredToday,
            long deliveredYesterdaySoFar,
            /** The newest orders placed. */
            List<AdminOrderViews.Row> newest,
            /** The orders whose status changed most recently. */
            List<Activity> recent) {
    }

    public record CurrencyRevenue(String currency, long todayMinor, String todayFormatted,
                                  long yesterdayMinor, String yesterdayFormatted) {
    }

    @Transactional(readOnly = true)
    public Dashboard dashboard() {
        Days days = Days.at(clock.instant());
        List<String> pendingNames = PENDING.stream().map(Enum::name).toList();

        List<OrderEntity> newest = orders.findAll(
                PageRequest.of(0, LIST_SIZE, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))))
                .getContent();

        return new Dashboard(
                orders.count(createdBetween(days.todayStarts(), days.now())),
                orders.count(createdBetween(days.yesterdayStarts(), days.yesterdaySameTime())),
                orders.count((root, query, cb) -> root.get("status").in(PENDING)),
                events.countInStatusesAt(days.yesterdaySameTime(), pendingNames),
                orders.count(deliveredBetween(days.todayStarts(), days.now())),
                orders.count(deliveredBetween(days.yesterdayStarts(), days.yesterdaySameTime())),
                rows.rows(newest),
                recent());
    }

    private List<Activity> recent() {
        Map<Long, Instant> changed = new LinkedHashMap<>();
        for (Object[] row : events.latestActivity(LIST_SIZE)) {
            changed.put(((Number) row[0]).longValue(), toInstant(row[1]));
        }
        if (changed.isEmpty()) {
            return List.of();
        }
        Map<Long, OrderEntity> byId = orders.findAllById(changed.keySet()).stream()
                .collect(Collectors.toMap(OrderEntity::getId, Function.identity()));
        // Kept in the order the activity query gave, newest change first.
        List<OrderEntity> ordered = changed.keySet().stream().map(byId::get)
                .filter(java.util.Objects::nonNull).toList();
        List<AdminOrderViews.Row> built = rows.rows(ordered);
        List<Activity> out = new ArrayList<>();
        for (int i = 0; i < ordered.size(); i++) {
            out.add(new Activity(built.get(i), changed.get(ordered.get(i).getId())));
        }
        return out;
    }

    /**
     * Today's revenue by the existing rule, per currency, with rupees always present.
     *
     * <p>The rule is {@link OrderRepository#revenueSince}'s: delivered and completed
     * orders, dated by when they were placed. It is not redefined here for a daily card.
     */
    @Transactional(readOnly = true)
    public List<CurrencyRevenue> revenueToday() {
        Days days = Days.at(clock.instant());
        Map<Currency, Long> today = byCurrency(orders.revenueByCurrency(days.todayStarts(), days.now()));
        Map<Currency, Long> yesterday = byCurrency(
                orders.revenueByCurrency(days.yesterdayStarts(), days.yesterdaySameTime()));

        // Rupees first and always shown, as the reference's card is a rupee figure; any
        // other currency taken in either window follows.
        Set<Currency> shown = new java.util.LinkedHashSet<>();
        shown.add(Currency.INR);
        shown.addAll(today.keySet());
        shown.addAll(yesterday.keySet());

        List<CurrencyRevenue> out = new ArrayList<>();
        for (Currency currency : shown) {
            long t = today.getOrDefault(currency, 0L);
            long y = yesterday.getOrDefault(currency, 0L);
            out.add(new CurrencyRevenue(currency.name(), t, Money.ofMinor(t, currency).format(),
                    y, Money.ofMinor(y, currency).format()));
        }
        return out;
    }

    private static Map<Currency, Long> byCurrency(List<Object[]> rows) {
        Map<Currency, Long> out = new LinkedHashMap<>();
        for (Object[] row : rows) {
            out.put((Currency) row[0], ((Number) row[1]).longValue());
        }
        return out;
    }

    private static Specification<OrderEntity> createdBetween(Instant from, Instant before) {
        return (root, query, cb) -> cb.and(
                cb.greaterThanOrEqualTo(root.get("createdAt"), from),
                cb.lessThan(root.get("createdAt"), before));
    }

    private static Specification<OrderEntity> deliveredBetween(Instant from, Instant before) {
        return (root, query, cb) -> cb.and(
                cb.greaterThanOrEqualTo(root.get("deliveredAt"), from),
                cb.lessThan(root.get("deliveredAt"), before));
    }

    /** Native queries hand back a timestamp as whatever the driver chose. */
    private static Instant toInstant(Object value) {
        if (value instanceof Instant instant) {
            return instant;
        }
        if (value instanceof java.time.OffsetDateTime odt) {
            return odt.toInstant();
        }
        if (value instanceof java.sql.Timestamp ts) {
            return ts.toInstant();
        }
        throw new IllegalStateException("Unexpected timestamp type " + value.getClass());
    }
}
