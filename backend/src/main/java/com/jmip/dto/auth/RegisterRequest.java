package com.jmip.dto.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * A new account.
 *
 * <p>The password bounds are those of the hashing algorithm: bcrypt reads at most 72 bytes,
 * so a longer password would be silently truncated. The byte limit is enforced again in the
 * service, since {@code @Size} counts characters, not bytes.
 *
 * @param fullName how the user wants to be addressed; shown in the app, never logged
 * @param email    the sign-in name; normalised to lower case before it is stored
 * @param password plain text, only ever held in memory long enough to be hashed
 */
public record RegisterRequest(
        @NotBlank(message = "full name is required")
        @Size(max = 100, message = "full name must be at most 100 characters")
        String fullName,

        @NotBlank(message = "email is required")
        @Email(message = "email must be a valid email address")
        @Size(max = 320, message = "email must be at most 320 characters")
        String email,

        @NotBlank(message = "password is required")
        @Size(min = 12, max = 72, message = "password must be between 12 and 72 characters")
        String password) {

    /** Surrounding spaces from pasted input are dropped before validation sees them. */
    public RegisterRequest {
        fullName = fullName == null ? null : fullName.strip().replaceAll("\\s+", " ");
        email = email == null ? null : email.strip();
    }

    /** Never prints the password (or the name, which is personal data). */
    @Override
    public String toString() {
        return "RegisterRequest[email=" + email + ", password=****]";
    }
}
