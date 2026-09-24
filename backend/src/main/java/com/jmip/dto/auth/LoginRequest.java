package com.jmip.dto.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Credentials for signing in. Not yet exposed by an endpoint; login arrives in a later step.
 * No format or strength rules here: a sign-in attempt should fail as "invalid credentials",
 * never reveal which rule an existing password would break.
 */
public record LoginRequest(
        @NotBlank(message = "email is required")
        @Size(max = 320, message = "email must be at most 320 characters")
        String email,

        @NotBlank(message = "password is required")
        @Size(max = 72, message = "password must be at most 72 characters")
        String password) {

    public LoginRequest {
        email = email == null ? null : email.strip();
    }

    /** Never prints the password. */
    @Override
    public String toString() {
        return "LoginRequest[email=" + email + ", password=****]";
    }
}
