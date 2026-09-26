package com.globalfutservice.marketing;

/**
 * What a campaign is about.
 *
 * <p><b>Not who it goes to.</b> That is {@link CampaignAudience}, and the two are kept
 * apart on purpose. A coins sale may reasonably go to everyone who opted in, not only to
 * people who have bought coins before; tying the type to the audience would quietly
 * shrink every audience to past buyers of the thing on sale, and "General" would have no
 * audience at all. The type decides content — where the button goes — and nothing about
 * recipients.
 *
 * <p>The button destination is one of the existing {@link CtaPreset} paths, never free
 * text, for the same reason the presets exist: a campaign goes to every opted-in customer
 * at once and a mistyped URL cannot be recalled.
 */
public enum CampaignType {
    COINS(CtaPreset.BUY_COINS),
    BOOSTING(CtaPreset.BOOK_BOOSTING),
    COACHING(CtaPreset.BOOK_COACHING),
    GENERAL(CtaPreset.ORDER_NOW);

    /** The button label every type uses, as the reference design draws it. */
    public static final String BUTTON_TEXT = "ORDER NOW";

    private final CtaPreset destination;

    CampaignType(CtaPreset destination) {
        this.destination = destination;
    }

    /** Where this type's "ORDER NOW" button goes. */
    public String buttonPath() {
        return destination.path();
    }
}
