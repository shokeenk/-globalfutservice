package com.globalfutservice.admin;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import com.globalfutservice.catalog.RateCardRepository;
import com.globalfutservice.config.AppProperties;
import com.globalfutservice.identity.AccountRole;
import com.globalfutservice.security.AccountPrincipal;
import com.globalfutservice.web.ApiExceptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * Coin prices are set on the coin pricing page and nowhere else. The rate card's endpoints
 * no longer touch coins: the old Coin rates endpoints, which wrote PC's price into both
 * structures, are gone, and the general one refuses a coin row nothing would read.
 */
class AdminRateCardCoinRowsTest {

    private final AdminRateCardController controller = new AdminRateCardController(mock(RateCardRepository.class),
            mock(AppProperties.class));

    @Test
    @DisplayName("the old Coin rates endpoints are gone: nothing can write PC's price into PlayStation + Xbox")
    void oldEndpointsGone() {
        List<String> paths = Arrays.stream(AdminRateCardController.class.getDeclaredMethods())
                .flatMap(AdminRateCardCoinRowsTest::paths).toList();
        assertThat(paths).noneMatch(p -> p.contains("coin"));
    }

    @Test
    @DisplayName("the general rate endpoint refuses a coin row: nothing would read it")
    void noCoinRows() {
        AccountPrincipal admin = new AccountPrincipal(7L, "acc_owner", "owner@example.test", AccountRole.ADMIN);
        assertThatThrownBy(() -> controller.update(new AdminRateCardController.UpdateRateRequest("TRADING_SERVICE",
                "PC", null, "INR", 1_300_000, null, null, null, null, null), admin))
                .isInstanceOfSatisfying(ApiExceptions.BadRequestException.class,
                        e -> assertThat(e.getMessage()).contains("coin pricing page"));
    }

    private static java.util.stream.Stream<String> paths(Method m) {
        GetMapping get = m.getAnnotation(GetMapping.class);
        PostMapping post = m.getAnnotation(PostMapping.class);
        return java.util.stream.Stream.concat(
                get == null ? java.util.stream.Stream.empty() : Arrays.stream(get.value()),
                post == null ? java.util.stream.Stream.empty() : Arrays.stream(post.value()));
    }
}
