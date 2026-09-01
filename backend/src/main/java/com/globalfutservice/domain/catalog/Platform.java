package com.globalfutservice.domain.catalog;

/**
 * Console family. Rates differ per platform because the underlying markets differ —
 * PC coins are scarcer and therefore dearer than console coins.
 */
public enum Platform {
    PC("PC"),
    PLAYSTATION("PlayStation"),
    XBOX("Xbox");

    private final String displayName;

    Platform(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
