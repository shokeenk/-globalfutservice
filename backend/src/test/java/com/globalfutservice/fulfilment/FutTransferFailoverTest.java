package com.globalfutservice.fulfilment;

import java.time.Duration;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.globalfutservice.domain.catalog.Platform;
import com.globalfutservice.fulfilment.FakeFutTransfer.Reply;
import com.globalfutservice.fulfilment.FutTransferClient.ReadError;
import com.globalfutservice.fulfilment.FutTransferClient.ReadFailed;
import com.globalfutservice.fulfilment.FutTransferClient.ReadOk;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The vendor's two domains: reads may move to the backup when the primary fails; placing an
 * order never does.
 *
 * <p>Both domains are local stand-ins here, so each can be made slow or broken on its own
 * and every request to either is counted.
 */
class FutTransferFailoverTest {

    private static final String REF = "GFS-26-FAILOVER";

    private FakeFutTransfer primary;
    private FakeFutTransfer backup;
    private FutTransferClient client;

    @BeforeEach
    void setUp() throws Exception {
        primary = new FakeFutTransfer();
        backup = new FakeFutTransfer();
        client = new FutTransferClient(VendorTestSupport.props(primary.baseUrl(), backup.baseUrl(), Duration.ofMillis(400)),
                new ObjectMapper(), VendorTestSupport.running()).withoutRetryPauses();
    }

    @AfterEach
    void tearDown() {
        primary.close();
        backup.close();
    }

    private FutTransferClient.Placement place() {
        return client.submitOrder(REF, "Rahul", Platform.PC, 500, VendorTestSupport.signIn());
    }

    @Test
    @DisplayName("a primary timeout on /orderAPI makes zero calls to the backup's /orderAPI")
    void placementNeverFailsOver() {
        primary.on("/orderAPI", Reply.slow(1_500));
        backup.on("/orderAPI", Reply.ok(FakeFutTransfer.ORDER_ACCEPTED));

        assertThat(place()).isEqualTo(new FutTransferClient.Uncertain("TIMEOUT"));

        assertThat(primary.calls("/orderAPI")).isEqualTo(1);
        assertThat(backup.calls("/orderAPI")).isZero();
    }

    @Test
    @DisplayName("nor after a 5xx or a refused connection: the order may exist, so it is only ever looked up")
    void placementNeverFailsOverOnErrors() {
        primary.on("/orderAPI", Reply.of(503, "Service Unavailable"));
        backup.on("/orderAPI", Reply.ok(FakeFutTransfer.ORDER_ACCEPTED));
        assertThat(place()).isEqualTo(new FutTransferClient.Uncertain("HTTP_503"));

        primary.close();
        assertThat(place()).isEqualTo(new FutTransferClient.Uncertain("CONNECTION_ERROR"));
        assertThat(backup.calls("/orderAPI")).isZero();
    }

    @Test
    @DisplayName("the lookup after an uncertain placement may use the backup, and finds the order there")
    void lookupFailsOver() {
        primary.on("/orderAPI", Reply.slow(1_500));
        primary.on("/orderStatusAPI", Reply.of(503, "Service Unavailable"));
        backup.on("/orderStatusAPI", Reply.ok(FakeFutTransfer.status(REF, 500)));

        place();
        FutTransferClient.Lookup found = client.lookupByReference(REF, 500);

        assertThat(found).isInstanceOf(FutTransferClient.Found.class);
        assertThat(primary.calls("/orderStatusAPI")).isEqualTo(1);
        assertThat(backup.calls("/orderStatusAPI")).isEqualTo(1);
        assertThat(backup.calls("/orderAPI")).isZero();
    }

    @Test
    @DisplayName("a status read moves to the backup on a timeout")
    void bulkReadFailsOver() {
        primary.on("/orderStatusBulkAPI", Reply.slow(1_500));
        backup.on("/orderStatusBulkAPI", Reply.ok("{\"" + REF + "\":{\"status\":\"finished\",\"accountCheck\":\"finished\","
                + "\"amountOrdered\":500,\"amount\":500}}"));

        FutTransferClient.Read<List<FutTransferClient.SupplierStatus>> read = client.statusBulk(List.of(REF));

        assertThat(read).isInstanceOf(ReadOk.class);
        assertThat(((ReadOk<List<FutTransferClient.SupplierStatus>>) read).value()).hasSize(1);
    }

    @Test
    @DisplayName("three tries -- primary, backup, primary -- and then it says TRANSIENT")
    void bothDown() {
        primary.on("/orderStatusBulkAPI", Reply.of(502, "Bad Gateway"));
        backup.on("/orderStatusBulkAPI", Reply.of(502, "Bad Gateway"));

        assertThat(client.statusBulk(List.of(REF))).isEqualTo(new ReadFailed<>(ReadError.TRANSIENT, "HTTP_502"));
        assertThat(primary.calls("/orderStatusBulkAPI")).isEqualTo(2);
        assertThat(backup.calls("/orderStatusBulkAPI")).isEqualTo(1);
    }

    @Test
    @DisplayName("a 403, a 429, an unexplained 4xx or an unreadable answer is not retried, and is typed")
    void notRetried() {
        primary.on("/orderStatusBulkAPI", Reply.of(403, "{\"error\":\"Unauthorized\"}"));
        assertThat(client.statusBulk(List.of(REF))).isEqualTo(new ReadFailed<>(ReadError.AUTH, "HTTP_403"));

        primary.on("/orderStatusBulkAPI", Reply.of(429, "Too many requests"));
        assertThat(client.statusBulk(List.of(REF))).isEqualTo(new ReadFailed<>(ReadError.RATE_LIMITED, "HTTP_429"));

        primary.on("/orderStatusBulkAPI", Reply.of(404, "notFound"));
        assertThat(client.statusBulk(List.of(REF))).isEqualTo(new ReadFailed<>(ReadError.NEEDS_REVIEW, "notFound"));

        primary.on("/orderStatusBulkAPI", Reply.ok("<html>maintenance</html>"));
        assertThat(client.statusBulk(List.of(REF))).isEqualTo(new ReadFailed<>(ReadError.UNKNOWN, "UNPARSEABLE_RESPONSE"));

        assertThat(primary.calls("/orderStatusBulkAPI")).isEqualTo(4);
        assertThat(backup.requests()).isEmpty();
    }
}
