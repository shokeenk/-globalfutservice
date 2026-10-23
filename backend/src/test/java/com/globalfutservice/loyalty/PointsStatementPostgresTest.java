package com.globalfutservice.loyalty;

import com.globalfutservice.config.AppProperties;
import com.globalfutservice.identity.web.AccountController;
import com.globalfutservice.marketing.MarketingPreferenceService;
import com.globalfutservice.security.AccountPrincipal;
import com.globalfutservice.identity.AccountRole;
import com.globalfutservice.storecredit.StoreCreditService;
import jakarta.persistence.EntityManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;

/**
 * The points breakdown and history against the real schema, built by the real wallet
 * operations: for every account, earned − spent + returned − removed = available, refunds
 * returning spent points never count as earned, the history's running balance ends at the
 * balance, and what the account pages are sent matches the ledger. Runs when
 * GFS_TEST_PG_URL is set.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PointsStatementPostgresTest {

    private static final Instant NOW = Instant.parse("2026-10-23T08:00:00Z");

    private String schema;
    private JdbcTemplate jdbc;
    private LocalContainerEntityManagerFactoryBean factory;
    private TransactionTemplate tx;
    private PointsLedgerRepository ledger;
    private LoyaltyService loyalty;
    private PointsStatement statement;
    private AccountController accountPages;

    @BeforeAll
    void migrate() {
        String url = System.getenv("GFS_TEST_PG_URL");
        assumeTrue(url != null && !url.isBlank(), "GFS_TEST_PG_URL not set: no database to test against");
        schema = "points_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        DriverManagerDataSource ds = new DriverManagerDataSource(
                url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
                System.getenv("GFS_TEST_PG_USER"), System.getenv("GFS_TEST_PG_PASSWORD"));
        jdbc = new JdbcTemplate(ds);
        Flyway.configure().dataSource(ds).schemas(schema).defaultSchema(schema).locations("classpath:db/migration")
                .initSql("SET search_path TO " + schema + ", public").load().migrate();

        factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(ds);
        factory.setPackagesToScan("com.globalfutservice");
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setJpaPropertyMap(Map.of(
                "hibernate.hbm2ddl.auto", "none",
                "hibernate.default_schema", schema,
                "hibernate.jdbc.time_zone", "UTC",
                "hibernate.physical_naming_strategy",
                "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy",
                "hibernate.implicit_naming_strategy",
                "org.springframework.boot.orm.jpa.hibernate.SpringImplicitNamingStrategy"));
        factory.afterPropertiesSet();
        tx = new TransactionTemplate(new JpaTransactionManager(factory.getObject()));
        EntityManager shared = SharedEntityManagerCreator.createSharedEntityManager(factory.getObject());
        ledger = new JpaRepositoryFactory(shared).getRepository(PointsLedgerRepository.class);
        AppProperties props = mock(AppProperties.class, RETURNS_DEEP_STUBS);
        loyalty = new LoyaltyService(ledger, props, Clock.fixed(NOW, ZoneOffset.UTC));
        statement = new PointsStatement(new NamedParameterJdbcTemplate(ds));
        accountPages = new AccountController(loyalty, props, mock(MarketingPreferenceService.class),
                mock(StoreCreditService.class), statement);
        buildAccounts();
    }

    @AfterAll
    void drop() {
        if (factory != null) {
            factory.destroy();
        }
        if (jdbc != null) {
            jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    // ------------------------------------------------------------------ fixtures

    private long customer;
    private long refunded;
    private long quiet;

    private long account(String name) {
        return jdbc.queryForObject("""
                insert into account (public_id, email, email_normalised, role, password_hash)
                values (?, ?, ?, 'CUSTOMER', 'x') returning id
                """, Long.class, "acc_" + name, name + "@example.test", name + "@example.test");
    }

    private long order(long accountId, String ref, String status, long pointsEarned, Instant guaranteeEnds,
                       boolean settled) {
        return jdbc.queryForObject("""
                insert into orders (public_ref, account_id, season, sku, quantity, delivery_method, currency,
                                    subtotal_minor, total_minor, price_breakdown, status, quote_id, guest_email,
                                    points_earned, delivered_at, guarantee_expires_at, points_settled_at)
                values (?, ?, 'FC26', 'TRADING_SERVICE', 1, 'PLAYER_AUCTION', 'INR', 100000, 100000,
                        '{"lines":[]}'::jsonb, ?, ?, null, ?, ?, ?, ?)
                returning id
                """, Long.class, ref, accountId, status, "q_" + ref, pointsEarned,
                guaranteeEnds == null ? null : Timestamp.from(guaranteeEnds.minusSeconds(7 * 86_400)),
                guaranteeEnds == null ? null : Timestamp.from(guaranteeEnds), settled ? Timestamp.from(NOW) : null);
    }

    private void run(Runnable r) {
        tx.executeWithoutResult(s -> r.run());
    }

    /** Like the customer who asked: 49 earned, 46 spent, 3 left; and two more with every kind of line. */
    private void buildAccounts() {
        customer = account("asked");
        long a = order(customer, "GFS-26-EARN01", "COMPLETED", 40, NOW.minusSeconds(86_400), true);
        long b = order(customer, "GFS-26-SPEND1", "COMPLETED", 0, NOW.minusSeconds(86_400), true);
        run(() -> loyalty.award(customer, a, 40, "GFS-26-EARN01"));
        run(() -> ledger.save(PointsLedgerEntity.dailyBonus(customer, 4, LocalDate.of(2026, 10, 1))));
        run(() -> ledger.save(PointsLedgerEntity.dailyBonus(customer, 5, LocalDate.of(2026, 10, 2))));
        run(() -> loyalty.redeem(customer, b, 46, "GFS-26-SPEND1"));
        // Still on the way: delivered with the window closing on the 28th; paid, not delivered yet.
        order(customer, "GFS-26-WAIT01", "DELIVERED", 25, Instant.parse("2026-10-28T09:00:00Z"), false);
        order(customer, "GFS-26-WAIT02", "PAID", 12, null, false);
        // Never on the way: not paid, refunded, already landed.
        order(customer, "GFS-26-NOPAY1", "AWAITING_PAYMENT", 30, null, false);
        order(customer, "GFS-26-REFND1", "REFUNDED", 30, null, false);

        refunded = account("refunded");
        long c = order(refunded, "GFS-26-EARN02", "COMPLETED", 100, NOW, true);
        long d = order(refunded, "GFS-26-SPEND2", "REFUNDED", 0, null, false);
        long e = order(refunded, "GFS-26-CHAMP1", "COMPLETED", 20, NOW, true);
        run(() -> loyalty.award(refunded, c, 100, "GFS-26-EARN02"));
        run(() -> loyalty.award(refunded, e, 20, "GFS-26-CHAMP1"));
        run(() -> loyalty.redeem(refunded, d, 60, "GFS-26-SPEND2"));
        run(() -> loyalty.reverseRedemption(refunded, d, 60, "GFS-26-SPEND2"));
        run(() -> loyalty.clawback(refunded, c, 10, "GFS-26-EARN02", null));
        run(() -> loyalty.deductForSettlement(refunded, e, 5, "GFS-26-CHAMP1", null));
        run(() -> loyalty.adjust(refunded, null, 15, "Goodwill", null));
        run(() -> loyalty.adjust(refunded, null, -7, "Duplicate goodwill", null));

        quiet = account("quiet");
    }

    private List<Long> allAccounts() {
        return jdbc.queryForList("select id from account order by id", Long.class);
    }

    private long ledgerSum(long accountId) {
        return Objects.requireNonNull(jdbc.queryForObject(
                "select coalesce(sum(amount), 0) from points_ledger where account_id = ?", Long.class, accountId));
    }

    private List<PointsStatement.Line> wholeHistory(long accountId, int pageSize) {
        List<PointsStatement.Line> all = new ArrayList<>();
        for (int page = 0; ; page++) {
            PointsStatement.History h = statement.history(accountId, page, pageSize);
            all.addAll(h.entries());
            if (h.entries().isEmpty() || all.size() >= h.total()) {
                return all;
            }
        }
    }

    private static AccountPrincipal signedIn(long id) {
        return new AccountPrincipal(id, "acc_" + id, id + "@example.test", AccountRole.CUSTOMER);
    }

    // ------------------------------------------------------------------ tests

    @Test
    @DisplayName("49 earned − 46 spent = 3 available, and the two orders still on the way with their dates")
    void theCustomerWhoAsked() {
        PointsStatement.Breakdown b = statement.breakdown(customer);
        assertThat(b.available()).isEqualTo(3);
        assertThat(b.earned()).isEqualTo(49);
        assertThat(b.spent()).isEqualTo(46);
        assertThat(b.returned()).isZero();
        assertThat(b.removed()).isZero();
        assertThat(b.onTheWay()).isEqualTo(37);
        assertThat(b.pending()).containsExactly(
                new PointsStatement.Pending("GFS-26-WAIT01", 25, Instant.parse("2026-10-28T09:00:00Z")),
                new PointsStatement.Pending("GFS-26-WAIT02", 12, null));
    }

    @Test
    @DisplayName("points returned after a refund are not earned; clawbacks and settlements come off earned")
    void refundsAreNotEarnings() {
        PointsStatement.Breakdown b = statement.breakdown(refunded);
        assertThat(b.earned()).as("100 + 20 + 15 goodwill − 10 clawback − 5 settlement").isEqualTo(120);
        assertThat(b.spent()).isEqualTo(60);
        assertThat(b.returned()).isEqualTo(60);
        assertThat(b.removed()).isEqualTo(7);
        assertThat(b.available()).isEqualTo(113);
    }

    @Test
    @DisplayName("for every account: earned − spent + returned − removed = available = the ledger's sum")
    void everyAccountAddsUp() {
        assertThat(allAccounts()).contains(customer, refunded, quiet);
        for (long id : allAccounts()) {
            PointsStatement.Breakdown b = statement.breakdown(id);
            assertThat(b.earned() - b.spent() + b.returned() - b.removed())
                    .as("account %d", id).isEqualTo(b.available());
            assertThat(b.available()).as("account %d", id).isEqualTo(ledgerSum(id));
            assertThat(b.available()).as("account %d", id).isEqualTo(loyalty.balance(id));
        }
    }

    @Test
    @DisplayName("the history: every line, newest first, each balance the one before plus its amount")
    void historyRunsToTheBalance() {
        for (long id : allAccounts()) {
            List<PointsStatement.Line> lines = wholeHistory(id, 3);
            assertThat(lines).hasSize(jdbc.queryForObject(
                    "select count(*) from points_ledger where account_id = ?", Integer.class, id));
            if (lines.isEmpty()) {
                continue;
            }
            assertThat(lines.get(0).balanceAfter()).as("account %d", id).isEqualTo(ledgerSum(id));
            for (int i = 0; i < lines.size() - 1; i++) {
                assertThat(lines.get(i).balanceAfter())
                        .isEqualTo(lines.get(i + 1).balanceAfter() + lines.get(i).amount());
            }
            PointsStatement.Line oldest = lines.get(lines.size() - 1);
            assertThat(oldest.balanceAfter()).isEqualTo(oldest.amount());
        }
        List<PointsStatement.Line> asked = wholeHistory(customer, 100);
        assertThat(asked).extracting(PointsStatement.Line::kind).containsExactly(
                PointsStatement.Kind.SPENT, PointsStatement.Kind.DAILY_BONUS, PointsStatement.Kind.DAILY_BONUS,
                PointsStatement.Kind.EARNED);
        assertThat(asked.get(0).orderRef()).isEqualTo("GFS-26-SPEND1");
        assertThat(asked.get(0).amount()).isEqualTo(-46);
        assertThat(wholeHistory(refunded, 100)).extracting(PointsStatement.Line::kind).contains(
                PointsStatement.Kind.RETURNED, PointsStatement.Kind.REMOVED, PointsStatement.Kind.ADJUSTMENT);
    }

    @Test
    @DisplayName("what the account pages are sent matches the ledger, \"earned in total\" included")
    void accountPagesMatchTheLedger() {
        for (long id : allAccounts()) {
            AccountPrincipal me = signedIn(id);
            PointsStatement.Breakdown sent = accountPages.points(me).getBody();
            assertThat(sent).isEqualTo(statement.breakdown(id));
            assertThat(sent.available()).isEqualTo(ledgerSum(id));
            AccountController.WalletResponse wallet = accountPages.wallet(me, 25).getBody();
            assertThat(wallet.balance()).isEqualTo(ledgerSum(id));
            assertThat(wallet.lifetimeEarned()).isEqualTo(sent.earned());
            assertThat(accountPages.pointsHistory(me, 0, 20).getBody().total())
                    .isEqualTo(wholeHistory(id, 100).size());
        }
        assertThat(accountPages.wallet(signedIn(refunded), 25).getBody().lifetimeEarned())
                .as("not 180, which counted the 60 returned").isEqualTo(120);
    }
}
