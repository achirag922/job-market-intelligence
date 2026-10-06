package com.jmip.dto.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** The one-time code from the verification email. */
public record VerifyEmailRequest(
        @NotBlank(message = "email is required")
        @Size(max = 320, message = "email must be at most 320 characters")
        String email,

        @NotBlank(message = "code is required")
        @Pattern(regexp = "\\d{6}", message = "code must be 6 digits")
        String code) {

    public VerifyEmailRequest {
        email = email == null ? null : email.strip();
        code = code == null ? null : code.strip();
    }

    @Override
    public String toString() {
        return "VerifyEmailRequest[email=" + email + ", code=****]";
    }
}
