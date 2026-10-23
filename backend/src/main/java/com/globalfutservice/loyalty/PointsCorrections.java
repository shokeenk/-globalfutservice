package com.globalfutservice.loyalty;

import com.globalfutservice.domain.loyalty.LoyaltyTier;
import com.globalfutservice.web.ApiExceptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Supplier;

/**
 * An admin's two corrections (both CORRECTION entries) to a customer's points, each with a reason the customer sees
 * and the admin's name on it:
 * <ul>
 *   <li><b>Reverse this entry</b>: a CORRECTION of the exact opposite amount, linked to the
 *       line it takes back. Neither line counts as earned or toward the tier after it.
 *   <li><b>Set points balance to N</b>: one CORRECTION for the difference, up or down, never
 *       below zero. It does not count as earned or toward the tier.
 * </ul>
 * Each is previewed first: the change is made for real inside a transaction that is then
 * rolled back, so the numbers shown before confirming come from exactly the code that
 * confirming runs. Nothing here runs on its own.
 */
@Service
public class PointsCorrections {

    private static final Logger log = LoggerFactory.getLogger(PointsCorrections.class);

    /** Longest reason kept: it is shown on the customer's history. */
    public static final int MAX_REASON = 300;

    private final PointsLedgerRepository ledger;
    private final LoyaltyService loyalty;
    private final PointsStatement statement;
    private final TransactionTemplate tx;

    public PointsCorrections(PointsLedgerRepository ledger, LoyaltyService loyalty, PointsStatement statement,
                             PlatformTransactionManager transactions) {
        this.ledger = ledger;
        this.loyalty = loyalty;
        this.statement = statement;
        this.tx = new TransactionTemplate(transactions);
    }

    /** The three numbers an admin sees before and after: available, earned, and the tier. */
    public record Standing(long available, long earned, long lifetime, String tier) {
    }

    public record Outcome(Standing before, Standing after, long change) {
    }

    // ------------------------------------------------------------------ reverse an entry

    public Outcome previewReverse(long accountId, long entryId, String reason, long actorId) {
        return dryRun(accountId, () -> reverseIn(accountId, entryId, clean(reason), actorId));
    }

    public Outcome reverse(long accountId, long entryId, String reason, long actorId, String actorLabel) {
        String why = clean(reason);
        Outcome outcome = apply(accountId, () -> reverseIn(accountId, entryId, why, actorId));
        log.info("Admin {} reversed points entry {} on account {} ({} points): {}", actorLabel, entryId, accountId,
                outcome.change(), why);
        return outcome;
    }

    private long reverseIn(long accountId, long entryId, String reason, long actorId) {
        PointsLedgerEntity line = ledger.findById(entryId)
                .filter(e -> e.getAccountId() == accountId)
                .orElseThrow(() -> new ApiExceptions.NotFoundException("That line is not on this customer's points."));
        if (line.getReversesId() != null) {
            throw new ApiExceptions.ConflictException("is_a_reversal",
                    "That line is itself a reversal. Use \"Set points balance\" instead.");
        }
        if (ledger.existsByReversesId(entryId)) {
            throw new ApiExceptions.ConflictException("already_reversed", "That line has already been reversed.");
        }
        if (line.getAmount() == 0) {
            throw new ApiExceptions.ConflictException("nothing_to_reverse", "That line moved no points.");
        }
        try {
            ledger.saveAndFlush(PointsLedgerEntity.correction(accountId, -line.getAmount(), reason, actorId, entryId));
        } catch (DataIntegrityViolationException raced) {
            throw new ApiExceptions.ConflictException("already_reversed", "That line has already been reversed.");
        }
        return -line.getAmount();
    }

    // ------------------------------------------------------------------ set the balance

    public Outcome previewSetBalance(long accountId, long target, String reason, long actorId) {
        return dryRun(accountId, () -> setBalanceIn(accountId, target, clean(reason), actorId));
    }

    public Outcome setBalance(long accountId, long target, String reason, long actorId, String actorLabel) {
        String why = clean(reason);
        Outcome outcome = apply(accountId, () -> setBalanceIn(accountId, target, why, actorId));
        log.info("Admin {} set the points balance of account {} to {} ({} points): {}", actorLabel, accountId,
                target, outcome.change(), why);
        return outcome;
    }

    private long setBalanceIn(long accountId, long target, String reason, long actorId) {
        if (target < 0) {
            throw new ApiExceptions.BadRequestException("A points balance cannot be below 0.");
        }
        long difference = target - ledger.balanceOf(accountId);
        if (difference == 0) {
            throw new ApiExceptions.ConflictException("no_change", "The balance is already " + target + ".");
        }
        ledger.saveAndFlush(PointsLedgerEntity.correction(accountId, difference, reason, actorId, null));
        return difference;
    }

    // ------------------------------------------------------------------ helpers

    private Outcome dryRun(long accountId, Supplier<Long> change) {
        return tx.execute(status -> {
            Standing before = standing(accountId);
            long moved = change.get();
            Standing after = standing(accountId);
            status.setRollbackOnly();
            return new Outcome(before, after, moved);
        });
    }

    private Outcome apply(long accountId, Supplier<Long> change) {
        return tx.execute(status -> {
            // One correction at a time per customer: the second waits and sees the first.
            ledger.lockAccount(accountId);
            Standing before = standing(accountId);
            long moved = change.get();
            return new Outcome(before, standing(accountId), moved);
        });
    }

    private Standing standing(long accountId) {
        PointsStatement.Breakdown b = statement.breakdown(accountId);
        long lifetime = loyalty.lifetimePoints(accountId);
        return new Standing(b.available(), b.earned(), lifetime, LoyaltyTier.forLifetimePoints(lifetime).name());
    }

    private static String clean(String reason) {
        String why = reason == null ? "" : reason.trim();
        if (why.isEmpty()) {
            throw new ApiExceptions.BadRequestException("Give a reason: the customer sees it on their history.");
        }
        if (why.length() > MAX_REASON) {
            throw new ApiExceptions.BadRequestException("Keep the reason under " + MAX_REASON + " characters.");
        }
        return why;
    }
}
