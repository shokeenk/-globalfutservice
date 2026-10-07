package com.globalfutservice.payments.payop;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.globalfutservice.config.AppProperties;
import com.globalfutservice.domain.money.Currency;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Payop's API: create an invoice, read a transaction, list the methods this project may use.
 *
 * <p>Server-side only. The secret key signs invoices and the JWT authorises reads; neither
 * is ever logged, returned or put in an exception message. Nor is any request or response
 * body: a failure is logged as the call, the HTTP status and a {@link ErrorCode}, and for a
 * validation error the names of the fields Payop objected to -- never their values.
 */
@Component
public class PayopClient {

    private static final Logger log = LoggerFactory.getLogger(PayopClient.class);

    /** Payop's IDs are UUIDs; anything else is refused before it reaches a URL or a page. */
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9-]{8,64}");
    private static final Pattern APPLICATION_ID = Pattern.compile("[A-Za-z0-9-]{1,64}");
    private static final Pattern FIELD_NAME = Pattern.compile("[A-Za-z0-9_.\\[\\]]{1,64}");

    /** Why a call failed, as far as anyone needs to know. */
    public enum ErrorCode {
        UNAUTHORIZED, FORBIDDEN, NOT_FOUND, WRONG_SIGNATURE, METHOD_NOT_ENABLED, INVOICE_OVERDUE,
        VALIDATION, REJECTED, SERVER_ERROR, UNAVAILABLE, BAD_RESPONSE
    }

    /** A failed call. The message holds the call, status and code only, never a body. */
    public static final class PayopException extends RuntimeException {
        private final ErrorCode code;
        private final int httpStatus;
        private final List<String> fields;

        public PayopException(String call, ErrorCode code, int httpStatus, List<String> fields) {
            super("Payop " + call + " failed: " + code + (httpStatus > 0 ? " (HTTP " + httpStatus + ")" : "")
                    + (fields.isEmpty() ? "" : " fields=" + fields));
            this.code = code;
            this.httpStatus = httpStatus;
            this.fields = List.copyOf(fields);
        }

        public ErrorCode code() {
            return code;
        }

        public int httpStatus() {
            return httpStatus;
        }

        /** For a validation error: the request fields Payop objected to. */
        public List<String> fields() {
            return fields;
        }

        /** Worth trying again later: Payop or the network failed, not the request. */
        public boolean isTransient() {
            return code == ErrorCode.SERVER_ERROR || code == ErrorCode.UNAVAILABLE;
        }
    }

    /**
     * What an invoice is made from. {@code amount} is the exact string signed and sent, e.g.
     * "104.48"; {@code attemptId} travels in the metadata and comes back in the IPN.
     */
    public record InvoiceRequest(String orderRef, String amount, Currency currency, String description,
                                 String payerEmail, long methodId, String language, String resultUrl,
                                 String failUrl, UUID attemptId) {
    }

    /**
     * A transaction as Payop reports it. {@code productAmount} and {@code productCurrency}
     * are the invoice's amount and currency, before Payop converts to the method's own; null
     * when Payop's answer lacks them, which a caller must treat as unconfirmed.
     */
    public record Transaction(String id, int state, String error, String orderId, BigDecimal productAmount,
                              String productCurrency, String metadataAttemptId) {
    }

    /**
     * An invoice as Payop reports it: its status (0 new, 1 paid, 2 overdue, 4 pending,
     * 5 failed) and the transaction made against it, when Payop names one. The status proves
     * nothing on its own -- a paid invoice is still confirmed through {@link #transaction}.
     */
    public record InvoiceInfo(int status, String transactionId) {

        public static final int PAID = 1;
    }

    /** One method this project may use, as Payop lists it. Countries are ISO codes, upper case. */
    public record AvailableMethod(long id, String title, String type, List<String> currencies,
                                  List<String> countries) {
    }

    private final AppProperties props;
    private final PayopTokenWatch tokenWatch;
    private final HttpClient http;
    private final ObjectMapper json = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    public PayopClient(AppProperties props, PayopTokenWatch tokenWatch) {
        this.props = props;
        this.tokenWatch = tokenWatch;
        this.http = HttpClient.newBuilder().connectTimeout(props.payop().timeout())
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    /** Creates the invoice and returns Payop's ID for it. */
    public String createInvoice(InvoiceRequest r) {
        AppProperties.Payop p = props.payop();
        Map<String, Object> order = new LinkedHashMap<>();
        order.put("id", r.orderRef());
        order.put("amount", r.amount());
        order.put("currency", r.currency().name());
        order.put("description", r.description());
        order.put("items", List.of());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("publicKey", p.publicKey());
        body.put("order", order);
        body.put("payer", Map.of("email", r.payerEmail()));
        body.put("paymentMethod", String.valueOf(r.methodId()));
        body.put("language", r.language());
        body.put("resultUrl", r.resultUrl());
        body.put("failPath", r.failUrl());
        body.put("metadata", Map.of("attemptId", r.attemptId().toString(), "orderRef", r.orderRef()));
        body.put("signature", PayopSignature.of(r.amount(), r.currency().name(), r.orderRef(), p.secretKey()));

        String call = "create invoice";
        HttpResponse<byte[]> response;
        try {
            response = send(call, HttpRequest.newBuilder(URI.create(p.apiUrl() + "/v1/invoices/create"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body))), false);
        } catch (IOException e) {
            throw failed(call, ErrorCode.BAD_RESPONSE, 0, List.of());
        }
        // Payop's documented place for the ID is the "identifier" header; the body's "data"
        // still carries it but is marked for removal, so it is only the fallback.
        String id = response.headers().firstValue("identifier").orElse(null);
        if (id == null) {
            JsonNode data = read(call, response).path("data");
            id = data.isTextual() ? data.asText() : null;
        }
        if (id == null || !ID.matcher(id).matches()) {
            throw failed(call, ErrorCode.BAD_RESPONSE, response.statusCode(), List.of());
        }
        return id;
    }

    /** {@code GET /v2/transactions/{txid}}, authorised with the JWT. */
    public Transaction transaction(String txid) {
        if (txid == null || !ID.matcher(txid).matches()) {
            throw new IllegalArgumentException("not a Payop transaction ID");
        }
        String call = "get transaction";
        HttpResponse<byte[]> response = send(call, HttpRequest.newBuilder(
                URI.create(props.payop().apiUrl() + "/v2/transactions/" + txid)).GET(), true);
        JsonNode data = read(call, response).path("data");
        if (!data.isObject() || !data.path("state").canConvertToInt()) {
            throw failed(call, ErrorCode.BAD_RESPONSE, response.statusCode(), List.of());
        }
        JsonNode error = data.path("error");
        String errorMessage = error.isObject() ? text(error.path("message")) : text(error);
        String orderId = text(data.path("orderId"));
        if (orderId == null) {
            orderId = text(data.path("orderIdentifier"));
        }
        if (decimal(data.path("productAmount")) == null || text(data.path("productCurrency")) == null) {
            // Names only, never values: what Payop's answer did contain, so the shape can be checked.
            List<String> fields = new ArrayList<>();
            data.fieldNames().forEachRemaining(fields::add);
            log.warn("Payop transaction answer without productAmount/productCurrency; fields present: {}", fields);
        }
        return new Transaction(text(data.path("identifier")), data.path("state").asInt(), errorMessage, orderId,
                decimal(data.path("productAmount")), text(data.path("productCurrency")),
                text(data.path("metadata").path("attemptId")));
    }

    /**
     * Payop's view of one of our invoices: for the reconciliation that catches a payment
     * whose IPN never arrived or was refused. Payop's invoice endpoint needs no token.
     */
    public InvoiceInfo invoice(String invoiceId) {
        if (invoiceId == null || !ID.matcher(invoiceId).matches()) {
            throw new IllegalArgumentException("not a Payop invoice ID");
        }
        String call = "get invoice";
        HttpResponse<byte[]> response = send(call, HttpRequest.newBuilder(
                URI.create(props.payop().apiUrl() + "/v1/invoices/" + invoiceId)).GET(), false);
        JsonNode data = read(call, response).path("data");
        if (!data.isObject() || !data.path("status").canConvertToInt()) {
            throw failed(call, ErrorCode.BAD_RESPONSE, response.statusCode(), List.of());
        }
        String txid = text(data.path("transactionIdentifier"));
        return new InvoiceInfo(data.path("status").asInt(), txid != null && ID.matcher(txid).matches() ? txid : null);
    }

    /** The methods this project may use right now. */
    public List<AvailableMethod> availableMethods() {
        String applicationId = props.payop().applicationId();
        if (applicationId == null || !APPLICATION_ID.matcher(applicationId).matches()) {
            throw new IllegalStateException("GFS_PAYOP_APPLICATION_ID is not set to a Payop project ID");
        }
        String call = "list methods";
        HttpResponse<byte[]> response = send(call, HttpRequest.newBuilder(URI.create(props.payop().apiUrl()
                + "/v1/instrument-settings/payment-methods/available-for-application/" + applicationId)).GET(), true);
        JsonNode data = read(call, response).path("data");
        if (!data.isArray()) {
            throw failed(call, ErrorCode.BAD_RESPONSE, response.statusCode(), List.of());
        }
        List<AvailableMethod> out = new ArrayList<>();
        for (JsonNode m : data) {
            if (!m.path("identifier").canConvertToLong()) {
                continue;
            }
            out.add(new AvailableMethod(m.path("identifier").asLong(), text(m.path("title")), text(m.path("type")),
                    codes(m.path("currencies")), codes(m.path("countries"))));
        }
        return out;
    }

    /* ------------------------------------------------------------------ transport --- */

    private HttpResponse<byte[]> send(String call, HttpRequest.Builder request, boolean withToken) {
        request.timeout(props.payop().timeout()).header("Accept", "application/json");
        if (withToken) {
            request.header("Authorization", "Bearer " + props.payop().jwtToken());
        }
        HttpResponse<byte[]> response;
        try {
            response = http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw failed(call, ErrorCode.UNAVAILABLE, 0, List.of());
        } catch (IOException e) {
            log.warn("Payop {} failed: {}", call, e.getClass().getSimpleName());
            throw new PayopException(call, ErrorCode.UNAVAILABLE, 0, List.of());
        }
        int status = response.statusCode();
        if (status == 200 || status == 201) {
            return response;
        }
        if (status == 401) {
            tokenWatch.refused(call);
            throw failed(call, ErrorCode.UNAUTHORIZED, status, List.of());
        }
        if (status == 403) {
            throw failed(call, ErrorCode.FORBIDDEN, status, List.of());
        }
        if (status >= 500) {
            throw failed(call, ErrorCode.SERVER_ERROR, status, List.of());
        }
        // A 4xx: Payop says what it objected to in "message", a sentence or, for a validation
        // error, an object keyed by field. Only known sentences and the field names are kept.
        JsonNode message;
        try {
            message = json.readTree(response.body()).path("message");
        } catch (IOException e) {
            message = json.missingNode();
        }
        if (message.isObject()) {
            List<String> fields = new ArrayList<>();
            for (Iterator<String> names = message.fieldNames(); names.hasNext(); ) {
                String name = names.next();
                if (FIELD_NAME.matcher(name).matches() && fields.size() < 10) {
                    fields.add(name);
                }
            }
            throw failed(call, ErrorCode.VALIDATION, status, fields);
        }
        String text = message.isTextual() ? message.asText().toLowerCase(Locale.ROOT) : "";
        ErrorCode code = text.contains("wrong signature") ? ErrorCode.WRONG_SIGNATURE
                : text.contains("method must be enabled") ? ErrorCode.METHOD_NOT_ENABLED
                : text.contains("overdue") ? ErrorCode.INVOICE_OVERDUE
                : status == 404 || text.contains("not found") ? ErrorCode.NOT_FOUND
                : ErrorCode.REJECTED;
        throw failed(call, code, status, List.of());
    }

    private JsonNode read(String call, HttpResponse<byte[]> response) {
        try {
            return json.readTree(response.body());
        } catch (IOException e) {
            throw failed(call, ErrorCode.BAD_RESPONSE, response.statusCode(), List.of());
        }
    }

    private static PayopException failed(String call, ErrorCode code, int status, List<String> fields) {
        PayopException e = new PayopException(call, code, status, fields);
        log.warn(e.getMessage());
        return e;
    }

    private static String text(JsonNode node) {
        return node.isValueNode() && !node.isNull() && !node.asText().isBlank() ? node.asText() : null;
    }

    private static BigDecimal decimal(JsonNode node) {
        if (node.isNumber()) {
            return node.decimalValue();
        }
        if (node.isTextual()) {
            try {
                return new BigDecimal(node.asText().trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static List<String> codes(JsonNode array) {
        List<String> out = new ArrayList<>();
        if (array.isArray()) {
            for (JsonNode c : array) {
                if (c.isTextual() && !c.asText().isBlank()) {
                    out.add(c.asText().trim().toUpperCase(Locale.ROOT));
                }
            }
        }
        return out;
    }
}
