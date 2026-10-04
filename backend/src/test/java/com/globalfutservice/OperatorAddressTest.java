package com.globalfutservice;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The operator's street address is not published, at the owner's instruction (October 2026).
 * This fails if any part of it comes back anywhere a customer could read it: the emails, the
 * Discord, WhatsApp and Telegram messages and everything else under {@code src/main}, and on
 * the storefront its pages, components, translations, HTML shell and public files.
 *
 * <p>The parts are held as fingerprints, not text: a test that spelled the address out would
 * publish it again in a public repository. A fingerprint keeps it out of reading and
 * searching; it does not stop someone who already knows what to guess. The storefront's
 * operatorAddress.test.ts carries the same fingerprints and the same hash.
 *
 * <p>Not scanned: database migrations, because one that clears the address from stored rows
 * has to name it; and tests, this one included.
 */
class OperatorAddressTest {

    /** The removed address, part by part: house number, locality, city, state and postcode. */
    static final Set<Long> ADDRESS =
            Set.of(8335211447407610L, 426394199867781L, 4085892071911414L, 4595916220753159L, 3313117660318515L);

    private static final Pattern WORD = Pattern.compile("[a-z]+");
    private static final Pattern INITIALS = Pattern.compile("(?<![a-z])[a-z](?:[\\s.]+[a-z]){3,}(?![a-z])");
    private static final Pattern SIX_DIGITS = Pattern.compile("(?<!\\d)\\d{3}[\\s-]?\\d{3}(?!\\d)");
    private static final Pattern NUMBER_PAIR = Pattern.compile("(?<!\\d)\\d{1,4}\\s*/\\s*\\d{1,4}(?!\\d)");

    private static final Set<String> TEXT = Set.of(
            ".java", ".ts", ".tsx", ".html", ".txt", ".xml", ".json", ".yml", ".yaml", ".properties", ".sql", ".md");

    @Test
    @DisplayName("no part of the operator's address is anywhere a customer could read it")
    void addressIsGone() throws IOException {
        List<Path> files = new ArrayList<>();
        files.addAll(textFiles(Path.of("src", "main")));
        files.addAll(textFiles(Path.of("..", "frontend", "src")));
        files.addAll(textFiles(Path.of("..", "frontend", "public")));
        files.add(Path.of("..", "frontend", "index.html"));
        assertThat(files).as("the scan found the sources").hasSizeGreaterThan(300);

        List<String> hits = new ArrayList<>();
        for (Path file : files) {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                if (carries(lines.get(i), ADDRESS)) hits.add(file + ":" + (i + 1));
            }
        }
        assertThat(hits).as("lines carrying part of the operator's address").isEmpty();
    }

    @Test
    @DisplayName("the operator's name, Discord and email are not mistaken for it")
    void keptDetails() {
        for (String kept : List.of("Vinay Kumar Sharma", "Global FUT Services", "globalfutservices",
                "globalfutservices@gmail.com")) {
            assertThat(carries(kept, ADDRESS)).as(kept).isFalse();
        }
    }

    @Test
    @DisplayName("the matching works, shown on made-up parts rather than the real ones")
    void matching() {
        assertThat(cyrb53("abcd")).as("the same hash as the storefront's test").isEqualTo(3842662445558733L);
        Set<Long> example = Stream.of("abcd", "london", "560001", "7/12")
                .map(OperatorAddressTest::cyrb53).collect(Collectors.toSet());
        for (String written : List.of("ABCD", "A.B.C.D.", "a b c d", "LONDON", "Londoner", "greaterlondon", "560001",
                "560 001", "560-001", "7/12", "7 / 12")) {
            assertThat(carries(written, example)).as(written).isTrue();
        }
        for (String other : List.of("lond on", "17/120", "7/123", "15600012", "5600011")) {
            assertThat(carries(other, example)).as(other).isFalse();
        }
    }

    /**
     * Whether a line carries one of the fingerprinted parts, in any of the ways it could be
     * written: three to ten letters anywhere inside a word, initials spelled out with dots or
     * spaces, a six-digit number with or without a space or hyphen in the middle, or a
     * number/number pair, in any case and spacing.
     */
    static boolean carries(String line, Set<Long> parts) {
        String lower = line.toLowerCase(Locale.ROOT);
        Matcher word = WORD.matcher(lower);
        while (word.find()) {
            for (int length = 3; length <= 10; length++) {
                for (int i = word.start(); i + length <= word.end(); i++) {
                    if (parts.contains(cyrb53(lower, i, i + length))) return true;
                }
            }
        }
        List<String> normalised = new ArrayList<>();
        for (Matcher m = INITIALS.matcher(lower); m.find(); ) normalised.add(m.group().replaceAll("[^a-z]", ""));
        for (Matcher m = SIX_DIGITS.matcher(lower); m.find(); ) normalised.add(m.group().replaceAll("\\D", ""));
        for (Matcher m = NUMBER_PAIR.matcher(lower); m.find(); ) normalised.add(m.group().replaceAll("\\s", ""));
        return normalised.stream().anyMatch(text -> parts.contains(cyrb53(text)));
    }

    static long cyrb53(String text) {
        return cyrb53(text, 0, text.length());
    }

    /** cyrb53 (public domain): a small 53-bit string hash, over {@code text[from, to)}. */
    static long cyrb53(String text, int from, int to) {
        int h1 = 0xdeadbeef;
        int h2 = 0x41c6ce57;
        for (int i = from; i < to; i++) {
            int ch = text.charAt(i);
            h1 = (h1 ^ ch) * -1640531535;   // 2654435761
            h2 = (h2 ^ ch) * 1597334677;
        }
        h1 = (h1 ^ (h1 >>> 16)) * -2048144789;       // 2246822507
        h1 ^= (h2 ^ (h2 >>> 13)) * -1028477387;      // 3266489909
        h2 = (h2 ^ (h2 >>> 16)) * -2048144789;
        h2 ^= (h1 ^ (h1 >>> 13)) * -1028477387;
        return 4294967296L * (2097151 & h2) + (h1 & 0xFFFFFFFFL);
    }

    private static List<Path> textFiles(Path root) throws IOException {
        Path migrations = Path.of("src", "main", "resources", "db", "migration");
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> !p.startsWith(migrations))
                    .filter(p -> !p.getFileName().toString().matches(".*\\.test\\.tsx?$"))
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        int dot = name.lastIndexOf('.');
                        return dot >= 0 && TEXT.contains(name.substring(dot));
                    })
                    .toList();
        }
    }
}
