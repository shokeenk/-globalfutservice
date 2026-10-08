package com.globalfutservice.domain.catalog;

import java.util.List;

/**
 * A coin price structure: the platforms that share one set of coin prices.
 *
 * <p>PC has its own market and prices. PlayStation and Xbox share one -- as FUT Transfer
 * does, whose PS market includes Xbox. The customer still picks their own platform, and
 * the order carries it: the partner needs to know which console the coins go to, even
 * though both pay the same.
 */
public enum CoinMarket {

    PC("PC", List.of(Platform.PC)),
    CONSOLE("PlayStation & Xbox", List.of(Platform.PLAYSTATION, Platform.XBOX));

    private final String displayName;
    private final List<Platform> platforms;

    CoinMarket(String displayName, List<Platform> platforms) {
        this.displayName = displayName;
        this.platforms = platforms;
    }

    public String displayName() {
        return displayName;
    }

    public List<Platform> platforms() {
        return platforms;
    }

    /** Which structure prices coins for {@code platform}. */
    public static CoinMarket of(Platform platform) {
        for (CoinMarket market : values()) {
            if (market.platforms.contains(platform)) {
                return market;
            }
        }
        throw new IllegalArgumentException("No coin market for " + platform);
    }
}
