package com.globalfutservice.popups;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.globalfutservice.popups.PopupService.PopupInput;
import com.globalfutservice.popups.PopupService.PopupView;
import com.globalfutservice.popups.PopupTypes.Button;
import com.globalfutservice.popups.PopupTypes.Copy;
import com.globalfutservice.popups.PopupTypes.Frequency;
import com.globalfutservice.popups.PopupTypes.Label;
import com.globalfutservice.popups.PopupTypes.Page;
import com.globalfutservice.popups.PopupTypes.Preset;
import com.globalfutservice.popups.PopupTypes.Trigger;
import com.globalfutservice.popups.PopupTypes.Type;
import jakarta.persistence.EntityManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The guide's seven popups as V60 creates them: drafts, switched off, the copy word for
 * word, and the rules that come with them. Runs when GFS_TEST_PG_URL is set.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GuidePopupsPostgresTest {

    private static final String REFERRAL = "Don't forget to use your referral code for 5% OFF.";

    private String schema;
    private JdbcTemplate jdbc;
    private LocalContainerEntityManagerFactoryBean factory;
    private TransactionTemplate tx;
    private PopupService popups;
    private PopupPublicService live;
    private long adminId;

    @BeforeAll
    void migrate() {
        String url = System.getenv("GFS_TEST_PG_URL");
        assumeTrue(url != null && !url.isBlank(), "GFS_TEST_PG_URL not set: no database to test against");
        schema = "guide_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
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
        JpaTransactionManager transactions = new JpaTransactionManager(factory.getObject());
        transactions.setDataSource(ds);
        tx = new TransactionTemplate(transactions);
        EntityManager shared = SharedEntityManagerCreator.createSharedEntityManager(factory.getObject());
        PopupCampaignRepository campaigns = new JpaRepositoryFactory(shared).getRepository(PopupCampaignRepository.class);
        Clock clock = Clock.fixed(Instant.parse("2026-11-03T12:00:00Z"), ZoneOffset.UTC);
        popups = new PopupService(campaigns, new PopupImageStore(jdbc, transactions), jdbc,
                new ObjectMapper().registerModule(new JavaTimeModule()), clock);
        live = new PopupPublicService(campaigns, popups, jdbc, clock);
        adminId = jdbc.queryForObject("""
                insert into account (public_id, email, email_normalised, password_hash, role)
                values ('acc_admin', 'owner@example.test', 'owner@example.test', 'x', 'ADMIN') returning id
                """, Long.class);
    }

    @AfterAll
    void drop() {
        if (factory != null) factory.destroy();
        if (jdbc != null) jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }

    private PopupView guide(Preset preset) {
        return popups.list().stream().filter(p -> p.preset() == preset).findFirst().orElseThrow();
    }

    private static PopupInput asInput(PopupView p, List<Button> buttons) {
        return new PopupInput(p.name(), p.type(), p.copy(), buttons, p.targetPages(), p.targetPaths(), p.triggerType(),
                p.triggerValue(), p.triggerEvent(), p.startsAt(), p.endsAt(), p.frequency(), p.frequencyDays(),
                p.priority(), p.triggerScroll(), p.excludeCheckout());
    }

    @Test
    @DisplayName("seven drafts, all switched off, no images, and none served to the storefront")
    void sevenDrafts() {
        List<PopupView> all = popups.list();
        assertThat(all).extracting(PopupView::preset).containsExactlyInAnyOrder(Preset.values());
        assertThat(all).allSatisfy(p -> {
            assertThat(p.status()).isEqualTo(PopupTypes.Status.DRAFT);
            assertThat(p.enabled()).isFalse();
            assertThat(p.desktopImage()).isNull();
            assertThat(p.copy().es()).isNotNull();
            assertThat(p.copy().fr()).isNotNull();
        });
        for (Page page : Page.values()) {
            assertThat(live.forPage(page, "/")).isEmpty();
        }
    }

    @Test
    @DisplayName("each one is set up as the guide describes")
    void setUp() {
        PopupView home = guide(Preset.HOME_ENTRY);
        assertThat(home.type()).isEqualTo(Type.CENTER_MODAL);
        assertThat(home.targetPages()).containsExactly(Page.HOME);
        assertThat(home.frequency()).isEqualTo(Frequency.SESSION);
        assertThat(home.buttons()).extracting(b -> b.label().en() + " → " + b.destination())
                .containsExactly("Explore Boosting → /boosting", "Buy Coins → /order");

        PopupView coins = guide(Preset.COINS_OFFER);
        assertThat(coins.type()).isEqualTo(Type.BOTTOM_RIGHT);
        assertThat(coins.targetPages()).containsExactly(Page.COINS);
        assertThat(List.of(coins.triggerType(), coins.triggerValue(), coins.triggerScroll()))
                .containsExactly(Trigger.DELAY_OR_SCROLL, 8, 40);
        assertThat(List.of(coins.frequency(), coins.frequencyDays())).containsExactly(Frequency.DAYS, 1);
        assertThat(coins.buttons()).isEmpty();
        assertThat(coins.copy().en()).isEqualTo(new Copy("COIN PRICES JUST DROPPED!",
                "Get the best rates on FC coins right now.", REFERRAL, "You are covered with our 100% Safety Policy."));

        PopupView coinsCheckout = guide(Preset.COINS_CHECKOUT);
        assertThat(coinsCheckout.targetPages()).containsExactly(Page.COINS_CHECKOUT);
        assertThat(List.of(coinsCheckout.triggerType(), coinsCheckout.triggerEvent())).containsExactly(Trigger.STEP, "coins:continue");
        assertThat(coinsCheckout.copy().en()).isEqualTo(new Copy("LIVE ORDER TRACKING & LIVE CHAT",
                "After placing your order, you will get live order tracking and live chat support so you can stay "
                        + "updated and get help whenever you need it.", null, null));

        PopupView champs = guide(Preset.CHAMPS_LISTING);
        assertThat(champs.targetPages()).containsExactly(Page.CHAMPS_LISTING);
        assertThat(champs.triggerType()).isEqualTo(Trigger.DELAY_OR_SCROLL);
        assertThat(champs.buttons()).extracting(b -> b.label().en() + " → " + b.destination())
                .containsExactly("Explore Champs Boosting → /boosting");
        assertThat(champs.copy().en()).isEqualTo(new Copy("GET YOUR CHAMPS RANK DONE!",
                "Book Rank 1, Rank 2 or Rank 3 with our boosting service.", REFERRAL, null));

        PopupView champsCheckout = guide(Preset.CHAMPS_CHECKOUT);
        assertThat(champsCheckout.targetPages()).containsExactly(Page.CHAMPS_CHECKOUT);
        assertThat(List.of(champsCheckout.triggerType(), champsCheckout.triggerEvent())).containsExactly(Trigger.STEP, "champs:checkout");
        assertThat(champsCheckout.copy().en()).isEqualTo(new Copy("ONE STEP CLOSER TO GET YOUR RANK!",
                "Book Rank 1, Rank 2 or Rank 3 with our boosting service.", REFERRAL,
                "After you complete the payment, you will be connected to a booster on our team to start your order."
                        + "\n\nYou are covered by our 100% Safety Policy."));

        PopupView membership = guide(Preset.MEMBERSHIP);
        assertThat(membership.type()).isEqualTo(Type.CENTER_MODAL);
        assertThat(membership.targetPages()).contains(Page.REWARDS);
        assertThat(membership.copy().en()).isEqualTo(new Copy("UNLOCK MORE BENEFITS",
                "The more you spend, the higher your tier. Get bigger discounts and exclusive perks with GFS Membership.",
                null, "Eligible orders earn points. Points are worth {pointValue} each at checkout."));

        PopupView rewards = guide(Preset.REWARDS_REMINDER);
        assertThat(rewards.excludeCheckout()).isTrue();
        assertThat(rewards.triggerType()).isEqualTo(Trigger.DELAY_OR_SCROLL);
        assertThat(rewards.buttons()).extracting(b -> b.label().en() + " → " + b.destination())
                .containsExactly("Explore Rewards → /rewards");
        assertThat(rewards.copy().en()).isEqualTo(new Copy("MORE ORDERS, BIGGER REWARDS",
                "Earn points with every eligible order and unlock higher discounts.", null,
                "Earn points · Higher discounts · Exclusive perks"));
    }

    @Test
    @DisplayName("no price, tier percentage or promoter code is written into any of them, in any language")
    void nothingHardCoded() {
        String all = String.join("\n", jdbc.queryForList(
                "select copy::text || buttons::text from popup_campaign where preset is not null", String.class));
        assertThat(all).doesNotContain("₹", "Bronze", "Silver", "Gold", "Platinum", "Diamond", "Elite", "VINU");
        assertThat(all).doesNotContainPattern("\\d+(\\.\\d+)? ?(K|M)\\b");
    }

    @Test
    @DisplayName("the admin refuses a button on the coins offer, coins checkout and Champs checkout popups")
    void noButtons() {
        Button button = new Button(new Label("Buy", null, null), "/order");
        for (Preset preset : List.of(Preset.COINS_OFFER, Preset.COINS_CHECKOUT, Preset.CHAMPS_CHECKOUT)) {
            PopupView p = guide(preset);
            assertThatThrownBy(() -> tx.execute(s -> popups.update(p.id(), asInput(p, List.of(button)), adminId)))
                    .isInstanceOfSatisfying(PopupService.PopupInvalid.class,
                            e -> assertThat(e.problems()).containsKey("buttons"));
            // A copy keeps the rule.
            PopupView copy = tx.execute(s -> popups.duplicate(p.id(), adminId));
            assertThat(copy.preset()).isEqualTo(preset);
            assertThatThrownBy(() -> tx.execute(s -> popups.update(copy.id(), asInput(copy, List.of(button)), adminId)))
                    .isInstanceOf(PopupService.PopupInvalid.class);
            tx.execute(s -> { popups.delete(copy.id(), adminId); return null; });
        }
        // The others may have them.
        PopupView champs = guide(Preset.CHAMPS_LISTING);
        assertThatCode(() -> tx.execute(s -> popups.update(champs.id(), asInput(champs, champs.buttons()), adminId)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("every guide popup passes the admin's own checks as it stands, so it can be edited and published")
    void allValid() {
        for (PopupView p : popups.list()) {
            assertThatCode(() -> PopupService.check(asInput(p, p.buttons()), p.preset())).doesNotThrowAnyException();
        }
    }
}
