package com.globalfutservice.payments.payop;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.globalfutservice.coaching.CoachingService;
import com.globalfutservice.config.AppProperties;
import com.globalfutservice.domain.money.Currency;
import com.globalfutservice.domain.orders.OrderStatus;
import com.globalfutservice.fulfilment.VendorOrderLedger;
import com.globalfutservice.notify.DiscordBotClient;
import com.globalfutservice.notify.discord.DiscordVerificationService;
import com.globalfutservice.orders.OrderEntity;
import com.globalfutservice.orders.OrderPaymentState;
import com.globalfutservice.orders.OrderRepository;
import com.globalfutservice.orders.web.OrderDtos;
import com.globalfutservice.orders.web.OrderMapper;
import com.globalfutservice.web.ApiExceptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PayopCheckoutServiceTest {

    private static final String INVOICE_1 = "11111111-aaaa-4bbb-8ccc-000000000001";
    private static final String INVOICE_2 = "22222222-aaaa-4bbb-8ccc-000000000002";

    private final PayopFakes.Invoices invoices = new PayopFakes.Invoices();
    private final OrderRepository orders = mock(OrderRepository.class);
    private final PayopMethodsService methods = mock(PayopMethodsService.class);
    private final FxRateService fx = mock(FxRateService.class);
    private final PayopClient client = mock(PayopClient.class);
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-04T12:00:00Z"));
    private boolean enabled = true;
    private PayopCheckoutService checkout;

    /** Bank transfer: 0.30 EUR + 4.0%. Wallet: 2.00 EUR + 3.0%. */
    private final PayopFeeMethodEntity bank = PayopFakes.method(381, "Bank transfer", "0.30", "4.0");
    private final PayopFeeMethodEntity wallet = PayopFakes.method(700001, "Wallet", "2.00", "3.0");

    private final OrderEntity eur = PayopFakes.order(7, "GFS-26-EUR00001", Currency.EUR, OrderStatus.AWAITING_PAYMENT);
    private final OrderEntity usd = PayopFakes.order(8, "GFS-26-USD00001", Currency.USD, OrderStatus.AWAITING_PAYMENT);
    private final OrderEntity inr = PayopFakes.order(9, "GFS-26-INR00001", Currency.INR, OrderStatus.AWAITING_PAYMENT);

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

    @BeforeEach
    void setUp() {
        AppProperties props = mock(AppProperties.class);
        when(props.payop()).thenAnswer(inv -> PayopStartupCheckTest.payop(enabled, "pub", "secret", "jwt", "606", null));
        when(props.publicUrl()).thenReturn("https://globalfutservices.com");
        when(fx.eurTo(Currency.EUR)).thenReturn(Optional.of(
                new FxRateService.RateUsed(BigDecimal.ONE, FxRateService.NONE, LocalDate.of(2026, 10, 4))));
        when(fx.eurTo(Currency.USD)).thenReturn(Optional.of(
                new FxRateService.RateUsed(new BigDecimal("1.1225"), "ECB", LocalDate.of(2026, 10, 2))));
        PayopClient.AvailableMethod liveBank = new PayopClient.AvailableMethod(381, "Bank", "bank_transfer",
                List.of("EUR"), List.of("DE"));
        PayopClient.AvailableMethod liveWallet = new PayopClient.AvailableMethod(700001, "Wallet", "ewallet",
                List.of("EUR"), List.of("DE"));
        when(methods.forCountry("DE")).thenReturn(new PayopMethodsService.Availability(List.of(
                new PayopMethodsService.Offered(bank, liveBank), new PayopMethodsService.Offered(wallet, liveWallet)),
                false));
        when(methods.forCountry("FR")).thenReturn(new PayopMethodsService.Availability(List.of(), false));
        when(methods.find(anyLong(), anyString())).thenAnswer(inv -> methods.forCountry(inv.getArgument(1))
                .methods().stream().filter(o -> o.fee().getMethodId() == (long) inv.getArgument(0)).findFirst());
        when(orders.findById(7L)).thenReturn(Optional.of(eur));
        when(orders.findById(8L)).thenReturn(Optional.of(usd));
        when(client.createInvoice(any())).thenReturn(INVOICE_1, INVOICE_2);
        checkout = new PayopCheckoutService(invoices.repo, orders, methods, fx, client, props, new ObjectMapper(),
                PayopFakes.noTransactions(), clock);
    }

    private long totalFor(OrderEntity order, long methodId) {
        return checkout.options(order, "DE").methods().stream().filter(m -> m.methodId() == methodId)
                .findFirst().orElseThrow().totalMinor();
    }

    @Nested
    @DisplayName("what is offered")
    class Offer {

        @Test
        @DisplayName("each method's fee is grossed up on the price without the 2.5% card fee, coupon already off")
        void pricesEachMethod() {
            PayopCheckoutService.Options o = checkout.options(eur, "de");
            // 92.25 paid by card would include 2.25 of card fee; Payop starts from 90.00.
            assertThat(o.netMinor()).isEqualTo(9000);
            assertThat(o.unavailable()).isNull();
            // (90.00 + 0.30) / 0.96 = 94.0625 -> 94.07; (90.00 + 2.00) / 0.97 = 94.845... -> 94.85
            assertThat(o.methods()).containsExactly(
                    new PayopCheckoutService.MethodOption(381, "Bank transfer", "bank_transfer", 407, 9407),
                    new PayopCheckoutService.MethodOption(700001, "Wallet", "bank_transfer", 485, 9485));
        }

        @Test
        @DisplayName("no double fee: the 2.5% line is gone from a Payop total, only the method's own fee is added")
        void noDoubleFee() {
            long total = totalFor(eur, 381);
            assertThat(total - 9000).as("the only fee").isEqualTo(407);
            assertThat(total).isNotEqualTo(9225 + 407);
        }

        @Test
        @DisplayName("the EUR fixed part is converted at the recorded rate for other currencies")
        void convertsFixedPart() {
            // USD at 1.1225: 0.30 EUR = 0.33675 USD. (90.00 + 0.33675) / 0.96 = 94.1008 -> 94.11
            assertThat(totalFor(usd, 381)).isEqualTo(9411);
        }

        /** India, with a card method priced by hand: 0.20 EUR + 3.5%. */
        private void indiaWithCards() {
            when(fx.eurTo(Currency.INR)).thenReturn(Optional.of(
                    new FxRateService.RateUsed(new BigDecimal("90.50"), "ECB", LocalDate.of(2026, 10, 2))));
            when(orders.findById(9L)).thenReturn(Optional.of(inr));
            PayopFeeMethodEntity cards = PayopFeeMethodEntity.manual(900001, "Visa / Mastercard", "cards_international",
                    null, new BigDecimal("0.20"), new BigDecimal("3.5"), List.of("IN"), List.of("INR", "USD"), true, 9L,
                    Instant.EPOCH);
            when(methods.forCountry("IN")).thenReturn(new PayopMethodsService.Availability(List.of(
                    new PayopMethodsService.Offered(cards, new PayopClient.AvailableMethod(900001, "Cards",
                            "cards_international", List.of("INR"), List.of("IN")))), false));
        }

        @Test
        @DisplayName("INR orders are offered Payop: the method's fee only, never the 2.5%, invoiced in INR")
        void inr() {
            indiaWithCards();
            PayopCheckoutService.Options o = checkout.options(inr, "IN");

            assertThat(o.currency()).isEqualTo(Currency.INR);
            assertThat(o.netMinor()).as("₹92.25 by UPI, less its ₹2.25 card fee").isEqualTo(9000);
            assertThat(o.cards()).isTrue();
            // 0.20 EUR at 90.50 = ₹18.10; (₹90.00 + ₹18.10) / 0.965 = ₹112.0207 -> ₹112.03
            assertThat(o.methods()).singleElement().satisfies(m -> {
                assertThat(m.card()).isTrue();
                assertThat(m.feeMinor()).isEqualTo(2203);
                assertThat(m.totalMinor()).isEqualTo(11203).isEqualTo(9000 + 2203);
            });

            checkout.start(inr, 900001, "IN", 11203, "en");
            ArgumentCaptor<PayopClient.InvoiceRequest> sent = ArgumentCaptor.forClass(PayopClient.InvoiceRequest.class);
            verify(client).createInvoice(sent.capture());
            assertThat(sent.getValue().currency()).isEqualTo(Currency.INR);
            assertThat(sent.getValue().amount()).isEqualTo("112.03");
            // UPI on the same order is still paid at its own total, 2.5% included.
            assertThat(inr.getTotalMinor()).isEqualTo(9225);
        }

        @Test
        @DisplayName("named for what is offered: cards where a card method is offered, not where none is, nor when off")
        void cardsOffered() {
            indiaWithCards();
            assertThat(checkout.cardsOffered("IN")).isTrue();
            assertThat(checkout.cardsOffered("de")).as("bank transfer and a wallet").isFalse();
            enabled = false;
            assertThat(checkout.cardsOffered("IN")).isFalse();
        }

        @Test
        @DisplayName("off by default: nothing is offered at all")
        void off() {
            enabled = false;
            assertThatThrownBy(() -> checkout.options(eur, "DE")).isInstanceOf(ApiExceptions.NotFoundException.class);
            assertThatThrownBy(() -> checkout.start(eur, 381, "DE", 9407, "en"))
                    .isInstanceOf(ApiExceptions.NotFoundException.class);
        }

        @Test
        @DisplayName("only an order waiting for payment, and only for a real country")
        void preconditions() {
            OrderEntity paid = PayopFakes.order(10, "GFS-26-PAID0001", Currency.EUR, OrderStatus.PAID);
            assertThatThrownBy(() -> checkout.options(paid, "DE")).isInstanceOf(ApiExceptions.ConflictException.class);
            assertThatThrownBy(() -> checkout.options(eur, "Germany"))
                    .isInstanceOf(ApiExceptions.BadRequestException.class);
        }

        @Test
        @DisplayName("without a rate for the currency, or without Payop's list, nothing is offered and it says why")
        void unavailable() {
            when(fx.eurTo(Currency.USD)).thenReturn(Optional.empty());
            assertThat(checkout.options(usd, "DE").unavailable()).isEqualTo("NO_RATE");
            when(methods.forCountry("DE")).thenReturn(new PayopMethodsService.Availability(List.of(), true));
            assertThat(checkout.options(eur, "DE").unavailable()).isEqualTo("PAYOP_UNAVAILABLE");
        }
    }

    @Nested
    @DisplayName("starting a payment")
    class Start {

        @Test
        @DisplayName("creates one invoice for exactly the recomputed total, for the order's email, and keeps the fee")
        void creates() {
            PayopCheckoutService.Started s = checkout.start(eur, 381, "DE", 9407, "es");

            ArgumentCaptor<PayopClient.InvoiceRequest> sent = ArgumentCaptor.forClass(PayopClient.InvoiceRequest.class);
            verify(client).createInvoice(sent.capture());
            PayopClient.InvoiceRequest r = sent.getValue();
            assertThat(r.orderRef()).isEqualTo("GFS-26-EUR00001");
            assertThat(r.amount()).isEqualTo("94.07");
            assertThat(r.currency()).isEqualTo(Currency.EUR);
            assertThat(r.payerEmail()).isEqualTo("buyer@example.com");
            assertThat(r.methodId()).isEqualTo(381);
            assertThat(r.language()).isEqualTo("es");
            assertThat(r.resultUrl()).isEqualTo(
                    "https://globalfutservices.com/payment/payop/return?ref=GFS-26-EUR00001&invoice={{invoiceId}}");
            assertThat(r.failUrl()).endsWith("&result=failed");

            PayopInvoiceEntity a = invoices.forOrder(7).get(0);
            assertThat(a.getStatus()).isEqualTo(PayopInvoiceEntity.Status.OPEN);
            assertThat(a.getInvoiceId()).isEqualTo(INVOICE_1);
            assertThat(a.getAttemptId()).isEqualTo(r.attemptId());
            assertThat(a.getAmountSent()).isEqualTo(r.amount());
            assertThat(a.getNetMinor()).isEqualTo(9000);
            assertThat(a.getFeeMinor()).isEqualTo(407);
            assertThat(a.getTotalMinor()).isEqualTo(9407);
            assertThat(a.getFixedEur()).isEqualByComparingTo("0.30");
            assertThat(a.getPercent()).isEqualByComparingTo("4.0");
            assertThat(a.getFxSource()).isEqualTo(FxRateService.NONE);
            assertThat(a.getCountry()).isEqualTo("DE");
            assertThat(a.getExpiresAt()).isEqualTo(now.get().plusSeconds(24 * 3600));

            assertThat(s.redirectUrl()).isEqualTo(
                    "https://checkout.payop.com/es/payment/invoice-preprocessing/" + INVOICE_1);
            assertThat(s.totalMinor()).isEqualTo(9407);
        }

        @Test
        @DisplayName("a total sent by the browser that is not the server's is refused, and nothing is created")
        void tamperedTotal() {
            assertThatThrownBy(() -> checkout.start(eur, 381, "DE", 9000, "en"))
                    .isInstanceOfSatisfying(ApiExceptions.ConflictException.class,
                            e -> assertThat(e.code()).isEqualTo("price_changed"));
            // The wallet's total sent with the bank chosen: a method change is recomputed too.
            assertThatThrownBy(() -> checkout.start(eur, 381, "DE", 9485, "en"))
                    .isInstanceOf(ApiExceptions.ConflictException.class);
            assertThat(invoices.rows).isEmpty();
            verify(client, never()).createInvoice(any());
        }

        @Test
        @DisplayName("a method not offered in the chosen country is refused")
        void methodNotOffered() {
            assertThatThrownBy(() -> checkout.start(eur, 381, "FR", 9407, "en"))
                    .isInstanceOfSatisfying(ApiExceptions.ConflictException.class,
                            e -> assertThat(e.code()).isEqualTo("method_unavailable"));
            assertThatThrownBy(() -> checkout.start(eur, 999, "DE", 9407, "en"))
                    .isInstanceOf(ApiExceptions.ConflictException.class);
            verify(client, never()).createInvoice(any());
        }

        @Test
        @DisplayName("asking again for the same method returns the same invoice: no second charge is created")
        void retryReuses() {
            PayopCheckoutService.Started first = checkout.start(eur, 381, "DE", 9407, "en");
            PayopCheckoutService.Started again = checkout.start(eur, 381, "DE", 9407, "en");
            assertThat(again.invoiceId()).isEqualTo(first.invoiceId());
            verify(client, times(1)).createInvoice(any());
            assertThat(invoices.forOrder(7)).hasSize(1);
        }

        @Test
        @DisplayName("choosing another method replaces the attempt; still only one active, the old one still payable")
        void switchingMethod() {
            checkout.start(eur, 381, "DE", 9407, "en");
            PayopCheckoutService.Started second = checkout.start(eur, 700001, "DE", 9485, "en");

            assertThat(second.invoiceId()).isEqualTo(INVOICE_2);
            List<PayopInvoiceEntity> all = invoices.forOrder(7);
            assertThat(all).extracting(PayopInvoiceEntity::getStatus)
                    .containsExactly(PayopInvoiceEntity.Status.EXPIRED, PayopInvoiceEntity.Status.OPEN);
            assertThat(all.get(0).getReviewReason()).isEqualTo("REPLACED");
            assertThat(checkout.claimsBlockedUntil(7L)).contains(now.get().plusSeconds(24 * 3600));
        }

        @Test
        @DisplayName("Payop refusing to create it: the attempt is closed and the customer told to try again")
        void payopRefuses() {
            when(client.createInvoice(any())).thenThrow(new PayopClient.PayopException("create invoice",
                    PayopClient.ErrorCode.WRONG_SIGNATURE, 422, List.of()));
            assertThatThrownBy(() -> checkout.start(eur, 381, "DE", 9407, "en"))
                    .isInstanceOf(ApiExceptions.UpstreamException.class);
            PayopInvoiceEntity a = invoices.forOrder(7).get(0);
            assertThat(a.getStatus()).isEqualTo(PayopInvoiceEntity.Status.FAILED);
            assertThat(a.getReviewReason()).isEqualTo("CREATE_WRONG_SIGNATURE");
            assertThat(checkout.claimsBlockedUntil(7L)).as("no invoice exists, so nothing to wait for").isEmpty();
        }

        @Test
        @DisplayName("a creation still in flight blocks a second one")
        void inFlight() {
            PayopInvoiceEntity creating = new PayopInvoiceEntity(7L, java.util.UUID.randomUUID(), bank,
                    new FxRateService.RateUsed(BigDecimal.ONE, FxRateService.NONE, LocalDate.of(2026, 10, 4)),
                    Currency.EUR, new PayopFeeCalculator.FeeQuote(9000, 407, 9407), "94.07", "DE", now.get(),
                    now.get().plusSeconds(86400));
            invoices.repo.save(creating);
            assertThatThrownBy(() -> checkout.start(eur, 381, "DE", 9407, "en"))
                    .isInstanceOfSatisfying(ApiExceptions.ConflictException.class,
                            e -> assertThat(e.code()).isEqualTo("payment_starting"));
            assertThat(checkout.claimsBlockedUntil(7L)).isPresent();

            // Interrupted for more than two minutes: closed by the sweep, and a new start works.
            now.set(now.get().plusSeconds(180));
            assertThat(checkout.start(eur, 381, "DE", 9407, "en").invoiceId()).isEqualTo(INVOICE_1);
            assertThat(creating.getStatus()).isEqualTo(PayopInvoiceEntity.Status.FAILED);
            assertThat(creating.getReviewReason()).isEqualTo("CREATE_INTERRUPTED");
        }

        @Test
        @DisplayName("an unknown language falls back to English")
        void language() {
            assertThat(checkout.start(eur, 381, "DE", 9407, "xx").redirectUrl())
                    .startsWith("https://checkout.payop.com/en/");
        }
    }

    @Nested
    @DisplayName("going back to a method, and the limits on new invoices")
    class Resume {

        private static final String INVOICE_3 = "33333333-aaaa-4bbb-8ccc-000000000003";

        /** Attempts already made for an order at these times, all closed. */
        private void madeAt(long orderId, Instant... times) {
            for (Instant t : times) {
                invoices.open(orderId, java.util.UUID.randomUUID().toString(), 381, Currency.EUR, 9000, 407, "94.07", t)
                        .expired("EXPIRED", t.plusSeconds(1));
            }
        }

        private Instant hoursAgo(long h) {
            return now.get().minusSeconds(h * 3600);
        }

        @Test
        @DisplayName("back to the first method at the same price: that invoice again, not a third one")
        void reopensTheEarlierInvoice() {
            checkout.start(eur, 381, "DE", 9407, "en");
            checkout.start(eur, 700001, "DE", 9485, "en");

            PayopCheckoutService.Started back = checkout.start(eur, 381, "DE", 9407, "en");

            assertThat(back.invoiceId()).isEqualTo(INVOICE_1);
            verify(client, times(2)).createInvoice(any());
            List<PayopInvoiceEntity> all = invoices.forOrder(7);
            assertThat(all).extracting(PayopInvoiceEntity::getInvoiceId, PayopInvoiceEntity::getStatus).containsExactly(
                    org.assertj.core.groups.Tuple.tuple(INVOICE_1, PayopInvoiceEntity.Status.OPEN),
                    org.assertj.core.groups.Tuple.tuple(INVOICE_2, PayopInvoiceEntity.Status.EXPIRED));
            assertThat(all.get(1).getReviewReason()).isEqualTo(PayopInvoiceEntity.REPLACED);
            assertThat(all.get(0).getReviewReason()).isNull();
        }

        @Test
        @DisplayName("once the earlier invoice's 24 hours are up, going back makes a new attempt")
        void newAttemptWhenExpired() {
            when(client.createInvoice(any())).thenReturn(INVOICE_1, INVOICE_2, INVOICE_3);
            checkout.start(eur, 381, "DE", 9407, "en");
            checkout.start(eur, 700001, "DE", 9485, "en");
            now.set(now.get().plusSeconds(24 * 3600 + 1));

            assertThat(checkout.start(eur, 381, "DE", 9407, "en").invoiceId()).isEqualTo(INVOICE_3);
            verify(client, times(3)).createInvoice(any());
        }

        @Test
        @DisplayName("when the price has moved, going back makes a new attempt at the new price")
        void newAttemptWhenThePriceChanged() {
            when(client.createInvoice(any())).thenReturn(INVOICE_1, INVOICE_2, INVOICE_3);
            when(orders.findById(8L)).thenReturn(Optional.of(usd));
            checkout.start(usd, 381, "DE", totalFor(usd, 381), "en");
            checkout.start(usd, 700001, "DE", totalFor(usd, 700001), "en");
            when(fx.eurTo(Currency.USD)).thenReturn(Optional.of(
                    new FxRateService.RateUsed(new BigDecimal("1.3000"), "ECB", LocalDate.of(2026, 10, 3))));

            PayopCheckoutService.Started back = checkout.start(usd, 381, "DE", totalFor(usd, 381), "en");

            assertThat(back.invoiceId()).isEqualTo(INVOICE_3);
            assertThat(back.totalMinor()).isNotEqualTo(9411);
        }

        @Test
        @DisplayName("five new invoices per order in 24 hours: the sixth is refused with when the next can be opened")
        void perOrderLimit() {
            madeAt(7, hoursAgo(23), hoursAgo(20), hoursAgo(10), hoursAgo(5), hoursAgo(1));

            assertThatThrownBy(() -> checkout.start(eur, 381, "DE", 9407, "en"))
                    .isInstanceOfSatisfying(ApiExceptions.TooManyRequestsException.class, e -> {
                        assertThat(e.code()).isEqualTo("payment_attempts_order");
                        assertThat(e.retryAt()).isEqualTo(hoursAgo(23).plusSeconds(24 * 3600));
                    });
            verify(client, never()).createInvoice(any());
        }

        @Test
        @DisplayName("attempts older than the window do not count")
        void oldAttemptsDoNotCount() {
            madeAt(7, hoursAgo(30), hoursAgo(29), hoursAgo(28), hoursAgo(27), hoursAgo(26));
            assertThat(checkout.start(eur, 381, "DE", 9407, "en").invoiceId()).isEqualTo(INVOICE_1);
        }

        @Test
        @DisplayName("ten new invoices per account in an hour, across its orders: the next is refused")
        void perAccountLimit() {
            org.springframework.test.util.ReflectionTestUtils.setField(eur, "accountId", 42L);
            org.springframework.test.util.ReflectionTestUtils.setField(usd, "accountId", 42L);
            invoices.accountOf.put(7L, 42L);
            invoices.accountOf.put(8L, 42L);
            Instant first = now.get().minusSeconds(50 * 60);
            madeAt(7, first, now.get().minusSeconds(40 * 60), now.get().minusSeconds(30 * 60),
                    now.get().minusSeconds(20 * 60));
            madeAt(8, now.get().minusSeconds(45 * 60), now.get().minusSeconds(35 * 60),
                    now.get().minusSeconds(25 * 60), now.get().minusSeconds(15 * 60),
                    now.get().minusSeconds(10 * 60), now.get().minusSeconds(5 * 60));

            assertThatThrownBy(() -> checkout.start(eur, 381, "DE", 9407, "en"))
                    .isInstanceOfSatisfying(ApiExceptions.TooManyRequestsException.class, e -> {
                        assertThat(e.code()).isEqualTo("payment_attempts_account");
                        assertThat(e.retryAt()).isEqualTo(first.plusSeconds(3600));
                    });
        }

        @Test
        @DisplayName("handing back or reopening an invoice is never limited; only a new one is")
        void reuseIsNotLimited() {
            madeAt(7, hoursAgo(20), hoursAgo(10), hoursAgo(5), hoursAgo(1));
            PayopCheckoutService.Started fifth = checkout.start(eur, 381, "DE", 9407, "en");

            assertThat(checkout.start(eur, 381, "DE", 9407, "en").invoiceId()).isEqualTo(fifth.invoiceId());
            assertThatThrownBy(() -> checkout.start(eur, 700001, "DE", 9485, "en"))
                    .isInstanceOf(ApiExceptions.TooManyRequestsException.class);
            verify(client, times(1)).createInvoice(any());
        }
    }

    @Nested
    @DisplayName("the 24 hours")
    class Lifetime {

        @Test
        @DisplayName("manual claims wait while the invoice can be paid, and open again after 24 hours")
        void claimsWait() {
            checkout.start(eur, 381, "DE", 9407, "en");
            assertThat(checkout.claimsBlockedUntil(7L)).isPresent();
            now.set(now.get().plusSeconds(24 * 3600 - 1));
            assertThat(checkout.claimsBlockedUntil(7L)).isPresent();
            now.set(now.get().plusSeconds(1));
            assertThat(checkout.claimsBlockedUntil(7L)).isEmpty();
            checkout.expireStale();
            assertThat(invoices.forOrder(7).get(0).getStatus()).isEqualTo(PayopInvoiceEntity.Status.EXPIRED);
        }
    }

    @Nested
    @DisplayName("the return page")
    class Return {

        @Test
        @DisplayName("visiting it never marks anything paid: it only reads")
        void readOnly() {
            checkout.start(eur, 381, "DE", 9407, "en");
            PayopInvoiceEntity a = invoices.forOrder(7).get(0);
            org.mockito.Mockito.clearInvocations(invoices.repo, orders, client);

            PayopCheckoutService.ReturnStatus s = checkout.returnStatus("gfs-26-eur00001", INVOICE_1);

            assertThat(s.payment()).isEqualTo("PENDING");
            assertThat(s.order()).isEqualTo("AWAITING_PAYMENT");
            assertThat(a.getStatus()).isEqualTo(PayopInvoiceEntity.Status.OPEN);
            assertThat(eur.getStatus()).isEqualTo(OrderStatus.AWAITING_PAYMENT);
            verify(invoices.repo, never()).save(any());
            verify(invoices.repo, never()).saveAndFlush(any());
            verify(client, never()).transaction(anyString());
        }

        @Test
        @DisplayName("needs the order reference and the invoice to belong together")
        void mismatch() {
            checkout.start(eur, 381, "DE", 9407, "en");
            assertThatThrownBy(() -> checkout.returnStatus("GFS-26-USD00001", INVOICE_1))
                    .isInstanceOf(ApiExceptions.NotFoundException.class);
            assertThatThrownBy(() -> checkout.returnStatus("GFS-26-EUR00001", "nope"))
                    .isInstanceOf(ApiExceptions.NotFoundException.class);
            verify(orders, never()).findById(eq(99L));
        }
    }

    @Nested
    @DisplayName("a Payop payment carries the fee sheet's fee for its method, and never the 2.5% card fee")
    class SheetFeeOnly {

        private final OrderEntity gbp = PayopFakes.order(11, "GFS-26-GBP00001", Currency.GBP,
                OrderStatus.AWAITING_PAYMENT);
        private final OrderMapper reader = new OrderMapper(new ObjectMapper(), mock(AppProperties.class),
                mock(DiscordVerificationService.class), mock(DiscordBotClient.class), mock(CoachingService.class),
                mock(VendorOrderLedger.class), mock(OrderPaymentState.class), clock);

        @BeforeEach
        void gbp() {
            when(fx.eurTo(Currency.GBP)).thenReturn(Optional.of(
                    new FxRateService.RateUsed(new BigDecimal("0.8650"), "ECB", LocalDate.of(2026, 10, 2))));
            when(orders.findById(11L)).thenReturn(Optional.of(gbp));
        }

        private OrderEntity order(Currency currency) {
            return switch (currency) {
                case EUR -> eur;
                case USD -> usd;
                case GBP -> gbp;
                default -> throw new IllegalArgumentException(currency.name());
            };
        }

        /*
         * Each order: 100.00, a 10.00 coupon off, so 90.00 -- and 2.25 of card fee, 92.25, when
         * paid with UPI, PayPal or USDT. Bank transfer's sheet fee is 0.30 EUR + 4.0%, the fixed
         * part converted at the day's rate: (90.00 + 0.30 x rate) / 0.96, rounded up.
         */
        @ParameterizedTest(name = "{0}: total and invoice {2} = 90.00 + the sheet fee {1}; the 2.25 card fee nowhere")
        @CsvSource({"EUR, 407, 9407, 94.07", "USD, 411, 9411, 94.11", "GBP, 403, 9403, 94.03"})
        void inEveryCurrency(Currency currency, long fee, long total, String invoiceAmount) {
            OrderEntity order = order(currency);
            PayopCheckoutService.MethodOption bankTransfer = checkout.options(order, "DE").methods().get(0);
            assertThat(bankTransfer.feeMinor()).isEqualTo(fee);
            assertThat(bankTransfer.totalMinor()).isEqualTo(total).isEqualTo(9000 + fee);

            checkout.start(order, 381, "DE", total, "en");
            ArgumentCaptor<PayopClient.InvoiceRequest> sent = ArgumentCaptor.forClass(PayopClient.InvoiceRequest.class);
            verify(client).createInvoice(sent.capture());
            assertThat(sent.getValue().amount()).isEqualTo(invoiceAmount);

            // From the moment the invoice is open, the order reads as this payment: its lines, its total.
            List<OrderDtos.OrderLineDto> lines = reader.lines(order);
            assertThat(lines).extracting(OrderDtos.OrderLineDto::code)
                    .containsExactly("BASE", "COUPON_DISCOUNT", "PAYMENT_FEE");
            assertThat(lines.get(2).label()).isEqualTo("Payment processing fee (Bank transfer)");
            assertThat(lines.get(2).amountMinor()).isEqualTo(fee);
            assertThat(lines.stream().mapToLong(OrderDtos.OrderLineDto::amountMinor).sum()).isEqualTo(total);
            assertThat(reader.payableTotalMinor(order)).isEqualTo(total);
            // UPI, PayPal and USDT are still paid at the order's own total, 2.5% included.
            assertThat(order.getTotalMinor()).isEqualTo(9225);
        }

        @Test
        @DisplayName("a coaching order with a coupon: the invoice is the price after the coupon, plus the sheet fee")
        void coachingWithCoupon() {
            // €20.00 session, SAVE10 takes €2.00 off, and €0.45 of card fee for UPI, PayPal and USDT.
            OrderEntity coaching = new OrderEntity("GFS-26-COACH001", "q_12", "FC27",
                    com.globalfutservice.domain.catalog.Sku.COACHING, null, "SINGLE_SESSION", BigDecimal.ONE,
                    com.globalfutservice.domain.orders.DeliveryMethod.SCHEDULED_SESSION, Currency.EUR, 2000, 1845, """
                    {"quoteId":"q_12","currency":"EUR","couponCode":"SAVE10","lines":[
                      {"code":"BASE","label":"Single session","amountMinor":2000,"amountFormatted":"€20.00"},
                      {"code":"COUPON_DISCOUNT","label":"Coupon SAVE10 (10% off)","amountMinor":-200,"amountFormatted":"-€2.00"},
                      {"code":"GATEWAY_FEE","label":"Payment processing (2.5%)","amountMinor":45,"amountFormatted":"€0.45"}
                    ],"totalMinor":1845}
                    """);
            org.springframework.test.util.ReflectionTestUtils.setField(coaching, "id", 12L);
            org.springframework.test.util.ReflectionTestUtils.setField(coaching, "status", OrderStatus.AWAITING_PAYMENT);
            coaching.setGuestEmail("buyer@example.com");
            when(orders.findById(12L)).thenReturn(Optional.of(coaching));

            PayopCheckoutService.Options o = checkout.options(coaching, "DE");
            assertThat(o.netMinor()).as("after the coupon, without the 2.5%").isEqualTo(1800);
            // (18.00 + 0.30) / 0.96 = 19.0625 -> 19.07
            assertThat(o.methods().get(0).totalMinor()).isEqualTo(1907);

            checkout.start(coaching, 381, "DE", 1907, "en");
            ArgumentCaptor<PayopClient.InvoiceRequest> sent = ArgumentCaptor.forClass(PayopClient.InvoiceRequest.class);
            verify(client).createInvoice(sent.capture());
            assertThat(sent.getValue().amount()).isEqualTo("19.07");
            assertThat(reader.lines(coaching)).extracting(OrderDtos.OrderLineDto::code)
                    .containsExactly("BASE", "COUPON_DISCOUNT", "PAYMENT_FEE");
        }

        @Test
        @DisplayName("UPI, PayPal and USDT keep the 2.5%: an order with no Payop invoice reads as placed")
        void cardFeeStays() {
            assertThat(reader.lines(eur)).extracting(OrderDtos.OrderLineDto::code)
                    .containsExactly("BASE", "COUPON_DISCOUNT", "GATEWAY_FEE");
            assertThat(reader.payableTotalMinor(eur)).isEqualTo(9225);
        }

        @Test
        @DisplayName("another method, then back: the order reads as whichever invoice is open; once none can be paid, as placed")
        void swapsBothWays() {
            long wallet = totalFor(eur, 700001);
            checkout.start(eur, 700001, "DE", wallet, "en");
            assertThat(reader.payableTotalMinor(eur)).isEqualTo(9485);
            checkout.start(eur, 381, "DE", totalFor(eur, 381), "en");
            assertThat(reader.lines(eur).get(2).label()).isEqualTo("Payment processing fee (Bank transfer)");
            assertThat(reader.payableTotalMinor(eur)).isEqualTo(9407);
            checkout.start(eur, 700001, "DE", wallet, "en");
            assertThat(reader.lines(eur).get(2).label()).isEqualTo("Payment processing fee (Wallet)");
            assertThat(reader.payableTotalMinor(eur)).isEqualTo(9485);

            // A day on, nothing can be paid at Payop: the order is the one placed, card fee and all.
            now.set(now.get().plus(Duration.ofHours(25)));
            assertThat(reader.lines(eur)).extracting(OrderDtos.OrderLineDto::code).contains("GATEWAY_FEE");
            assertThat(reader.payableTotalMinor(eur)).isEqualTo(9225);
        }
    }
}
