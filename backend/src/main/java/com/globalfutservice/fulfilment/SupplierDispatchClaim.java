package com.globalfutservice.fulfilment;

import com.globalfutservice.orders.OrderRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The two committed writes either side of a supplier submission.
 *
 * <p><b>Why these are not just methods on {@link SupplierFulfilmentService}.</b> Two
 * reasons, and both are the difference between working and only looking like it.
 *
 * <p>Spring's {@code @Transactional} is implemented with a proxy, and a proxy is only
 * involved when the call arrives from outside the object. A service calling its own
 * annotated method goes straight to the implementation and the annotation does nothing —
 * silently. Putting these behind a real bean boundary is what makes them mean anything.
 *
 * <p>More importantly they run {@code REQUIRES_NEW}. The claim has to be <i>committed</i>
 * before the supplier is called, or a second caller reading the same row sees the value
 * the first one has not flushed yet and submits the same order again. A claim that shares
 * its caller's transaction is not a claim at all; it becomes visible at exactly the moment
 * it has stopped being useful.
 */
@Component
public class SupplierDispatchClaim {

    private final OrderRepository orders;

    public SupplierDispatchClaim(OrderRepository orders) {
        this.orders = orders;
    }

    /**
     * @return true if this caller now owns the dispatch and may call the supplier
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean tryClaim(Long orderId, int maxAttempts) {
        return orders.claimForDispatch(orderId, maxAttempts) == 1;
    }

    /**
     * Record what the supplier called it.
     *
     * <p>Its own committed transaction for the same reason as the claim: the caller may
     * still be inside a longer-running transaction, and the id has to be durable the
     * instant the partner has accepted the order. Losing it would leave coins sent and no
     * record of the submission.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordAccepted(Long orderId, String supplierOrderId) {
        orders.recordSupplierOrderId(orderId, supplierOrderId);
    }
}
