package com.globalfutservice.fulfilment;

import java.time.Duration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.globalfutservice.domain.catalog.Platform;
import com.globalfutservice.fulfilment.FakeFutTransfer.Reply;
import com.globalfutservice.fulfilment.FutTransferClient.Accepted;
import com.globalfutservice.fulfilment.FutTransferClient.Found;
import com.globalfutservice.fulfilment.FutTransferClient.NotConfirmed;
import com.globalfutservice.fulfilment.FutTransferClient.Reason;
import com.globalfutservice.fulfilment.FutTransferClient.Refused;
import com.globalfutservice.fulfilment.FutTransferClient.Uncertain;
import com.globalfutservice.fulfilment.FutTransferClient.Unrecognised;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.globalfutservice.fulfilment.VendorTestSupport.RAW_KEY;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every answer {@code /orderAPI} can give, sorted into what it proves.
 *
 * <p>The distinction that matters: a refusal the vendor documents proves nothing was
 * created, while a timeout, a 5xx or an unreadable answer proves nothing at all -- the
 * order may exist -- and must never be treated as permission to send again.
 */
class FutTransferPlacementTest {

    private static final String REF = "GFS-26-PLACE001";

    private FakeFutTransfer vendor;
    private FutTransferClient client;

    @BeforeEach
    void setUp() throws Exception {
        vendor = new FakeFutTransfer();
        client = new FutTransferClient(VendorTestSupport.props(vendor.baseUrl(), Duration.ofMillis(800)),
                new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        vendor.close();
    }

    private FutTransferClient.Placement place() {
        return client.submitOrder(REF, "Rahul", Platform.PLAYSTATION, 500, VendorTestSupport.signIn());
    }

    @Test
    @DisplayName("sends our reference as externalOrderID, the amount in K, and the key only as its MD5")
    void whatIsSent() {
        vendor.on("/orderAPI", Reply.ok(FakeFutTransfer.ORDER_ACCEPTED));

        assertThat(place()).isEqualTo(new Accepted(FakeFutTransfer.VENDOR_ID));

        JsonNode sent = vendor.requests().get(0).body();
        assertThat(sent.get("externalOrderID").asText()).isEqualTo(REF);
        assertThat(sent.get("amount").asLong()).isEqualTo(500);
        assertThat(sent.get("platform").asText()).isEqualTo("PS");
        assertThat(sent.get("ba").asText()).isEqualTo("11112222");
        assertThat(sent.get("ba2").asText()).isEqualTo("33334444");
        assertThat(sent.get("apiKey").asText()).isEqualTo(FutTransferClient.md5(RAW_KEY)).isNotEqualTo(RAW_KEY);
    }

    @Test
    @DisplayName("a timeout is uncertain: the order may exist")
    void timeout() {
        vendor.on("/orderAPI", Reply.slow(2_000));
        assertThat(place()).isEqualTo(new Uncertain("TIMEOUT"));
    }

    @Test
    @DisplayName("a 2xx without an order id, or that cannot be read, is uncertain")
    void unreadableSuccess() {
        vendor.on("/orderAPI", Reply.ok("<html>502 Bad Gateway</html>"));
        assertThat(place()).isEqualTo(new Uncertain("UNPARSEABLE_RESPONSE"));

        vendor.on("/orderAPI", Reply.ok("{}"));
        assertThat(place()).isEqualTo(new Uncertain("NO_ORDER_ID"));

        vendor.on("/orderAPI", Reply.ok("{\"orderID\":\"not an id; drop table\"}"));
        assertThat(place()).isEqualTo(new Uncertain("MALFORMED_ORDER_ID"));
    }

    @Test
    @DisplayName("a 5xx is uncertain, because a failed database write may still have written")
    void serverError() {
        vendor.on("/orderAPI", Reply.of(500, "Database operation failed"));
        assertThat(place()).isEqualTo(new Uncertain("HTTP_500"));
    }

    @Test
    @DisplayName("a connection that is refused outright is still uncertain, not a refusal")
    void connectionRefused() {
        int port = Integer.parseInt(vendor.baseUrl().substring(vendor.baseUrl().lastIndexOf(':') + 1));
        vendor.close();
        FutTransferClient offline = new FutTransferClient(
                VendorTestSupport.props("http://127.0.0.1:" + port, Duration.ofMillis(800)), new ObjectMapper());
        assertThat(offline.submitOrder(REF, "Rahul", Platform.PC, 500, VendorTestSupport.signIn()))
                .isEqualTo(new Uncertain("CONNECTION_ERROR"));
    }

    @Test
    @DisplayName("a documented sign-in code, as JSON or plain text, is a definite refusal of the sign-in")
    void signInRefused() {
        vendor.on("/orderAPI", Reply.of(400, "{\"error\":\"InvalidPassword\"}"));
        assertThat(place()).isEqualTo(new Refused(Reason.SIGN_IN_REJECTED, 400, "InvalidPassword"));

        vendor.on("/orderAPI", Reply.of(400, "invalidba1"));
        assertThat(place()).isEqualTo(new Refused(Reason.SIGN_IN_REJECTED, 400, "InvalidBA1"));
    }

    @Test
    @DisplayName("other documented codes are a refusal of the order, not of the sign-in")
    void orderRefused() {
        vendor.on("/orderAPI", Reply.of(400, "InvalidAmount"));
        assertThat(place()).isEqualTo(new Refused(Reason.ORDER_REJECTED, 400, "InvalidAmount"));
    }

    @Test
    @DisplayName("an undocumented 400, or free text, goes to an admin and is never stored as text")
    void unknownRefusal() {
        vendor.on("/orderAPI", Reply.of(400, "SomethingNew"));
        assertThat(place()).isEqualTo(new Unrecognised(400, "SomethingNew"));

        vendor.on("/orderAPI", Reply.of(400, "{\"error\":\"Required field(s) are empty (user, pass)\"}"));
        assertThat(place()).isEqualTo(new Unrecognised(400, "UNPARSEABLE_ERROR"));
    }

    @Test
    @DisplayName("403 and 429 are definite refusals; 402, 406 and anything else go to an admin")
    void otherStatuses() {
        vendor.on("/orderAPI", Reply.of(403, "{\"error\":\"Unauthorized\"}"));
        assertThat(place()).isEqualTo(new Refused(Reason.AUTH_FAILED, 403, "Unauthorized"));

        vendor.on("/orderAPI", Reply.of(429, "Too many requests. Please try again later."));
        assertThat(place()).isEqualTo(new Refused(Reason.RATE_LIMITED, 429, "HTTP_429"));

        vendor.on("/orderAPI", Reply.of(402, "insufficientBalance"));
        assertThat(place()).isEqualTo(new Unrecognised(402, "insufficientBalance"));

        vendor.on("/orderAPI", Reply.of(406, "insufficientStock"));
        assertThat(place()).isEqualTo(new Unrecognised(406, "insufficientStock"));
    }

    @Test
    @DisplayName("no backup code: not sent at all")
    void noBackupCode() {
        var noCodes = new com.globalfutservice.credentials.web.CredentialDtos.RevealedCredentials(
                "customer@example.test", VendorTestSupport.PASSWORD, java.util.List.of(), null, null);
        assertThat(client.submitOrder(REF, "Rahul", Platform.PC, 500, noCodes))
                .isEqualTo(new Refused(Reason.SIGN_IN_REJECTED, 0, "NoBackupCode"));
        assertThat(vendor.calls("/orderAPI")).isZero();
    }

    // ------------------------------------------------------------------ lookup ---

    @Test
    @DisplayName("the lookup asks by our reference with externalID 1, and finds a matching order")
    void lookupFound() {
        vendor.on("/orderStatusAPI", Reply.ok(FakeFutTransfer.status(REF, 500)));

        FutTransferClient.Lookup found = client.lookupByReference(REF, 500);

        assertThat(found).isInstanceOf(Found.class);
        assertThat(((Found) found).amountOrderedK()).isEqualTo(500);
        JsonNode sent = vendor.requests().get(0).body();
        assertThat(sent.get("orderID").asText()).isEqualTo(REF);
        assertThat(sent.get("externalID").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("anything short of an exact match is not confirmation")
    void lookupNotConfirmed() {
        vendor.on("/orderStatusAPI", Reply.ok(FakeFutTransfer.status("GFS-26-SOMEONEELSE", 500)));
        assertThat(client.lookupByReference(REF, 500)).isEqualTo(new NotConfirmed("REFERENCE_NOT_ECHOED"));

        vendor.on("/orderStatusAPI", Reply.ok(FakeFutTransfer.status(REF, 300)));
        assertThat(client.lookupByReference(REF, 500)).isEqualTo(new NotConfirmed("AMOUNT_MISMATCH"));

        vendor.on("/orderStatusAPI", Reply.of(404, "notFound"));
        assertThat(client.lookupByReference(REF, 500)).isEqualTo(new NotConfirmed("HTTP_404"));

        vendor.on("/orderStatusAPI", Reply.ok("{\"error\":\"Order not found\"}"));
        assertThat(client.lookupByReference(REF, 500)).isEqualTo(new NotConfirmed("REFERENCE_NOT_ECHOED"));

        vendor.on("/orderStatusAPI", Reply.ok("garbage"));
        assertThat(client.lookupByReference(REF, 500)).isEqualTo(new NotConfirmed("UNPARSEABLE_RESPONSE"));
    }

    @Test
    @DisplayName("no key, password or backup code -- ours or the ones the vendor sends back -- is ever logged")
    void secretsNeverLogged() {
        try (VendorTestSupport.LogCapture logs = new VendorTestSupport.LogCapture()) {
            vendor.on("/orderAPI", Reply.of(400, "{\"error\":\"InvalidPassword\"}"));
            place();
            vendor.on("/orderAPI", Reply.of(500, "Database operation failed"));
            place();
            vendor.on("/orderAPI", Reply.ok(FakeFutTransfer.ORDER_ACCEPTED));
            place();
            vendor.on("/orderStatusAPI", Reply.ok(FakeFutTransfer.status(REF, 500)));
            client.lookupByReference(REF, 500);

            assertThat(logs.all()).contains(REF);
            for (String secret : VendorTestSupport.secrets()) {
                assertThat(logs.all()).as("a secret in the log").doesNotContain(secret);
            }
        }
    }
}
