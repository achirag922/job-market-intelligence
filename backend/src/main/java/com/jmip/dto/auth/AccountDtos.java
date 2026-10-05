package com.jmip.dto.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** V9.17: account settings. The account is always the session's own; passwords are never logged or returned. */
public final class AccountDtos {

    private AccountDtos() {
    }

    public record ProfileRequest(@NotBlank(message = "full name is required") @Size(max = 100) String fullName) {
    }

    public record PasswordChangeRequest(
            @NotBlank(message = "enter your current password") String currentPassword,
            @NotBlank(message = "enter a new password")
            @Size(min = 12, max = 72, message = "password must be between 12 and 72 characters") String newPassword) {

        @Override
        public String toString() {
            return "PasswordChangeRequest[****]";
        }
    }

    /** Deleting the account needs the password again, so an open session alone cannot do it. */
    public record DeleteAccountRequest(@NotBlank(message = "enter your password to confirm") String password) {

        @Override
        public String toString() {
            return "DeleteAccountRequest[****]";
        }
    }
}
