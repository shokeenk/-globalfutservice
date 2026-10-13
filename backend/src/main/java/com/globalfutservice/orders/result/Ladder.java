package com.globalfutservice.orders.result;

import com.globalfutservice.domain.money.Currency;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The levels an order could have reached and what each was priced at, as the order's
 * price list stood when it was priced, in its currency.
 *
 * @param target            the level ordered
 * @param orderedPriceMinor the ordered level's list price
 * @param rungs             every level on sale then, lowest first, one per level
 */
public record Ladder(ResultKind kind, Currency currency, int target, long orderedPriceMinor, List<Rung> rungs) {

    /** One tier: its level, its listing and its list price. */
    public record Rung(int level, String variant, String label, long priceMinor) {
    }

    public Ladder {
        rungs = List.copyOf(rungs);
        if (rungs.isEmpty()) {
            throw new IllegalArgumentException("A ladder has at least the ordered rung");
        }
    }

    /** The lowest level on sale: below it, a result is settled by hand. */
    public int lowest() {
        return rungs.get(0).level();
    }

    /**
     * What a level was priced at: its own tier, or -- for a level with no tier of its own --
     * the highest tier below it. Empty below the lowest tier.
     */
    public Optional<Rung> rungFor(int level) {
        Rung found = null;
        for (Rung rung : rungs) {
            if (rung.level() <= level) {
                found = rung;
            }
        }
        return Optional.ofNullable(found);
    }

    /** What an admin can choose: every level from the lowest tier up to the one ordered. */
    public List<Integer> options() {
        List<Integer> out = new ArrayList<>();
        for (int level = lowest(); level <= target; level++) {
            out.add(level);
        }
        return out;
    }
}
