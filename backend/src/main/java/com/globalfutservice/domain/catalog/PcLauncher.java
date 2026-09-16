package com.globalfutservice.domain.catalog;

/**
 * How a PC account signs in to FC.
 *
 * <p>Boosting only, and only when the platform is {@link Platform#PC}. The same EA
 * account reached through Steam, the EA app or Epic is a different sign-in each time --
 * a booster handed the wrong one cannot get in, and finding that out costs the slot they
 * had set aside. Console accounts have no equivalent question, which is why this is
 * nullable rather than a fourth platform.
 *
 * <p>Stored as the enum name in {@code orders.pc_launcher}, with a CHECK constraint in
 * V22 repeating the three values: the database refuses a row this enum would not accept.
 */
public enum PcLauncher {
    STEAM("Steam"),
    EA_APP("EA app"),
    EPIC("Epic Games");

    private final String displayName;

    PcLauncher(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
