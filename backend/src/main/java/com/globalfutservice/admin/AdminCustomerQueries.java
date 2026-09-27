package com.globalfutservice.admin;

import com.globalfutservice.domain.money.Currency;
import com.globalfutservice.domain.money.Money;
import com.globalfutservice.orders.OrderEntity;
import com.globalfutservice.orders.OrderRepository;
import com.globalfutservice.orders.OrderService;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Who the customers are, read straight from the orders and accounts.
 *
 * <p><b>A customer is an account or a guest.</b> Every customer account is one. So is every
 * email that has checked out without an account: a guest is as much a customer as anybody
 * with a password, and most checkouts are made without signing in. A guest order placed
 * with an account's own email address belongs to that account, so the same person is not
 * listed twice. Staff accounts are not customers, and orders they place are left out.
 *
 * <p><b>Nothing is stored.</b> There is no customers table to keep in step: the list is
 * worked out from {@code orders} and {@code account} each time, with SQL doing the grouping
 * so a page of twenty-five costs a handful of queries however many orders there are.
 *
 * <p>Inside the queries each customer has a key: {@code a<account id>} or
 * {@code g<email>}. Outside, the key is {@code a-<account public id>} or
 * {@code g-<reference of the guest's first order>}, so no email ever sits in an address.
 */
@Service
public class AdminCustomerQueries {

    /**
     * Every order, with the customer it belongs to. Guest orders under an account's email
     * are that account's; an order owned by a staff account keeps the staff key and so
     * matches no customer.
     */
    private static final String ORD = """
            ord as (
              select o.id, o.public_ref, o.created_at, o.status, o.total_minor, o.currency, o.guest_name,
                     o.ea_platform_handle, o.discord_username, o.sku,
                     coalesce(o.platform, o.coaching_platform) as platform,
                     case when coalesce(o.account_id, m.id) is not null
                          then 'a' || coalesce(o.account_id, m.id)
                          else 'g' || lower(o.guest_email) end as ckey
                from orders o
                left join account m on o.account_id is null and m.email_normalised = lower(o.guest_email)
            )""";

    /** Customer accounts, then each guest email, as one list. */
    private static final String CUST = """
            cust as (
              select 'a' || a.id as ckey, 'ACCOUNT' as kind, 'a-' || a.public_id as ref, a.email,
                     a.display_name as name, a.created_at as joined, a.disabled_at, a.locked_until,
                     a.oauth_provider
                from account a
               where a.role = 'CUSTOMER'
              union all
              select o.ckey, 'GUEST', 'g-' || (array_agg(o.public_ref order by o.created_at, o.id))[1],
                     substr(o.ckey, 2), null, min(o.created_at), null, null, null
                from ord o
               where o.ckey like 'g%'
               group by o.ckey
            ),
            agg as (
              select ckey, count(*) as orders, max(created_at) as last_order, min(created_at) as first_order
                from ord group by ckey
            )""";

    /**
     * What has actually been paid and kept: paid and every status after, except a refund.
     * Store credit counts, because the money was kept and credit given on top.
     */
    static final List<String> PAID_KEPT = List.of("PAID", "CREDENTIALS_PENDING", "READY_FOR_DELIVERY",
            "IN_PROGRESS", "ON_HOLD", "DELIVERED", "COMPLETED", "DISPUTED", "CREDITED");

    private final NamedParameterJdbcTemplate jdbc;
    private final OrderRepository orders;
    private final Clock clock;

    public AdminCustomerQueries(NamedParameterJdbcTemplate jdbc, OrderRepository orders, Clock clock) {
        this.jdbc = jdbc;
        this.orders = orders;
        this.clock = clock;
    }

    public enum Filter { ALL, WITH_ORDERS, WITHOUT_ORDERS }

    public record Spent(String currency, long minor, String formatted) {
    }

    /** One row of the customer table. {@code spent} is null for anyone but an admin. */
    public record Row(String key, String kind, String name, String email, String eaHandle,
                      String platform, long orders, List<Spent> spent, Instant lastOrderAt,
                      Instant joinedAt, String status, boolean discordConnected) {
    }

    public record Page(List<Row> items, long total, int page, int size) {
    }

    public record Overview(long total, long newThisMonth, long newLastMonthSoFar,
                           long withOrders, long withOrdersLastMonth) {
    }

    public record RecentOrder(String publicRef, String sku, String serviceLabel, String status,
                              Instant createdAt) {
    }

    public record Detail(Row customer, List<RecentOrder> recentOrders) {
    }

    /** A customer located from an outside key, as the queries know it. */
    record Located(String ckey, String ref) {
    }

    @Transactional(readOnly = true)
    public Page search(String search, Filter filter, int page, int size, boolean withMoney) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        String where = where(search, filter, params);
        long total = Optional.ofNullable(jdbc.queryForObject(
                "with " + ORD + ", " + CUST + " select count(*) from cust c left join agg g on g.ckey = c.ckey " + where,
                params, Long.class)).orElse(0L);

        params.addValue("limit", size).addValue("offset", (long) page * size);
        List<Base> base = jdbc.query("with " + ORD + ", " + CUST + """
                 select c.*, coalesce(g.orders, 0) as orders, g.last_order
                   from cust c left join agg g on g.ckey = c.ckey
                """ + where + """
                  order by g.last_order desc nulls last, c.joined desc, c.ckey
                  limit :limit offset :offset
                """, params, AdminCustomerQueries::base);
        return new Page(enrich(base, withMoney), total, page, size);
    }

    @Transactional(readOnly = true)
    public Overview overview() {
        Instant now = clock.instant();
        ZonedDateTime local = now.atZone(AdminOrderQueries.BUSINESS_ZONE);
        ZonedDateTime monthStart = local.toLocalDate().withDayOfMonth(1).atStartOfDay(AdminOrderQueries.BUSINESS_ZONE);
        ZonedDateTime sameTimeLastMonth = local.minusMonths(1);
        ZonedDateTime lastMonthStart = monthStart.minusMonths(1);

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("monthStart", Timestamp.from(monthStart.toInstant()))
                .addValue("lastMonthStart", Timestamp.from(lastMonthStart.toInstant()))
                .addValue("lastMonthNow", Timestamp.from(sameTimeLastMonth.toInstant()));
        return jdbc.queryForObject("with " + ORD + ", " + CUST + """
                 select count(*) as total,
                        count(*) filter (where c.joined >= :monthStart) as new_this_month,
                        count(*) filter (where c.joined >= :lastMonthStart and c.joined < :lastMonthNow) as new_last_month,
                        count(*) filter (where g.orders > 0) as with_orders,
                        count(*) filter (where g.first_order < :lastMonthNow) as with_orders_then
                   from cust c left join agg g on g.ckey = c.ckey
                """, params, (rs, i) -> new Overview(rs.getLong("total"), rs.getLong("new_this_month"),
                rs.getLong("new_last_month"), rs.getLong("with_orders"), rs.getLong("with_orders_then")));
    }

    @Transactional(readOnly = true)
    public Optional<Detail> detail(String key, boolean withMoney) {
        return locate(key).flatMap(found -> {
            MapSqlParameterSource params = new MapSqlParameterSource("ckey", found.ckey());
            List<Base> base = jdbc.query("with " + ORD + ", " + CUST + """
                     select c.*, coalesce(g.orders, 0) as orders, g.last_order
                       from cust c left join agg g on g.ckey = c.ckey
                      where c.ckey = :ckey
                    """, params, AdminCustomerQueries::base);
            if (base.isEmpty()) {
                return Optional.empty();
            }
            List<Long> recentIds = jdbc.queryForList("with " + ORD + """
                     select id from ord where ckey = :ckey order by created_at desc, id desc limit 3
                    """, params, Long.class);
            Map<Long, OrderEntity> byId = orders.findAllById(recentIds).stream()
                    .collect(Collectors.toMap(OrderEntity::getId, Function.identity()));
            List<RecentOrder> recent = recentIds.stream().map(byId::get).filter(java.util.Objects::nonNull)
                    .map(o -> new RecentOrder(o.getPublicRef(), o.getSku().name(), OrderService.describe(o),
                            o.getStatus().name(), o.getCreatedAt()))
                    .toList();
            return Optional.of(new Detail(enrich(base, withMoney).get(0), recent));
        });
    }

    /**
     * An outside key to the customer it names, or empty. {@code a-} keys must be customer
     * accounts; {@code g-} keys must be a guest order whose email has no account.
     */
    Optional<Located> locate(String key) {
        if (key == null || key.length() < 3) {
            return Optional.empty();
        }
        String value = key.substring(2);
        if (key.startsWith("a-")) {
            return jdbc.query("select id from account where public_id = :v and role = 'CUSTOMER'",
                    new MapSqlParameterSource("v", value), (rs, i) -> rs.getLong(1))
                    .stream().findFirst().map(id -> new Located("a" + id, key));
        }
        if (key.startsWith("g-")) {
            return jdbc.query("""
                    select lower(o.guest_email) from orders o
                     where o.public_ref = :v and o.account_id is null
                       and not exists (select 1 from account m where m.email_normalised = lower(o.guest_email))
                    """, new MapSqlParameterSource("v", value), (rs, i) -> rs.getString(1))
                    .stream().findFirst().map(email -> new Located("g" + email, key));
        }
        return Optional.empty();
    }

    /** The WHERE for the list and its count, with the search and filter bound. */
    private static String where(String search, Filter filter, MapSqlParameterSource params) {
        List<String> clauses = new ArrayList<>();
        if (search != null && !search.isBlank()) {
            params.addValue("p", "%" + AdminOrderSpecs.escapeLike(search.trim().toLowerCase(Locale.ROOT)) + "%");
            clauses.add("""
                    (lower(c.email) like :p escape '\\' or lower(coalesce(c.name, '')) like :p escape '\\'
                     or exists (select 1 from ord x where x.ckey = c.ckey and (
                          lower(coalesce(x.guest_name, '')) like :p escape '\\'
                          or lower(coalesce(x.ea_platform_handle, '')) like :p escape '\\'
                          or lower(coalesce(x.discord_username, '')) like :p escape '\\'))
                     or exists (select 1 from ord x join discord_order_verification v on v.order_id = x.id
                                 where x.ckey = c.ckey and (lower(v.discord_user_id) like :p escape '\\'
                                       or lower(coalesce(v.discord_username, '')) like :p escape '\\')))""");
        }
        if (filter == Filter.WITH_ORDERS) {
            clauses.add("coalesce(g.orders, 0) > 0");
        } else if (filter == Filter.WITHOUT_ORDERS) {
            clauses.add("coalesce(g.orders, 0) = 0");
        }
        return clauses.isEmpty() ? " " : " where " + String.join(" and ", clauses) + " ";
    }

    /** What the list query itself returns, before the per-page details are added. */
    record Base(String ckey, String kind, String ref, String email, String name, Instant joined,
                Instant disabledAt, Instant lockedUntil, String oauthProvider, long orders,
                Instant lastOrder) {
    }

    private static Base base(ResultSet rs, int i) throws SQLException {
        return new Base(rs.getString("ckey"), rs.getString("kind"), rs.getString("ref"),
                rs.getString("email"), rs.getString("name"), instant(rs, "joined"),
                instant(rs, "disabled_at"), instant(rs, "locked_until"), rs.getString("oauth_provider"),
                rs.getLong("orders"), instant(rs, "last_order"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }

    /**
     * The details a page of customers needs from their orders: the name typed at checkout,
     * the latest EA ID and platform, what they have spent, and whether Discord knows them.
     * One query each for the whole page.
     */
    private List<Row> enrich(List<Base> base, boolean withMoney) {
        if (base.isEmpty()) {
            return List.of();
        }
        MapSqlParameterSource keys = new MapSqlParameterSource("keys",
                base.stream().map(Base::ckey).toList());

        Map<String, String[]> latest = new HashMap<>();
        jdbc.query("with " + ORD + """
                 select ckey,
                   (array_agg(guest_name order by created_at desc) filter (where coalesce(guest_name, '') <> ''))[1] as name,
                   (array_agg(ea_platform_handle order by created_at desc) filter (where coalesce(ea_platform_handle, '') <> ''))[1] as ea,
                   (array_agg(platform order by created_at desc) filter (where platform is not null))[1] as platform
                   from ord where ckey in (:keys) group by ckey
                """, keys, (RowCallbackHandler) rs -> {
            latest.put(rs.getString("ckey"),
                    new String[]{rs.getString("name"), rs.getString("ea"), rs.getString("platform")});
        });

        Map<String, List<Spent>> spent = new HashMap<>();
        if (withMoney) {
            keys.addValue("paid", PAID_KEPT);
            jdbc.query("with " + ORD + """
                     select ckey, currency, sum(total_minor) as minor from ord
                      where ckey in (:keys) and status in (:paid)
                      group by ckey, currency order by ckey, sum(total_minor) desc
                    """, keys, (RowCallbackHandler) rs -> {
                Currency currency = Currency.valueOf(rs.getString("currency").trim());
                long minor = rs.getLong("minor");
                spent.computeIfAbsent(rs.getString("ckey"), k -> new ArrayList<>())
                        .add(new Spent(currency.name(), minor, Money.ofMinor(minor, currency).format()));
            });
        }

        Set<String> onDiscord = new HashSet<>(jdbc.queryForList("with " + ORD + """
                 select distinct x.ckey from ord x join discord_order_verification v on v.order_id = x.id
                  where x.ckey in (:keys)
                """, keys, String.class));

        Instant now = clock.instant();
        Map<String, Row> out = new LinkedHashMap<>();
        for (Base b : base) {
            String[] l = latest.getOrDefault(b.ckey(), new String[3]);
            boolean guest = "GUEST".equals(b.kind());
            String name = !blank(b.name()) ? b.name().trim()
                    : !blank(l[0]) ? l[0].trim()
                    : guest ? "Guest" : b.email().substring(0, Math.max(1, b.email().indexOf('@')));
            String status = guest ? "GUEST"
                    : b.disabledAt() != null ? "DISABLED"
                    : b.lockedUntil() != null && b.lockedUntil().isAfter(now) ? "LOCKED"
                    : "ACTIVE";
            boolean discord = onDiscord.contains(b.ckey()) || "discord".equalsIgnoreCase(b.oauthProvider());
            out.put(b.ckey(), new Row(b.ref(), b.kind(), name, b.email(), l[1], l[2], b.orders(),
                    withMoney ? spent.getOrDefault(b.ckey(), List.of()) : null,
                    b.lastOrder(), b.joined(), status, discord));
        }
        return new ArrayList<>(out.values());
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
