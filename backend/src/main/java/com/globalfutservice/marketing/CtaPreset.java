package com.globalfutservice.marketing;

import java.util.Arrays;
import java.util.Optional;

/**
 * The buttons a campaign may carry, and where each one goes.
 *
 * <p>A fixed list rather than two free-text fields, which is what the specification asks
 * for and is right for a reason worth stating: a campaign is written once and sent to
 * every opted-in customer at once. A mistyped URL in that position is not a bug somebody
 * notices in review — it is four hundred people landing on a 404 with no way to recall the
 * mail. Choosing from a list cannot produce a broken link.
 *
 * <p>Paths are relative and resolved against the configured public URL at render time, so
 * these stay correct across environments and a staging campaign cannot link to production.
 */
public enum CtaPreset {
    BUY_COINS("BUY COINS", "/order?service=TRADING_SERVICE"),
    ORDER_NOW("ORDER NOW", "/order"),
    BOOK_BOOSTING("BOOK BOOSTING", "/boosting"),
    BOOK_COACHING("BOOK COACHING", "/coaching"),
    VIEW_REWARDS("VIEW REWARDS", "/rewards");

    private final String text;
    private final String path;

    CtaPreset(String text, String path) {
        this.text = text;
        this.path = path;
    }

    public String text() {
        return text;
    }

    public String path() {
        return path;
    }

    public static Optional<CtaPreset> byName(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(c -> c.name().equalsIgnoreCase(name.trim()))
                .findFirst();
    }
}
