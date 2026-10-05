package com.globalfutservice.payments.payop;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import com.globalfutservice.config.AppProperties;
import com.globalfutservice.domain.money.Currency;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FxRateServiceTest {

    private static final Clock NOW = Clock.fixed(Instant.parse("2026-10-04T12:00:00Z"), ZoneOffset.UTC);
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 4);

    /** The shape of eurofxref-daily.xml, trimmed. The ECB publishes no AED rate. */
    private static final String ECB_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <gesmes:Envelope xmlns:gesmes="http://www.gesmes.org/xml/2002-08-01" xmlns="http://www.ecb.int/vocabulary/2002-08-01/eurofxref">
            \t<gesmes:subject>Reference rates</gesmes:subject>
            \t<gesmes:Sender>
            \t\t<gesmes:name>European Central Bank</gesmes:name>
            \t</gesmes:Sender>
            \t<Cube>
            \t\t<Cube time='2026-10-02'>
            \t\t\t<Cube currency='USD' rate='1.1225'/>
            \t\t\t<Cube currency='JPY' rate='161.50'/>
            \t\t\t<Cube currency='GBP' rate='0.85033'/>
            \t\t\t<Cube currency='INR' rate='96.4310'/>
            \t\t</Cube>
            \t</Cube>
            </gesmes:Envelope>
            """;

    private HttpServer server;
    private final AtomicInteger requests = new AtomicInteger();
    private int status = 200;

    private final FxRateRepository repo = mock(FxRateRepository.class);
    private final List<FxRateEntity> saved = new ArrayList<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/eurofxref-daily.xml", exchange -> {
            requests.incrementAndGet();
            byte[] body = ECB_XML.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, status == 200 ? body.length : -1);
            if (status == 200) {
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        server.start();
        when(repo.save(any(FxRateEntity.class))).thenAnswer(inv -> {
            saved.add(inv.getArgument(0));
            return inv.getArgument(0);
        });
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private FxRateService service(boolean payopEnabled) {
        AppProperties props = mock(AppProperties.class);
        when(props.fx()).thenReturn(new AppProperties.Fx(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/eurofxref-daily.xml", Duration.ofDays(5)));
        when(props.payop()).thenReturn(PayopStartupCheckTest.payop(payopEnabled, null, null, null, null, null));
        return new FxRateService(repo, props, NOW);
    }

    private static FxRateEntity rate(String quote, String rate, String source, LocalDate date) {
        return new FxRateEntity(quote, new BigDecimal(rate), source, date, NOW.instant(), null);
    }

    @Test
    @DisplayName("reads the ECB's daily file: its date and every rate in it")
    void parsesEcb() throws Exception {
        FxRateService.EcbRates ecb = FxRateService.parseEcb(ECB_XML.getBytes(StandardCharsets.UTF_8));
        assertThat(ecb.date()).isEqualTo(LocalDate.of(2026, 10, 2));
        assertThat(ecb.rates()).containsEntry("USD", new BigDecimal("1.1225"))
                .containsEntry("GBP", new BigDecimal("0.85033"))
                .containsEntry("INR", new BigDecimal("96.4310"))
                .doesNotContainKey("AED");
    }

    @Test
    @DisplayName("a document with a DOCTYPE, or with no rates, is refused")
    void refusesBadDocuments() {
        String xxe = """
                <?xml version="1.0"?>
                <!DOCTYPE r [<!ENTITY x SYSTEM "file:///etc/passwd">]>
                <Cube><Cube time='2026-10-02'><Cube currency='USD' rate='&x;'/></Cube></Cube>
                """;
        assertThatThrownBy(() -> FxRateService.parseEcb(xxe.getBytes(StandardCharsets.UTF_8)))
                .hasMessageContaining("DOCTYPE");
        assertThatThrownBy(() -> FxRateService.parseEcb("<Cube><Cube time='2026-10-02'/></Cube>"
                .getBytes(StandardCharsets.UTF_8))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("EUR is one EUR, and recorded as needing no rate")
    void eurIsIdentity() {
        assertThat(service(true).eurTo(Currency.EUR)).contains(
                new FxRateService.RateUsed(BigDecimal.ONE, FxRateService.NONE, TODAY));
    }

    @Test
    @DisplayName("an ECB rate up to five days old is used, ahead of an admin's")
    void freshEcbWins() {
        when(repo.findFirstByQuoteAndSourceOrderByRateDateDescIdDesc("USD", FxRateEntity.ECB))
                .thenReturn(Optional.of(rate("USD", "1.1225", FxRateEntity.ECB, TODAY.minusDays(5))));
        when(repo.findFirstByQuoteAndSourceOrderByRateDateDescIdDesc("USD", FxRateEntity.ADMIN))
                .thenReturn(Optional.of(rate("USD", "1.2000", FxRateEntity.ADMIN, TODAY)));
        assertThat(service(true).eurTo(Currency.USD)).contains(
                new FxRateService.RateUsed(new BigDecimal("1.1225"), FxRateEntity.ECB, TODAY.minusDays(5)));
    }

    @Test
    @DisplayName("an ECB rate older than that gives way to the admin's")
    void staleEcbFallsBackToAdmin() {
        when(repo.findFirstByQuoteAndSourceOrderByRateDateDescIdDesc("USD", FxRateEntity.ECB))
                .thenReturn(Optional.of(rate("USD", "1.1225", FxRateEntity.ECB, TODAY.minusDays(6))));
        when(repo.findFirstByQuoteAndSourceOrderByRateDateDescIdDesc("USD", FxRateEntity.ADMIN))
                .thenReturn(Optional.of(rate("USD", "1.1300", FxRateEntity.ADMIN, TODAY.minusDays(1))));
        assertThat(service(true).eurTo(Currency.USD)).contains(
                new FxRateService.RateUsed(new BigDecimal("1.1300"), FxRateEntity.ADMIN, TODAY.minusDays(1)));
    }

    @Test
    @DisplayName("AED: the ECB has none, so only an admin's rate will do; without one there is no rate")
    void aedNeedsAnAdminRate() {
        when(repo.findFirstByQuoteAndSourceOrderByRateDateDescIdDesc(eq("AED"), anyString()))
                .thenReturn(Optional.empty());
        assertThat(service(true).eurTo(Currency.AED)).isEmpty();
        when(repo.findFirstByQuoteAndSourceOrderByRateDateDescIdDesc("AED", FxRateEntity.ADMIN))
                .thenReturn(Optional.of(rate("AED", "4.1224", FxRateEntity.ADMIN, TODAY)));
        assertThat(service(true).eurTo(Currency.AED)).map(FxRateService.RateUsed::source).contains(FxRateEntity.ADMIN);
    }

    @Test
    @DisplayName("refresh stores the day's rates for the site's currencies, once")
    void refreshStoresOnce() {
        when(repo.existsByQuoteAndSourceAndRateDate("GBP", FxRateEntity.ECB, LocalDate.of(2026, 10, 2)))
                .thenReturn(true);
        assertThat(service(true).refreshFromEcb()).isEqualTo(2);
        assertThat(saved).extracting(FxRateEntity::getQuote).containsExactly("INR", "USD");
        assertThat(saved).allSatisfy(r -> {
            assertThat(r.getSource()).isEqualTo(FxRateEntity.ECB);
            assertThat(r.getRateDate()).isEqualTo(LocalDate.of(2026, 10, 2));
        });
    }

    @Test
    @DisplayName("a failed fetch stores nothing and keeps the rates already held")
    void failedFetch() {
        status = 503;
        assertThat(service(true).refreshFromEcb()).isZero();
        verify(repo, never()).save(any());
    }

    @Test
    @DisplayName("the schedule does not reach out to the ECB while Payop is off")
    void scheduleIdleWhileOff() {
        service(false).refreshOnSchedule();
        assertThat(requests.get()).isZero();
        service(true).refreshOnSchedule();
        assertThat(requests.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("an admin rate must be for a currency other than EUR, and above zero")
    void adminRateChecks() {
        FxRateService fx = service(true);
        assertThatThrownBy(() -> fx.enterAdminRate(Currency.EUR, BigDecimal.ONE, TODAY, 1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> fx.enterAdminRate(Currency.AED, BigDecimal.ZERO, TODAY, 1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(fx.enterAdminRate(Currency.AED, new BigDecimal("4.1224"), TODAY, 7L).getEnteredBy()).isEqualTo(7L);
    }
}
