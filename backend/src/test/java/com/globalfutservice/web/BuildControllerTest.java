package com.globalfutservice.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The running commit, as seen from outside: 7 characters of it, or "unknown", and nothing else. */
class BuildControllerTest {

    @Test
    @DisplayName("Render's commit, shortened to 7 characters; anything else is unknown")
    void shortCommit() {
        assertThat(BuildController.shortCommit("5df9afd0c1b2a3948576e1f2d3c4b5a69788f0e1")).isEqualTo("5df9afd");
        assertThat(BuildController.shortCommit("5DF9AFD0C1")).isEqualTo("5df9afd");
        assertThat(BuildController.shortCommit(null)).isEqualTo("unknown");
        assertThat(BuildController.shortCommit("")).isEqualTo("unknown");
        assertThat(BuildController.shortCommit("main; rm -rf /")).isEqualTo("unknown");
    }

    @Test
    @DisplayName("the answer carries the commit only, and is never cached")
    void answer() {
        var response = new BuildController("8d2f92be41014746f65b7b0bc81ae1566c3d9212").build();
        assertThat(response.getBody()).containsOnlyKeys("commit").containsEntry("commit", "8d2f92b");
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
    }
}
