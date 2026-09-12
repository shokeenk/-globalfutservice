package com.globalfutservice.fulfilment;

import com.globalfutservice.config.AppProperties;
import com.globalfutservice.credentials.CredentialVaultService;
import com.globalfutservice.credentials.web.CredentialDtos;
import com.globalfutservice.orders.OrderEntity;
import com.globalfutservice.orders.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Automated coin fulfilment: hands the order to the supplier, then keeps our copy of its
 * state in step with theirs.
 *
 * <p><b>Disabled is a supported state, not a broken one.</b> With no supplier configured
 * the order stops at {@code READY_FOR_DELIVERY} for an operator to work by hand, which is
 * exactly how this system behaved before the integration existed. That is what makes the
 * integration safe to switch off in an incident rather than something the storefront
 * depends on to function.
 *
 * <p><b>Credentials are read, used, and dropped.</b> The plaintext exists as a local in
 * {@link #dispatch}, for the length of one HTTP call. It is never held on a field, never
 * put on the order, and never logged — see {@link FutTransferClient} for why the client
 * refuses to log a body at all.
 */
@Service
public class SupplierFulfilmentService {

    private static final Logger log = LoggerFactory.getLogger(SupplierFulfilmentService.class);

    private final FutTransferClient client;
    private final CredentialVaultService vault;
    private final OrderRepository orders;
    private final AppProperties props;

    public SupplierFulfilmentService(FutTransferClient client, CredentialVaultService vault,
                                     OrderRepository orders, AppProperties props) {
        this.client = client;
        this.vault = vault;
        this.orders = orders;
        this.props = props;
    }

    public boolean isEnabled() {
        return client.isEnabled();
    }

    // ---------------------------------------------------------------- dispatch ---

    /**
     * Sends a paid, credentialed coin order to the supplier.
     *
     * <p>Called after the vault has sealed the sign-in. Failure here is deliberately
     * <b>not</b> propagated to the customer: they have paid and submitted everything asked
     * of them, and the order is fulfillable by hand. A supplier outage becomes an operator
     * alert, not a red error on a checkout the customer cannot retry.
     *
     * @return the supplier's order id, or null if it was not dispatched
     */
    @Transactional
    public String dispatch(OrderEntity order) {
        return dispatch(order, false);
    }

    /**
     * @param propagate whether the partner's own reason should reach the caller.
     *                  False on the customer path, where a supplier outage must not
     *                  become a red error on a checkout nobody can retry; true on the
     *                  operator path, where somebody is waiting to be told what happened
     *                  and "check the application log" is not an answer they can act on
     *                  from the screen they are looking at.
     */
    private String dispatch(OrderEntity order, boolean propagate) {
        if (!isEnabled()) {
            log.debug("Supplier disabled — order {} stays for manual fulfilment",
                    order.getPublicRef());
            return null;
        }
        if (!order.getSku().isCoinTransfer()) {
            /*
             * Keyed off the SKU, not the delivery method. The delivery-method test
             * excluded coaching and let boosting through -- and boosting reaches this
             * point looking dispatchable, because it holds a sign-in like a coin order
             * does. See Sku#isCoinTransfer for what the partner's API actually takes.
             */
            log.debug("Order {} is not a coin order — nothing to dispatch",
                    order.getPublicRef());
            return null;
        }
        if (order.getSupplierOrderId() != null) {
            return order.getSupplierOrderId(); // already sent; never submit twice
        }
        if (order.getSupplierDispatchAttempts() >= props.futTransfer().maxDispatchAttempts()) {
            return null;                       // parked; an operator owns it now
        }

        order.recordDispatchAttempt();
        try {
            /*
             * Opened as the system rather than an operator. The vault counts and attributes
             * every read because staff are in its threat model, and an automated dispatch
             * that borrowed a human's id would put a name against a read they did not make.
             */
            CredentialDtos.RevealedCredentials creds = vault.reveal(order.getId(), null);

            long amountK = FutTransferClient.toThousands(order.getQuantity());
            FutTransferClient.Accepted accepted = client.submitOrder(
                    order.getPublicRef(),
                    customerNameFor(order),
                    order.getPlatform(),
                    amountK,
                    creds);

            order.setSupplierOrderId(accepted.supplierOrderId());
            orders.save(order);
            log.info("Order {} dispatched to supplier as {}",
                    order.getPublicRef(), accepted.supplierOrderId());
            return accepted.supplierOrderId();

        } catch (RuntimeException e) {
            /*
             * Message only. Whatever went wrong, the request that caused it held an EA
             * password, and a stack trace from a serialisation layer can quote the value
             * that failed.
             */
            log.error("Could not dispatch order {} to supplier (attempt {}): {}",
                    order.getPublicRef(), order.getSupplierDispatchAttempts(), e.getMessage());
            orders.save(order);

            if (propagate) {
                /*
                 * The partner's own words, not a summary of them. The message is built
                 * from the status, the path and the order reference -- never a request
                 * body -- so it is safe to put in front of an operator, and it is the
                 * difference between "try again later" and "our API account is out of
                 * balance".
                 */
                String reason = e.getMessage() == null ? "no reason given" : e.getMessage().trim();
                if (!reason.endsWith(".")) {
                    reason = reason + ".";
                }
                throw new FutTransferClient.FutTransferException(
                        "The fulfilment partner refused order " + order.getPublicRef()
                                + ": " + reason + " The order has not moved.");
            }

            if (order.getSupplierDispatchAttempts() >= props.futTransfer().maxDispatchAttempts()) {
                /*
                 * Parked rather than retried forever. The order is paid and has a sign-in
                 * on file, so it is fulfillable by hand — and an unbounded retry against a
                 * supplier that is rejecting our credentials is how an API account gets
                 * locked. It stays at READY_FOR_DELIVERY, which is the queue an operator
                 * already works.
                 */
                log.error("Order {} parked after {} failed dispatches — needs manual fulfilment",
                        order.getPublicRef(), order.getSupplierDispatchAttempts());
            }
            return null;
        }
    }

    // --------------------------------------------------------------- approval ---

    /**
     * An operator has reviewed the order and released it to the supplier.
     *
     * <p><b>Why this exists next to {@link #dispatch} rather than replacing it.</b> They
     * differ in one way that matters: dispatch swallows failure because it used to run
     * inside a customer's checkout, where a supplier outage must not become a red error
     * on a form they cannot retry. This runs inside an operator's click, where the
     * opposite is true -- somebody is looking at the screen, waiting to be told whether it
     * worked, and a silent failure would leave them believing an order was released when
     * it was not.
     *
     * <p>The order is <b>not</b> moved by this method. It returns what happened and lets
     * the caller decide, so the status change and the audit entry are written in the same
     * place, attributed to the operator who made them.
     *
     * <p>Credentials: read inside {@link #dispatch}, used for one HTTP call, and dropped.
     * Nothing here ever sees the plaintext, and nothing writes it anywhere.
     *
     * @throws FutTransferException with the supplier's own reason when the submission is
     *                              refused -- safe to show an operator, and never
     *                              containing the sign-in
     */
    @Transactional
    public String approveAndDispatch(OrderEntity order, Long operatorAccountId) {
        if (!isEnabled()) {
            throw new FutTransferClient.FutTransferException(
                    "The fulfilment partner is not configured, so this order cannot be "
                            + "released automatically. Work it by hand and mark it in progress.");
        }
        if (order.getSupplierOrderId() != null) {
            // Not an error worth failing on -- the operator's intent is already satisfied,
            // and re-submitting the same sign-in is the one thing to avoid.
            log.info("Order {} was already with the supplier as {}; approval is a no-op",
                    order.getPublicRef(), order.getSupplierOrderId());
            return order.getSupplierOrderId();
        }

        /*
         * The audit line, written before the call rather than after.
         *
         * If the process dies mid-request there has still been an outbound submission of
         * this customer's sign-in, and the record of who authorised it must not depend on
         * that request coming back. Order reference, operator id, outcome -- never a
         * credential value; see the class note and FutTransferClient.
         */
        log.info("FULFILMENT APPROVAL: operator {} is releasing order {} to the supplier",
                operatorAccountId, order.getPublicRef());

        String supplierId = dispatch(order, true);
        if (supplierId == null) {
            // dispatch() logged the cause and swallowed it. The operator gets told plainly
            // rather than being left to infer failure from an unchanged screen.
            throw new FutTransferClient.FutTransferException(
                    "The fulfilment partner did not accept order " + order.getPublicRef()
                            + ". The order has not moved and no further attempt was made. "
                            + "Check the application log for the partner's reason.");
        }

        log.info("FULFILMENT APPROVED: order {} released by operator {} as supplier order {}",
                order.getPublicRef(), operatorAccountId, supplierId);
        return supplierId;
    }

    private static String customerNameFor(OrderEntity order) {
        if (order.getEaPlatformHandle() != null && !order.getEaPlatformHandle().isBlank()) {
            return order.getEaPlatformHandle();
        }
        // Never the email address: it goes into the supplier's customer record, and their
        // record outlives our vault.
        return order.getPublicRef();
    }
}
