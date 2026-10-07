package com.globalfutservice.credentials;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.globalfutservice.affiliate.AffiliateService;
import com.globalfutservice.coaching.AfterCommit;
import com.globalfutservice.coaching.CoachingService;
import com.globalfutservice.config.AppProperties;
import com.globalfutservice.credentials.web.CredentialDtos;
import com.globalfutservice.domain.orders.OrderStatus;
import com.globalfutservice.identity.AccountRepository;
import com.globalfutservice.loyalty.LoyaltyService;
import com.globalfutservice.notify.NotificationService;
import com.globalfutservice.notify.feed.CustomerFeedService;
import com.globalfutservice.orders.OrderEntity;
import com.globalfutservice.orders.OrderEventRepository;
import com.globalfutservice.orders.OrderRepository;
import com.globalfutservice.orders.OrderService;
import com.globalfutservice.payments.PaymentGateway;
import com.globalfutservice.payments.PaymentRepository;
import com.globalfutservice.pricing.CouponService;
import com.globalfutservice.pricing.QuoteService;
import com.globalfutservice.web.ApiExceptions;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Every backup code a coin order needs, each in EA's format, or the sign-in is refused --
 * here, on the server, whatever the storefront did or did not check.
 *
 * <p>Client testing found a checkout with one of three codes filled in. The storefront now
 * says which are missing; this is what stops a request that never went through it.
 */
class BackupCodeRulesTest {

    private static void refused(List<String> codes, int required, String code, String message) {
        assertThatThrownBy(() -> SignInRules.backupCodes(codes, required))
                .isInstanceOfSatisfying(ApiExceptions.BadRequestException.class, e -> {
                    assertThat(e.code()).isEqualTo(code);
                    assertThat(e.getMessage()).isEqualTo(message);
                });
    }

    @Nested
    @DisplayName("the rule")
    class Rule {

        @Test
        @DisplayName("1 of 3 is refused, naming the first one missing")
        void oneOfThree() {
            refused(List.of("12345678"), 3, "backup_code_required", "Backup code 2 is required.");
            refused(List.of("12345678", "87654321"), 3, "backup_code_required", "Backup code 3 is required.");
            refused(List.of(), 3, "backup_code_required", "Backup code 1 is required.");
            refused(null, 3, "backup_code_required", "Backup code 1 is required.");
            // Blank boxes are not codes.
            refused(List.of("12345678", "  ", ""), 3, "backup_code_required", "Backup code 2 is required.");
        }

        @ParameterizedTest(name = "\"{0}\"")
        @ValueSource(strings = {"1234567", "123456789", "1234abcd", "BACKUP12", "1234 5678"})
        @DisplayName("a code that is not exactly 8 digits is refused, by its position")
        void format(String bad) {
            refused(List.of("12345678", bad, "87654321"), 3, "backup_code_invalid",
                    "Backup code 2 must be exactly 8 digits.");
        }

        @Test
        @DisplayName("all three, in EA's format: accepted, trimmed, in the order given")
        void complete() {
            assertThat(SignInRules.backupCodes(List.of(" 12345678", "87654321 ", "11112222"), 3))
                    .containsExactly("12345678", "87654321", "11112222");
        }

        @Test
        @DisplayName("the number required is a setting; more than FUT Transfer's five is refused")
        void configurable() {
            assertThat(SignInRules.backupCodes(List.of("12345678"), 1)).containsExactly("12345678");
            refused(List.of("12345678", "87654321", "11112222", "33334444"), 5, "backup_code_required",
                    "Backup code 5 is required.");
            List<String> six = Arrays.asList("11111111", "22222222", "33333333", "44444444", "55555555", "66666666");
            refused(six, 3, "backup_code_invalid", "Send at most 5 backup codes.");
        }
    }

    @Nested
    @DisplayName("the setting")
    class Setting {

        private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

        private AppProperties.Fulfilment bind(Map<String, String> values) {
            return new Binder(new MapConfigurationPropertySource(values)).bind("f", AppProperties.Fulfilment.class).get();
        }

        @Test
        @DisplayName("defaults to 3, and 1 to 5 is accepted; 0 or 6 stops startup")
        void bounds() {
            assertThat(bind(Map.of("f.refund-fee-bps", "500")).backupCodesRequired()).isEqualTo(3);
            for (String ok : new String[] {"1", "5"}) {
                assertThat(validator.validate(bind(Map.of("f.backup-codes-required", ok)))).isEmpty();
            }
            for (String bad : new String[] {"0", "6"}) {
                assertThat(validator.validate(bind(Map.of("f.backup-codes-required", bad)))).hasSize(1);
            }
        }
    }

    @Nested
    @DisplayName("submitting a sign-in")
    class Submitting {

        private final CredentialVaultService vault = mock(CredentialVaultService.class);
        private final AppProperties props = mock(AppProperties.class);
        private final OrderService service = new OrderService(mock(OrderRepository.class),
                mock(OrderEventRepository.class), mock(PaymentRepository.class), mock(PaymentGateway.class),
                mock(QuoteService.class), mock(LoyaltyService.class), mock(AffiliateService.class), vault,
                mock(NotificationService.class), mock(AccountRepository.class), mock(CoachingService.class),
                mock(CouponService.class), mock(CustomerFeedService.class), new ObjectMapper(), props,
                Clock.systemUTC(), AfterCommit.immediate());

        private OrderEntity unpaidCoinOrder() {
            when(props.fulfilment()).thenReturn(new Binder(new MapConfigurationPropertySource(
                    Map.of("f.backup-codes-required", "3"))).bind("f", AppProperties.Fulfilment.class).get());
            OrderEntity order = mock(OrderEntity.class);
            when(order.getId()).thenReturn(7L);
            when(order.getPublicRef()).thenReturn("GFS-26-CODES001");
            when(order.requiresCredentials()).thenReturn(true);
            when(order.getStatus()).thenReturn(OrderStatus.AWAITING_PAYMENT);
            return order;
        }

        private static CredentialDtos.SubmitCredentialsRequest withCodes(List<String> codes) {
            return new CredentialDtos.SubmitCredentialsRequest("ea@example.test", "correct-horse", codes,
                    null, null, true, true, true, true);
        }

        @Test
        @DisplayName("1 of 3 codes: refused, and nothing reaches the vault")
        void oneOfThreeRefused() {
            OrderEntity order = unpaidCoinOrder();
            assertThatThrownBy(() -> service.submitCredentials(order, withCodes(List.of("12345678")), 1L))
                    .isInstanceOfSatisfying(ApiExceptions.BadRequestException.class,
                            e -> assertThat(e.code()).isEqualTo("backup_code_required"));
            verify(vault, never()).store(anyLong(), any());
        }

        @Test
        @DisplayName("a malformed code: refused, and nothing reaches the vault")
        void malformedRefused() {
            OrderEntity order = unpaidCoinOrder();
            assertThatThrownBy(() -> service.submitCredentials(order,
                    withCodes(List.of("12345678", "1234", "87654321")), 1L))
                    .isInstanceOfSatisfying(ApiExceptions.BadRequestException.class,
                            e -> assertThat(e.code()).isEqualTo("backup_code_invalid"));
            verify(vault, never()).store(anyLong(), any());
        }

        @Test
        @DisplayName("all three: stored, trimmed")
        void completeStored() {
            OrderEntity order = unpaidCoinOrder();
            service.submitCredentials(order, withCodes(List.of("12345678 ", " 87654321", "11112222")), 1L);
            ArgumentCaptor<CredentialDtos.SubmitCredentialsRequest> stored =
                    ArgumentCaptor.forClass(CredentialDtos.SubmitCredentialsRequest.class);
            verify(vault).store(any(), stored.capture());
            assertThat(stored.getValue().backupCodes()).containsExactly("12345678", "87654321", "11112222");
            assertThat(stored.getValue().eaPassword()).isEqualTo("correct-horse");
        }
    }
}
