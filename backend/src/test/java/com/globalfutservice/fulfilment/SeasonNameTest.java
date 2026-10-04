package com.globalfutservice.fulfilment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The site names the current game FC 27. This fails if the previous one, FC 26 in any
 * spelling, comes back in any string the backend can send or show: the emails, the Discord,
 * WhatsApp and Telegram messages, what the support chat is told, the admin API's labels.
 *
 * <p>Only string literals are read, through the same scanner as {@link CustomerVocabularyTest},
 * so comments are left out. So is {@code AppProperties}: GFS_SEASON's default is the key prices
 * are looked up by, it stays FC26, and it never reaches a customer.
 */
class SeasonNameTest {

    /** FC26, FC 26, FC-26, FC™ 26 -- but not FC 260 or FC 2026. Capital FC, as visible text writes it. */
    static final Pattern PREVIOUS_SEASON = Pattern.compile("FC\\s*(?:™\\s*)?-?\\s*26(?!\\d)");

    private static final Path MAIN = Path.of("src", "main", "java", "com", "globalfutservice");
    private static final Path SETTINGS = MAIN.resolve("config").resolve("AppProperties.java");

    @Test
    @DisplayName("no string the backend sends or shows names FC 26")
    void noPreviousSeason() throws IOException {
        List<Path> files;
        try (Stream<Path> walk = Files.walk(MAIN)) {
            files = walk.filter(p -> p.toString().endsWith(".java")).filter(p -> !p.equals(SETTINGS)).toList();
        }
        assertThat(files).hasSizeGreaterThan(200);

        List<String> hits = new ArrayList<>();
        for (Path file : files) {
            for (String literal : CustomerVocabularyTest.literals(Files.readString(file))) {
                if (PREVIOUS_SEASON.matcher(literal).find()) hits.add(file.getFileName() + ": " + literal);
            }
        }
        assertThat(hits).as("strings naming the previous season").isEmpty();
    }

    @Test
    @DisplayName("every spelling of FC 26 is caught, and comments, keys and other numbers are not")
    void pattern() {
        for (String written : List.of("FC26", "FC 26", "FC-26", "EA SPORTS FC 26", "FC™ 26", "Buy FC26 coins")) {
            assertThat(PREVIOUS_SEASON.matcher(written).find()).as(written).isTrue();
        }
        for (String other : List.of("FC 27", "FC 260", "FC 2026", "GFS-26-70C4DPWH", "26 wins")) {
            assertThat(PREVIOUS_SEASON.matcher(other).find()).as(other).isFalse();
        }
        assertThat(CustomerVocabularyTest.literals("// EA FC 26 in a comment\nString s = \"FC 27\";"))
                .noneMatch(s -> PREVIOUS_SEASON.matcher(s).find());
    }
}
