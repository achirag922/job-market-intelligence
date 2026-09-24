package com.jmip.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * An application user.
 *
 * <p>Only what authentication needs. The password is held solely as an encoded hash, set
 * from outside by the service that did the hashing; this class never sees a plain-text
 * password. {@link #toString()} is written by hand so the hash can never reach a log line
 * by accident, and no API response is built from this entity directly.
 */
@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User {

    @Id
    private UUID id;

    /** Normalised: trimmed and lower-case. */
    @Column(name = "email", nullable = false, unique = true, length = 320)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 30)
    private UserRole role;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public User(UUID id, String email, String passwordHash, UserRole role, OffsetDateTime createdAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.email = Objects.requireNonNull(email, "email");
        this.passwordHash = Objects.requireNonNull(passwordHash, "passwordHash");
        this.role = Objects.requireNonNull(role, "role");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = createdAt;
    }

    /** For re-hashing on a password change or an algorithm upgrade. */
    public void changePasswordHash(String newPasswordHash, OffsetDateTime at) {
        this.passwordHash = Objects.requireNonNull(newPasswordHash, "newPasswordHash");
        this.updatedAt = at;
    }

    public void changeRole(UserRole newRole, OffsetDateTime at) {
        this.role = Objects.requireNonNull(newRole, "newRole");
        this.updatedAt = at;
    }

    @Override
    public String toString() {
        return "User[id=" + id + ", role=" + role + "]";
    }
}
