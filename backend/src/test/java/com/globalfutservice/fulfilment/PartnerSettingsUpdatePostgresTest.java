package com.globalfutservice.fulfilment;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.globalfutservice.config.AppProperties;
import com.globalfutservice.credentials.CredentialVaultService;
import com.globalfutservice.fulfilment.FakeFutTransfer.Reply;
import com.globalfutservice.fulfilment.VendorOrderActions.Status;
import com.globalfutservice.notify.FulfilmentAlert;
import com.globalfutservice.notify.NotificationService;
import com.globalfutservice.orders.OrderEntity;
import com.globalfutservice.orders.OrderRepository;
import com.globalfutservice.orders.OrderService;
import com.globalfutservice.scheduling.SchedulerLock;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * minTransferAmount 0 at FUT Transfer for orders sent with the old 50 (2026-10-15): which
 * orders V55 marks, the one /editOrderAPI call each gets after the deploy -- at most once,
 * recorded, listed -- and the admin button that does the same for one order.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PartnerSettingsUpdatePostgresTest {

    private static final VendorOrderActions.Admin ADMIN =
            new VendorOrderActions.Admin(null, "admin@example.test", "acc_admin");

    private TestDatabase db;
    private FakeFutTransfer vendor;
    private VendorOrderLedger ledger;
    private VendorOrderActions actions;
    private VendorControl control;
    private NotificationService notifications;
    private PartnerSettingsUpdate sweep;
    private final Map<Long, String> refs = new java.util.concurrent.ConcurrentHashMap<>();

    @BeforeAll
    void migrate() {
        db = TestDatabase.migrated();
    }

    @AfterAll
    void drop() {
        if (db != null) db.drop();
    }

    @BeforeEach
    void setUp() throws Exception {
        db.jdbc.update("delete from vendor_order_action");
        db.jdbc.update("delete from vendor_call");
        db.jdbc.update("delete from vendor_order");
        db.jdbc.update("update vendor_control set paused = false");
        vendor = new FakeFutTransfer();
        AppProperties props = VendorTestSupport.props(vendor.baseUrl(), Duration.ofMillis(800));
        control = VendorTestSupport.running();
        FutTransferClient client = new FutTransferClient(props, new ObjectMapper(), control,
                new VendorCallLog(db.named)).withoutRetryPauses();
        ledger = new VendorOrderLedger(db.named);
        OrderService orderService = mock(OrderService.class);
        when(orderService.requireAny(anyString())).thenAnswer(inv -> order(idOf(inv.getArgument(0))));
        actions = new VendorOrderActions(client, control, ledger, new VendorOrderActionLog(db.named),
                mock(CredentialVaultService.class), orderService, props, new ObjectMapper());
        OrderRepository orders = mock(OrderRepository.class);
        when(orders.findById(anyLong())).thenAnswer(inv -> Optional.of(order(inv.getArgument(0))));
        notifications = mock(NotificationService.class);
        sweep = new PartnerSettingsUpdate(ledger, actions, client, control, orders, notifications,
                mock(SchedulerLock.class), props).withoutPauses();
    }

    @AfterEach
    void tearDown() {
        vendor.close();
    }

    private OrderEntity order(long id) {
        OrderEntity o = mock(OrderEntity.class);
        when(o.getId()).thenReturn(id);
        when(o.getPublicRef()).thenReturn(refs.get(id));
        return o;
    }

    private long idOf(String ref) {
        return refs.entrySet().stream().filter(e -> e.getValue().equals(ref)).findFirst().orElseThrow().getKey();
    }

    /** A public-pool order FUT Transfer has, as V55 found it: due unless {@code due} is null. */
    private long sent(String ref, String state, String due) {
        long id = db.order(ref, "IN_PROGRESS", "0.5");
        refs.put(id, ref);
        db.vendorOrder(id, ref, "vid-" + ref.substring(ref.length() - 4), state, 500, "PUBLIC_POOL");
        db.jdbc.update("update vendor_order set settings_update = ? where order_id = ?", due, id);
        return id;
    }

    private String settings(long id) {
        return db.jdbc.queryForObject("select settings_update from vendor_order where order_id = ?", String.class, id);
    }

    private List<Map<String, Object>> history(long id) {
        return db.jdbc.queryForList("select action, actor_label, outcome, code, detail from vendor_order_action "
                + "where order_id = ? order by id", id);
    }

    private static final String APPLIED = "{\"outcome\":\"success\",\"updatedFields\":[\"minTransferAmount\"]}";

    @Test
    @DisplayName("after the deploy: each order due gets one /editOrderAPI with minTransferAmount 0 and nothing else, "
            + "recorded on the order, and listed")
    void eachOnce() {
        long a = sent("GFS-26-SETUPD01", "IN_DELIVERY", "PENDING");
        long b = sent("GFS-26-SETUPD02", "AWAITING_CUSTOMER", "PENDING");
        long notDue = sent("GFS-26-SETUPD03", "IN_DELIVERY", null);
        vendor.on("/editOrderAPI", Reply.ok(APPLIED));

        List<String> touched = sweep.run();

        assertThat(touched).containsExactly("GFS-26-SETUPD01 DONE (updated)", "GFS-26-SETUPD02 DONE (updated)");
        assertThat(vendor.calls("/editOrderAPI")).isEqualTo(2);
        assertThat(vendor.requests()).allSatisfy(r -> {
            assertThat(r.path()).isEqualTo("/editOrderAPI");
            List<String> fields = new java.util.ArrayList<>();
            r.body().fieldNames().forEachRemaining(fields::add);
            assertThat(fields).containsExactly("apiUser", "apiKey", "orderID", "minTransferAmount");
            assertThat(r.body().get("minTransferAmount").asInt()).isZero();
        });
        assertThat(vendor.requests()).extracting(r -> r.body().get("orderID").asText())
                .containsExactly("vid-PD01", "vid-PD02");

        assertThat(settings(a)).isEqualTo("DONE");
        assertThat(settings(b)).isEqualTo("DONE");
        assertThat(settings(notDue)).isNull();
        // In the order's calls log, and on its admin timeline, said as the deploy's.
        assertThat(db.jdbc.queryForObject("select count(*) from vendor_call where endpoint = '/editOrderAPI'",
                Integer.class)).isEqualTo(2);
        assertThat(history(a)).singleElement().satisfies(h -> {
            assertThat(h.get("action")).isEqualTo("UPDATE_SETTINGS");
            assertThat(h.get("actor_label")).isEqualTo("automatic: settings update on deploy");
            assertThat(h.get("outcome")).isEqualTo("DONE");
            assertThat((String) h.get("detail")).contains("minTransferAmount 0").contains("Nothing else");
        });
        // Nothing else about the orders moved.
        assertThat(ledger.find(a).orElseThrow().state()).isEqualTo("IN_DELIVERY");
        assertThat(ledger.find(b).orElseThrow().state()).isEqualTo("AWAITING_CUSTOMER");

        // Staff get the list.
        ArgumentCaptor<FulfilmentAlert> alert = ArgumentCaptor.forClass(FulfilmentAlert.class);
        verify(notifications).fulfilmentAlert(alert.capture());
        assertThat(alert.getValue().detail()).contains("GFS-26-SETUPD01").contains("GFS-26-SETUPD02")
                .doesNotContain("GFS-26-SETUPD03");

        // At most once: the next run, and every one after, sends nothing.
        assertThat(sweep.run()).isEmpty();
        assertThat(vendor.calls("/editOrderAPI")).isEqualTo(2);
    }

    @Test
    @DisplayName("a refusal or a lost answer is recorded and never sent again by the deploy; an order no longer open is "
            + "skipped without a call")
    void refusedUncertainSkipped() {
        long refused = sent("GFS-26-SETREF01", "IN_DELIVERY", "PENDING");
        long lost = sent("GFS-26-SETUNC01", "SUBMITTED", "PENDING");
        long finished = sent("GFS-26-SETDLV01", "DELIVERED", "PENDING");
        vendor.on("/editOrderAPI", body -> body.path("orderID").asText().equals("vid-EF01")
                ? Reply.of(404, "{\"error\":\"orderNotFound\"}") : Reply.of(500, "x"));

        List<String> touched = sweep.run();

        assertThat(touched).containsExactly("GFS-26-SETREF01 REFUSED (orderNotFound)", "GFS-26-SETUNC01 UNCERTAIN (HTTP_500)",
                "GFS-26-SETDLV01 SKIPPED");
        assertThat(settings(refused)).isEqualTo("REFUSED");
        assertThat(settings(lost)).isEqualTo("UNCERTAIN");
        assertThat(settings(finished)).isEqualTo("SKIPPED");
        assertThat(vendor.calls("/editOrderAPI")).isEqualTo(2);
        ArgumentCaptor<FulfilmentAlert> alert = ArgumentCaptor.forClass(FulfilmentAlert.class);
        verify(notifications).fulfilmentAlert(alert.capture());
        assertThat(alert.getValue().detail()).contains("Not confirmed for GFS-26-SETREF01, GFS-26-SETUNC01")
                .contains("Update settings at partner");

        assertThat(sweep.run()).isEmpty();
        assertThat(vendor.calls("/editOrderAPI")).isEqualTo(2);
    }

    @Test
    @DisplayName("asked to wait: that order goes back and the run ends; paused or off: nothing is claimed")
    void waitsAndPauses() {
        long first = sent("GFS-26-SETWAI01", "IN_DELIVERY", "PENDING");
        long second = sent("GFS-26-SETWAI02", "IN_DELIVERY", "PENDING");
        vendor.on("/editOrderAPI", Reply.of(429, "Too Many Requests"));

        sweep.run();

        assertThat(vendor.calls("/editOrderAPI")).isOne();
        assertThat(settings(first)).isEqualTo("PENDING");
        assertThat(settings(second)).isEqualTo("PENDING");

        when(control.isPaused()).thenReturn(true);
        assertThat(sweep.run()).isEmpty();
        assertThat(vendor.calls("/editOrderAPI")).isOne();
        assertThat(settings(first)).isEqualTo("PENDING");

        // Back, and the partner answers: both sent, once each.
        when(control.isPaused()).thenReturn(false);
        vendor.on("/editOrderAPI", Reply.ok(APPLIED));
        assertThat(sweep.run()).hasSize(2);
        assertThat(vendor.calls("/editOrderAPI")).isEqualTo(3);
        assertThat(settings(first)).isEqualTo("DONE");
        assertThat(settings(second)).isEqualTo("DONE");
    }

    @Test
    @DisplayName("nothing due, nothing sent, nobody told")
    void nothingDue() {
        sent("GFS-26-SETNON01", "IN_DELIVERY", null);
        assertThat(sweep.run()).isEmpty();
        assertThat(vendor.requests()).isEmpty();
        verify(notifications, never()).fulfilmentAlert(any());
    }

    @Test
    @DisplayName("the admin button: the same one call for one order, on its timeline under the admin, and the deploy "
            + "does not send it again")
    void adminButton() {
        long id = sent("GFS-26-SETBTN01", "NEEDS_REVIEW", "PENDING");
        vendor.on("/editOrderAPI", Reply.ok(APPLIED));

        VendorOrderActions.Result r = actions.updateSettings(order(id), ADMIN);

        assertThat(r.status()).isEqualTo(Status.DONE);
        assertThat(r.code()).isEqualTo(FutTransferClient.UPDATED);
        assertThat(r.message()).contains("minTransferAmount 0").contains("applied it");
        assertThat(vendor.calls("/editOrderAPI")).isOne();
        assertThat(history(id)).singleElement().satisfies(h -> {
            assertThat(h.get("action")).isEqualTo("UPDATE_SETTINGS");
            assertThat(h.get("actor_label")).isEqualTo("admin@example.test");
        });
        assertThat(settings(id)).isEqualTo("DONE");
        assertThat(sweep.run()).isEmpty();
        assertThat(vendor.calls("/editOrderAPI")).isOne();

        // Pressing it again is safe: the same value, one more call.
        assertThat(actions.updateSettings(order(id), ADMIN).status()).isEqualTo(Status.DONE);
        assertThat(vendor.calls("/editOrderAPI")).isEqualTo(2);

        // Offered while the partner has the order; never once it is done, or without the partner's id.
        assertThat(VendorOrderActions.available(ledger.detail(id).orElseThrow(),
                com.globalfutservice.domain.orders.OrderStatus.IN_PROGRESS)).contains("UPDATE_SETTINGS");
        long delivered = sent("GFS-26-SETBTN02", "DELIVERED", null);
        assertThat(VendorOrderActions.available(ledger.detail(delivered).orElseThrow(),
                com.globalfutservice.domain.orders.OrderStatus.DELIVERED)).doesNotContain("UPDATE_SETTINGS");
        assertThat(actions.updateSettings(order(delivered), ADMIN).status()).isEqualTo(Status.NOT_SENT);
        long noId = db.order("GFS-26-SETBTN03", "IN_PROGRESS", "0.5");
        refs.put(noId, "GFS-26-SETBTN03");
        db.vendorOrder(noId, "GFS-26-SETBTN03", null, "SUBMITTED", 500, "PUBLIC_POOL");
        assertThat(actions.updateSettings(order(noId), ADMIN).message()).contains("no partner order id");
        assertThat(vendor.calls("/editOrderAPI")).isEqualTo(2);
    }

    @Test
    @DisplayName("V55 marks only open public-pool orders FUT Transfer has, and still running there")
    void migrationMarks() {
        String url = System.getenv("GFS_TEST_PG_URL");
        assumeTrue(url != null && !url.isBlank(), "GFS_TEST_PG_URL not set: no database to test against");
        String schema = "settings_upd_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        DriverManagerDataSource ds = new DriverManagerDataSource(url + (url.contains("?") ? "&" : "?")
                + "currentSchema=" + schema + ",public", System.getenv("GFS_TEST_PG_USER"),
                System.getenv("GFS_TEST_PG_PASSWORD"));
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        try {
            flyway(ds, schema, "54").migrate();
            before(jdbc, "GFS-26-MIG00001", "vid-mig-01", "IN_DELIVERY", "PUBLIC_POOL", "transfersInProgress");
            before(jdbc, "GFS-26-MIG00002", "vid-mig-02", "SUBMITTED", "PUBLIC_POOL", null);
            before(jdbc, "GFS-26-MIG00003", "vid-mig-03", "AWAITING_CUSTOMER", "PUBLIC_POOL", "interrupted");
            before(jdbc, "GFS-26-MIG00004", "vid-mig-04", "NEEDS_REVIEW", "PUBLIC_POOL", "paused");
            before(jdbc, "GFS-26-MIG00005", "vid-mig-05", "NEEDS_REVIEW", "PUBLIC_POOL", "finished");
            before(jdbc, "GFS-26-MIG00006", "vid-mig-06", "NEEDS_REVIEW", "PUBLIC_POOL", "Cancelled");
            before(jdbc, "GFS-26-MIG00007", null, "SUBMITTED", "PUBLIC_POOL", null);
            before(jdbc, "GFS-26-MIG00008", "vid-mig-08", "IN_DELIVERY", "OWN_SENDERS", "transfersInProgress");
            before(jdbc, "GFS-26-MIG00009", "vid-mig-09", "DELIVERED", "PUBLIC_POOL", "finished");
            before(jdbc, "GFS-26-MIG00010", "vid-mig-10", "NEEDS_REVIEW", "PUBLIC_POOL", "archived");
            flyway(ds, schema, "latest").migrate();

            assertThat(jdbc.queryForList("select external_ref from vendor_order where settings_update = 'PENDING' "
                    + "order by external_ref", String.class))
                    .containsExactly("GFS-26-MIG00001", "GFS-26-MIG00002", "GFS-26-MIG00003", "GFS-26-MIG00004");
            assertThat(jdbc.queryForObject("select count(*) from vendor_order where settings_update is not null "
                    + "and settings_update <> 'PENDING'", Integer.class)).isZero();
        } finally {
            jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    private static Flyway flyway(DriverManagerDataSource ds, String schema, String target) {
        return Flyway.configure().dataSource(ds).schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration").target(target)
                .initSql("SET search_path TO " + schema + ", public").load();
    }

    private static void before(JdbcTemplate jdbc, String ref, String vid, String state, String mode, String status) {
        long id = jdbc.queryForObject("""
                insert into orders (public_ref, season, sku, quantity, delivery_method, currency, subtotal_minor,
                                    total_minor, price_breakdown, status, quote_id, guest_email)
                values (?, 'FC26', 'TRADING_SERVICE', 0.5, 'PLAYER_AUCTION', 'INR', 100000, 100000,
                        '{"quantity":0.5}'::jsonb, 'IN_PROGRESS', ?, 'buyer@example.test')
                returning id
                """, Long.class, ref, "q_" + ref);
        jdbc.update("""
                insert into vendor_order (order_id, external_ref, vendor_order_id, state, amount_ordered_k, submitted_at,
                                          order_mode, vendor_status)
                values (?, ?, ?, ?, 500, now(), ?, ?)
                """, id, ref, vid, state, mode, status);
    }
}
