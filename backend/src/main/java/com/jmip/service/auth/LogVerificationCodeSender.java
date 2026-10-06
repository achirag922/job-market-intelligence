package com.jmip.service.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

/**
 * Local development only: prints the code to the backend log instead of emailing it, so the
 * flow can be exercised without SMTP credentials. The email address is not printed. Chosen
 * explicitly with {@code JMIP_VERIFICATION_DELIVERY=log}; never use it where real people sign up.
 */
public class LogVerificationCodeSender implements VerificationCodeSender {

    private static final Logger log = LoggerFactory.getLogger(LogVerificationCodeSender.class);

    @Override
    public void send(String email, String fullName, String code, Duration validFor) {
        log.warn("DEVELOPMENT ONLY — email delivery is off. Verification code: {} (valid {} min)",
                code, validFor.toMinutes());
    }
}
