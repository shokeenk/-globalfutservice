package com.globalfutservice.payments.payop;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import com.globalfutservice.config.AppProperties;
import com.globalfutservice.payments.payop.PayopCallbackService.Outcome;
import com.globalfutservice.scheduling.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * The safety net under Payop's IPN: asks Payop about every invoice that could have been paid
 * and is not settled here, and pays the order through exactly the checks an IPN goes through.
 *
 * <p>Client testing found a real payment, complete in Payop's portal, whose order stayed
 * unpaid until staff stepped in: the IPN was refused at the address check. That check is
 * fixed, but an IPN can also simply not arrive -- a URL missing in Payop's panel, an outage
 * at either end -- and the customer has paid either way. So every few minutes each
 * unsettled invoice from the last day is looked up; one Payop reports paid is confirmed with
 * its transaction (accepted, exact amount, currency, order and attempt) and applied once.
 * The invoice status alone never pays anything.
 *
 * <p>The IPN and this job can confirm the same payment at the same moment. They share
 * {@link PayopCallbackService#confirm}, which applies under the invoice's row lock: whichever
 * comes second finds it paid. One runner at a time, across instances.
 */
@Service
public class PayopReconciliation {

    private static final Logger log = LoggerFactory.getLogger(PayopReconciliation.class);

    /** Not looked at by the job: paid, being created, or already with a person. */
    static final Set<PayopInvoiceEntity.Status> SETTLED = EnumSet.of(PayopInvoiceEntity.Status.PAID,
            PayopInvoiceEntity.Status.CREATING, PayopInvoiceEntity.Status.REVIEW, PayopInvoiceEntity.Status.DUPLICATE);

    /** One invoice checked, and what came of it. */
    public record Checked(String invoiceId, String status, Outcome outcome) {
    }

    private final PayopInvoiceRepository invoices;
    private final PayopClient client;
    private final PayopCallbackService callbacks;
    private final SchedulerLock lock;
    private final AppProperties props;
    private final Clock clock;

    public PayopReconciliation(PayopInvoiceRepository invoices, PayopClient client, PayopCallbackService callbacks,
                               SchedulerLock lock, AppProperties props, Clock clock) {
        this.invoices = invoices;
        this.client = client;
        this.callbacks = callbacks;
        this.lock = lock;
        this.props = props;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${gfs.payop.reconcile-every:PT5M}", initialDelayString = "PT2M")
    public void scheduled() {
        if (!props.payop().enabled()) {
            return;
        }
        try {
            lock.runExclusively("payop-reconcile", this::sweep);
        } catch (RuntimeException e) {
            log.error("Payop reconciliation failed: {}", e.getClass().getSimpleName());
        }
    }

    /** Every unsettled invoice from the window, each checked with Payop. */
    public List<Checked> sweep() {
        Instant since = clock.instant().minus(props.payop().reconcileWindow());
        List<Checked> checked = new ArrayList<>();
        for (PayopInvoiceEntity a : invoices.findUnsettledSince(since, SETTLED)) {
            checked.add(new Checked(a.getInvoiceId(), a.getStatus().name(), check(a)));
        }
        long acted = checked.stream().filter(c -> c.outcome() != Outcome.PENDING).count();
        if (acted > 0) {
            log.info("Payop reconciliation: {} invoice(s) checked, {} changed or flagged", checked.size(), acted);
        }
        return checked;
    }

    /**
     * Staff's "Re-check Payop payment" on an order: every invoice of the order that Payop has
     * and is not already paid or a duplicate, whatever its age -- in review included, since a
     * payment held for want of a transaction ID may now have one.
     */
    public List<Checked> recheckOrder(Long orderId) {
        List<Checked> checked = new ArrayList<>();
        for (PayopInvoiceEntity a : invoices.findByOrderIdOrderByCreatedAtDesc(orderId)) {
            if (a.getInvoiceId() == null || a.getStatus() == PayopInvoiceEntity.Status.PAID
                    || a.getStatus() == PayopInvoiceEntity.Status.DUPLICATE) {
                continue;
            }
            checked.add(new Checked(a.getInvoiceId(), a.getStatus().name(), check(a)));
        }
        return checked;
    }

    /**
     * One invoice: Payop's view of it, and if Payop reports it paid, the transaction it names
     * confirmed and applied through {@link PayopCallbackService#confirm}. Paid with no
     * transaction named goes to a person. Payop unreachable changes nothing; the next run
     * asks again.
     */
    Outcome check(PayopInvoiceEntity a) {
        try {
            PayopClient.InvoiceInfo info = client.invoice(a.getInvoiceId());
            if (info.status() != PayopClient.InvoiceInfo.PAID) {
                return Outcome.PENDING;
            }
            Outcome outcome = info.transactionId() == null
                    ? callbacks.reviewPaidWithoutTransaction(a.getId())
                    : callbacks.confirm(a.getId(), info.transactionId());
            log.info("Payop reconciliation for invoice {}: {}", a.getInvoiceId(), outcome);
            return outcome;
        } catch (PayopClient.PayopException e) {
            log.warn("Payop reconciliation could not check invoice {}: {}", a.getInvoiceId(), e.code());
            return Outcome.UNAVAILABLE;
        }
    }
}
