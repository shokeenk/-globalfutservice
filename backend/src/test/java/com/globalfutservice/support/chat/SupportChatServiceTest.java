package com.globalfutservice.support.chat;

import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.globalfutservice.coaching.CoachEntity;
import com.globalfutservice.coaching.CoachRepository;
import com.globalfutservice.coaching.CoachingSessionEntity;
import com.globalfutservice.coaching.CoachingSessionRepository;
import com.globalfutservice.config.AppProperties;
import com.globalfutservice.domain.catalog.Platform;
import com.globalfutservice.domain.catalog.Sku;
import com.globalfutservice.domain.coaching.SessionStatus;
import com.globalfutservice.domain.orders.OrderStatus;
import com.globalfutservice.identity.AccountEntity;
import com.globalfutservice.identity.AccountRepository;
import com.globalfutservice.orders.OrderEntity;
import com.globalfutservice.orders.OrderService;
import com.globalfutservice.support.chat.SupportChatService.Mode;
import com.globalfutservice.web.ApiExceptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The support page's data, and above all what the live chat is told: the owner's order
 * only, the fields on the allowlist only, and never a credential or anything about payment.
 */
class SupportChatServiceTest {

    private static final long ACCOUNT = 42L;
    private static final String REF = "GFS-26-70C4DPWH";
    private static final String KEY = "tawk-secure-key-for-tests-only";
    private static final Instant NOW = Instant.parse("2026-10-02T10:00:00Z");

    /** Every text field the service was not meant to read answers with this, so a leak shows. */
    private static final String SECRET = "SECRET-";

    private OrderService orders;
    private AccountRepository accounts;
    private CoachingSessionRepository sessions;
    private CoachRepository coaches;
    private AppProperties props;
    private SupportChatService service;

    @BeforeEach
    void setUp() {
        orders = mock(OrderService.class);
        accounts = mock(AccountRepository.class);
        sessions = mock(CoachingSessionRepository.class);
        coaches = mock(CoachRepository.class);
        props = mock(AppProperties.class);
        when(props.tawk()).thenReturn(new AppProperties.Tawk(KEY));
        service = new SupportChatService(orders, accounts, sessions, coaches, props,
                Clock.fixed(NOW, ZoneOffset.UTC));
        AccountEntity account = secretive(AccountEntity.class);
        when(account.getEmail()).thenReturn("rahul@example.test");
        when(account.getDisplayName()).thenReturn("Rahul");
        when(accounts.findById(ACCOUNT)).thenReturn(Optional.of(account));
    }

    /**
     * A mock whose every text getter answers {@link #SECRET} plus its own name, until a test
     * says otherwise. What the service reads beyond what it was stubbed with is a leak.
     */
    private static <T> T secretive(Class<T> type) {
        return mock(type, invocation -> invocation.getMethod().getReturnType() == String.class
                ? SECRET + invocation.getMethod().getName()
                : Mockito.RETURNS_DEFAULTS.answer(invocation));
    }

    private OrderEntity order(Sku sku, OrderStatus status, String label, Platform platform, String quantity) {
        OrderEntity o = secretive(OrderEntity.class);
        when(o.getId()).thenReturn(7L);
        when(o.getPublicRef()).thenReturn(REF);
        when(o.getSku()).thenReturn(sku);
        when(o.getStatus()).thenReturn(status);
        when(o.getServiceLabel()).thenReturn(label);
        when(o.getPlatform()).thenReturn(platform);
        when(o.getQuantity()).thenReturn(quantity == null ? null : new BigDecimal(quantity));
        when(orders.requireOwned(REF, ACCOUNT)).thenReturn(o);
        return o;
    }

    @Test
    @DisplayName("boosting: the order, its service, platform and status in the customer's words; nothing else applies")
    void boosting() {
        order(Sku.BOOST_CHAMPS, OrderStatus.PAID, "Champs Boosting — 14 wins · Elite II", Platform.PLAYSTATION, "1");

        SupportChatService.SupportContext ctx = service.contextFor(REF, ACCOUNT);

        assertThat(ctx.mode()).isEqualTo(Mode.BOOSTING);
        assertThat(ctx.chat().attributes()).containsExactly(
                java.util.Map.entry("order-id", REF),
                java.util.Map.entry("service", "Champs Boosting — 14 wins · Elite II"),
                java.util.Map.entry("platform", "PlayStation"),
                java.util.Map.entry("order-status", "Queued"),
                java.util.Map.entry("coin-amount", "n/a"),
                java.util.Map.entry("session-id", "n/a"),
                java.util.Map.entry("coach", "n/a"));
        assertThat(ctx.chat().name()).isEqualTo("Rahul");
        assertThat(ctx.chat().email()).isEqualTo("rahul@example.test");
        assertThat(ctx.summary().status()).isEqualTo("Queued");
    }

    @Test
    @DisplayName("coins: the coin amount, in the words the order page uses")
    void coins() {
        order(Sku.TRADING_SERVICE, OrderStatus.IN_PROGRESS, null, Platform.PC, "0.5");

        SupportChatService.SupportContext ctx = service.contextFor(REF, ACCOUNT);

        assertThat(ctx.mode()).isEqualTo(Mode.COINS);
        assertThat(ctx.chat().attributes().get("coin-amount")).isEqualTo(ctx.summary().coins()).isNotEqualTo("n/a");
        assertThat(ctx.chat().attributes().get("order-status")).isEqualTo("Being delivered");
        assertThat(ctx.chat().attributes().get("platform")).isEqualTo("PC");
    }

    @Test
    @DisplayName("coaching: the next session still to come and its coach; failing that, the latest")
    void coaching() {
        order(Sku.COACHING, OrderStatus.IN_PROGRESS, "FUT Classes — 6 sessions", null, "1");
        CoachingSessionEntity past = session("CS-PAST", NOW.minusSeconds(86_400), SessionStatus.COMPLETED, 1L);
        CoachingSessionEntity later = session("CS-LATER", NOW.plusSeconds(7 * 86_400), SessionStatus.SCHEDULED, 2L);
        CoachingSessionEntity next = session("CS-NEXT", NOW.plusSeconds(86_400), SessionStatus.SCHEDULED, 2L);
        when(sessions.findByOrderIdOrderByIdAsc(7L)).thenReturn(List.of(past, later, next));
        CoachEntity vinay = mock(CoachEntity.class);
        when(vinay.getDisplayName()).thenReturn("Vinay");
        when(coaches.findById(2L)).thenReturn(Optional.of(vinay));

        SupportChatService.SupportContext ctx = service.contextFor(REF, ACCOUNT);

        assertThat(ctx.mode()).isEqualTo(Mode.COACHING);
        assertThat(ctx.chat().attributes().get("session-id")).isEqualTo("CS-NEXT");
        assertThat(ctx.chat().attributes().get("coach")).isEqualTo("Vinay");
        assertThat(ctx.chat().attributes().get("coin-amount")).isEqualTo("n/a");
        assertThat(ctx.summary().sessionStartsAt()).isEqualTo(NOW.plusSeconds(86_400));

        // Nothing still to come: the latest one booked.
        when(sessions.findByOrderIdOrderByIdAsc(7L)).thenReturn(List.of(past));
        CoachEntity other = mock(CoachEntity.class);
        when(other.getDisplayName()).thenReturn("Arjun");
        when(coaches.findById(1L)).thenReturn(Optional.of(other));
        assertThat(service.contextFor(REF, ACCOUNT).chat().attributes())
                .containsEntry("session-id", "CS-PAST").containsEntry("coach", "Arjun");
    }

    private static CoachingSessionEntity session(String ref, Instant startsAt, SessionStatus status, long coachId) {
        CoachingSessionEntity s = secretive(CoachingSessionEntity.class);
        when(s.getPublicRef()).thenReturn(ref);
        when(s.getStartsAt()).thenReturn(startsAt);
        when(s.getStatus()).thenReturn(status);
        when(s.getCoachId()).thenReturn(coachId);
        when(s.getCustomerTimezone()).thenReturn("Asia/Kolkata");
        return s;
    }

    @Test
    @DisplayName("the mode is the order's, whatever page asked")
    void modeFromTheOrder() {
        assertThat(SupportChatService.modeOf(Sku.TRADING_SERVICE)).isEqualTo(Mode.COINS);
        assertThat(SupportChatService.modeOf(Sku.BOOST_RIVALS)).isEqualTo(Mode.BOOSTING);
        assertThat(SupportChatService.modeOf(Sku.COACHING)).isEqualTo(Mode.COACHING);
        assertThat(SupportChatService.modeOf(Sku.CARDS)).isEqualTo(Mode.BOOSTING);
    }

    @Test
    @DisplayName("another customer's order, or one that does not exist, is not found -- the same answer for both")
    void ownerOnly() {
        when(orders.requireOwned(anyString(), anyLong()))
                .thenThrow(new ApiExceptions.NotFoundException("No such order."));
        assertThatThrownBy(() -> service.contextFor("GFS-26-NOTYOURS", ACCOUNT))
                .isInstanceOf(ApiExceptions.NotFoundException.class).hasMessage("No such order.");
    }

    @Test
    @DisplayName("Secure Mode: the email signed with the key as tawk.to checks it; no key, no hash")
    void secureMode() throws Exception {
        order(Sku.BOOST_CHAMPS, OrderStatus.PAID, "Champs Boosting", Platform.PC, "1");
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(KEY.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String expected = HexFormat.of().formatHex(mac.doFinal("rahul@example.test".getBytes(StandardCharsets.UTF_8)));

        assertThat(service.contextFor(REF, ACCOUNT).chat().hash()).isEqualTo(expected).hasSize(64);

        when(props.tawk()).thenReturn(new AppProperties.Tawk(""));
        assertThat(service.contextFor(REF, ACCOUNT).chat().hash()).isNull();
        assertThat(new AppProperties.Tawk(KEY).toString()).doesNotContain(KEY).contains("[redacted]");
    }

    @Test
    @DisplayName("values tawk.to would refuse are made acceptable: never empty, never over 255 characters")
    void values() {
        assertThat(SupportChatService.value(null)).isEqualTo("n/a");
        assertThat(SupportChatService.value("  ")).isEqualTo("n/a");
        assertThat(SupportChatService.value("x".repeat(300))).hasSize(255);
        assertThat(SupportChatService.ATTRIBUTE_KEYS).allMatch(k -> k.matches("[a-z0-9-]+"));
    }

    // --------------------------------------------------------------- the allowlist ---

    @Test
    @DisplayName("nothing the service was not meant to read reaches the page or the chat, in any mode")
    void nothingElseLeaks() throws Exception {
        ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
        for (Sku sku : List.of(Sku.BOOST_CHAMPS, Sku.TRADING_SERVICE, Sku.COACHING)) {
            order(sku, OrderStatus.IN_PROGRESS, "A service", Platform.XBOX, "0.25");
            CoachingSessionEntity upcoming = session("CS-1", NOW.plusSeconds(3600), SessionStatus.SCHEDULED, 2L);
            when(sessions.findByOrderIdOrderByIdAsc(7L)).thenReturn(List.of(upcoming));
            CoachEntity coach = secretive(CoachEntity.class);
            when(coach.getDisplayName()).thenReturn("Vinay");
            when(coaches.findById(2L)).thenReturn(Optional.of(coach));

            String out = json.writeValueAsString(service.contextFor(REF, ACCOUNT));

            // Guest email and name, EA handle, Discord, notes, price breakdown, coupon,
            // supplier codes, password hash, OAuth ids: every one answers SECRET- above.
            assertThat(out).as(sku.name()).doesNotContain(SECRET);
        }
    }

    @Test
    @DisplayName("the chat's keys are exactly the allowlist, and the response has no field for anything else")
    void allowlistIsTheShape() {
        for (Sku sku : List.of(Sku.BOOST_CHAMPS, Sku.TRADING_SERVICE, Sku.COACHING)) {
            order(sku, OrderStatus.PAID, "A service", Platform.PC, "1");
            assertThat(service.contextFor(REF, ACCOUNT).chat().attributes().keySet())
                    .containsExactlyElementsOf(SupportChatService.ATTRIBUTE_KEYS);
        }

        Set<String> fields = new HashSet<>();
        for (Class<?> record : List.of(SupportChatService.SupportContext.class, SupportChatService.Summary.class,
                SupportChatService.Chat.class)) {
            Arrays.stream(record.getRecordComponents()).map(RecordComponent::getName).forEach(fields::add);
        }
        assertThat(fields).containsExactlyInAnyOrder("mode", "summary", "chat", "reference", "service", "platform",
                "status", "coins", "session", "sessionStartsAt", "sessionTimezone", "coach", "name", "email", "hash",
                "attributes");
        assertThat(fields).noneMatch(f -> f.matches("(?i).*(pass|backup|code|ea|payment|card|upi|token|secret).*"));
    }

    @Test
    @DisplayName("the service cannot reach the credential vault or a payment: it is not given either")
    void cannotReachSecrets() {
        Set<String> dependencies = new HashSet<>();
        for (var ctor : SupportChatService.class.getConstructors()) {
            for (Class<?> p : ctor.getParameterTypes()) dependencies.add(p.getName());
        }
        assertThat(dependencies).noneMatch(d -> d.contains(".credentials.") || d.contains(".payments.")
                || d.contains("Vault") || d.contains("Payment"));
    }
}
