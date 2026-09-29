package com.globalfutservice.fulfilment;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

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
 * {@code RazorpayGateway} is: this uses a handful of the supplier's endpoints, and every
 * transitive dependency on a path that carries live EA credentials is supply-chain surface
 * that has to be justified.
 *
 * <p><b>Nothing on this class ever logs a body.</b> The request to {@code /orderAPI}
 * contains a customer's EA password and backup codes in clear -- that is the supplier's
 * contract, not a choice -- and its status response can carry the backup codes back. Log
 * lines carry the endpoint, our reference, the HTTP status or failure, and the time taken.
 * Responses are read into named fields and nothing else.
 *
 * <p><b>Authentication is a static MD5.</b> The supplier wants {@code apiUser} and an MD5
 * of the API key in the JSON body of every request. There is no nonce, timestamp or
 * expiry, so the digest is a bearer secret that replays forever and is treated exactly
 * like a password. The configured value is the key as the supplier issued it; the digest
 * is computed per request.
 *
 * <p><b>Placing an order never throws and is never retried here.</b> {@link #submitOrder}
 * says what happened as one of four outcomes, and the one that matters most is
 * {@link Uncertain}: a timeout or an unreadable answer means the order may exist, and the
 * only safe next step is {@link #lookupByReference}, never a second placement.
 */
@Component
public class FutTransferClient {

    private static final Logger log = LoggerFactory.getLogger(FutTransferClient.class);

    /** The supplier caps a bulk status query at twenty ids. */
    static final int BULK_LIMIT = 20;

    /** The documented codes that mean the customer's sign-in itself was refused. */
    static final Set<String> SIGN_IN_CODES = Set.of(
            "invalidpassword", "invalidba1", "invalidba2", "invalidba3", "invalidba4", "invalidba5");

    /** An error code we are prepared to store and show: a bare identifier, never free text. */
    private static final Pattern CODE = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,63}");

    /** The vendor's order ids are UUIDs; anything else is not an id we will store. */
    private static final Pattern ORDER_ID = Pattern.compile("[A-Za-z0-9-]{1,64}");

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

    /** What placing an order came to. */
    public sealed interface Placement permits Accepted, Refused, Unrecognised, Uncertain {
    }

    /** The vendor created the order and told us its id. */
    public record Accepted(String vendorOrderId) implements Placement {
    }

    /**
     * The vendor definitely refused before creating anything -- a documented refusal -- or
     * we refused to send. {@code httpStatus} is 0 when nothing was sent.
     */
    public record Refused(Reason reason, int httpStatus, String code) implements Placement {
    }

    public enum Reason {
        /** The customer's sign-in or backup code was rejected. */
        SIGN_IN_REJECTED,
        /** Something else about the order was rejected, e.g. InvalidAmount. */
        ORDER_REJECTED,
        /** 403: our API credentials failed, or order creation is blocked. */
        AUTH_FAILED,
        /** 429: the same EA account was submitted too recently. */
        RATE_LIMITED
    }

    /** The vendor answered, but not with anything its documentation explains. */
    public record Unrecognised(int httpStatus, String code) implements Placement {
    }

    /** We cannot tell whether the order was created. Look it up; never send again. */
    public record Uncertain(String code) implements Placement {
    }

    /**
     * Hands one order, and the customer's sign-in, to the supplier.
     *
     * <p>{@code externalOrderID} is our own {@code publicRef}, always. It is what lets
     * {@link #lookupByReference} find the order when the answer to this call is lost.
     *
     * @param amountThousands coins in K -- the supplier's unit, <em>not</em> millions
     */
    public Placement submitOrder(String publicRef,
                                 String customerName,
                                 Platform platform,
                                 long amountThousands,
                                 CredentialDtos.RevealedCredentials creds) {

        List<String> codes = creds.backupCodes() == null ? List.of() : creds.backupCodes();
        if (codes.isEmpty()) {
            // Not sent: the supplier requires one, and we know it would be refused.
            log.warn("FUT Transfer /orderAPI for {} not sent: no backup code on file", publicRef);
            return new Refused(Reason.SIGN_IN_REJECTED, 0, "NoBackupCode");
        }

        Map<String, Object> body = auth();
        body.put("externalOrderID", publicRef);
        body.put("customerName", customerName);
        body.put("user", creds.eaEmail());
        body.put("pass", creds.eaPassword());
        body.put("platform", platformCode(platform));
        body.put("amount", amountThousands);
        // `ba` is required and `ba2`..`ba5` are not; positional, as the supplier documents.
        for (int i = 0; i < Math.min(codes.size(), 5); i++) {
            body.put(i == 0 ? "ba" : "ba" + (i + 1), codes.get(i));
        }

        // GFS Transfer Method 3.0: the same settings on every order, from configuration.
        AppProperties.FutTransfer cfg = props.futTransfer();
        AppProperties.FutTransferOrder method = cfg.order();
        body.put("persona", method.persona());
        body.put("updateCustomer", method.updateCustomer());
        body.put("stopOrderAfterOnboarding", method.stopOrderAfterOnboarding());
        body.put("lockOnboarding", method.lockOnboarding());
        body.put("disableCustomerLock", method.disableCustomerLock());
        body.put("skipCustomerCheck", method.skipCustomerCheck());
        body.put("transferMethod", cfg.transferMethod());
        body.put("senderGroup", method.senderGroup());
        body.put("topUpEnabled", method.topUpEnabled());
        body.put("autoFinishCycle", method.autoFinishCycle());
        body.put("minTransferAmount", method.minTransferAmount());
        body.put("pauseIfBelowMinTransfer", method.pauseIfBelowMinTransfer());
        body.put("riskLevel", cfg.riskLevel());

        Exchange ex = exchange("/orderAPI", body, publicRef);
        Placement outcome = classifyPlacement(ex);
        log.info("FUT Transfer /orderAPI for {}: {}", publicRef, describe(outcome));
        return outcome;
    }

    private Placement classifyPlacement(Exchange ex) {
        if (ex.failure() != null) {
            return new Uncertain(ex.failure());
        }
        int status = ex.status();
        if (status / 100 == 2) {
            JsonNode json = ex.json(mapper);
            if (json == null || !json.isObject()) {
                return new Uncertain("UNPARSEABLE_RESPONSE");
            }
            String id = text(json, "orderID");
            if (id == null || id.isBlank()) {
                return new Uncertain("NO_ORDER_ID");
            }
            if (!ORDER_ID.matcher(id).matches()) {
                return new Uncertain("MALFORMED_ORDER_ID");
            }
            return new Accepted(id);
        }
        if (status >= 500) {
            // "Database operation failed" may still have written the order. Look it up.
            return new Uncertain("HTTP_" + status);
        }
        String code = ex.errorCode(mapper);
        if (status == 400) {
            String permanent = permanentCode(code);
            if (permanent != null) {
                Reason reason = SIGN_IN_CODES.contains(permanent.toLowerCase(Locale.ROOT))
                        ? Reason.SIGN_IN_REJECTED : Reason.ORDER_REJECTED;
                return new Refused(reason, 400, permanent);
            }
            return new Unrecognised(400, code == null ? "UNPARSEABLE_ERROR" : code);
        }
        if (status == 403) {
            return new Refused(Reason.AUTH_FAILED, 403, code == null ? "HTTP_403" : code);
        }
        if (status == 429) {
            return new Refused(Reason.RATE_LIMITED, 429, code == null ? "HTTP_429" : code);
        }
        return new Unrecognised(status, code == null ? "HTTP_" + status : code);
    }

    /** The configured spelling of a documented permanent code, matched without case. */
    private String permanentCode(String code) {
        if (code == null) return null;
        for (String known : props.futTransfer().permanentErrorCodes()) {
            if (known != null && known.trim().equalsIgnoreCase(code)) return known.trim();
        }
        return null;
    }

    // ------------------------------------------------------------------ lookup ---

    /** Whether the vendor has an order under our reference. */
    public sealed interface Lookup permits Found, NotConfirmed {
    }

    /**
     * The vendor has an order under our reference, for the amount we asked for. The status
     * response does not document the vendor's own order id, so it is not part of this.
     */
    public record Found(String status,
                        String accountCheck,
                        String economyState,
                        Long amountOrderedK,
                        Long amountDeliveredK,
                        Long coinsUsed,
                        BigDecimal toPay,
                        boolean aborted) implements Lookup {
    }

    /**
     * Not proven to exist. This is <em>not</em> "does not exist": the vendor has not
     * documented what its lookup returns for an unknown reference, so no answer here is
     * treated as proof that nothing was created.
     */
    public record NotConfirmed(String code) implements Lookup {
    }

    /**
     * Asks whether an order exists under our reference, via {@code /orderStatusAPI} with
     * {@code externalID: 1}.
     *
     * <p>Found only when the answer is a 200 whose {@code externalOrderID} is our reference
     * exactly, whose {@code amountOrdered} is the amount we asked for, and which has a
     * status. Anything short of that is {@link NotConfirmed}.
     */
    public Lookup lookupByReference(String publicRef, long expectedThousands) {
        Map<String, Object> body = auth();
        body.put("orderID", publicRef);
        body.put("externalID", 1);
        body.put("isMotherID", 0);

        Exchange ex = exchange("/orderStatusAPI", body, publicRef);
        Lookup outcome = classifyLookup(ex, publicRef, expectedThousands);
        log.info("FUT Transfer lookup for {}: {}", publicRef,
                outcome instanceof NotConfirmed n ? "not confirmed (" + n.code() + ")" : "found");
        return outcome;
    }

    private Lookup classifyLookup(Exchange ex, String publicRef, long expectedThousands) {
        if (ex.failure() != null) return new NotConfirmed(ex.failure());
        if (ex.status() != 200) return new NotConfirmed("HTTP_" + ex.status());
        JsonNode json = ex.json(mapper);
        if (json == null || !json.isObject()) return new NotConfirmed("UNPARSEABLE_RESPONSE");
        if (!publicRef.equals(text(json, "externalOrderID"))) return new NotConfirmed("REFERENCE_NOT_ECHOED");
        Long ordered = asLong(json, "amountOrdered");
        if (ordered == null || ordered != expectedThousands) return new NotConfirmed("AMOUNT_MISMATCH");
        String status = text(json, "status");
        if (status == null || status.isBlank()) return new NotConfirmed("NO_STATUS");
        return new Found(status, text(json, "accountCheck"), text(json, "economyState"), ordered,
                asLong(json, "amount"), asLong(json, "coinsUsed"), asDecimal(json, "toPay"),
                json.path("wasAborted").asInt(0) == 1);
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
     * <p>{@code externalID} makes the supplier interpret the ids as our
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

    /**
     * One request and its answer, or the name of what went wrong. Never throws, and never
     * lets a body reach a log line or an exception message.
     */
    private Exchange exchange(String path, Map<String, Object> body, String context) {
        AppProperties.FutTransfer cfg = props.futTransfer();
        if (!cfg.isConfigured()) {
            return Exchange.failed("NOT_CONFIGURED");
        }
        long started = System.nanoTime();
        Exchange result;
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
            result = new Exchange(res.statusCode(), res.body(), null);
        } catch (HttpTimeoutException e) {
            result = Exchange.failed("TIMEOUT");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            result = Exchange.failed("INTERRUPTED");
        } catch (IOException e) {
            // Connection refused, reset or dropped. Its message is not logged: some
            // serialisation failures quote the value that failed, and here that is a password.
            result = Exchange.failed("CONNECTION_ERROR");
        } catch (RuntimeException e) {
            result = Exchange.failed("CLIENT_ERROR");
        }
        long ms = (System.nanoTime() - started) / 1_000_000;
        log.info("FUT Transfer {} for {}: {} in {} ms", path, context,
                result.failure() != null ? result.failure() : "HTTP " + result.status(), ms);
        return result;
    }

    /** The poller's calls, which still treat anything but a 2xx JSON answer as a failure. */
    private JsonNode post(String path, Map<String, Object> body, String context) {
        Exchange ex = exchange(path, body, context);
        if (ex.failure() != null) {
            throw new FutTransferException("Could not reach the supplier at " + path + " (" + ex.failure() + ")");
        }
        if (ex.status() / 100 != 2) {
            throw new FutTransferException(
                    "Supplier returned HTTP " + ex.status() + " from " + path + " for " + context);
        }
        JsonNode json = ex.json(mapper);
        if (json == null) {
            throw new FutTransferException("Supplier returned an unreadable answer from " + path);
        }
        return json;
    }

    /** A status and a body held only long enough to read named fields from it. */
    private record Exchange(int status, String body, String failure) {

        static Exchange failed(String failure) {
            return new Exchange(0, null, failure);
        }

        JsonNode json(ObjectMapper mapper) {
            if (body == null || body.isBlank()) return null;
            try {
                return mapper.readTree(body);
            } catch (Exception e) {
                return null;
            }
        }

        /**
         * The error's code, if it has one we can name: the vendor answers some errors as
         * JSON ({@code {"error": "InvalidPassword"}}) and some as bare text
         * ({@code lowAmount}). Free text is never returned, so it never reaches storage.
         */
        String errorCode(ObjectMapper mapper) {
            if (body == null || body.isBlank()) return null;
            JsonNode json = json(mapper);
            if (json != null && json.isObject()) {
                for (String field : List.of("error", "code", "message", "msg", "outcome")) {
                    JsonNode v = json.get(field);
                    if (v != null && v.isTextual() && CODE.matcher(v.asText().trim()).matches()) {
                        return v.asText().trim();
                    }
                }
                return null;
            }
            String text = body.trim();
            return CODE.matcher(text).matches() ? text : null;
        }
    }

    private static String describe(Placement p) {
        return switch (p) {
            case Accepted a -> "accepted as " + a.vendorOrderId();
            case Refused r -> "refused " + r.reason() + " (" + r.code() + ")";
            case Unrecognised u -> "unrecognised HTTP " + u.httpStatus() + " (" + u.code() + ")";
            case Uncertain u -> "uncertain (" + u.code() + ")";
        };
    }

    static String platformCode(Platform platform) {
        return switch (platform) {
            case PC -> "PC";
            case PLAYSTATION -> "PS";
            case XBOX -> "XB";
        };
    }

    static String md5(String raw) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            return HexFormat.of().formatHex(md.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("MD5 is unavailable on this JVM");
        }
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() || v.isContainerNode() ? null : v.asText();
    }

    private static Long asLong(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() || !v.isIntegralNumber() ? null : v.asLong();
    }

    private static BigDecimal asDecimal(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() || !v.isNumber() ? null : v.decimalValue();
    }

    /** Never carries a request body, for the reason given on the class. */
    public static class FutTransferException extends RuntimeException {
        public FutTransferException(String message) {
            super(message);
        }
    }
}
