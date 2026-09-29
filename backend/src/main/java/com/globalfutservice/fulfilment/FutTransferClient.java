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

    /** The failure code for a call that was not made because calls are paused. */
    static final String PAUSED = "VENDOR_PAUSED";

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
    private final VendorControl control;
    private final VendorCallLog calls;
    private final HttpClient http;

    public FutTransferClient(AppProperties props, ObjectMapper mapper, VendorControl control, VendorCallLog calls) {
        this.props = props;
        this.mapper = mapper;
        this.control = control;
        this.calls = calls;
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

        // The primary domain only, and once. Never the backup, never again: after a timeout
        // the order may exist, and sending it anywhere else could create a second one.
        Exchange ex = exchange(Kind.PLACEMENT, props.futTransfer().baseUrl(), "/orderAPI", body, List.of(publicRef));
        Placement outcome = classifyPlacement(ex);
        log.info("FUT Transfer /orderAPI for {}: {}", publicRef, describe(outcome));
        return outcome;
    }

    private Placement classifyPlacement(Exchange ex) {
        if (PAUSED.equals(ex.failure())) {
            // Stopped before anything was sent: a definite "not created".
            return new Refused(Reason.AUTH_FAILED, 0, PAUSED);
        }
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

        Exchange ex = readExchange("/orderStatusAPI", body, List.of(publicRef));
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

    // ---------------------------------------------------------------- cooldown ---

    /** Whether an EA account can receive another transfer yet. */
    public record Cooldown(boolean ready, long remainingSeconds) {
    }

    /**
     * Asks the vendor whether this EA account is in its transfer cooldown, via
     * {@code /getCooldownStatus} with {@code account}.
     *
     * <p>Only an answer that says {@code success: true} with an {@code isReady} flag counts.
     * The account's email goes to the vendor in the request, as it will in the order; it is
     * never the log context, which is our reference.
     */
    public Read<Cooldown> cooldown(String eaAccountEmail, String publicRef) {
        Map<String, Object> body = auth();
        body.put("account", eaAccountEmail);
        Exchange ex = readExchange("/getCooldownStatus", body, List.of(publicRef));
        Read<Cooldown> read = classifyRead(ex, json -> {
            if (!json.path("success").isBoolean() || !json.path("success").asBoolean()) {
                throw new IllegalStateException("no success flag");
            }
            if (!json.path("isReady").isBoolean()) {
                throw new IllegalStateException("no isReady flag");
            }
            return new Cooldown(json.get("isReady").asBoolean(), Math.max(0, json.path("cooldownRemaining").asLong(0)));
        });
        log.info("FUT Transfer cooldown for {}: {}", publicRef, read instanceof ReadOk<Cooldown> ok
                ? (ok.value().ready() ? "ready" : "cooling down " + ok.value().remainingSeconds() + "s")
                : ((ReadFailed<Cooldown>) read).error() + " (" + ((ReadFailed<Cooldown>) read).code() + ")");
        return read;
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
    public Read<List<SupplierStatus>> statusBulk(List<String> publicRefs) {
        if (publicRefs.isEmpty()) return new ReadOk<>(List.of());
        if (publicRefs.size() > BULK_LIMIT) {
            throw new IllegalArgumentException("The supplier caps a bulk query at " + BULK_LIMIT);
        }

        Map<String, Object> body = auth();
        body.put("orderIDs", publicRefs);
        body.put("externalID", 1);
        body.put("isMotherID", 0);

        Exchange ex = readExchange("/orderStatusBulkAPI", body, publicRefs);
        return classifyRead(ex, res -> publicRefs.stream()
                .map(ref -> {
                    JsonNode n = res.get(ref);
                    return n == null || n.isNull() || !n.isObject() ? null : parseStatus(ref, n);
                })
                .filter(java.util.Objects::nonNull)
                .toList());
    }

    // ------------------------------------------------------------------ reads ---

    /** What a status read came to. Never thrown: a read that fails says how. */
    public sealed interface Read<T> permits ReadOk, ReadFailed {
    }

    public record ReadOk<T>(T value) implements Read<T> {
    }

    public record ReadFailed<T>(ReadError error, String code) implements Read<T> {
    }

    public enum ReadError {
        /** 403: our API credentials were refused. */
        AUTH,
        /** 429: back off. The vendor sends no Retry-After, so the caller chooses how long. */
        RATE_LIMITED,
        /** Timeouts, dropped connections and 5xx, still failing after the retries. */
        TRANSIENT,
        /** A 4xx the documentation does not explain. */
        NEEDS_REVIEW,
        /** A 2xx we could not read. */
        UNKNOWN
    }

    private <T> Read<T> classifyRead(Exchange ex, java.util.function.Function<JsonNode, T> parse) {
        if (PAUSED.equals(ex.failure())) return new ReadFailed<>(ReadError.AUTH, PAUSED);
        if (ex.failure() != null) return new ReadFailed<>(ReadError.TRANSIENT, ex.failure());
        int status = ex.status();
        if (status >= 500) return new ReadFailed<>(ReadError.TRANSIENT, "HTTP_" + status);
        if (status == 403) return new ReadFailed<>(ReadError.AUTH, "HTTP_403");
        if (status == 429) return new ReadFailed<>(ReadError.RATE_LIMITED, "HTTP_429");
        if (status / 100 != 2) {
            String code = ex.errorCode(mapper);
            return new ReadFailed<>(ReadError.NEEDS_REVIEW, code == null ? "HTTP_" + status : code);
        }
        JsonNode json = ex.json(mapper);
        if (json == null || !json.isObject()) return new ReadFailed<>(ReadError.UNKNOWN, "UNPARSEABLE_RESPONSE");
        try {
            return new ReadOk<>(parse.apply(json));
        } catch (RuntimeException e) {
            return new ReadFailed<>(ReadError.UNKNOWN, "UNPARSEABLE_RESPONSE");
        }
    }

    /**
     * A read, retried while it fails for a reason that may pass: the primary domain, then
     * the backup, then the primary again, with a short, jittered pause between. A 403, a
     * 429 or any other answer ends it at once. Writes never come through here.
     */
    private Exchange readExchange(String path, Map<String, Object> body, List<String> refs) {
        AppProperties.FutTransfer cfg = props.futTransfer();
        String[] domains = {cfg.baseUrl(), cfg.backupBaseUrl(), cfg.baseUrl()};
        Exchange ex = null;
        for (int attempt = 0; attempt < domains.length; attempt++) {
            if (attempt > 0) {
                try {
                    sleeper.sleep(readBackoff(attempt));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return Exchange.failed("INTERRUPTED");
                }
            }
            ex = exchange(Kind.READ, domains[attempt], path, body, refs);
            if (!worthRetrying(ex)) return ex;
        }
        return ex;
    }

    private static boolean worthRetrying(Exchange ex) {
        if (ex.failure() != null) {
            return !ex.failure().equals("NOT_CONFIGURED") && !ex.failure().equals("INTERRUPTED")
                    && !ex.failure().equals(PAUSED);
        }
        return ex.status() >= 500;
    }

    /** Half a second, then a second, each plus up to a quarter of a second of jitter. */
    static Duration readBackoff(int attempt) {
        long base = 500L << (attempt - 1);
        return Duration.ofMillis(base + java.util.concurrent.ThreadLocalRandom.current().nextLong(250));
    }

    /** How the client waits between read attempts; replaced in tests. */
    interface Sleeper {
        void sleep(Duration d) throws InterruptedException;
    }

    private Sleeper sleeper = d -> Thread.sleep(d.toMillis());

    /** For tests: waits nothing, so retries are checked without slowing the suite. */
    FutTransferClient withoutRetryPauses() {
        this.sleeper = d -> {
        };
        return this;
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

        post(props.futTransfer().baseUrl(), "/correctCredentialsAPI", body, List.of(publicRef));
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
    /** What a call is, for the audit trail: placing an order, reading, or writing. */
    private enum Kind { PLACEMENT, READ, WRITE }

    private Exchange exchange(Kind kind, String domain, String path, Map<String, Object> body, List<String> refs) {
        String context = String.join(",", refs);
        AppProperties.FutTransfer cfg = props.futTransfer();
        if (!cfg.isConfigured()) {
            return Exchange.failed("NOT_CONFIGURED");
        }
        if (control.isPaused()) {
            log.warn("FUT Transfer {} for {}: not sent, calls are paused", path, context);
            return Exchange.failed(PAUSED);
        }
        long started = System.nanoTime();
        Exchange result;
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(domain + path))
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
        record(kind, !domain.equals(cfg.baseUrl()), path, result, refs, ms);
        if (result.status() == 403) {
            // Our credentials were refused. Everything stops until an admin resumes it.
            control.pause("HTTP_403 " + path, refs.size() == 1 ? refs.get(0) : null);
        }
        log.info("FUT Transfer {}{} for {}: {} in {} ms", domain.equals(cfg.baseUrl()) ? "" : "(backup) ", path,
                context, result.failure() != null ? result.failure() : "HTTP " + result.status(), ms);
        return result;
    }

    /** A write whose failure is reported by throwing: correcting a sign-in, for now. */
    private JsonNode post(String domain, String path, Map<String, Object> body, List<String> refs) {
        String context = String.join(",", refs);
        Exchange ex = exchange(Kind.WRITE, domain, path, body, refs);
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

    /**
     * One row in {@code vendor_call} for this attempt: what was called, what came back and
     * what it means. Never the body. Calls that were not made -- paused, not configured --
     * are not vendor calls and are not recorded.
     */
    private void record(Kind kind, boolean backup, String path, Exchange ex, List<String> refs, long ms) {
        String result;
        String code;
        String vendorOrderId = null;
        if (kind == Kind.PLACEMENT) {
            Placement p = classifyPlacement(ex);
            switch (p) {
                case Accepted a -> {
                    result = "ACCEPTED";
                    code = null;
                    vendorOrderId = a.vendorOrderId();
                }
                case Refused r -> {
                    result = "REFUSED";
                    code = r.code();
                }
                case Unrecognised u -> {
                    result = "UNRECOGNISED";
                    code = u.code();
                }
                case Uncertain u -> {
                    result = "UNCERTAIN";
                    code = u.code();
                }
            }
        } else {
            Read<JsonNode> r = classifyRead(ex, json -> json);
            if (r instanceof ReadFailed<JsonNode> f) {
                result = f.error().name();
                code = f.code();
            } else {
                result = "OK";
                code = null;
            }
        }
        calls.record(path, backup, ex.failure() == null ? ex.status() : null, result, code, refs, vendorOrderId, ms);
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
