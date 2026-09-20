package com.globalfutservice.marketing;

/**
 * Who a campaign goes to.
 *
 * <p>A deliberately short list. The specification left the segments open — "define what
 * segmentation options actually make sense given the existing customer data model" — and
 * what the model actually supports is consent plus what somebody has bought. Anything
 * finer (spend bands, recency, lapsed buyers) would be inventing a segmentation strategy
 * nobody has asked for, and every segment added here is one more audience query to keep
 * correct.
 *
 * <p><b>Every option is intersected with marketing consent.</b> There is no "everyone"
 * here and there must never be one: an audience that can be expressed without consent is
 * an audience somebody will eventually select by accident.
 */
public enum CampaignAudience {
    ALL_OPTED_IN("Everyone opted in"),
    COINS_BUYERS("Bought coins before"),
    BOOSTING_BUYERS("Bought boosting before"),
    COACHING_BUYERS("Bought coaching before");

    private final String label;

    CampaignAudience(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** The SKUs that qualify somebody for this segment, or empty for consent alone. */
    public java.util.List<String> skus() {
        return switch (this) {
            case ALL_OPTED_IN -> java.util.List.of();
            case COINS_BUYERS -> java.util.List.of("TRADING_SERVICE");
            case BOOSTING_BUYERS -> java.util.List.of("BOOST_CHAMPS", "BOOST_RIVALS");
            case COACHING_BUYERS -> java.util.List.of("COACHING");
        };
    }
}
