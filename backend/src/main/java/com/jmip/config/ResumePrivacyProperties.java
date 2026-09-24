package com.jmip.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * How resumes are protected and how long they are kept.
 *
 * @param encryptionKey base64 of a 256-bit AES key. Blank outside production means resume
 *                      files and extracted text are stored unencrypted; the prod profile
 *                      refuses to start without it. Never logged
 * @param retention     delete resumes this long after upload. Zero (the default) keeps them
 *                      until their owner deletes them; anything else must be at least a day,
 *                      so a typo such as "30s" cannot wipe every resume
 * @param retentionCron when the retention sweep runs
 */
@ConfigurationProperties(prefix = "jmip.resume.privacy")
public record ResumePrivacyProperties(
        @DefaultValue("") String encryptionKey,
        @DefaultValue("0s") Duration retention,
        @DefaultValue("0 30 3 * * *") String retentionCron) {

    static final Duration MINIMUM_RETENTION = Duration.ofDays(1);

    public ResumePrivacyProperties {
        encryptionKey = encryptionKey == null ? "" : encryptionKey.strip();
        retention = retention == null ? Duration.ZERO : retention;
        if (retention.isNegative()) {
            throw new IllegalArgumentException("jmip.resume.privacy.retention must not be negative");
        }
        if (!retention.isZero() && retention.compareTo(MINIMUM_RETENTION) < 0) {
            throw new IllegalArgumentException(
                    "jmip.resume.privacy.retention must be 0 (keep until deleted) or at least 1 day, was " + retention);
        }
    }

    public boolean retentionEnabled() {
        return !retention.isZero();
    }

    @Override
    public String toString() {
        return "ResumePrivacyProperties[encryptionKey=" + (encryptionKey.isEmpty() ? "<unset>" : "****")
                + ", retention=" + retention + ", retentionCron=" + retentionCron + "]";
    }
}
