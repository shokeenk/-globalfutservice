package com.globalfutservice.payments.payop;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * English country names, as Payop's pricing sheet writes them, to ISO 3166-1 alpha-2 codes.
 *
 * <p>Built from Java's own list of ISO countries and their English names, so every country is
 * covered without a hand-written table. The few names the sheet spells differently from Java
 * are aliases here; a name matching neither is refused rather than guessed, and the import
 * names the row.
 */
public final class CountryNames {

    /** Every country: the sheet's "International". */
    public static final String EVERYWHERE = "*";

    /** Names the sheet uses that Java's English names spell differently. */
    private static final Map<String, String> ALIASES = Map.of(
            "czech republic", "CZ",
            "united kingdom of great britain and northern ireland", "GB",
            "united kingdom", "GB",
            "united states of america", "US",
            "turkey", "TR",
            "russian federation", "RU",
            "republic of korea", "KR",
            "viet nam", "VN");

    private static final Map<String, String> BY_NAME = new HashMap<>();

    static {
        for (String iso : Locale.getISOCountries()) {
            BY_NAME.put(normalise(Locale.of("", iso).getDisplayCountry(Locale.ENGLISH)), iso);
        }
        BY_NAME.putAll(ALIASES);
    }

    private CountryNames() {
    }

    /** The ISO code, {@link #EVERYWHERE} for "International", or empty when unknown. */
    public static Optional<String> iso(String englishName) {
        if (englishName == null || englishName.isBlank()) {
            return Optional.empty();
        }
        String name = normalise(englishName);
        if (name.equals("international")) {
            return Optional.of(EVERYWHERE);
        }
        return Optional.ofNullable(BY_NAME.get(name));
    }

    private static String normalise(String s) {
        return s.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
