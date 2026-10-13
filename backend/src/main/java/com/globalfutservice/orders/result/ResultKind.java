package com.globalfutservice.orders.result;

import com.globalfutservice.domain.catalog.Sku;

import java.util.Optional;

/**
 * What kind of result an order is measured by.
 *
 * <p>One today: the wins of a Champions order. The settlement, the cards, the emails and
 * the default job are written against a kind, a target and an achieved level -- never
 * against "wins" -- so another service adds its kind, its {@link ResultLadder} and its
 * labels, and nothing else.
 */
public enum ResultKind {

    /** FUT Champions: the level is a number of wins. */
    CHAMPS_WINS(Sku.BOOST_CHAMPS);

    private final Sku sku;

    ResultKind(Sku sku) {
        this.sku = sku;
    }

    public Sku sku() {
        return sku;
    }

    public static Optional<ResultKind> forSku(Sku sku) {
        for (ResultKind kind : values()) {
            if (kind.sku == sku) {
                return Optional.of(kind);
            }
        }
        return Optional.empty();
    }
}
