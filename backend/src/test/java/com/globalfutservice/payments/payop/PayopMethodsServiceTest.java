package com.globalfutservice.payments.payop;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.globalfutservice.config.AppProperties;
import com.globalfutservice.notify.NotificationService;
import com.globalfutservice.notify.PaymentAlert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PayopMethodsServiceTest {

    private final PayopClient client = mock(PayopClient.class);
    private final PayopFeeMethodRepository fees = mock(PayopFeeMethodRepository.class);
    private final NotificationService notifications = mock(NotificationService.class);
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-04T12:00:00Z"));
    private final List<PayopFeeMethodEntity> table = new ArrayList<>();
    private PayopMethodsService service;

    private final Clock clock = new Clock() {
        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    };

    private static PayopFeeMethodEntity fee(long id, String name, List<String> countries) {
        return new PayopFeeMethodEntity(id, name, "bank_transfer", "Europe", new BigDecimal("0.30"),
                new BigDecimal("2.4"), countries, List.of("EUR"), null, Instant.EPOCH);
    }

    private static PayopClient.AvailableMethod live(long id, String title, List<String> countries) {
        return new PayopClient.AvailableMethod(id, title, "bank_transfer", List.of("EUR"), countries);
    }

    @BeforeEach
    void setUp() {
        when(fees.findByActiveTrue()).thenAnswer(inv -> List.copyOf(table));
        AppProperties props = mock(AppProperties.class);
        when(props.payop()).thenReturn(PayopStartupCheckTest.payop(true, "pub", "secret", "jwt", "606", null));
        when(props.publicUrl()).thenReturn("https://globalfutservices.com");
        service = new PayopMethodsService(client, fees, notifications, props, clock);
    }

    private List<Long> ids(String country) {
        return service.forCountry(country).methods().stream().map(o -> o.fee().getMethodId()).toList();
    }

    @Test
    @DisplayName("offered only when Payop lists it AND the fee table prices it; Payop's countries decide")
    void intersection() {
        table.add(fee(1, "Bank DE", List.of("DE")));          // Payop says AT only: Payop wins
        table.add(fee(2, "Sheet only", List.of("DE")));       // not in Payop's list: hidden
        table.add(fee(4, "Wallet", List.of("*")));            // Payop gives no countries: the table's apply
        when(client.availableMethods()).thenReturn(List.of(
                live(1, "Bank DE", List.of("AT")),
                live(3, "Payop only", List.of("DE")),           // not priced: hidden, staff told
                live(4, "Wallet", List.of())));

        assertThat(ids("AT")).containsExactly(1L, 4L);
        assertThat(ids("DE")).containsExactly(4L);
        assertThat(service.find(1, "AT")).isPresent();
        assertThat(service.find(1, "DE")).isEmpty();
        assertThat(service.find(2, "DE")).isEmpty();
        assertThat(service.find(3, "DE")).isEmpty();
    }

    @Test
    @DisplayName("a method Payop lists but the table does not price is raised with staff once, not per visitor")
    void unpricedAlertedOnce() {
        when(client.availableMethods()).thenReturn(List.of(live(3, "Payop only", List.of("DE"))));
        service.forCountry("DE");
        service.forCountry("AT");
        service.refresh();
        service.forCountry("DE");

        ArgumentCaptor<PaymentAlert> alert = ArgumentCaptor.forClass(PaymentAlert.class);
        verify(notifications, times(1)).paymentAlert(alert.capture());
        assertThat(alert.getValue().code()).isEqualTo("METHOD_UNPRICED");
        assertThat(alert.getValue().detail()).contains("3 Payop only");
        assertThat(alert.getValue().adminDeepLink()).isEqualTo("https://globalfutservices.com/admin/payop");
    }

    @Test
    @DisplayName("Payop's list is cached for an hour, then asked for again")
    void cached() {
        table.add(fee(1, "Bank", List.of("AT")));
        when(client.availableMethods()).thenReturn(List.of(live(1, "Bank", List.of("AT"))));
        service.forCountry("AT");
        now.set(now.get().plus(Duration.ofMinutes(59)));
        service.forCountry("AT");
        verify(client, times(1)).availableMethods();
        now.set(now.get().plus(Duration.ofMinutes(2)));
        service.forCountry("AT");
        verify(client, times(2)).availableMethods();
    }

    @Test
    @DisplayName("Payop unreachable: a list under six hours old is still used; with none, Payop is not offered")
    void unreachable() {
        table.add(fee(1, "Bank", List.of("AT")));
        PayopClient.PayopException down =
                new PayopClient.PayopException("list methods", PayopClient.ErrorCode.UNAVAILABLE, 0, List.of());
        when(client.availableMethods()).thenThrow(down);
        PayopMethodsService.Availability none = service.forCountry("AT");
        assertThat(none.unavailable()).isTrue();
        assertThat(none.methods()).isEmpty();

        // doReturn form: re-stubbing with when() would call the mock, and throw, while stubbing.
        doReturn(List.of(live(1, "Bank", List.of("AT")))).doThrow(down).when(client).availableMethods();
        service.forCountry("AT");
        now.set(now.get().plus(Duration.ofHours(5)));
        assertThat(ids("AT")).containsExactly(1L);
        now.set(now.get().plus(Duration.ofHours(2)));
        assertThat(service.forCountry("AT").unavailable()).isTrue();
        verify(notifications, never()).paymentAlert(any());
    }

    @Test
    @DisplayName("sorted by name, so the list reads the same for everyone")
    void sorted() {
        table.add(fee(1, "zeta Bank", List.of("AT")));
        table.add(fee(2, "Alpha Wallet", List.of("AT")));
        when(client.availableMethods()).thenReturn(List.of(live(1, "z", List.of("AT")), live(2, "a", List.of("AT"))));
        assertThat(ids("AT")).containsExactly(2L, 1L);
    }
}
