package com.globalfutservice.fulfilment;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.globalfutservice.config.AppProperties;
import com.globalfutservice.credentials.web.CredentialDtos;
import com.globalfutservice.domain.catalog.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The FUT Transfer supplier API.
 *
 * <p>Thin and hand-written on the JDK client, for the same reason
 * {@code RazorpayGateway} is: this uses three of the supplier's forty-six endpoints, and
 * every transitive dependency on a path that carries live EA credentials is supply-chain
 * surface that has to be justified.
 *
 * <p><b>Nothing on this class ever logs a body.</b> The request to
 * {@code /orderAPI} contains a customer's EA password and backup codes in clear — that is
 * the supplier's contract, not a choice — so the usual habit of logging a payload on
 * failure would write live credentials into the application log, where they would outlive
 * the vault that exists to destroy them. Log lines here carry the order reference and the
 * HTTP status, and nothing else.
 *
 * <p><b>Authentication is a static MD5.</b> The supplier wants {@code apiUser} and an MD5
 * of the API key in the JSON body of every request. There is no nonce, no timestamp and no
 * expiry, so the digest is a bearer secret that replays forever and being hashed buys
 * nothing — it is treated exactly like a password. MD5 is computed here rather than stored
 * so the configured value is the key as the supplier issued it.
 */
@Component
public class FutTransferClient {

    private static final Logger log = LoggerFactory.getLogger(FutTransferClient.class);

    /** The supplier caps a bulk status query at twenty ids. */
    static final int BULK_LIMIT = 20;

    private final AppProperties props;
    private final ObjectMapper mapper;
    private final HttpClient http;

    public FutTransferClient(AppProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public boolean isEnabled() {
        return props.futTransfer().isConfigured();
    }

    // ------------------------------------------------------------------ submit ---

    /** What the supplier gives back when an order is accepted. */
    public record Accepted(String supplierOrderId) {
    }

    /**
     * Hands one order, and the customer's sign-in, to the supplier.
     *
     * <p>{@code externalOrderID} is our own {@code publicRef}. That single field is what
     * makes every later call answerable without a foreign key: status can be queried by
     * our reference with {@code externalID: 1}, so a lost supplier id is an inconvenience
     * rather than an orphaned order.
     *
     * @param amountThousands coins in K — the supplier's unit, <em>not</em> millions
     */
    public Accepted submitOrder(String publicRef,
                                String customerName,
                                Platform platform,
                                long amountThousands,
                                CredentialDtos.RevealedCredentials creds) {

        Map<String, Object> body = auth();
        body.put("externalOrderID", publicRef);
        body.put("customerName", customerName);
        body.put("user", creds.eaEmail());
        body.put("pass", creds.eaPassword());
        body.put("platform", platformCode(platform));
        body.put("amount", amountThousands);
        body.put("persona", "-1");

        /*
         * `ba` is required and `ba2`..`ba5` are not. Sent positionally rather than as a
         * list because that is the shape the supplier documents; anything past the fifth
         * code is dropped rather than silently renumbered.
         */
        List<String> codes = creds.backupCodes() == null ? List.of() : creds.backupCodes();
        if (codes.isEmpty()) {
            throw new FutTransferException(
                    "The supplier requires at least one EA backup code for order " + publicRef);
        }
        for (int i = 0; i < Math.min(codes.size(), 5); i++) {
            body.put(i == 0 ? "ba" : "ba" + (i + 1), codes.get(i));
        }

        AppProperties.FutTransfer cfg = props.futTransfer();
        body.put("transferMethod", cfg.transferMethod());
        body.put("riskLevel", cfg.riskLevel());
        body.put("updateCustomer", "1");

        JsonNode res = post("/orderAPI", body, publicRef);

        String id = firstText(res, "orderID", "orderId", "id");
        if (id == null || id.isBlank()) {
            // Their error text is safe to surface: it describes the order, not the sign-in.
            throw new FutTransferException(
                    "Supplier accepted no order id for " + publicRef + ": " + shortError(res));
        }
        log.info("Supplier accepted order {} as {}", publicRef, id);
        return new Accepted(id);
    }

    // ------------------------------------------------------------------ status ---

    /** One order's supplier-side state, as three separate vocabularies plus progress. */
    public record SupplierStatus(String orderRef,
                                 String status,
                                 String accountCheck,
                                 String economyState,
                                 Long amountOrdered,
                                 Long amountDelivered,
                                 boolean aborted) {
    }

    /**
     * Reads up to twenty orders in one call, keyed by <em>our</em> reference.
     *
     * <p>{@code isExternal} makes the supplier interpret the ids as our
     * {@code externalOrderID}s, which is why the returned map is keyed by {@code publicRef}
     * and the caller never has to hold their ids to poll.
     */
    public List<SupplierStatus> statusBulk(List<String> publicRefs) {
        if (publicRefs.isEmpty()) return List.of();
        if (publicRefs.size() > BULK_LIMIT) {
            throw new IllegalArgumentException("The supplier caps a bulk query at " + BULK_LIMIT);
        }

        Map<String, Object> body = auth();
        body.put("orderIDs", publicRefs);
        body.put("externalID", 1);
        body.put("isMotherID", 0);

        JsonNode res = post("/orderStatusBulkAPI", body, String.join(",", publicRefs));

        return publicRefs.stream()
                .map(ref -> {
                    JsonNode n = res.get(ref);
                    return n == null || n.isNull() ? null : parseStatus(ref, n);
                })
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    /**
     * Replaces a rejected sign-in and resumes the order.
     *
     * <p>{@code continue: 1} is the difference between a corrected password and a
     * corrected password that actually restarts the work.
     */
    public void correctCredentials(String publicRef, CredentialDtos.RevealedCredentials creds) {
        Map<String, Object> body = auth();
        body.put("orderID", publicRef);
        body.put("externalOrderID", 1);
        body.put("user", creds.eaEmail());
        body.put("pass", creds.eaPassword());
        List<String> codes = creds.backupCodes() == null ? List.of() : creds.backupCodes();
        for (int i = 0; i < Math.min(codes.size(), 5); i++) {
            body.put(i == 0 ? "ba" : "ba" + (i + 1), codes.get(i));
        }
        body.put("continue", 1);

        post("/correctCredentialsAPI", body, publicRef);
        log.info("Supplier credentials replaced and order {} resumed", publicRef);
    }

    // ------------------------------------------------------------------ plumbing ---

    private SupplierStatus parseStatus(String ref, JsonNode n) {
        return new SupplierStatus(
                ref,
                text(n, "status"),
                text(n, "accountCheck"),
                text(n, "economyState"),
                asLong(n, "amountOrdered"),
                asLong(n, "amount"),
                n.path("wasAborted").asInt(0) == 1);
    }

    /** The two fields every request carries. A fresh map each time; never cached. */
    private Map<String, Object> auth() {
        AppProperties.FutTransfer cfg = props.futTransfer();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("apiUser", cfg.apiUser());
        body.put("apiKey", md5(cfg.apiKey()));
        return body;
    }

    private JsonNode post(String path, Map<String, Object> body, String context) {
        AppProperties.FutTransfer cfg = props.futTransfer();
        if (!cfg.isConfigured()) {
            throw new FutTransferException("The supplier integration is not configured.");
        }
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(cfg.baseUrl() + path))
                    .timeout(cfg.timeout())
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            mapper.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() / 100 != 2) {
                /*
                 * Status and path only. The request body that produced this may hold an EA
                 * password, and a 4xx handler that echoes "the request we sent" is the
                 * commonest way credentials reach a log file.
                 */
                throw new FutTransferException(
                        "Supplier returned HTTP " + res.statusCode() + " from " + path
                        + " for " + context);
            }
            return mapper.readTree(res.body());

        } catch (FutTransferException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FutTransferException("Interrupted calling " + path);
        } catch (Exception e) {
            // No cause chained on purpose: a serialisation failure's message can quote the
            // value that failed, and the value here is a password.
            throw new FutTransferException("Could not reach the supplier at " + path);
        }
    }

    /** Coins in millions, as this application stores them, to the supplier's K. */
    public static long toThousands(BigDecimal quantityMillions) {
        return quantityMillions.multiply(BigDecimal.valueOf(1000))
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact();
    }

    static String platformCode(Platform platform) {
        return switch (platform) {
            case PC -> "PC";
            case PLAYSTATION -> "PS";
            case XBOX -> "XB";
        };
    }

    private static String md5(String raw) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            return HexFormat.of().formatHex(md.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("MD5 is unavailable on this JVM", e);
        }
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static Long asLong(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() || !v.isNumber() ? null : v.asLong();
    }

    private static String firstText(JsonNode n, String... fields) {
        for (String f : fields) {
            String v = text(n, f);
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    private static String shortError(JsonNode n) {
        String msg = firstText(n, "error", "message", "msg");
        return msg == null ? "no id in response" : msg.substring(0, Math.min(msg.length(), 160));
    }

    /** Never carries a request body, for the reason given on the class. */
    public static class FutTransferException extends RuntimeException {
        public FutTransferException(String message) {
            super(message);
        }
    }
}
