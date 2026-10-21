package com.globalfutservice.popups;

import java.util.List;

/** The vocabulary of a popup campaign. See V59. */
public final class PopupTypes {

    private PopupTypes() {
    }

    /** A centred modal over a dimmed page, or a compact card in the bottom-right corner. */
    public enum Type { CENTER_MODAL, BOTTOM_RIGHT }

    /** When it appears: as the page loads, after N seconds, after N% scroll, or at a named page step. */
    public enum Trigger { PAGE_LOAD, DELAY, SCROLL, STEP }

    /** How often one browser sees it: once per session, once every N days, or every visit. */
    public enum Frequency { SESSION, DAYS, EVERY_VISIT }

    public enum Status { DRAFT, PUBLISHED }

    /**
     * The storefront pages a popup can target, besides "selected pages" by path. The
     * storefront says which one it is on; the server only filters by it.
     */
    public enum Page { HOME, COINS, COINS_CHECKOUT, CHAMPS_LISTING, CHAMPS_CHECKOUT, REWARDS }

    /** One language's words. Only the headline is required, and only in English. */
    public record Copy(String headline, String body, String referral, String trust) {
    }

    /** English, plus optional Spanish and French that fall back to English where empty. */
    public record LocalizedCopy(Copy en, Copy es, Copy fr) {
    }

    /** A button label in each language; English required. */
    public record Label(String en, String es, String fr) {
    }

    /** A button: its label, and a path on this site or an https link on globalfutservices.com. */
    public record Button(Label label, String destination) {
    }

    /** At most this many buttons. */
    public static final int MAX_BUTTONS = 2;

    /** The page steps the storefront fires today; any name of the same shape is accepted. */
    public static final List<String> KNOWN_STEPS = List.of("coins:continue", "champs:checkout");
}
