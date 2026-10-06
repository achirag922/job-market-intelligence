package com.jmip.service.auth;

import com.jmip.common.exception.InvalidRequestException;

import java.util.List;
import java.util.Locale;

/**
 * Password strength, beyond the 12–72 length bounds on the request.
 *
 * <p>Follows current guidance (NIST SP 800-63B) rather than composition rules: length does
 * the heavy lifting, and what is refused is what an attacker would try first — repeated or
 * patterned characters, the most common password fragments, and the user's own email.
 * Nothing here is a secret, so the reasons are stated plainly.
 */
final class PasswordPolicy {

    static final int MIN_DISTINCT_CHARACTERS = 6;

    private static final List<String> COMMON_FRAGMENTS = List.of(
            "password", "passw0rd", "qwerty", "123456", "abcdef", "letmein", "welcome", "iloveyou", "admin");

    private PasswordPolicy() {
    }

    /** @throws InvalidRequestException naming the first rule the password breaks */
    static void check(String password, String normalisedEmail) {
        String lower = password.toLowerCase(Locale.ROOT);

        if (password.isBlank()) {
            throw new InvalidRequestException("password must not be blank");
        }
        if (lower.codePoints().distinct().count() < MIN_DISTINCT_CHARACTERS) {
            throw new InvalidRequestException(
                    "password is too repetitive: use at least " + MIN_DISTINCT_CHARACTERS + " different characters");
        }
        for (String fragment : COMMON_FRAGMENTS) {
            if (lower.contains(fragment)) {
                throw new InvalidRequestException("password contains a commonly used sequence and is easy to guess");
            }
        }
        String localPart = normalisedEmail.substring(0, Math.max(0, normalisedEmail.indexOf('@')));
        if (localPart.length() >= 3 && lower.contains(localPart)) {
            throw new InvalidRequestException("password must not contain your email address");
        }
    }
}
