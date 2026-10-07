package com.globalfutservice.config;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

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
                Duration.ofHours(72), AppProperties.FutTransferOrderMode.PUBLIC_POOL,
                new AppProperties.FutTransferPublicPool(AppProperties.BuyNowThresholdMode.ORDER_AMOUNT, null, false,
                        null), AppProperties.FutTransferAutoDispatch.OFF, null);
    }

    private static AppProperties.FutTransferPublicPool pool(String threshold, boolean sendMaxPrice, String maxPrice) {
        return new AppProperties.FutTransferPublicPool(AppProperties.BuyNowThresholdMode.FIXED,
                threshold == null ? null : new BigDecimal(threshold), sendMaxPrice,
                maxPrice == null ? null : new BigDecimal(maxPrice));
    }

    @Test
    @DisplayName("public-pool numbers must be above zero when given; missing is allowed and refused at Approve")
    void publicPoolRanges() {
        assertThat(validator.validate(pool("8.5", true, "9.25"))).isEmpty();
        assertThat(validator.validate(pool(null, true, null))).isEmpty();
        assertThat(validator.validate(pool("0", false, null))).hasSize(1);
        assertThat(validator.validate(pool("-1", false, null))).hasSize(1);
        assertThat(validator.validate(pool(null, true, "0"))).hasSize(1);
    }

    @Test
    @DisplayName("the defaults bind as documented: public pool, order amount, no maxPrice, and empty means unset")
    void publicPoolBinding() {
        // What application.yml hands over when none of the new variables is set.
        var source = new MapConfigurationPropertySource(Map.of(
                "p.buy-now-threshold-mode", "ORDER_AMOUNT",
                "p.buy-now-threshold", "",
                "p.send-max-price", "false",
                "p.max-price", ""));
        AppProperties.FutTransferPublicPool bound = new Binder(source)
                .bind("p", AppProperties.FutTransferPublicPool.class).get();
        assertThat(bound.buyNowThresholdMode()).isEqualTo(AppProperties.BuyNowThresholdMode.ORDER_AMOUNT);
        assertThat(bound.buyNowThreshold()).isNull();
        assertThat(bound.sendMaxPrice()).isFalse();
        assertThat(bound.maxPrice()).isNull();

        var fixed = new MapConfigurationPropertySource(Map.of(
                "p.buy-now-threshold-mode", "FIXED", "p.buy-now-threshold", "8.63"));
        assertThat(new Binder(fixed).bind("p", AppProperties.FutTransferPublicPool.class).get().buyNowThreshold())
                .isEqualByComparingTo("8.63");

        var mode = new MapConfigurationPropertySource(Map.of("m", "OWN_SENDERS"));
        assertThat(new Binder(mode).bind("m", AppProperties.FutTransferOrderMode.class).get())
                .isEqualTo(AppProperties.FutTransferOrderMode.OWN_SENDERS);
    }

    @Test
    @DisplayName("automatic sending binds off by default, and an empty limit means every paid coin order, whatever its size")
    void autoDispatchDefaults() {
        // What application.yml hands over when none of the variables is set.
        var source = new MapConfigurationPropertySource(Map.of(
                "a.enabled", "false", "a.max-k", "", "a.every", "PT15S"));
        AppProperties.FutTransferAutoDispatch auto = new Binder(source)
                .bind("a", AppProperties.FutTransferAutoDispatch.class).get();
        assertThat(auto.enabled()).isFalse();
        assertThat(auto.maxK()).isNull();
        assertThat(auto.allows(1)).isTrue();
        assertThat(auto.allows(100_000)).isTrue();

        // GFS_FUTTRANSFER_AUTO_DISPATCH_MAX_K still works when it is set.
        var capped = new MapConfigurationPropertySource(Map.of("a.enabled", "true", "a.max-k", "2000"));
        AppProperties.FutTransferAutoDispatch limited = new Binder(capped)
                .bind("a", AppProperties.FutTransferAutoDispatch.class).get();
        assertThat(limited.allows(2000)).isTrue();
        assertThat(limited.allows(2001)).isFalse();
    }

    @Test
    @DisplayName("FUT Transfer's progress page: unset by default; set, an https:// address with the order's id put in")
    void progressPage() {
        AppProperties.FutTransfer unset = config("https://futtransfer.top", "https://eatransfer.top", "targetedSnipe", 1);
        assertThat(unset.progressPageUrl()).isNull();
        assertThat(unset.progressPageFor("3f2a-77")).isEmpty();
        assertThat(withPage("  ").progressPageUrl()).isNull();

        AppProperties.FutTransfer set = withPage("https://futtransfer.top/progress.php?id={orderId}");
        assertThat(set.progressPageFor("3f2a-77")).hasValue("https://futtransfer.top/progress.php?id=3f2a-77");
        assertThat(set.progressPageFor("a b&c")).hasValue("https://futtransfer.top/progress.php?id=a%20b%26c");
        assertThat(set.progressPageFor(null)).isEmpty();

        assertThatThrownBy(() -> withPage("http://futtransfer.top/progress.php?id={orderId}"))
                .hasMessageContaining("progress-page-url must be an https:// address");
        assertThatThrownBy(() -> withPage("https://futtransfer.top/progress.php"))
                .hasMessageContaining("{orderId}");
    }

    private static AppProperties.FutTransfer withPage(String address) {
        AppProperties.FutTransfer f = config("https://futtransfer.top", "https://eatransfer.top", "targetedSnipe", 1);
        return new AppProperties.FutTransfer(f.enabled(), f.baseUrl(), f.apiUser(), f.apiKey(), f.transferMethod(),
                f.riskLevel(), f.polling(), f.timeout(), f.maxDispatchAttempts(), f.permanentErrorCodes(),
                f.backupBaseUrl(), f.order(), f.cooldownCheck(), f.reviewCredentialRetention(), f.orderMode(),
                f.publicPool(), f.autoDispatch(), address);
    }

    @Test
    @DisplayName("a mode that is not one of ours stops startup rather than being guessed")
    void unknownModeRefused() {
        var bad = new MapConfigurationPropertySource(Map.of("p.buy-now-threshold-mode", "PRICE"));
        assertThatThrownBy(() -> new Binder(bad).bind("p", AppProperties.FutTransferPublicPool.class))
                .isInstanceOf(BindException.class);
        var badMode = new MapConfigurationPropertySource(Map.of("m", "PUBLIC"));
        assertThatThrownBy(() -> new Binder(badMode).bind("m", AppProperties.FutTransferOrderMode.class))
                .isInstanceOf(BindException.class);
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
