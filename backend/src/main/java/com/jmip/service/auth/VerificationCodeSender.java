package com.jmip.service.auth;

import java.time.Duration;

/** Delivers a verification code to the account's email address. */
public interface VerificationCodeSender {

    /**
     * @param fullName may be null for older accounts
     * @throws RuntimeException when delivery fails; the caller decides what that means
     */
    void send(String email, String fullName, String code, Duration validFor);
}
