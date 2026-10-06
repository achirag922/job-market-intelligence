package com.jmip.common.exception;

/**
 * A verification code was refused: wrong, expired, or out of attempts. Each has its own
 * message so the user knows whether to retype or ask for a new code. An unknown email gets
 * the "wrong code" message, the same as a mistyped code.
 */
public class VerificationCodeException extends RuntimeException {

    public static final String INVALID = "That code is not correct. Check the email and try again.";
    public static final String EXPIRED = "That code has expired. Request a new one.";
    public static final String TOO_MANY_ATTEMPTS = "Too many incorrect attempts. Request a new code.";

    public VerificationCodeException(String message) {
        super(message);
    }
}
