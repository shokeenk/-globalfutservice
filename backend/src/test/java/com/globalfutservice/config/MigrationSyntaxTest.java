package com.globalfutservice.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Migrations have to parse, and nothing else here checks that.
 *
 * <p>There is no test database, so the suite never executes a migration. That gap has a
 * sharp edge: a migration that cannot be parsed is not caught by anything until the
 * application tries to boot, and the way it fails is quiet. Flyway aborts, Spring never
 * finishes starting, the container fails its health check, and the platform keeps the
 * previous build running — so from outside, a broken migration is indistinguishable from a
 * deployment that simply never arrived. That is exactly how V19 shipped and sat unnoticed.
 *
 * <p><b>Postgres nests block comments.</b> Unlike C, a {@code /*} inside a block comment
 * opens another one, and the first closing delimiter closes only the inner. V19 explained
 * why the content-type list is not a wildcard, spelled that wildcard out, and thereby
 * opened a nested comment it never closed. The migration was unterminated from that point
 * to the end of the file.
 */
class MigrationSyntaxTest {

    private static final Path MIGRATIONS = Path.of("src/main/resources/db/migration");

    @Test
    @DisplayName("every migration's block comments are balanced, counting nesting")
    void blockCommentsAreBalanced() throws IOException {
        List<String> broken = new ArrayList<>();

        for (Path file : sqlFiles()) {
            String sql = Files.readString(file, StandardCharsets.UTF_8);
            int depth = 0;

            for (int i = 0; i < sql.length() - 1; ) {
                String pair = sql.substring(i, i + 2);
                if ("/*".equals(pair)) {
                    depth++;
                    i += 2;
                } else if ("*/".equals(pair)) {
                    depth--;
                    i += 2;
                } else {
                    i++;
                }
            }

            if (depth != 0) {
                broken.add(file.getFileName() + " (comment depth ends at " + depth + ")");
            }
        }

        if (!broken.isEmpty()) {
            throw new AssertionError(
                    "Unbalanced block comment, which Postgres refuses to parse and which "
                            + "stops the application booting. Usually a '/*' sequence inside "
                            + "prose — a media type or a path — opening a nested comment. Use "
                            + "line comments for anything containing a slash-star: " + broken);
        }
    }

    @Test
    @DisplayName("the scan finds migrations, so a green run means something")
    void scanFindsMigrations() throws IOException {
        // A guard that silently examines nothing is not a guard. If the resources move,
        // this fails rather than passing over an empty list forever.
        if (sqlFiles().size() < 10) {
            throw new AssertionError("Expected to find the migration files under " + MIGRATIONS
                    + " but found " + sqlFiles().size());
        }
    }

    private static List<Path> sqlFiles() throws IOException {
        try (var paths = Files.list(MIGRATIONS)) {
            return paths.filter(p -> p.getFileName().toString().endsWith(".sql")).toList();
        }
    }
}
