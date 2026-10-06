package com.jmip.dto.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ResendVerificationRequest(
        @NotBlank(message = "email is required")
        @Size(max = 320, message = "email must be at most 320 characters")
        String email) {

    public ResendVerificationRequest {
        email = email == null ? null : email.strip();
    }
}
