package com.globalfutservice.fulfilment;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.globalfutservice.config.AppProperties;
import com.globalfutservice.domain.catalog.Platform;
import com.globalfutservice.fulfilment.FakeFutTransfer.Reply;
import com.globalfutservice.fulfilment.FutTransferClient.Accepted;
import com.globalfutservice.fulfilment.FutTransferClient.Reason;
import com.globalfutservice.fulfilment.FutTransferClient.Refused;
import com.globalfutservice.fulfilment.FutTransferClient.Uncertain;
import com.globalfutservice.fulfilment.FutTransferClient.Unrecognised;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Buying an order's coins from FUT Transfer's public seller pool, {@code /buyCoinsAPI}.
 *
 * <p>The same rules as {@code /orderAPI} -- one call, to the primary domain only, and a lost
 * answer is never permission to send again -- with the refusals the collection documents
 * for this endpoint: 402, 406, and 400 supplierNotFound / supplierOverpriced. Any other
 * 400 is not assumed to mean anything.
 */
class FutTransferBuyCoinsTest {

    private static final String REF = "GFS-26-POOL0001";
    private static final BigDecimal THRESHOLD = new BigDecimal("500");

    private FakeFutTransfer vendor;
    private FakeFutTransfer backup;
    private FutTransferClient client;

    @BeforeEach
    void setUp() throws Exception {
        vendor = new FakeFutTransfer();
        backup = new FakeFutTransfer();
        AppProperties props = VendorTestSupport.props(vendor.baseUrl(), backup.baseUrl(), Duration.ofMillis(600),
                AppProperties.FutTransferOrderMode.PUBLIC_POOL, VendorTestSupport.ORDER_AMOUNT_POOL);
        client = new FutTransferClient(props, new ObjectMapper(), VendorTestSupport.running(),
                VendorTestSupport.noCallLog()).withoutRetryPauses();
    }

    @AfterEach
    void tearDown() {
        vendor.close();
        backup.close();
    }

    private FutTransferClient.Placement buy() {
        return buy(null);
    }

    private FutTransferClient.Placement buy(BigDecimal maxPrice) {
        return client.buyCoins(REF, "Rahul", Platform.PLAYSTATION, 500, VendorTestSupport.signIn(), THRESHOLD, maxPrice);
    }

    @Test
    @DisplayName("sends the guide's defaults: targetedSnipe, top-up 300, auto-finish 1, the threshold, nothing else")
    void whatIsSent() {
        vendor.on("/buyCoinsAPI", Reply.ok(FakeFutTransfer.ORDER_ACCEPTED));

        assertThat(buy()).isEqualTo(new Accepted(FakeFutTransfer.VENDOR_ID));
        assertThat(vendor.calls("/orderAPI")).isZero();

        JsonNode sent = vendor.requests().get(0).body();
        Map<String, Object> expected = new java.util.LinkedHashMap<>();
        expected.put("transferMethod", "targetedSnipe");
        expected.put("topUpEnabled", 300);
        expected.put("autoFinishCycle", 1);
        expected.put("buyNowThreshold", 500);
        expected.put("minTransferAmount", 50);
        expected.put("pauseIfBelowMinTransfer", 0);
        expected.put("riskLevel", 1);
        expected.put("persona", "-1");
        expected.put("updateCustomer", "1");
        expected.put("stopOrderAfterOnboarding", 0);
        expected.put("lockOnboarding", "0");
        expected.put("disableCustomerLock", "0");
        expected.put("skipCustomerCheck", 0);
        expected.put("externalOrderID", REF);
        expected.put("amount", 500);
        expected.put("platform", "PS");
        expected.forEach((field, value) -> assertThat(new ObjectMapper().convertValue(sent.get(field), Object.class))
                .as(field).isEqualTo(value));

        Set<String> fields = new TreeSet<>();
        sent.fieldNames().forEachRemaining(fields::add);
        Set<String> allowed = new TreeSet<>(expected.keySet());
        allowed.addAll(List.of("customerName", "user", "pass", "ba", "ba2", "apiUser", "apiKey"));
        assertThat(fields).isEqualTo(allowed);
        // Said outright, since these are the ones that would change what is bought.
        assertThat(fields).doesNotContain("supplierID", "privateSupplier", "playerToBuy", "maxPrice", "senderGroup");
    }

    @Test
    @DisplayName("maxPrice goes only when one is given, as given")
    void maxPriceWhenGiven() {
        vendor.on("/buyCoinsAPI", Reply.ok(FakeFutTransfer.ORDER_ACCEPTED));

        buy(new BigDecimal("9.25"));

        assertThat(vendor.requests().get(0).body().get("maxPrice").decimalValue()).isEqualByComparingTo("9.25");
    }

    @Test
    @DisplayName("402 is a refusal: our balance does not cover it, and nothing was created")
    void insufficientFunds() {
        vendor.on("/buyCoinsAPI", Reply.of(402, "{\"error\":\"insufficientFunds\"}"));
        assertThat(buy()).isEqualTo(new Refused(Reason.INSUFFICIENT_FUNDS, 402, "insufficientFunds"));

        vendor.on("/buyCoinsAPI", Reply.of(402, ""));
        assertThat(buy()).isEqualTo(new Refused(Reason.INSUFFICIENT_FUNDS, 402, "HTTP_402"));
    }

    @Test
    @DisplayName("406 is a refusal: not enough stock")
    void noStock() {
        vendor.on("/buyCoinsAPI", Reply.of(406, "insufficientStock"));
        assertThat(buy()).isEqualTo(new Refused(Reason.NO_STOCK, 406, "insufficientStock"));
    }

    @Test
    @DisplayName("400 supplierOverpriced and supplierNotFound are refusals, as JSON or bare text")
    void documentedBadRequests() {
        vendor.on("/buyCoinsAPI", Reply.of(400, "{\"error\":\"supplierOverpriced\"}"));
        assertThat(buy()).isEqualTo(new Refused(Reason.SUPPLIER_REFUSED, 400, "supplierOverpriced"));

        vendor.on("/buyCoinsAPI", Reply.of(400, "supplierNotFound"));
        assertThat(buy()).isEqualTo(new Refused(Reason.SUPPLIER_REFUSED, 400, "supplierNotFound"));
    }

    @Test
    @DisplayName("any other 400 proves nothing -- even /orderAPI's InvalidPassword is not taken as a sign-in refusal here")
    void undocumentedBadRequests() {
        vendor.on("/buyCoinsAPI", Reply.of(400, "{\"error\":\"InvalidPassword\"}"));
        assertThat(buy()).isEqualTo(new Unrecognised(400, "InvalidPassword"));

        vendor.on("/buyCoinsAPI", Reply.of(400, "Invalid JSON"));
        assertThat(buy()).isEqualTo(new Unrecognised(400, "UNPARSEABLE_ERROR"));
    }

    @Test
    @DisplayName("403 and 429 mean what they mean for /orderAPI")
    void authAndRateLimit() {
        vendor.on("/buyCoinsAPI", Reply.of(429, "tooManyRequests"));
        assertThat(buy()).isEqualTo(new Refused(Reason.RATE_LIMITED, 429, "tooManyRequests"));

        vendor.on("/buyCoinsAPI", Reply.of(403, "{\"error\":\"Unauthorized\"}"));
        assertThat(buy()).isEqualTo(new Refused(Reason.AUTH_FAILED, 403, "Unauthorized"));
    }

    @Test
    @DisplayName("a timeout or a 5xx is uncertain, and neither ever reaches the backup domain")
    void neverFailsOver() {
        vendor.on("/buyCoinsAPI", Reply.slow(1_500));
        backup.on("/buyCoinsAPI", Reply.ok(FakeFutTransfer.ORDER_ACCEPTED));
        assertThat(buy()).isEqualTo(new Uncertain("TIMEOUT"));

        vendor.on("/buyCoinsAPI", Reply.of(503, "Service Unavailable"));
        assertThat(buy()).isEqualTo(new Uncertain("HTTP_503"));

        assertThat(vendor.calls("/buyCoinsAPI")).isEqualTo(2);
        assertThat(backup.requests()).isEmpty();
    }

    @Test
    @DisplayName("/orderAPI is unchanged: a 402 there is still unexplained, not a funds refusal")
    void ownSendersUnchanged() {
        vendor.on("/orderAPI", Reply.of(402, "insufficientFunds"));
        assertThat(client.submitOrder(REF, "Rahul", Platform.PLAYSTATION, 500, VendorTestSupport.signIn()))
                .isEqualTo(new Unrecognised(402, "insufficientFunds"));
        assertThat(vendor.calls("/buyCoinsAPI")).isZero();
    }

    @Test
    @DisplayName("no backup code: not sent at all")
    void noBackupCode() {
        var noCodes = new com.globalfutservice.credentials.web.CredentialDtos.RevealedCredentials(
                "customer@example.test", VendorTestSupport.PASSWORD, List.of(), null, null);
        assertThat(client.buyCoins(REF, "Rahul", Platform.PC, 500, noCodes, THRESHOLD, null))
                .isEqualTo(new Refused(Reason.SIGN_IN_REJECTED, 0, "NoBackupCode"));
        assertThat(vendor.requests()).isEmpty();
    }

    @Test
    @DisplayName("nothing secret reaches a log line, whatever the answer")
    void nothingSecretLogged() {
        try (var logs = new VendorTestSupport.LogCapture()) {
            vendor.on("/buyCoinsAPI", Reply.ok(FakeFutTransfer.ORDER_ACCEPTED));
            buy();
            vendor.on("/buyCoinsAPI", Reply.of(402, "insufficientFunds"));
            buy();
            vendor.on("/buyCoinsAPI", Reply.slow(1_500));
            buy();
            for (String secret : VendorTestSupport.secrets()) {
                assertThat(logs.all()).doesNotContain(secret);
            }
            assertThat(logs.all()).contains("/buyCoinsAPI for " + REF);
        }
    }

    // ----------------------------------------------------------------- balance ---

    /** The collection's "Get Buy Conditions" example, trimmed to what is read. */
    private static final String CONDITIONS = """
            {"transferFee":0,"suppliers":[],"privateSuppliers":[],"toolFee":0.25,"balance":5000}
            """;

    @Test
    @DisplayName("the balance is read as reported, and its value is never logged")
    void balance() {
        vendor.on("/buyConditionAPI", Reply.ok(CONDITIONS.replace("5000", "4321.75")));
        try (var logs = new VendorTestSupport.LogCapture()) {
            assertThat(client.balance(REF)).isEqualTo(new FutTransferClient.ReadOk<>(new BigDecimal("4321.75")));
            assertThat(logs.all()).doesNotContain("4321");
        }
        JsonNode sent = vendor.requests().get(0).body();
        Set<String> fields = new TreeSet<>();
        sent.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactlyInAnyOrder("apiUser", "apiKey");
    }

    @Test
    @DisplayName("an answer without a balance is a failed read, and a read may use the backup domain")
    void balanceFailures() {
        vendor.on("/buyConditionAPI", Reply.ok("{\"suppliers\":[]}"));
        assertThat(client.balance(null)).isEqualTo(
                new FutTransferClient.ReadFailed<>(FutTransferClient.ReadError.UNKNOWN, "UNPARSEABLE_RESPONSE"));

        vendor.on("/buyConditionAPI", Reply.of(503, "down"));
        backup.on("/buyConditionAPI", Reply.ok(CONDITIONS));
        assertThat(client.balance(null)).isEqualTo(new FutTransferClient.ReadOk<>(new BigDecimal("5000")));
        assertThat(backup.calls("/buyConditionAPI")).isEqualTo(1);
    }

    // ---------------------------------------------------------------- mothers ---

    @Test
    @DisplayName("a report that says it is a mother order is marked as one; the collection's own example is not")
    void motherFlag() {
        String vid = FakeFutTransfer.VENDOR_ID;
        vendor.on("/orderStatusBulkAPI", Reply.ok("{\"" + vid + "\":" + FakeFutTransfer.status(REF, 500) + "}"));
        var plain = (FutTransferClient.ReadOk<Map<String, FutTransferClient.SupplierStatus>>)
                client.statusByVendorIds(Map.of(vid, REF));
        assertThat(plain.value().get(vid).motherOrder()).isFalse();

        vendor.on("/orderStatusBulkAPI", Reply.ok("{\"" + vid + "\":"
                + FakeFutTransfer.status(REF, 500).replace("\"isMotherID\":0", "\"isMotherID\":1") + "}"));
        var mother = (FutTransferClient.ReadOk<Map<String, FutTransferClient.SupplierStatus>>)
                client.statusByVendorIds(Map.of(vid, REF));
        assertThat(mother.value().get(vid).motherOrder()).isTrue();
    }
}
