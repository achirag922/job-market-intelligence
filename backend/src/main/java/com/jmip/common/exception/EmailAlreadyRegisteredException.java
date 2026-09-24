package com.jmip.common.exception;

/**
 * An account already exists for this email. Carries no email in its message, so it cannot
 * leak one into a log or a response.
 */
public class EmailAlreadyRegisteredException extends RuntimeException {

    public EmailAlreadyRegisteredException() {
        super("An account with this email already exists");
    }
}
