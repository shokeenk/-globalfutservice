package com.globalfutservice.fulfilment;

import com.globalfutservice.config.AppProperties;
import com.globalfutservice.credentials.CredentialVaultService;
import com.globalfutservice.credentials.web.CredentialDtos;
import com.globalfutservice.domain.orders.DeliveryMethod;
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
        if (!isEnabled()) {
            log.debug("Supplier disabled — order {} stays for manual fulfilment",
                    order.getPublicRef());
            return null;
        }
        if (order.getDeliveryMethod() == DeliveryMethod.SCHEDULED_SESSION) {
            return null;                       // coaching is not a coin order
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

    private static String customerNameFor(OrderEntity order) {
        if (order.getEaPlatformHandle() != null && !order.getEaPlatformHandle().isBlank()) {
            return order.getEaPlatformHandle();
        }
        // Never the email address: it goes into the supplier's customer record, and their
        // record outlives our vault.
        return order.getPublicRef();
    }
}
