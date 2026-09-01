package com.globalfutservice.payments;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.globalfutservice.config.AppProperties;
import com.globalfutservice.domain.money.Money;
import com.globalfutservice.web.ApiExceptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A deliberately thin Razorpay client built on the JDK's own HTTP client.
 *
 * <p>Written by hand rather than pulled from the vendor SDK. The SDK drags in an
 * {@code org.json} dependency and its own HTTP stack for what amounts to two POSTs, and
 * every transitive dependency in a payment path is supply-chain surface. Both endpoints
 * used here are stable and documented.
 *
 * <p>Amounts are passed in minor units, which is what Razorpay expects and what this
 * application stores — so there is no conversion step anywhere on the money path.
 */
@Component
public class RazorpayGateway implements PaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(RazorpayGateway.class);

    private final AppProperties props;
    private final ObjectMapper mapper;
    private final HttpClient http;

    public RazorpayGateway(AppProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public boolean isEnabled() {
        AppProperties.Razorpay r = props.razorpay();
        return r.enabled()
                && r.keyId() != null && !r.keyId().isBlank()
                && r.keySecret() != null && !r.keySecret().isBlank();
    }

    @Override
    public GatewayOrder createOrder(String receipt, Money amount, String customerEmail, String notes) {
        if (!isEnabled()) {
            // Local development and CI: hand back a deterministic stub so the whole
            // checkout flow can be exercised end to end without live credentials. The
            // prefix makes it obvious in the database that no money was involved.
            log.warn("Razorpay disabled — issuing a stub order for {}", receipt);
            return new GatewayOrder("order_stub_" + receipt, "rzp_test_stub",
                    amount.minor(), amount.currency().name());
        }

        AppProperties.Razorpay cfg = props.razorpay();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount", amount.minor());
        body.put("currency", amount.currency().name());
        body.put("receipt", receipt);
        // Auto-capture. Authorising without capturing means money sits in limbo and has
        // to be swept by a second job; for a fulfil-on-payment business there is no
        // reason to separate the two.
        body.put("payment_capture", 1);
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("order_ref", receipt);
        // The line item description a risk reviewer will read. It says trading service,
        // because that is what is sold — and because it must match the Terms of Service
        // and the merchant-category declaration exactly.
        meta.put("description", notes == null ? "FC trading service" : notes);
        if (customerEmail != null) {
            meta.put("customer_email", customerEmail);
        }
        body.put("notes", meta);

        try {
            String auth = Base64.getEncoder().encodeToString(
                    (cfg.keyId() + ":" + cfg.keySecret()).getBytes(StandardCharsets.UTF_8));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(cfg.apiBaseUrl() + "/orders"))
                    .header("Authorization", "Basic " + auth)
                    .header("Content-Type", "application/json")
                    .timeout(cfg.timeout())
                    .POST(HttpRequest.BodyPublishers.ofString(
                            mapper.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                // The provider's error body can echo request fields. Log the status, not
                // the payload.
                log.error("Razorpay order creation for {} failed with HTTP {}",
                        receipt, response.statusCode());
                throw new ApiExceptions.UpstreamException(
                        "The payment provider rejected this order.", null);
            }

            JsonNode json = mapper.readTree(response.body());
            String providerOrderId = json.path("id").asText(null);
            if (providerOrderId == null) {
                throw new ApiExceptions.UpstreamException(
                        "The payment provider returned an unexpected response.", null);
            }
            return new GatewayOrder(providerOrderId, cfg.keyId(), amount.minor(),
                    amount.currency().name());

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiExceptions.UpstreamException("Payment setup was interrupted.", e);
        } catch (ApiExceptions.UpstreamException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiExceptions.UpstreamException("Could not reach the payment provider.", e);
        }
    }
}
