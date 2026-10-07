package com.globalfutservice.credentials;

import com.globalfutservice.web.ApiExceptions;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * What an EA sign-in must look like before it is sealed into the vault and, later, sent to
 * the fulfilment partner.
 *
 * <p>The storefront checks the same things, with the same numbers -- {@code
 * frontend/src/lib/signInRules.ts} -- but only so the customer hears about a mistake under
 * the field it is in. This is the check that counts: a request that skips the storefront is
 * refused here, before anything is stored.
 *
 * <p>Where the numbers come from. FUT Transfer documents a password of at least eight
 * characters, and a first backup code ({@code ba}) that is required, with {@code ba2} to
 * {@code ba5} optional, each at least six characters. EA issues backup codes as exactly
 * eight digits, and the partner's own example sends one ({@code "ba": "12345678"}), so a
 * code is held to EA's format: anything else would never sign in, and every code in that
 * format meets the partner's minimum. How many are required is the business's call, set
 * by {@code gfs.fulfilment.backup-codes-required} and at most the partner's five.
 */
public final class SignInRules {

    /** FUT Transfer's minimum, documented on the order endpoint. */
    public static final int PASSWORD_MIN = 8;

    /** EA's backup code: exactly eight digits. */
    public static final int BACKUP_CODE_LENGTH = 8;
    private static final Pattern BACKUP_CODE = Pattern.compile("\\d{" + BACKUP_CODE_LENGTH + "}");

    /** The most codes the partner takes: {@code ba} to {@code ba5}. */
    public static final int MAX_BACKUP_CODES = 5;

    /**
     * The same email shape the storefront checks: something, an @, a domain with a dot.
     * Stricter than a bare {@code @Email}, which accepts {@code name@gmail}.
     */
    public static final String EMAIL_REGEX = "^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$";

    private SignInRules() {
    }

    /**
     * What is wrong with a sign-in already on file, or empty when it meets every rule. For the
     * release, which checks the stored sign-in before sending it: one sealed before these rules
     * existed, or before the number of codes was raised, is caught here rather than refused by
     * the partner.
     */
    public static Optional<String> problem(String eaEmail, String eaPassword, List<String> backupCodes,
                                           int required) {
        if (eaEmail == null || !eaEmail.trim().matches(EMAIL_REGEX)) {
            return Optional.of("The EA email on file is not an email address.");
        }
        if (eaPassword == null || eaPassword.length() < PASSWORD_MIN) {
            return Optional.of("The EA password on file is shorter than " + PASSWORD_MIN + " characters.");
        }
        try {
            backupCodes(backupCodes, required);
            return Optional.empty();
        } catch (ApiExceptions.BadRequestException e) {
            return Optional.of(e.getMessage());
        }
    }

    /**
     * The backup codes, trimmed and in the order given, or a refusal naming the first one
     * missing or malformed -- "Backup code 2 is required.", by its position on the form.
     *
     * <p>Blank entries are dropped before counting, so a form that sends three boxes with
     * the second left empty is told code 3 is missing rather than code 2: the server cannot
     * know which box was which, and the storefront has already said it by position.
     */
    public static List<String> backupCodes(List<String> submitted, int required) {
        List<String> codes = new ArrayList<>();
        if (submitted != null) {
            for (String code : submitted) {
                if (code != null && !code.isBlank()) {
                    codes.add(code.trim());
                }
            }
        }
        for (int i = 0; i < codes.size(); i++) {
            if (!BACKUP_CODE.matcher(codes.get(i)).matches()) {
                throw new ApiExceptions.BadRequestException("backup_code_invalid",
                        "Backup code " + (i + 1) + " must be exactly " + BACKUP_CODE_LENGTH + " digits.");
            }
        }
        if (codes.size() < required) {
            throw new ApiExceptions.BadRequestException("backup_code_required",
                    "Backup code " + (codes.size() + 1) + " is required.");
        }
        if (codes.size() > MAX_BACKUP_CODES) {
            throw new ApiExceptions.BadRequestException("backup_code_invalid",
                    "Send at most " + MAX_BACKUP_CODES + " backup codes.");
        }
        return List.copyOf(codes);
    }
}
