package com.jmip.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/** V9.12: onboarding progress, keyed by the owner's id. No row means onboarding has not started. */
@Repository
public class OnboardingRepository {

    public record Row(String status, String targetRole, OffsetDateTime profileCompletedAt,
                      OffsetDateTime preferencesCompletedAt, OffsetDateTime skippedAt, OffsetDateTime completedAt) {
    }

    private final JdbcTemplate jdbcTemplate;

    public OnboardingRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<Row> find(UUID userId) {
        return jdbcTemplate.query("""
                SELECT status, target_role, profile_completed_at, preferences_completed_at, skipped_at, completed_at
                  FROM user_onboarding WHERE user_id = ?
                """, (rs, row) -> new Row(rs.getString(1), rs.getString(2), rs.getObject(3, OffsetDateTime.class),
                rs.getObject(4, OffsetDateTime.class), rs.getObject(5, OffsetDateTime.class),
                rs.getObject(6, OffsetDateTime.class)), userId).stream().findFirst();
    }

    public void saveProfile(UUID userId, String targetRole, OffsetDateTime now) {
        jdbcTemplate.update("""
                INSERT INTO user_onboarding (user_id, target_role, profile_completed_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (user_id) DO UPDATE SET target_role = EXCLUDED.target_role,
                    profile_completed_at = EXCLUDED.profile_completed_at, updated_at = EXCLUDED.updated_at
                """, userId, targetRole, ts(now), ts(now), ts(now));
    }

    public void savePreferences(UUID userId, OffsetDateTime now) {
        jdbcTemplate.update("""
                INSERT INTO user_onboarding (user_id, preferences_completed_at, created_at, updated_at)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (user_id) DO UPDATE SET preferences_completed_at = EXCLUDED.preferences_completed_at,
                    updated_at = EXCLUDED.updated_at
                """, userId, ts(now), ts(now), ts(now));
    }

    /** SKIPPED or COMPLETED; skipping never undoes a completion. */
    public void setStatus(UUID userId, String status, OffsetDateTime now) {
        boolean completed = "COMPLETED".equals(status);
        jdbcTemplate.update("""
                INSERT INTO user_onboarding (user_id, status, skipped_at, completed_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (user_id) DO UPDATE SET
                    status = CASE WHEN user_onboarding.status = 'COMPLETED' THEN 'COMPLETED' ELSE EXCLUDED.status END,
                    skipped_at = CASE WHEN EXCLUDED.status = 'SKIPPED' THEN EXCLUDED.skipped_at ELSE user_onboarding.skipped_at END,
                    completed_at = coalesce(user_onboarding.completed_at, EXCLUDED.completed_at),
                    updated_at = EXCLUDED.updated_at
                """, userId, status, completed ? null : ts(now), completed ? ts(now) : null, ts(now), ts(now));
    }

    private static Timestamp ts(OffsetDateTime at) {
        return Timestamp.from(at.toInstant());
    }
}
