package com.globalfutservice.payments.web;

import java.net.InetAddress;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import com.globalfutservice.config.AppProperties;
import com.globalfutservice.domain.money.Money;
import com.globalfutservice.orders.OrderEntity;
import com.globalfutservice.orders.OrderService;
import com.globalfutservice.payments.payop.PayopCallbackService;
import com.globalfutservice.payments.payop.PayopCheckoutService;
import com.globalfutservice.payments.payop.PayopClient;
import com.globalfutservice.payments.payop.TrustedClientAddress;
import com.globalfutservice.web.ApiExceptions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Paying through Payop: the customer's side, and Payop's notification.
 *
 * <p>The customer endpoints are open, authenticated the way order tracking is -- reference
 * plus the email on the order, sent in the body, never in a URL. They return prices the
 * server worked out and a page to send the customer to; none of them can mark anything paid.
 * Only the IPN can, and only after Payop's own API confirms it.
 */
@RestController
@RequestMapping("/api/v1/payments/payop")
@Tag(name = "Payments — Payop", description = "International payments through Payop")
public class PayopController {

    private static final Logger log = LoggerFactory.getLogger(PayopController.class);
    private static final Set<String> ISO_COUNTRIES = Set.of(Locale.getISOCountries());

    public record OptionsRequest(@NotBlank String order, @NotBlank String email, @NotBlank String country) {
    }

    public record MethodOptionDto(long methodId, String name, String type, long feeMinor, String feeFormatted,
                                  long totalMinor, String totalFormatted) {
    }

    /**
     * {@code unavailable}: null, or why nothing can be offered. {@code claimsBlockedUntil}:
     * when manual payment claims for this order open again, if a Payop invoice is payable.
     */
    public record OptionsResponse(String currency, long netMinor, String netFormatted, String feeLabel,
                                  List<MethodOptionDto> methods, String unavailable, Instant claimsBlockedUntil) {
    }

    public record StartRequest(@NotBlank String order, @NotBlank String email, @Positive long methodId,
                               @NotBlank String country, @Positive long expectedTotalMinor, String language) {
    }

    public record StartResponse(String redirectUrl, String invoiceId, long totalMinor, String totalFormatted,
                                Instant payableUntil) {
    }

    public record ReturnStatusResponse(String payment, String order, long totalMinor, String totalFormatted,
                                       String method, boolean transferStarted) {
    }

    public record CountryResponse(String country) {
    }

    private final PayopCheckoutService checkout;
    private final PayopCallbackService callbacks;
    private final OrderService orders;
    private final AppProperties props;
    private final TrustedClientAddress addresses;
    private final Set<InetAddress> payopAddresses;

    public PayopController(PayopCheckoutService checkout, PayopCallbackService callbacks, OrderService orders,
                           AppProperties props) {
        this.checkout = checkout;
        this.callbacks = callbacks;
        this.orders = orders;
        this.props = props;
        this.addresses = new TrustedClientAddress(props.payop().trustedProxies());
        this.payopAddresses = props.payop().ipnAllowedIps().stream()
                .map(ip -> TrustedClientAddress.ip(ip).orElseThrow(() -> new IllegalStateException(
                        "GFS_PAYOP_IPN_ALLOWED_IPS holds something that is not an IP address")))
                .collect(Collectors.toUnmodifiableSet());
    }

    @PostMapping("/options")
    @Operation(summary = "The Payop methods for a country, each with its fee and total, worked out by the server")
    public ResponseEntity<OptionsResponse> options(@Valid @RequestBody OptionsRequest request) {
        OrderEntity order = orders.requireGuest(request.order(), request.email());
        PayopCheckoutService.Options o = checkout.options(order, request.country());
        List<MethodOptionDto> methods = o.methods().stream().map(m -> new MethodOptionDto(m.methodId(), m.name(),
                m.type(), m.feeMinor(), Money.ofMinor(m.feeMinor(), o.currency()).format(), m.totalMinor(),
                Money.ofMinor(m.totalMinor(), o.currency()).format())).toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new OptionsResponse(
                o.currency().name(), o.netMinor(), Money.ofMinor(o.netMinor(), o.currency()).format(),
                PayopCheckoutService.FEE_LABEL, methods, o.unavailable(),
                checkout.claimsBlockedUntil(order.getId()).orElse(null)));
    }

    @PostMapping("/invoices")
    @Operation(summary = "Start paying with a Payop method; returns the page to send the customer to",
            description = """
                    The total the customer was shown is sent back and checked against the server's own;
                    if they differ nothing is created and the answer is 409 price_changed. Asking again
                    for the same method returns the same invoice.
                    """)
    public ResponseEntity<StartResponse> start(@Valid @RequestBody StartRequest request) {
        OrderEntity order = orders.requireGuest(request.order(), request.email());
        PayopCheckoutService.Started s = checkout.start(order, request.methodId(), request.country(),
                request.expectedTotalMinor(), request.language());
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(
                new StartResponse(s.redirectUrl(), s.invoiceId(), s.totalMinor(),
                        Money.ofMinor(s.totalMinor(), order.getCurrency()).format(), s.payableUntil()));
    }

    @GetMapping("/return-status")
    @Operation(summary = "What the page Payop returns the customer to may show",
            description = "Read-only. Arriving back from Payop proves nothing, so nothing is marked paid here.")
    public ResponseEntity<ReturnStatusResponse> returnStatus(@RequestParam String ref, @RequestParam String invoice) {
        PayopCheckoutService.ReturnStatus s = checkout.returnStatus(ref, invoice);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new ReturnStatusResponse(s.payment(),
                s.order(), s.totalMinor(), Money.ofMinor(s.totalMinor(), s.currency()).format(), s.methodName(),
                s.transferStarted()));
    }

    @GetMapping("/country")
    @Operation(summary = "A first guess at the customer's country, from Cloudflare; the customer can change it")
    public ResponseEntity<CountryResponse> country(
            @RequestHeader(value = "CF-IPCountry", required = false) String cfCountry) {
        String guess = cfCountry == null ? null : cfCountry.trim().toUpperCase(Locale.ROOT);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new CountryResponse(guess != null && ISO_COUNTRIES.contains(guess) ? guess : null));
    }

    @PostMapping("/callback")
    @Operation(summary = "Payop's IPN",
            description = """
                    Accepted only from Payop's published addresses, judged from what our own proxies
                    recorded and never from a header the caller sets. Even then it proves nothing on its
                    own: the transaction is fetched from Payop's API and checked against the invoice
                    before anything changes. 503 asks Payop to send it again.
                    """)
    public ResponseEntity<Void> callback(HttpServletRequest request, @RequestBody(required = false) byte[] body) {
        if (!props.payop().enabled()) {
            return ResponseEntity.notFound().build();
        }
        Optional<TrustedClientAddress.Resolved> source = addresses.resolve(request);
        boolean allowed = source.isPresent() && payopAddresses.contains(source.get().client());
        String from = source.map(s -> s.client().getHostAddress()).orElse("unknown");
        Optional<PayopCallbackService.Ipn> ipn = callbacks.parse(body == null ? new byte[0] : body);
        log.info("Payop IPN from {} (through Cloudflare: {}) for invoice {}: {}", from,
                source.map(TrustedClientAddress.Resolved::viaCloudflare).orElse(false),
                ipn.map(PayopCallbackService.Ipn::invoiceId).orElse("-"), allowed ? "allowed" : "REFUSED");
        if (!allowed) {
            ipn.ifPresent(i -> callbacks.recordRejected(i, from));
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        if (ipn.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        try {
            callbacks.handle(ipn.get());
        } catch (PayopClient.PayopException e) {
            // Payop could not be asked to confirm it. Nothing changed; Payop sends it again.
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        return ResponseEntity.ok().build();
    }
}
