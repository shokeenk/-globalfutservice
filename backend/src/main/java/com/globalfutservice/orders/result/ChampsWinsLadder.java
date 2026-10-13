package com.globalfutservice.orders.result;

import com.globalfutservice.catalog.RateCardEntity;
import com.globalfutservice.catalog.RateCardRepository;
import com.globalfutservice.orders.OrderEntity;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Champions ladder: the Champs Wins tiers on sale when the order was priced, each
 * placed by its number of wins.
 *
 * <p>A tier's wins are read from its code (WINS_15, or 16_WINS_ELITE_I for one added on
 * Service Listings) or else from its name ("15 wins · Elite I"). A tier with neither --
 * "+8 extra wins" -- is not a level and is left off. When two tiers on sale have the same
 * wins, the one the order was placed for is used, otherwise the first in listing order.
 */
@Component
public class ChampsWinsLadder implements ResultLadder {

    private static final Pattern SEEDED = Pattern.compile("^WINS_(\\d{1,3})$");
    private static final Pattern ADDED = Pattern.compile("^(\\d{1,3})_WINS(?:_.*)?$");
    private static final Pattern NAMED = Pattern.compile("^\\s*(\\d{1,3})\\s+wins?\\b", Pattern.CASE_INSENSITIVE);

    private final RateCardRepository rates;

    public ChampsWinsLadder(RateCardRepository rates) {
        this.rates = rates;
    }

    @Override
    public ResultKind kind() {
        return ResultKind.CHAMPS_WINS;
    }

    @Override
    public Optional<Ladder> forOrder(OrderEntity order, Instant pricedAt) {
        if (order.getVariant() == null) {
            return Optional.empty();
        }
        List<RateCardEntity> onSale = rates.findOnSaleAt(order.getSeason(), order.getSku(), order.getCurrency(),
                pricedAt);
        Map<Integer, Ladder.Rung> byLevel = new TreeMap<>();
        Ladder.Rung ordered = null;
        for (RateCardEntity row : onSale) {
            Integer wins = winsOf(row.getVariant(), row.getLabel());
            if (wins == null) {
                continue;
            }
            Ladder.Rung rung = new Ladder.Rung(wins, row.getVariant(), row.getLabel(), row.getUnitPriceMinor());
            if (row.getVariant().equals(order.getVariant())) {
                ordered = rung;
                byLevel.put(wins, rung);
            } else {
                byLevel.putIfAbsent(wins, rung);
            }
        }
        if (ordered == null) {
            return Optional.empty();
        }
        int target = ordered.level();
        List<Ladder.Rung> rungs = new ArrayList<>();
        for (Ladder.Rung rung : byLevel.values()) {
            if (rung.level() <= target) {
                rungs.add(rung);
            }
        }
        return Optional.of(new Ladder(kind(), order.getCurrency(), target, ordered.priceMinor(), rungs));
    }

    /** The number of wins a Champs tier is for, or null when it is not a number of wins. */
    static Integer winsOf(String variant, String label) {
        if (variant != null) {
            String code = variant.trim().toUpperCase(Locale.ROOT);
            Matcher m = SEEDED.matcher(code);
            if (m.matches()) {
                return Integer.valueOf(m.group(1));
            }
            m = ADDED.matcher(code);
            if (m.matches()) {
                return Integer.valueOf(m.group(1));
            }
        }
        if (label != null) {
            Matcher m = NAMED.matcher(label);
            if (m.find()) {
                return Integer.valueOf(m.group(1));
            }
        }
        return null;
    }
}
