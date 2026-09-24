package com.jmip.common.exception;

/**
 * A request that needed a signed-in user and did not have one, or a sign-in that failed.
 * The message is deliberately the same for an unknown email and a wrong password, so the
 * response cannot be used to find out which accounts exist.
 */
public class AuthenticationFailedException extends RuntimeException {

    public static final String INVALID_CREDENTIALS = "Invalid email or password";
    public static final String NOT_SIGNED_IN = "You are not signed in";

    public AuthenticationFailedException(String message) {
        super(message);
    }
}
