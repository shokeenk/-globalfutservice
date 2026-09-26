package com.globalfutservice.admin;

import com.globalfutservice.coaching.CoachAvailabilityRepository;
import com.globalfutservice.coaching.CoachEntity;
import com.globalfutservice.coaching.CoachExtraSlotRepository;
import com.globalfutservice.coaching.CoachRepository;
import com.globalfutservice.coaching.CoachTimeOffRepository;
import com.globalfutservice.coaching.CoachingService;
import com.globalfutservice.coaching.CoachingSessionEntity;
import com.globalfutservice.coaching.CoachingSessionRepository;
import com.globalfutservice.coaching.CoachingSettingsService;
import com.globalfutservice.coaching.SessionCreditRepository;
import com.globalfutservice.config.SecurityConfig;
import com.globalfutservice.domain.orders.OrderStatus;
import com.globalfutservice.identity.AccountRepository;
import com.globalfutservice.identity.AccountRole;
import com.globalfutservice.orders.OrderEntity;
import com.globalfutservice.orders.OrderRepository;
import com.globalfutservice.security.AccountPrincipal;
import com.globalfutservice.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Who may reach the Coaching diary's new endpoints.
 *
 * <p>The real {@link SecurityConfig} and method security. Two levels, as in the rest of this
 * controller: operators do the diary's day-to-day work -- confirming, moving and cancelling
 * sessions, reading the settings -- and only an admin changes the rules that decide which
 * slots exist: the settings and the extra windows.
 */
@WebMvcTest(AdminCoachingController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = {
        // Dummy values for placeholders that have no default. Not secrets: nothing signs
        // or decrypts anything in this test.
        "GFS_JWT_SECRET=test-only-jwt-secret-000000000000000000000000000000",
        "GFS_QUOTE_SECRET=test-only-quote-secret-00000000000000000000000000000",
        "GFS_CREDENTIAL_MASTER_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
})
class AdminCoachingSecurityTest {

    private static final String BASE = "/api/v1/admin/coaching";
    private static final String SETTINGS = """
            {"minNoticeMinutes":720,"bufferMinutes":15,"holdMinutes":120,
             "singleSessionMinutes":60,"blockSessionMinutes":40}
            """;

    @Autowired
    private MockMvc mvc;

    @MockBean private CoachRepository coaches;
    @MockBean private CoachAvailabilityRepository availability;
    @MockBean private CoachTimeOffRepository timeOff;
    @MockBean private CoachingSessionRepository sessions;
    @MockBean private CoachingService coaching;
    @MockBean private SessionCreditRepository credits;
    @MockBean private OrderRepository orders;
    @MockBean private AccountRepository accounts;
    @MockBean private CoachingSettingsService settings;
    @MockBean private CoachExtraSlotRepository extraSlots;
    @MockBean private JwtService jwtService;

    @BeforeEach
    void stub() {
        Instant start = Instant.parse("2026-10-05T13:30:00Z");
        CoachingSessionEntity held = CoachingSessionEntity.hold("ses_x", 7L, 1L, 42L, start,
                start.plus(Duration.ofHours(1)), "Asia/Kolkata", start.minus(Duration.ofDays(1)));
        // As it would come back from the database: a stored session always has an id.
        org.springframework.test.util.ReflectionTestUtils.setField(held, "id", 5L);
        OrderEntity paid = mock(OrderEntity.class);
        when(paid.getId()).thenReturn(42L);
        when(paid.getStatus()).thenReturn(OrderStatus.PAID);
        when(sessions.findByPublicRef("ses_x")).thenReturn(Optional.of(held));
        when(orders.findById(42L)).thenReturn(Optional.of(paid));
        when(coaching.confirmHoldForOrder(anyLong(), any(), any())).thenReturn(Optional.of(held));
        when(coaching.rescheduleByAdmin(any(), any(), any())).thenReturn(held);
        when(coaching.cancelByAdmin(any(), any(), any())).thenReturn(held);
        when(coaching.timeline(any())).thenReturn(List.of());
        when(coaches.findByPublicId("vinay"))
                .thenReturn(Optional.of(new CoachEntity("vinay", "Vinay", "Asia/Kolkata")));
        CoachingSettingsService.Settings current = new CoachingSettingsService.Settings(
                Duration.ofHours(12), Duration.ZERO, Duration.ofHours(2),
                Duration.ofMinutes(60), Duration.ofMinutes(40));
        when(settings.current()).thenReturn(current);
        when(settings.update(anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), any())).thenReturn(current);
        when(extraSlots.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    private static UsernamePasswordAuthenticationToken as(AccountRole role) {
        AccountPrincipal p = new AccountPrincipal(1L, "acc_test", "t@example.test", role);
        return new UsernamePasswordAuthenticationToken(p, null, p.authorities());
    }

    /** The diary's work: open to operators and admins. */
    static Stream<Arguments> operatorEndpoints() {
        return Stream.of(
                Arguments.of(HttpMethod.POST, BASE + "/sessions/ses_x/confirm", ""),
                Arguments.of(HttpMethod.POST, BASE + "/sessions/ses_x/reschedule",
                        "{\"startsAt\":\"2026-10-06T13:30:00Z\"}"),
                Arguments.of(HttpMethod.POST, BASE + "/sessions/ses_x/cancel", "{\"note\":\"coach ill\"}"),
                Arguments.of(HttpMethod.GET, BASE + "/sessions/ses_x/events", ""),
                Arguments.of(HttpMethod.GET, BASE + "/settings", ""),
                Arguments.of(HttpMethod.GET, BASE + "/coaches/vinay/extra-slots", ""));
    }

    /** The rules that decide which slots exist: admins only. */
    static Stream<Arguments> adminEndpoints() {
        return Stream.of(
                Arguments.of(HttpMethod.PUT, BASE + "/settings", SETTINGS),
                Arguments.of(HttpMethod.POST, BASE + "/coaches/vinay/extra-slots",
                        "{\"startsAt\":\"2026-10-06T04:30:00Z\",\"endsAt\":\"2026-10-06T07:30:00Z\"}"),
                Arguments.of(HttpMethod.DELETE, BASE + "/extra-slots/1", ""));
    }

    static Stream<Arguments> allNewEndpoints() {
        return Stream.concat(operatorEndpoints(), adminEndpoints());
    }

    private static MockHttpServletRequestBuilder call(HttpMethod method, String path, String body) {
        MockHttpServletRequestBuilder r = request(method, path).accept(MediaType.APPLICATION_JSON);
        return body.isEmpty() ? r : r.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @Nested
    class Access {

        @ParameterizedTest(name = "{0} {1}")
        @MethodSource("com.globalfutservice.admin.AdminCoachingSecurityTest#allNewEndpoints")
        @DisplayName("refuses a caller who is not signed in")
        void anonymous(HttpMethod method, String path, String body) throws Exception {
            mvc.perform(call(method, path, body)).andExpect(status().isUnauthorized());
        }

        @ParameterizedTest(name = "{0} {1}")
        @MethodSource("com.globalfutservice.admin.AdminCoachingSecurityTest#allNewEndpoints")
        @DisplayName("refuses a customer")
        void customer(HttpMethod method, String path, String body) throws Exception {
            mvc.perform(call(method, path, body).with(authentication(as(AccountRole.CUSTOMER))))
                    .andExpect(status().isForbidden());
        }

        @ParameterizedTest(name = "{0} {1}")
        @MethodSource("com.globalfutservice.admin.AdminCoachingSecurityTest#operatorEndpoints")
        @DisplayName("lets an operator do the diary's work")
        void operatorWork(HttpMethod method, String path, String body) throws Exception {
            mvc.perform(call(method, path, body).with(authentication(as(AccountRole.OPERATOR))))
                    .andExpect(status().is2xxSuccessful());
        }

        @ParameterizedTest(name = "{0} {1}")
        @MethodSource("com.globalfutservice.admin.AdminCoachingSecurityTest#adminEndpoints")
        @DisplayName("refuses an operator the rules that decide which slots exist")
        void operatorRefused(HttpMethod method, String path, String body) throws Exception {
            mvc.perform(call(method, path, body).with(authentication(as(AccountRole.OPERATOR))))
                    .andExpect(status().isForbidden());
        }

        @ParameterizedTest(name = "{0} {1}")
        @MethodSource("com.globalfutservice.admin.AdminCoachingSecurityTest#allNewEndpoints")
        @DisplayName("admits an admin everywhere")
        void admin(HttpMethod method, String path, String body) throws Exception {
            mvc.perform(call(method, path, body).with(authentication(as(AccountRole.ADMIN))))
                    .andExpect(status().is2xxSuccessful());
        }
    }

    @Nested
    class Confirming {

        @org.junit.jupiter.api.Test
        @DisplayName("refuses to confirm a hold whose order has not been paid")
        void unpaid() throws Exception {
            OrderEntity unpaid = mock(OrderEntity.class);
            when(unpaid.getId()).thenReturn(42L);
            when(unpaid.getStatus()).thenReturn(OrderStatus.AWAITING_PAYMENT);
            when(orders.findById(42L)).thenReturn(Optional.of(unpaid));

            mvc.perform(call(HttpMethod.POST, BASE + "/sessions/ses_x/confirm", "")
                            .with(authentication(as(AccountRole.OPERATOR))))
                    .andExpect(status().isConflict());
        }
    }
}
