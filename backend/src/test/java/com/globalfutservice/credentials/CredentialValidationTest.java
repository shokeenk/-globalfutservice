package com.globalfutservice.credentials;

import com.globalfutservice.credentials.web.CredentialDtos;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the checkout will accept as an EA sign-in.
 *
 * <p>The interesting constraint is the password minimum, and it is not ours: the
 * fulfilment partner documents eight characters and rejects anything shorter. Accepting a
 * six-character password here does not produce a validation error, it produces a paid
 * order that cannot be released -- discovered by an operator days later, with the customer
 * already charged. Cheaper to refuse at the point somebody can still fix it.
 */
class CredentialValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    private static CredentialDtos.SubmitCredentialsRequest withPassword(String password) {
        return new CredentialDtos.SubmitCredentialsRequest(
                "player@example.com", password, List.of("12345678"),
                null, null, true, true, true, true);
    }

    private static List<String> failedFields(CredentialDtos.SubmitCredentialsRequest request) {
        return validator.validate(request).stream()
                .map(v -> v.getPropertyPath().toString())
                .toList();
    }

    @Test
    @DisplayName("refuses a password the partner would reject")
    void shortPasswordIsRefused() {
        assertThat(failedFields(withPassword("short7c"))).contains("eaPassword");
    }

    @Test
    @DisplayName("accepts one of exactly the partner's minimum length")
    void eightCharactersIsEnough() {
        // The boundary itself, because an off-by-one here rejects valid sign-ins.
        assertThat(failedFields(withPassword("eightchr"))).doesNotContain("eaPassword");
    }

    @Test
    @DisplayName("still refuses a blank one, with the message about it being required")
    void blankIsStillRequired() {
        assertThat(failedFields(withPassword(""))).contains("eaPassword");
    }
}
