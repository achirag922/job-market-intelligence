package com.jmip.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * The live verification code for one user. Holds an HMAC of the code, never the code.
 */
@Entity
@Table(name = "email_verification_codes")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EmailVerificationCode {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "code_hash", nullable = false, length = 64)
    private String codeHash;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "failed_attempts", nullable = false)
    private int failedAttempts;

    @Column(name = "last_sent_at", nullable = false)
    private OffsetDateTime lastSentAt;

    public EmailVerificationCode(UUID userId, String codeHash, OffsetDateTime expiresAt, OffsetDateTime sentAt) {
        this.userId = userId;
        replace(codeHash, expiresAt, sentAt);
    }

    /** A resend: a new code, a new expiry, and a clean attempt count. */
    public void replace(String newCodeHash, OffsetDateTime newExpiresAt, OffsetDateTime sentAt) {
        this.codeHash = newCodeHash;
        this.expiresAt = newExpiresAt;
        this.lastSentAt = sentAt;
        this.failedAttempts = 0;
    }

    public void recordFailedAttempt() {
        failedAttempts++;
    }

    @Override
    public String toString() {
        return "EmailVerificationCode[userId=" + userId + ", expiresAt=" + expiresAt + "]";
    }
}
