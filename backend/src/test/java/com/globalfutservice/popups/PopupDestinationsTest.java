package com.globalfutservice.popups;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/** A popup button goes to a path on this site or an https link on globalfutservices.com -- nowhere else. */
class PopupDestinationsTest {

    @ParameterizedTest
    @ValueSource(strings = {"/order", "/boosting/checkout?listing=12", "/rewards#tiers", "/", "/help/faq-1",
            "https://globalfutservices.com/order", "https://www.globalfutservices.com/rewards?ref=pop",
            "https://GlobalFutServices.com/", " /order "})
    void allowed(String destination) {
        assertThat(PopupDestinations.problem(destination)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "//evil.example/x", "/\\evil.example", "http://globalfutservices.com/order",
            "https://evil.example/order", "https://globalfutservices.com.evil.example/", "https://evil.example/?q=globalfutservices.com",
            "https://user@globalfutservices.com/", "https://globalfutservices.com:8443/", "https://api.globalfutservices.com/",
            "javascript:alert(1)", "JAVASCRIPT:alert(1)", "data:text/html,hi", "order", "mailto:a@globalfutservices.com",
            "/order page", "/or\nder", "ftp://globalfutservices.com/"})
    void refused(String destination) {
        assertThat(PopupDestinations.problem(destination)).isPresent();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/help", "/coaching/book", "/"})
    void pagePaths(String path) {
        assertThat(PopupDestinations.isPagePath(path)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"help", "//x", "/order?x=1", "https://globalfutservices.com/help", "/a b"})
    void notPagePaths(String path) {
        assertThat(PopupDestinations.isPagePath(path)).isFalse();
    }
}
