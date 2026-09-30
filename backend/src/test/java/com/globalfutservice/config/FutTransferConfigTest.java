package com.globalfutservice.config;

import java.time.Duration;
import java.util.List;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The FUT Transfer settings are checked when the application starts, not when an order is sent. */
class FutTransferConfigTest {

    private static final String KEY = "raw-key-never-printed-0001";
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    private static AppProperties.FutTransfer config(String baseUrl, String backup, String method, int riskLevel) {
        return new AppProperties.FutTransfer(true, baseUrl, "api@example.test", KEY, method, riskLevel,
                new AppProperties.FutTransferPolling(Duration.ofSeconds(60), Duration.ofSeconds(10),
                        Duration.ofMinutes(15), 3, Duration.ofHours(6), Duration.ofMinutes(2),
                        Duration.ofMinutes(10)),
                Duration.ofSeconds(15), 3, List.of("InvalidPassword"), backup,
                new AppProperties.FutTransferOrder(300, 1, 50, 0, "1", 0, "0", "0", 0, "-1", "-1"), true,
                Duration.ofHours(72));
    }

    @Test
    @DisplayName("both domains must be HTTPS; plain HTTP only to this machine")
    void https() {
        assertThat(config("https://futtransfer.top/", "https://eatransfer.top/", "snipe", 2).baseUrl())
                .isEqualTo("https://futtransfer.top");
        assertThat(config("http://127.0.0.1:18091", "https://eatransfer.top", "snipe", 2).baseUrl())
                .isEqualTo("http://127.0.0.1:18091");

        assertThatThrownBy(() -> config("http://futtransfer.top", "https://eatransfer.top", "snipe", 2))
                .hasMessageContaining("base-url must be an https:// URL");
        assertThatThrownBy(() -> config("https://futtransfer.top", "http://eatransfer.top", "snipe", 2))
                .hasMessageContaining("backup-base-url must be an https:// URL");
        assertThatThrownBy(() -> config("https://futtransfer.top/orderAPI", "https://eatransfer.top", "snipe", 2))
                .hasMessageContaining("no path");
    }

    @Test
    @DisplayName("the risk level must be 1-6 and the method one the vendor documents")
    void ranges() {
        assertThat(validator.validate(config("https://futtransfer.top", "https://eatransfer.top", "targetedSnipe", 1))).isEmpty();
        assertThat(validator.validate(config("https://futtransfer.top", "https://eatransfer.top", "snipe", 6))).isEmpty();
        assertThat(validator.validate(config("https://futtransfer.top", "https://eatransfer.top", "snipe", 0))).hasSize(1);
        assertThat(validator.validate(config("https://futtransfer.top", "https://eatransfer.top", "snipe", 7))).hasSize(1);
        assertThat(validator.validate(config("https://futtransfer.top", "https://eatransfer.top", "banMode", 2))).hasSize(1);
    }

    @Test
    @DisplayName("the Method 3.0 settings stay inside the vendor's ranges")
    void methodRanges() {
        var ok = new AppProperties.FutTransferOrder(300, 1, 50, 0, "1", 0, "0", "0", 0, "-1", "-1");
        assertThat(validator.validate(ok)).isEmpty();
        assertThat(validator.validate(new AppProperties.FutTransferOrder(10000, 1, 50, 0, "1", 0, "0", "0", 0, "-1", "-1")))
                .hasSize(1);
        assertThat(validator.validate(new AppProperties.FutTransferOrder(300, 2, 50, 0, "1", 0, "0", "0", 0, "-1", "-1")))
                .hasSize(1);
        assertThat(validator.validate(new AppProperties.FutTransferOrder(300, 1, 50, 0, "yes", 0, "0", "0", 0, "-1", "-1")))
                .hasSize(1);
        assertThat(validator.validate(new AppProperties.FutTransferOrder(300, 1, 50, 0, "1", 0, "0", "0", 0, "all groups", "-1")))
                .hasSize(1);
    }

    @Test
    @DisplayName("printing the settings never prints the key")
    void redacted() {
        String printed = config("https://futtransfer.top", "https://eatransfer.top", "snipe", 2).toString();
        assertThat(printed).doesNotContain(KEY).contains("apiKey=[redacted]");
    }
}
