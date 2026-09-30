package com.globalfutservice.fulfilment;

import java.time.Duration;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.globalfutservice.fulfilment.FakeFutTransfer.Reply;
import com.globalfutservice.fulfilment.FutTransferClient.Change;
import com.globalfutservice.fulfilment.FutTransferClient.ChangeUncertain;
import com.globalfutservice.fulfilment.FutTransferClient.Changed;
import com.globalfutservice.fulfilment.FutTransferClient.NotChanged;
import com.globalfutservice.fulfilment.FutTransferClient.Refusal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Changing an order the vendor already has: a corrected sign-in, resume, stop, mark
 * finished. Addressed by the vendor's own id, sent once to the primary domain, and a lost
 * answer is never reported as done.
 */
class FutTransferWriteTest {

    private static final String REF = "GFS-26-WRITE001";
    private static final String VID = FakeFutTransfer.VENDOR_ID;

    private FakeFutTransfer vendor;
    private FakeFutTransfer backup;
    private VendorControl control;
    private VendorCallLog calls;
    private FutTransferClient client;

    @BeforeEach
    void setUp() throws Exception {
        vendor = new FakeFutTransfer();
        backup = new FakeFutTransfer();
        control = VendorTestSupport.running();
        calls = mock(VendorCallLog.class);
        client = new FutTransferClient(VendorTestSupport.props(vendor.baseUrl(), backup.baseUrl(), Duration.ofMillis(800)),
                new ObjectMapper(), control, calls).withoutRetryPauses();
    }

    @AfterEach
    void tearDown() {
        vendor.close();
        backup.close();
    }

    private Change correct() {
        return client.correctSignIn(VID, REF, VendorTestSupport.signIn());
    }

    @Test
    @DisplayName("a corrected sign-in goes to the vendor's order by its id, restarts it, and carries nothing else")
    void correctedSignInBody() {
        vendor.on("/correctCredentialsAPI", Reply.ok("{\"updatedPassword\":true,\"updatedBA\":true,\"wasContinued\":true}"));

        assertThat(correct()).isEqualTo(new Changed(FutTransferClient.CONTINUED));

        assertThat(vendor.requests()).hasSize(1);
        JsonNode body = vendor.requests().get(0).body();
        List<String> fields = new java.util.ArrayList<>();
        body.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactly("apiUser", "apiKey", "orderID", "user", "pass", "ba", "ba2", "continue");
        assertThat(body.path("orderID").asText()).isEqualTo(VID);
        assertThat(body.path("continue").asInt()).isEqualTo(1);
        assertThat(body.path("pass").asText()).isEqualTo(VendorTestSupport.PASSWORD);
        assertThat(body.path("ba").asText()).isEqualTo("11112222");
        assertThat(body.path("ba2").asText()).isEqualTo("33334444");
        assertThat(body.has("externalOrderID")).isFalse();
    }

    @Test
    @DisplayName("saved but not restarted is reported as such")
    void savedNotContinued() {
        vendor.on("/correctCredentialsAPI", Reply.ok("{\"updatedPassword\":true,\"wasContinued\":false}"));

        assertThat(correct()).isEqualTo(new Changed(FutTransferClient.SAVED));
    }

    @Test
    @DisplayName("no answer, a 5xx or an answer we cannot read may have happened: uncertain, sent once, never to the backup")
    void uncertain() {
        vendor.on("/correctCredentialsAPI", Reply.of(502, "Bad gateway"));
        assertThat(correct()).isEqualTo(new ChangeUncertain("HTTP_502"));

        vendor.on("/correctCredentialsAPI", new Reply(200, "{}", 2_000));
        assertThat(correct()).isEqualTo(new ChangeUncertain("TIMEOUT"));

        vendor.on("/correctCredentialsAPI", Reply.ok("not json"));
        assertThat(correct()).isEqualTo(new ChangeUncertain("UNPARSEABLE_RESPONSE"));

        vendor.on("/resumeOrderAPI", Reply.ok("{\"outcome\":\"stopped\"}"));
        assertThat(client.resume(VID, REF)).isEqualTo(new ChangeUncertain("UNEXPECTED_ANSWER"));

        assertThat(vendor.calls("/correctCredentialsAPI")).isEqualTo(3);
        assertThat(backup.requests()).isEmpty();
    }

    @Test
    @DisplayName("a refusal says what kind: our credentials, wait, or no; a 403 pauses every call")
    void refusals() {
        vendor.on("/correctCredentialsAPI", Reply.of(403, "{\"error\":\"Unauthorized\"}"));
        assertThat(correct()).isEqualTo(new NotChanged(Refusal.AUTH, "HTTP_403"));
        verify(control).pause("HTTP_403 /correctCredentialsAPI", REF);

        vendor.on("/resumeOrderAPI", Reply.of(429, "consoleLoginCooldown"));
        assertThat(client.resume(VID, REF)).isEqualTo(new NotChanged(Refusal.RATE_LIMITED, "consoleLoginCooldown"));

        vendor.on("/resumeOrderAPI", Reply.of(405, "{\"error\":\"TempBan\"}"));
        assertThat(client.resume(VID, REF)).isEqualTo(new NotChanged(Refusal.REFUSED, "TempBan"));

        vendor.on("/markFinishedAPI", Reply.of(404, ""));
        assertThat(client.markFinished(VID, REF)).isEqualTo(new NotChanged(Refusal.REFUSED, "HTTP_404"));
        assertThat(backup.requests()).isEmpty();
    }

    @Test
    @DisplayName("resume, stop and mark finished send the documented body and read the documented answer")
    void resumeStopMarkFinished() {
        vendor.on("/resumeOrderAPI", body -> Reply.ok("{\"outcome\":\"" + ("stop".equals(body.path("mode").asText())
                ? "stopped" : "resumed") + "\"}"));
        vendor.on("/markFinishedAPI", Reply.ok("{\"outcome\":\"marked\"}"));

        assertThat(client.resume(VID, REF)).isEqualTo(new Changed("resumed"));
        assertThat(client.stop(VID, REF)).isEqualTo(new Changed("stopped"));
        assertThat(client.markFinished(VID, REF)).isEqualTo(new Changed("marked"));

        List<FakeFutTransfer.Request> sent = vendor.requests();
        assertThat(sent).extracting(r -> r.body().path("mode").asText(null)).containsExactly("resume", "stop", null);
        assertThat(sent).allSatisfy(r -> assertThat(r.body().path("orderID").asText()).isEqualTo(VID));
        assertThat(sent.get(2).body().path("isMotherID").asInt(-1)).isEqualTo(0);
    }

    @Test
    @DisplayName("without the vendor's id nothing is sent: these never address an order by our reference")
    void noVendorId() {
        assertThat(client.correctSignIn(null, REF, VendorTestSupport.signIn()))
                .isEqualTo(new NotChanged(Refusal.REFUSED, "NO_VENDOR_ORDER_ID"));
        assertThat(client.stop("", REF)).isEqualTo(new NotChanged(Refusal.REFUSED, "NO_VENDOR_ORDER_ID"));
        assertThat(vendor.requests()).isEmpty();
    }

    @Test
    @DisplayName("every write is in the audit trail, as a write, with what came of it")
    void audited() {
        vendor.on("/correctCredentialsAPI", Reply.ok("{\"wasContinued\":true}"));
        correct();
        vendor.on("/correctCredentialsAPI", Reply.of(400, "{\"error\":\"InvalidBA1\"}"));
        correct();
        vendor.on("/correctCredentialsAPI", Reply.of(500, "x"));
        correct();

        verify(calls).record(eq("/correctCredentialsAPI"), eq(false), eq(200), eq("OK"), isNull(), eq(List.of(REF)),
                isNull(), anyLong());
        verify(calls).record(eq("/correctCredentialsAPI"), eq(false), eq(400), eq("REFUSED"), eq("InvalidBA1"),
                eq(List.of(REF)), isNull(), anyLong());
        verify(calls).record(eq("/correctCredentialsAPI"), eq(false), eq(500), eq("UNCERTAIN"), eq("HTTP_500"),
                eq(List.of(REF)), isNull(), anyLong());
        verify(calls, org.mockito.Mockito.never()).record(any(), eq(true), any(), any(), any(), any(), any(), anyLong());
    }

    @Test
    @DisplayName("nothing secret reaches a log line, whatever the vendor answers")
    void noSecretsLogged() {
        try (VendorTestSupport.LogCapture logs = new VendorTestSupport.LogCapture()) {
            vendor.on("/correctCredentialsAPI", Reply.ok("{\"wasContinued\":true,\"ba\":\"99887766\"}"));
            correct();
            vendor.on("/correctCredentialsAPI", Reply.of(400, "{\"error\":\"bad pass " + VendorTestSupport.PASSWORD + "\"}"));
            correct();
            vendor.on("/correctCredentialsAPI", Reply.of(500, "55443322"));
            correct();

            assertThat(logs.all()).contains("/correctCredentialsAPI");
            for (String secret : VendorTestSupport.secrets()) {
                assertThat(logs.all()).as("secret in logs").doesNotContain(secret);
            }
        }
    }
}
