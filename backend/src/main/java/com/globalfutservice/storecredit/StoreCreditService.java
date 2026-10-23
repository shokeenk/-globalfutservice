package com.globalfutservice.storecredit;

import com.globalfutservice.domain.money.Currency;
import com.globalfutservice.web.ApiExceptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * GFS Store Credit: a balance per currency, issued by a settlement and spent at checkout.
 *
 * <p>The rules it holds:
 * <ul>
 *   <li><b>One currency at a time.</b> Credit in rupees pays for rupee orders only. There is
 *       no conversion, so there is no rate to get wrong.</li>
 *   <li><b>Spending re-checks the balance</b> inside the order's transaction, under a lock on
 *       the account, rather than trusting the quote: two checkouts open at once cannot both
 *       spend the same credit.</li>
 *   <li><b>Once each per order.</b> An order is issued credit once, spends once and is given
 *       back what it spent once; a unique index backs every one of these.</li>
 *   <li><b>Never cash.</b> Nothing here pays credit out; it only ever comes back as credit.</li>
 * </ul>
 */
@Service
public class StoreCreditService {

    private static final Logger log = LoggerFactory.getLogger(StoreCreditService.class);

    private final StoreCreditRepository ledger;
    private final Clock clock;

    public StoreCreditService(StoreCreditRepository ledger, Clock clock) {
        this.ledger = ledger;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public long balance(Long accountId, Currency currency) {
        return accountId == null || currency == null ? 0L : Math.max(0L, ledger.balanceOf(accountId, currency));
    }

    /** What the customer holds in each currency they have ever held credit in, empty ones left out. */
    @Transactional(readOnly = true)
    public Map<Currency, Long> balances(Long accountId) {
        Map<Currency, Long> out = new LinkedHashMap<>();
        if (accountId == null) {
            return out;
        }
        for (StoreCreditRepository.CurrencyBalance b : ledger.balancesOf(accountId)) {
            if (b.getMinor() > 0) {
                out.put(b.getCurrency(), b.getMinor());
            }
        }
        return out;
    }

    /** The store credit an order was settled with, if any. */
    @Transactional(readOnly = true)
    public Optional<StoreCreditEntryEntity> issuedOn(Long orderId) {
        return orderId == null ? Optional.empty()
                : ledger.findByOrderIdAndEntryType(orderId, StoreCreditEntryEntity.Type.ISSUED);
    }

    @Transactional(readOnly = true)
    public Page<StoreCreditEntryEntity> statement(Long accountId, Pageable page) {
        return ledger.findByAccountIdOrderByCreatedAtDescIdDesc(accountId, page);
    }

    /**
     * Adds a Champions settlement to the customer's balance. Part of the settlement's own
     * transaction: if this fails, the settlement is not marked Issued.
     */
    @Transactional
    public StoreCreditEntryEntity issue(Long accountId, Long orderId, Currency currency, long amountMinor,
                                        String orderRef, Long actorId) {
        return issue(accountId, orderId, currency, amountMinor, orderRef,
                "Champions settlement on order " + orderRef, actorId);
    }

    /**
     * Adds store credit for an order: a settlement, or an old points credit moved across. At
     * most once per order, whatever the reason -- an order is settled once.
     */
    @Transactional
    public StoreCreditEntryEntity issue(Long accountId, Long orderId, Currency currency, long amountMinor,
                                        String orderRef, String description, Long actorId) {
        if (accountId == null) {
            throw new ApiExceptions.ConflictException("no_account",
                    "This order has no customer account to hold store credit.");
        }
        if (amountMinor <= 0) {
            throw new ApiExceptions.ConflictException("nothing_to_issue",
                    "There is no store credit to add on " + orderRef + ".");
        }
        if (ledger.existsByOrderIdAndEntryType(orderId, StoreCreditEntryEntity.Type.ISSUED)) {
            throw new ApiExceptions.ConflictException("already_issued",
                    "Store credit has already been issued for " + orderRef + ".");
        }
        StoreCreditEntryEntity entry;
        try {
            entry = ledger.saveAndFlush(new StoreCreditEntryEntity(accountId, currency,
                    StoreCreditEntryEntity.Type.ISSUED, amountMinor, orderId, description, actorId, clock.instant()));
        } catch (DataIntegrityViolationException raced) {
            // Two clicks at once: the unique index let one through.
            throw new ApiExceptions.ConflictException("already_issued",
                    "Store credit has already been issued for " + orderRef + ".");
        }
        log.info("Store credit of {} {} issued on order {}: {}", amountMinor, currency, orderRef, description);
        return entry;
    }

    /**
     * Spends credit on a new order, in the order's transaction.
     *
     * @throws ApiExceptions.ConflictException when the balance no longer covers it -- spent
     *         in another tab since the price was quoted. The order is refused, never placed
     *         with credit the customer no longer has.
     */
    @Transactional
    public void spend(Long accountId, Long orderId, Currency currency, long amountMinor, String orderRef) {
        if (amountMinor <= 0) {
            return;
        }
        if (accountId == null) {
            throw new ApiExceptions.BadRequestException("Sign in to use your GFS Store Credit.");
        }
        ledger.lockAccount(accountId);
        if (ledger.balanceOf(accountId, currency) < amountMinor) {
            throw new ApiExceptions.ConflictException("store_credit_changed",
                    "Your GFS Store Credit balance changed. Please refresh and try again.");
        }
        ledger.saveAndFlush(new StoreCreditEntryEntity(accountId, currency, StoreCreditEntryEntity.Type.SPENT,
                amountMinor, orderId, "Used on order " + orderRef, null, clock.instant()));
    }

    /**
     * Gives back credit an order spent, when the order is abandoned or refunded. At most once,
     * and never more than it spent.
     *
     * @return what was given back
     */
    @Transactional
    public long giveBack(Long accountId, Long orderId, Currency currency, long amountMinor, String orderRef,
                         String why) {
        if (accountId == null || amountMinor <= 0
                || ledger.existsByOrderIdAndEntryType(orderId, StoreCreditEntryEntity.Type.RETURNED)) {
            return 0L;
        }
        long spent = ledger.findByOrderIdAndEntryType(orderId, StoreCreditEntryEntity.Type.SPENT)
                .map(e -> -e.getAmountMinor()).orElse(0L);
        long back = Math.min(spent, amountMinor);
        if (back <= 0) {
            return 0L;
        }
        ledger.save(new StoreCreditEntryEntity(accountId, currency, StoreCreditEntryEntity.Type.RETURNED, back,
                orderId, why + " " + orderRef, null, clock.instant()));
        return back;
    }
}
