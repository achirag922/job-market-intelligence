package com.jmip.common.exception;

/**
 * A correct email and password for an account whose email is not yet confirmed. Only ever
 * raised after the password checked out, so it reveals nothing to someone guessing.
 */
public class EmailNotVerifiedException extends RuntimeException {

    public EmailNotVerifiedException() {
        super("Verify your email address to sign in. Enter the code we sent you.");
    }
}
