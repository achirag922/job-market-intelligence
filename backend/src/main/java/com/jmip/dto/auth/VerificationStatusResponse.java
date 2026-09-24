package com.jmip.dto.auth;

/**
 * The answer to signup and to a resend request. Identical whether or not the email belongs
 * to an account awaiting verification, so it cannot be used to discover accounts.
 *
 * @param email                    the address, as given, for the frontend to show masked
 * @param resendAvailableInSeconds how long before another code can be requested
 * @param codeValidForSeconds      how long a freshly sent code stays valid
 */
public record VerificationStatusResponse(String email, long resendAvailableInSeconds, long codeValidForSeconds) {
}
