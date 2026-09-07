package com.globalfutservice.payments;

import com.globalfutservice.config.AppProperties;
import com.globalfutservice.credentials.CredentialVaultService;
import com.globalfutservice.notify.NotificationService;
import com.globalfutservice.notify.PaymentClaimNotification;
import com.globalfutservice.domain.orders.OrderStatus;
import com.globalfutservice.domain.payments.ClaimStatus;
import com.globalfutservice.domain.payments.ImageType;
import com.globalfutservice.domain.payments.ManualPaymentMethod;
import com.globalfutservice.orders.OrderEntity;
import com.globalfutservice.orders.OrderService;
import com.globalfutservice.web.ApiExceptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Manual payments: the customer pays from their own app and tells us the reference.
 *
 * <p>The single rule this class exists to hold: <b>a claim never moves an order</b>.
 * Submitting one writes a row and stops. The order advances only from {@link
 * #verify}, which is reachable only from the admin API, and which calls the same
 * {@code markPaid} the gateway webhook calls. There is deliberately no path from
 * customer input to a paid order.
 */
@Service
public class ManualPaymentService {

    private static final Logger log = LoggerFactory.getLogger(ManualPaymentService.class);

    private final ManualPaymentClaimRepository claims;
    private final ManualPaymentProofRepository proofs;
    private final OrderService orderService;
    private final CredentialVaultService vaultService;
    private final NotificationService notifications;
    private final AppProperties props;

    public ManualPaymentService(ManualPaymentClaimRepository claims,
                                ManualPaymentProofRepository proofs,
                                OrderService orderService,
                                CredentialVaultService vaultService,
                                NotificationService notifications,
                                AppProperties props) {
        this.claims = claims;
        this.proofs = proofs;
        this.orderService = orderService;
        this.vaultService = vaultService;
        this.notifications = notifications;
        this.props = props;
    }

    /* ------------------------------------------------------------------ offer --- */

    /**
     * What a customer buying this sku may pay with.
     *
     * <p>One domestic UPI destination -- which one depends on the sku -- plus PayPal and
     * crypto, which are open to everybody. A method with no address configured is left
     * out entirely rather than shown as an empty panel: a payment option that cannot be
     * paid is worse than one that is absent, because the customer discovers it after
     * they have decided to use it.
     */
    @Transactional(readOnly = true)
    public List<PaymentOption> optionsFor(String sku) {
        List<PaymentOption> offered = new java.util.ArrayList<>(3);

        String upi = props.manualPayments().upiFor(sku);
        if (upi != null) {
            offered.add(new PaymentOption(ManualPaymentMethod.UPI, upi,
                    props.manualPayments().upiNameFor(sku), null));
        }

        // Offered on the strength of the email alone. The link is a scanning convenience
        // and can be absent; an account with no address is what makes PayPal unpayable.
        String paypalEmail = blankToNull(props.manualPayments().paypalEmail());
        if (paypalEmail != null) {
            offered.add(new PaymentOption(ManualPaymentMethod.PAYPAL, paypalEmail, null,
                    blankToNull(props.manualPayments().paypalLink())));
        }

        String crypto = blankToNull(props.manualPayments().cryptoTrc20());
        if (crypto != null) {
            offered.add(new PaymentOption(ManualPaymentMethod.CRYPTO, crypto, null, null));
        }

        return List.copyOf(offered);
    }

    /* ----------------------------------------------------------------- submit --- */

    /**
     * Records that a customer says they have paid.
     *
     * <p>The destination is resolved here from configuration, not taken from the
     * request. The browser is told where to send money and could be told to say
     * anything about where it sent it; only the server's own answer is worth writing
     * down as the record of where we asked them to pay.
     */
    @Transactional
    public ManualPaymentClaimEntity submit(OrderEntity order, ManualPaymentMethod method, String reference) {
        if (order.getStatus() != OrderStatus.AWAITING_PAYMENT) {
            // Includes the already-paid case. A customer submitting a reference against a
            // paid order has usually paid twice, which is a refund conversation and not
            // something to absorb silently.
            throw new ApiExceptions.ConflictException(
                    "payment_not_pending",
                    "This order is not waiting for payment. Contact support if you have paid twice.");
        }

        String destination = optionsFor(order.getSku().name()).stream()
                .filter(o -> o.method() == method)
                .map(PaymentOption::destination)
                .findFirst()
                .orElseThrow(() -> new ApiExceptions.BadRequestException(
                        "payment_method_unavailable",
                        "That payment method is not available for this order."));

        String cleaned = reference == null ? "" : reference.trim();
        if (cleaned.length() < 4) {
            throw new ApiExceptions.BadRequestException(
                    "reference_too_short",
                    "Enter the reference number your payment app gave you.");
        }

        /*
         * A resubmission replaces the pending claim rather than adding one. Customers
         * mistype a UTR and try again, and two pending rows for one order would leave an
         * operator guessing which reference to look for. The unique index in V18 enforces
         * this too -- this is the friendly half of the same rule.
         */
        Optional<ManualPaymentClaimEntity> existing =
                claims.findByOrderIdAndStatus(order.getId(), ClaimStatus.SUBMITTED);
        existing.ifPresent(claims::delete);

        ManualPaymentClaimEntity claim = claims.saveAndFlush(
                new ManualPaymentClaimEntity(order.getId(), method, destination, cleaned));

        // The reference itself is not secret -- it is on the customer's own statement and
        // an operator will read it out of this table anyway -- but it is not logged, so
        // that a log dump never carries a list of transaction ids alongside order refs.
        log.info("Manual payment claim {} recorded for order {} via {}",
                claim.getId(), order.getPublicRef(), method);

        /*
         * Tell somebody. This is the only event in the system with nothing behind it --
         * no gateway callback, no scheduled sweep -- so an unannounced claim is a
         * customer who has paid and will wait until a person happens to open the console.
         *
         * Fires after the claim is flushed, so an alert never describes a row that then
         * fails to save. It cannot throw into this transaction: NotificationService is
         * @Async and catches per channel. A webhook outage must not fail a submission the
         * customer has no way to retry meaningfully.
         *
         * The notification carries no EA sign-in. `hasCredentials` is a boolean by
         * design -- an operator needs to know the sign-in arrived, never what it is.
         */
        notifications.paymentClaimed(new PaymentClaimNotification(
                order.getPublicRef(),
                OrderService.describe(order),
                order.total().format(),
                method.name(),
                destination,
                cleaned,
                order.getGuestEmail(),
                order.getDiscordUsername(),
                vaultService.hasCredentials(order.getId()),
                claim.getSubmittedAt(),
                props.publicUrl() + "/admin/orders/" + order.getPublicRef()));

        return claim;
    }

    /* ------------------------------------------------------------------ proof --- */

    /** The largest screenshot accepted, matching the check constraint in V19. */
    public static final int MAX_PROOF_BYTES = 5 * 1024 * 1024;

    /**
     * Attaches, or replaces, the screenshot for an order's pending claim.
     *
     * <p>The type is decided here from the file's own bytes. What the browser announced
     * is discarded before it can influence anything, because the stored type is what an
     * operator's browser is later told to render the file as.
     *
     * @throws ApiExceptions.BadRequestException if the bytes are not a JPEG, PNG or WebP
     */
    @Transactional
    public ManualPaymentProofEntity attachProof(OrderEntity order, byte[] data) {
        ManualPaymentClaimEntity claim = claims
                .findByOrderIdAndStatus(order.getId(), ClaimStatus.SUBMITTED)
                .orElseThrow(() -> new ApiExceptions.ConflictException(
                        "no_pending_claim",
                        "Submit your payment reference first, then attach the screenshot."));

        if (data == null || data.length == 0) {
            throw new ApiExceptions.BadRequestException("empty_file", "That file was empty.");
        }
        if (data.length > MAX_PROOF_BYTES) {
            throw new ApiExceptions.BadRequestException("file_too_large",
                    "That image is larger than 5 MB. A screenshot is usually well under it.");
        }

        ImageType type = ImageType.sniff(data);
        if (type == null) {
            // Named formats rather than "invalid file": somebody who exported a PDF
            // receipt needs to know what to send instead, not that they were wrong.
            throw new ApiExceptions.BadRequestException("unsupported_image",
                    "Attach a JPG, PNG or WebP screenshot.");
        }

        ManualPaymentProofEntity proof = proofs.findByClaimId(claim.getId())
                .map(existing -> {
                    existing.replaceWith(type.mediaType(), data);
                    return existing;
                })
                .orElseGet(() -> new ManualPaymentProofEntity(claim.getId(), type.mediaType(), data));

        // Size and type only. The bytes are a customer's bank screenshot and have no
        // business in a log line.
        log.info("Payment proof attached to claim {} for order {} ({}, {} bytes)",
                claim.getId(), order.getPublicRef(), type.mediaType(), data.length);

        return proofs.saveAndFlush(proof);
    }

    @Transactional(readOnly = true)
    public Optional<ManualPaymentProofEntity> proofForClaim(Long claimId) {
        return proofs.findByClaimId(claimId);
    }

    /** Which of these claims carry a screenshot. Used to flag rows in the queue. */
    @Transactional(readOnly = true)
    public java.util.Set<Long> claimsWithProof(List<Long> claimIds) {
        return claimIds.isEmpty()
                ? java.util.Set.of()
                : java.util.Set.copyOf(proofs.claimIdsWithProof(claimIds));
    }

    /* ----------------------------------------------------------------- review --- */

    @Transactional(readOnly = true)
    public Page<ManualPaymentClaimEntity> pending(Pageable pageable) {
        return claims.findByStatusOrderBySubmittedAtAsc(ClaimStatus.SUBMITTED, pageable);
    }

    @Transactional(readOnly = true)
    public long pendingCount() {
        return claims.countByStatus(ClaimStatus.SUBMITTED);
    }

    @Transactional(readOnly = true)
    public List<ManualPaymentClaimEntity> forOrder(Long orderId) {
        return claims.findByOrderIdOrderBySubmittedAtDesc(orderId);
    }

    @Transactional(readOnly = true)
    public List<ManualPaymentClaimEntity> byReference(String reference) {
        return claims.findByReferenceOrderBySubmittedAtDesc(reference.trim());
    }

    /**
     * An operator found the money. This is the only route from a claim to a paid order.
     *
     * <p>Marks the claim first and the order second. If {@code markPaid} throws, the
     * transaction takes the claim's status back with it, so an operator never sees a
     * verified claim sitting on an unpaid order.
     */
    @Transactional
    public ManualPaymentClaimEntity verify(Long claimId, Long reviewerAccountId, String note) {
        ManualPaymentClaimEntity claim = require(claimId);
        requireUnreviewed(claim);

        OrderEntity order = orderService.requireById(claim.getOrderId());
        claim.review(ClaimStatus.VERIFIED, reviewerAccountId, note);
        claims.saveAndFlush(claim);

        // Same entry point the Razorpay webhook uses, so a manually verified order runs
        // the identical PAID -> credentials/delivery logic and coaching credits are
        // granted the same way. The reference goes into the order's timeline.
        orderService.markPaid(order, claim.getMethod().name() + " " + claim.getReference());

        log.info("Manual payment claim {} verified for order {} by account {}",
                claim.getId(), order.getPublicRef(), reviewerAccountId);
        return claim;
    }

    /**
     * An operator could not find the money. The order stays where it was, so the
     * customer can submit a corrected reference without support having to reopen
     * anything.
     */
    @Transactional
    public ManualPaymentClaimEntity reject(Long claimId, Long reviewerAccountId, String note) {
        ManualPaymentClaimEntity claim = require(claimId);
        requireUnreviewed(claim);
        claim.review(ClaimStatus.REJECTED, reviewerAccountId, note);
        return claims.saveAndFlush(claim);
    }

    private ManualPaymentClaimEntity require(Long claimId) {
        return claims.findById(claimId)
                .orElseThrow(() -> new ApiExceptions.NotFoundException("No such payment claim."));
    }

    private void requireUnreviewed(ManualPaymentClaimEntity claim) {
        if (claim.getStatus().isReviewed()) {
            // Two operators opening the same queue is normal; both acting on it should not
            // silently double-apply.
            throw new ApiExceptions.ConflictException(
                    "claim_already_reviewed",
                    "That claim has already been " + claim.getStatus().name().toLowerCase() + ".");
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /**
     * One payable destination offered to a customer.
     *
     * @param destination the account itself, and the value recorded against a claim
     * @param accountName who holds it, where there is a name to show
     * @param link        an optional way to open the payment rather than copy it; null
     *                    for methods where the destination is the only form there is
     */
    public record PaymentOption(ManualPaymentMethod method, String destination,
                                String accountName, String link) {
    }
}
