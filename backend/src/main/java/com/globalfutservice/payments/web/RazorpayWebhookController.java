package com.globalfutservice.payments.web;

import com.globalfutservice.config.AppProperties;
import com.globalfutservice.domain.payments.RazorpaySignature;
import com.globalfutservice.payments.PaymentWebhookService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/payments/webhook")
@Tag(name = "Payments", description = "Gateway callbacks")
public class RazorpayWebhookController {

    private static final Logger log = LoggerFactory.getLogger(RazorpayWebhookController.class);

    private final PaymentWebhookService webhookService;
    private final AppProperties props;

    public RazorpayWebhookController(PaymentWebhookService webhookService, AppProperties props) {
        this.webhookService = webhookService;
        this.props = props;
    }

    /**
     * The only endpoint that can move an order into PAID.
     *
     * <p>Note the parameter type: {@code String}, not a DTO. The signature is computed
     * over the exact bytes Razorpay sent, and binding to an object first would re-order
     * keys and normalise whitespace on the way to a MAC that could then never match.
     * JSON parsing happens after verification, in the service.
     *
     * <p>Always answers 200 once the signature checks out, even if applying the event
     * failed. A non-2xx tells the gateway to retry, and retrying against an application
     * bug produces a delivery storm rather than a fix; the delivery is stored either way
     * and can be replayed deliberately.
     */
    @PostMapping("/razorpay")
    @Operation(summary = "Razorpay webhook receiver")
    public ResponseEntity<String> receive(
            @RequestBody String rawBody,
            @RequestHeader(value = "X-Razorpay-Signature", required = false) String signature,
            @RequestHeader(value = "X-Razorpay-Event-Id", required = false) String eventId) {

        String secret = props.razorpay().webhookSecret();
        if (secret == null || secret.isBlank()) {
            // Fail closed. An unconfigured secret must not become an unauthenticated
            // endpoint that marks orders paid.
            log.error("Razorpay webhook received but no webhook secret is configured");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body("not configured");
        }

        if (!RazorpaySignature.verifyWebhook(rawBody, signature, secret)) {
            log.warn("Rejected webhook with an invalid signature");
            // 400, not 401: there is no authentication to challenge for, and a 401 would
            // invite the caller to try again with credentials.
            return ResponseEntity.badRequest().body("invalid signature");
        }

        // A delivery without an id cannot be de-duplicated. Falling back to a hash of the
        // body keeps idempotency working, since identical bodies are the retry case.
        String idempotencyKey = eventId != null && !eventId.isBlank()
                ? eventId
                : "sha:" + Integer.toHexString(rawBody.hashCode()) + ":" + rawBody.length();

        webhookService.handle(idempotencyKey, rawBody);
        return ResponseEntity.ok("ok");
    }
}
