package com.jmip.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Email verification by one-time code.
 *
 * @param delivery       {@code smtp} to email the code, {@code log} to print it to the backend
 *                       log for local development only
 * @param secret         HMAC key for stored codes. Blank outside production: a random key is
 *                       generated at startup, so pending codes do not survive a restart
 * @param codeTtl        how long a code stays valid
 * @param resendCooldown minimum time between two codes for the same account
 * @param maxAttempts    wrong guesses allowed before the code is void
 * @param mailFrom       sender address for the email; defaults to the SMTP username
 */
@ConfigurationProperties(prefix = "jmip.verification")
public record VerificationProperties(
        @DefaultValue("log") Delivery delivery,
        @DefaultValue("") String secret,
        @DefaultValue("10m") Duration codeTtl,
        @DefaultValue("60s") Duration resendCooldown,
        @DefaultValue("5") int maxAttempts,
        @DefaultValue("") String mailFrom) {

    public enum Delivery {
        SMTP,
        LOG
    }

    @Override
    public String toString() {
        return "VerificationProperties[delivery=" + delivery + ", secret=" + (secret.isBlank() ? "<generated>" : "****")
                + ", codeTtl=" + codeTtl + ", resendCooldown=" + resendCooldown + "]";
    }
}
