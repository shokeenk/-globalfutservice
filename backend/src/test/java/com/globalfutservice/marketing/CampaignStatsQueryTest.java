package com.globalfutservice.marketing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That the stats query can survive a campaign nobody has been sent.
 *
 * <p>An aggregate query with no {@code group by} returns one row even when nothing
 * matches, and in that row every {@code sum} is NULL — the sum of no values is unknown,
 * not zero. {@link CampaignStats} takes primitive {@code long}s, so a NULL reaches
 * Hibernate's instantiation and fails there, with an unboxing error that names no column.
 * Creating a campaign returned 500 for exactly this reason, after the row had already been
 * committed.
 *
 * <p>Asserting on the query text is a poor substitute for executing it, and it is here
 * because there is no substitute available: this project has no test database, so
 * {@code statsFor} cannot be run in a test at all. Every sum being wrapped is the one
 * property that made it safe, and an unwrapped one added later would otherwise reach
 * production the same way the first four did.
 */
class CampaignStatsQueryTest {

    @Test
    @DisplayName("every sum in the stats query is wrapped in coalesce")
    void everySumIsCoalesced() throws NoSuchMethodException {
        Method statsFor = CampaignRecipientRepository.class
                .getMethod("statsFor", Long.class);
        String jpql = statsFor.getAnnotation(Query.class).value();

        // Counted rather than pattern-matched on the whole string, so reformatting the
        // query does not fail the test while adding an unguarded sum does.
        int sums = countOf(jpql, "sum(");
        int guarded = countOf(jpql, "coalesce(sum(");

        assertThat(sums)
                .as("the query should still be summing something")
                .isGreaterThan(0);
        assertThat(guarded)
                .as("every sum must be coalesced, or a campaign with no recipients "
                        + "returns NULL into a primitive long and the endpoint 500s")
                .isEqualTo(sums);
    }

    @Test
    @DisplayName("the projected constructor is the one the query targets")
    void constructorArityMatchesProjection() throws NoSuchMethodException {
        Method statsFor = CampaignRecipientRepository.class
                .getMethod("statsFor", Long.class);
        String jpql = statsFor.getAnnotation(Query.class).value();

        // count(r) plus the four sums. If somebody adds a sixth expression without
        // adding a constructor to take it, the query fails at startup rather than here
        // -- but this keeps the two facts written down next to each other.
        assertThat(countOf(jpql, "sum(") + countOf(jpql, "count(")).isEqualTo(5);
        assertThat(CampaignStats.class.getConstructor(
                long.class, long.class, long.class, long.class, long.class)).isNotNull();
    }

    private static int countOf(String haystack, String needle) {
        int count = 0;
        for (int i = haystack.indexOf(needle); i >= 0;
             i = haystack.indexOf(needle, i + needle.length())) {
            count++;
        }
        return count;
    }
}
