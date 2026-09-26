package com.globalfutservice.admin;

import com.globalfutservice.marketing.CampaignType;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The limits on the campaign builder's first step, which the page's character counters
 * read out against. A counter that says 100 and a server that accepts 200 — or refuses at
 * 90 — is a counter nobody can trust.
 */
class CampaignDetailsRequestTest {

    private static final Validator VALIDATOR =
            Validation.buildDefaultValidatorFactory().getValidator();

    private static AdminCampaignController.DetailsRequest request(
            String title, String subject, CampaignType type, String promoTitle,
            String offer, String code, String description) {
        return new AdminCampaignController.DetailsRequest(
                title, subject, type, promoTitle, offer, code, null, description,
                true, true, true);
    }

    private static AdminCampaignController.DetailsRequest valid() {
        return request("TOTY Special Offer", "TOTY is here", CampaignType.COINS,
                "TOTY COINS SALE", "15% OFF", "HUNTER10", "Team of the Year is here.");
    }

    private static Set<String> failing(AdminCampaignController.DetailsRequest r) {
        return VALIDATOR.validate(r).stream()
                .map(ConstraintViolation::getPropertyPath)
                .map(Object::toString)
                .collect(Collectors.toSet());
    }

    @Test
    @DisplayName("a complete step passes")
    void validPasses() {
        assertThat(failing(valid())).isEmpty();
    }

    @Test
    @DisplayName("the subject stops at exactly 100 characters")
    void subjectLimit() {
        var at = request("n", "s".repeat(100), CampaignType.COINS, "t", null, null, "d");
        var over = request("n", "s".repeat(101), CampaignType.COINS, "t", null, null, "d");

        assertThat(failing(at)).isEmpty();
        assertThat(failing(over)).containsExactly("subject");
    }

    @Test
    @DisplayName("the description stops at exactly 500 characters")
    void descriptionLimit() {
        var at = request("n", "s", CampaignType.COINS, "t", null, null, "d".repeat(500));
        var over = request("n", "s", CampaignType.COINS, "t", null, null, "d".repeat(501));

        assertThat(failing(at)).isEmpty();
        assertThat(failing(over)).containsExactly("description");
    }

    @Test
    @DisplayName("the four starred fields and the type are required")
    void requiredFields() {
        var empty = request(" ", "", null, " ", null, null, "");

        assertThat(failing(empty))
                .containsExactlyInAnyOrder("title", "subject", "type", "promoTitle", "description");
    }

    @Test
    @DisplayName("offer, code and date are optional")
    void optionalFields() {
        var bare = request("n", "s", CampaignType.GENERAL, "t", null, null, "d");

        assertThat(failing(bare)).isEmpty();
    }

    @Test
    @DisplayName("offer and code are capped at 40")
    void shortFieldLimits() {
        var over = request("n", "s", CampaignType.COINS, "t", "o".repeat(41), "c".repeat(41), "d");

        assertThat(failing(over)).containsExactlyInAnyOrder("offerText", "promoCode");
    }
}
