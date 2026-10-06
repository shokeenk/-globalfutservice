package com.globalfutservice.payments.payop;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.globalfutservice.config.AppProperties;
import com.globalfutservice.domain.money.Currency;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Payop's API as its documentation shows it, served locally. Nothing here reaches Payop.
 */
class PayopClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SECRET = "secret-key-never-logged";
    private static final String JWT = "jwt-token-never-logged";
    private static final String EMAIL = "payer@example.com";
    private static final String INVOICE = "ec2aa893-e7f5-4a0d-98c4-ef1a424eaf5d";
    private static final String TXID = "dca59ca5-be19-470d-9494-9b76944e0241";
    private static final String SIGNATURE = PayopSignature.of("104.48", "EUR", "GFS-26-AB12CD34", SECRET);

    /** A canned answer: status, body, extra headers. */
    record Reply(int status, String body, Map<String, String> headers) {
        static Reply of(int status, String body) {
            return new Reply(status, body, Map.of());
        }
    }

    /** What reached the fake: method, path, Authorization header, body. */
    record Seen(String method, String path, String authorization, String body) {
    }

    private HttpServer server;
    private final Map<String, Reply> replies = new ConcurrentHashMap<>();
    private final List<Seen> seen = new CopyOnWriteArrayList<>();
    private final PayopTokenWatch tokenWatch = mock(PayopTokenWatch.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            seen.add(new Seen(exchange.getRequestMethod(), path,
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            Reply reply = replies.getOrDefault(path, Reply.of(404, "{\"message\":\"Not found\"}"));
            reply.headers().forEach((k, v) -> exchange.getResponseHeaders().add(k, v));
            byte[] body = reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(reply.status(), body.length == 0 ? -1 : body.length);
            if (body.length > 0) {
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        server.start();
        logs.start();
        ((Logger) LoggerFactory.getLogger(PayopClient.class)).addAppender(logs);
    }

    @AfterEach
    void stop() {
        ((Logger) LoggerFactory.getLogger(PayopClient.class)).detachAppender(logs);
        server.stop(0);
        // Whatever each test did, no secret, token, payer email or signature was logged.
        assertThat(logs.list).extracting(ILoggingEvent::getFormattedMessage).allSatisfy(line -> assertThat(line)
                .doesNotContain(SECRET).doesNotContain(JWT).doesNotContain(EMAIL).doesNotContain(SIGNATURE));
    }

    private PayopClient client(String apiUrl) {
        AppProperties props = mock(AppProperties.class);
        when(props.payop()).thenReturn(new AppProperties.Payop(true, apiUrl, "https://checkout.payop.com",
                "application-pub-1", SECRET, JWT, "606", null, List.of(), List.of(), Duration.ofHours(1),
                Duration.ofHours(24), Duration.ofSeconds(3), 5, Duration.ofHours(24), 10, Duration.ofHours(1)));
        return new PayopClient(props, tokenWatch);
    }

    private PayopClient client() {
        return client("http://127.0.0.1:" + server.getAddress().getPort());
    }

    private static PayopClient.InvoiceRequest invoice() {
        return new PayopClient.InvoiceRequest("GFS-26-AB12CD34", "104.48", Currency.EUR, "Order GFS-26-AB12CD34",
                EMAIL, 381, "es", "https://globalfutservices.com/payop/return?invoice={{invoiceId}}",
                "https://globalfutservices.com/payop/return?invoice={{invoiceId}}&failed=1",
                UUID.fromString("1b4e28ba-2fa1-11d2-883f-0016d3cca427"));
    }

    /* ------------------------------------------------------------------ invoices --- */

    @Test
    @DisplayName("creates an invoice: the documented body, signed on exactly the amount sent, ID from the header")
    void createsInvoice() throws Exception {
        replies.put("/v1/invoices/create", new Reply(200, "{\"data\":\"" + INVOICE + "\",\"status\":1}",
                Map.of("identifier", INVOICE)));

        assertThat(client().createInvoice(invoice())).isEqualTo(INVOICE);

        Seen request = seen.get(0);
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.authorization()).as("creating needs no token").isNull();
        JsonNode body = MAPPER.readTree(request.body());
        assertThat(body.path("publicKey").asText()).isEqualTo("application-pub-1");
        assertThat(body.at("/order/id").asText()).isEqualTo("GFS-26-AB12CD34");
        assertThat(body.at("/order/amount").isTextual()).isTrue();
        assertThat(body.at("/order/amount").asText()).isEqualTo("104.48");
        assertThat(body.at("/order/currency").asText()).isEqualTo("EUR");
        assertThat(body.at("/order/items").isArray()).isTrue();
        assertThat(body.at("/order/items")).isEmpty();
        assertThat(body.at("/payer/email").asText()).isEqualTo(EMAIL);
        assertThat(body.at("/payer").size()).as("the email is the only payer detail sent").isEqualTo(1);
        assertThat(body.path("paymentMethod").asText()).isEqualTo("381");
        assertThat(body.path("language").asText()).isEqualTo("es");
        assertThat(body.path("resultUrl").asText()).endsWith("invoice={{invoiceId}}");
        assertThat(body.path("failPath").asText()).endsWith("&failed=1");
        assertThat(body.at("/metadata/attemptId").asText()).isEqualTo("1b4e28ba-2fa1-11d2-883f-0016d3cca427");
        assertThat(body.path("signature").asText()).isEqualTo(SIGNATURE);
        assertThat(request.body()).doesNotContain(SECRET).doesNotContain(JWT);
    }

    @Test
    @DisplayName("without the header, falls back to the ID in the body")
    void idFromBody() {
        replies.put("/v1/invoices/create", Reply.of(200, "{\"data\":\"" + INVOICE + "\",\"status\":1}"));
        assertThat(client().createInvoice(invoice())).isEqualTo(INVOICE);
    }

    @Test
    @DisplayName("an ID that is not one of Payop's is refused, so it never reaches a redirect")
    void oddIdRefused() {
        replies.put("/v1/invoices/create", new Reply(200, "{}", Map.of("identifier", "../../evil?x=1")));
        assertThatThrownBy(() -> client().createInvoice(invoice()))
                .isInstanceOfSatisfying(PayopClient.PayopException.class,
                        e -> assertThat(e.code()).isEqualTo(PayopClient.ErrorCode.BAD_RESPONSE));
    }

    @Test
    @DisplayName("'Wrong signature' is typed, final, and nothing of the body is kept")
    void wrongSignature() {
        replies.put("/v1/invoices/create", Reply.of(422, "{\"message\":\"Wrong signature\"}"));
        assertThatThrownBy(() -> client().createInvoice(invoice()))
                .isInstanceOfSatisfying(PayopClient.PayopException.class, e -> {
                    assertThat(e.code()).isEqualTo(PayopClient.ErrorCode.WRONG_SIGNATURE);
                    assertThat(e.isTransient()).isFalse();
                });
    }

    @Test
    @DisplayName("a validation error keeps the field names Payop objected to, never the values")
    void validationFields() {
        replies.put("/v1/invoices/create", Reply.of(422,
                "{\"message\":{\"payer.email\":[\"" + EMAIL + " is not a valid email.\"],"
                        + "\"paymentMethod\":[\"This value should be of type int.\"]}}"));
        assertThatThrownBy(() -> client().createInvoice(invoice()))
                .isInstanceOfSatisfying(PayopClient.PayopException.class, e -> {
                    assertThat(e.code()).isEqualTo(PayopClient.ErrorCode.VALIDATION);
                    assertThat(e.fields()).containsExactly("payer.email", "paymentMethod");
                    assertThat(e.getMessage()).doesNotContain(EMAIL).doesNotContain("type int");
                });
    }

    @Test
    @DisplayName("a disabled method is typed as such")
    void methodNotEnabled() {
        replies.put("/v1/invoices/create", Reply.of(422, "{\"message\":\"Method must be enabled to use it\"}"));
        assertThatThrownBy(() -> client().createInvoice(invoice()))
                .isInstanceOfSatisfying(PayopClient.PayopException.class,
                        e -> assertThat(e.code()).isEqualTo(PayopClient.ErrorCode.METHOD_NOT_ENABLED));
    }

    /* -------------------------------------------------------------- transactions --- */

    @Test
    @DisplayName("reads a transaction with the token; the docs' example has no product amount, so none is invented")
    void docsTransactionExample() {
        replies.put("/v2/transactions/" + TXID, Reply.of(200, """
                {
                 "data": {
                   "identifier": "%s",
                   "amount": 100,
                   "currency": "USD",
                   "state": 5,
                   "error": "error message",
                   "createdAt": 1567402240,
                   "orderId": "134666",
                   "resultUrl": "https://your.site/result"
                 }
                }
                """.formatted(TXID)));

        PayopClient.Transaction t = client().transaction(TXID);

        assertThat(seen.get(0).authorization()).isEqualTo("Bearer " + JWT);
        assertThat(t.id()).isEqualTo(TXID);
        assertThat(t.state()).isEqualTo(5);
        assertThat(t.error()).isEqualTo("error message");
        assertThat(t.orderId()).isEqualTo("134666");
        assertThat(t.productAmount()).as("'amount' is not the invoice amount and is not used for it").isNull();
        assertThat(t.productCurrency()).isNull();
        assertThat(t.metadataAttemptId()).isNull();
    }

    @Test
    @DisplayName("reads the invoice's amount and currency, as a number or a string, exactly")
    void productAmount() {
        replies.put("/v2/transactions/" + TXID, Reply.of(200, """
                {"data": {"identifier": "%s", "state": 2, "orderId": "GFS-26-AB12CD34",
                          "amount": 112.31, "currency": "USD",
                          "productAmount": 104.48, "productCurrency": "EUR",
                          "metadata": {"attemptId": "1b4e28ba-2fa1-11d2-883f-0016d3cca427"},
                          "error": {"message": "", "code": ""}}}
                """.formatted(TXID)));
        PayopClient.Transaction t = client().transaction(TXID);
        assertThat(t.state()).isEqualTo(2);
        assertThat(t.productAmount()).isEqualByComparingTo("104.48");
        assertThat(t.productAmount().toPlainString()).isEqualTo("104.48");
        assertThat(t.productCurrency()).isEqualTo("EUR");
        assertThat(t.metadataAttemptId()).isEqualTo("1b4e28ba-2fa1-11d2-883f-0016d3cca427");
        assertThat(t.error()).isNull();

        replies.put("/v2/transactions/" + TXID, Reply.of(200,
                "{\"data\":{\"state\":2,\"productAmount\":\"10.10\",\"productCurrency\":\"GBP\"}}"));
        assertThat(client().transaction(TXID).productAmount()).isEqualTo(new BigDecimal("10.10"));
    }

    @Test
    @DisplayName("a transaction ID that is not Payop's never reaches a URL")
    void oddTxidRefused() {
        assertThatThrownBy(() -> client().transaction("../v1/invoices/x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client().transaction(null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(seen).isEmpty();
    }

    @Test
    @DisplayName("a 401 tells the token watch, which alerts staff")
    void unauthorized() {
        replies.put("/v2/transactions/" + TXID,
                Reply.of(401, "{\"message\":\"Full authentication is required to access this resource.\"}"));
        assertThatThrownBy(() -> client().transaction(TXID))
                .isInstanceOfSatisfying(PayopClient.PayopException.class,
                        e -> assertThat(e.code()).isEqualTo(PayopClient.ErrorCode.UNAUTHORIZED));
        verify(tokenWatch).refused("get transaction");
    }

    @Test
    @DisplayName("Payop failing, or unreachable, is transient: worth asking again")
    void transientFailures() {
        replies.put("/v2/transactions/" + TXID,
                Reply.of(500, "{\"message\":\"Something went wrong, try again or contact support.\"}"));
        assertThatThrownBy(() -> client().transaction(TXID))
                .isInstanceOfSatisfying(PayopClient.PayopException.class, e -> {
                    assertThat(e.code()).isEqualTo(PayopClient.ErrorCode.SERVER_ERROR);
                    assertThat(e.isTransient()).isTrue();
                });
        int port = server.getAddress().getPort();
        server.stop(0);
        assertThatThrownBy(() -> client("http://127.0.0.1:" + port).transaction(TXID))
                .isInstanceOfSatisfying(PayopClient.PayopException.class, e -> {
                    assertThat(e.code()).isEqualTo(PayopClient.ErrorCode.UNAVAILABLE);
                    assertThat(e.isTransient()).isTrue();
                });
        verify(tokenWatch, never()).refused(anyString());
    }

    @Test
    @DisplayName("an answer without a state is not a transaction")
    void noState() {
        replies.put("/v2/transactions/" + TXID, Reply.of(200, "{\"data\":{\"identifier\":\"x\"}}"));
        assertThatThrownBy(() -> client().transaction(TXID))
                .isInstanceOfSatisfying(PayopClient.PayopException.class,
                        e -> assertThat(e.code()).isEqualTo(PayopClient.ErrorCode.BAD_RESPONSE));
    }

    /* ------------------------------------------------------------------- methods --- */

    @Test
    @DisplayName("lists the project's methods from the docs' example, with the token")
    void methods() {
        replies.put("/v1/instrument-settings/payment-methods/available-for-application/606", Reply.of(200, """
                {
                   "data": [
                       {
                           "identifier": 336,
                           "type": "cards_local",
                           "title": "Argencard",
                           "logo": "https://payop.com/assets/images/payment_methods/argencard.jpg",
                           "currencies": ["USD"],
                           "countries": ["AR"],
                           "config": {"fields": [{"name": "email", "type": "email", "required": true}]}
                       },
                       {"title": "no identifier: skipped"}
                   ],
                   "status": 1
                }
                """));
        assertThat(client().availableMethods()).containsExactly(
                new PayopClient.AvailableMethod(336, "Argencard", "cards_local", List.of("USD"), List.of("AR")));
        assertThat(seen.get(0).authorization()).isEqualTo("Bearer " + JWT);
    }

    @Test
    @DisplayName("'Authorization token invalid' on the methods list is a 401 like any other")
    void methodsUnauthorized() {
        replies.put("/v1/instrument-settings/payment-methods/available-for-application/606",
                Reply.of(401, "{\"message\":\"Authorization token invalid\"}"));
        assertThatThrownBy(() -> client().availableMethods()).isInstanceOf(PayopClient.PayopException.class);
        verify(tokenWatch).refused("list methods");
    }
}
