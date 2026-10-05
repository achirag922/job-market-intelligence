package com.jmip.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * V9.14: hidden jobs, recently viewed jobs, saved searches and follow-up reminders. Every per-user
 * read and write is keyed by the owner's id.
 */
@Repository
public class WorkspaceRepository {

    /** Keeps the recently viewed list short and the table small. */
    public static final int RECENT_VIEWS_KEPT = 50;

    public record Timed(long jobId, OffsetDateTime at) {
    }

    public record SearchRow(UUID id, String name, String filters, OffsetDateTime createdAt) {
    }

    /** A follow-up that is due and has not been reminded for its current date. */
    public record DueFollowUp(UUID userId, String email, String fullName, UUID savedJobId, String jobTitle, String company,
                              LocalDate followUpOn, String note) {
    }

    private final JdbcTemplate jdbcTemplate;

    public WorkspaceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // ------------------------------------------------------------------ hidden

    public void hide(UUID userId, long jobId, OffsetDateTime at) {
        jdbcTemplate.update("INSERT INTO hidden_jobs (user_id, job_id, hidden_at) VALUES (?, ?, ?) ON CONFLICT DO NOTHING",
                userId, jobId, ts(at));
    }

    public boolean unhide(UUID userId, long jobId) {
        return jdbcTemplate.update("DELETE FROM hidden_jobs WHERE user_id = ? AND job_id = ?", userId, jobId) == 1;
    }

    public List<Long> hiddenIds(UUID userId) {
        return jdbcTemplate.queryForList("SELECT job_id FROM hidden_jobs WHERE user_id = ?", Long.class, userId);
    }

    public List<Timed> hidden(UUID userId, int limit) {
        return jdbcTemplate.query("SELECT job_id, hidden_at FROM hidden_jobs WHERE user_id = ? ORDER BY hidden_at DESC LIMIT ?",
                (rs, row) -> new Timed(rs.getLong(1), rs.getObject(2, OffsetDateTime.class)), userId, limit);
    }

    // ------------------------------------------------------------------ recently viewed

    /** Records a view (the latest wins) and drops the oldest beyond {@link #RECENT_VIEWS_KEPT}. */
    public void recordView(UUID userId, long jobId, OffsetDateTime at) {
        jdbcTemplate.update("""
                INSERT INTO job_views (user_id, job_id, viewed_at) VALUES (?, ?, ?)
                ON CONFLICT (user_id, job_id) DO UPDATE SET viewed_at = EXCLUDED.viewed_at
                """, userId, jobId, ts(at));
        jdbcTemplate.update("""
                DELETE FROM job_views WHERE user_id = ? AND job_id NOT IN (
                    SELECT job_id FROM job_views WHERE user_id = ? ORDER BY viewed_at DESC LIMIT ?)
                """, userId, userId, RECENT_VIEWS_KEPT);
    }

    public List<Timed> recentViews(UUID userId, int limit) {
        return jdbcTemplate.query("SELECT job_id, viewed_at FROM job_views WHERE user_id = ? ORDER BY viewed_at DESC LIMIT ?",
                (rs, row) -> new Timed(rs.getLong(1), rs.getObject(2, OffsetDateTime.class)), userId, limit);
    }

    // ------------------------------------------------------------------ saved searches

    public List<SearchRow> searches(UUID userId) {
        return jdbcTemplate.query("SELECT id, name, filters::text, created_at FROM saved_searches WHERE user_id = ? "
                        + "ORDER BY created_at DESC",
                (rs, row) -> new SearchRow(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        rs.getObject(4, OffsetDateTime.class)), userId);
    }

    public void insertSearch(UUID id, UUID userId, String name, String filtersJson, OffsetDateTime at) {
        jdbcTemplate.update("INSERT INTO saved_searches (id, user_id, name, filters, created_at) VALUES (?, ?, ?, ?::jsonb, ?)",
                id, userId, name, filtersJson, ts(at));
    }

    public boolean deleteSearch(UUID id, UUID userId) {
        return jdbcTemplate.update("DELETE FROM saved_searches WHERE id = ? AND user_id = ?", id, userId) == 1;
    }

    public boolean searchNameTaken(UUID userId, String name) {
        Boolean taken = jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM saved_searches WHERE user_id = ? AND lower(name) = lower(?))", Boolean.class,
                userId, name);
        return Boolean.TRUE.equals(taken);
    }

    // ------------------------------------------------------------------ follow-up reminders

    /** Due by {@code today}, not reminded for this date yet, on an open application of a verified account. */
    public List<DueFollowUp> dueFollowUps(LocalDate today) {
        return jdbcTemplate.query("""
                SELECT u.id, u.email, u.full_name, s.id, j.title, c.name, s.follow_up_on, s.follow_up_note
                  FROM saved_jobs s
                  JOIN users u ON u.id = s.user_id
                  JOIN jobs j ON j.id = s.job_id
                  JOIN companies c ON c.id = j.company_id
                 WHERE s.follow_up_on <= ?
                   AND (s.follow_up_reminded_on IS NULL OR s.follow_up_reminded_on <> s.follow_up_on)
                   AND s.status NOT IN ('REJECTED', 'WITHDRAWN')
                   AND u.email_verified_at IS NOT NULL
                 ORDER BY u.id, s.follow_up_on
                """, (rs, row) -> new DueFollowUp(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                rs.getObject(4, UUID.class), rs.getString(5), rs.getString(6), rs.getObject(7, LocalDate.class),
                rs.getString(8)), Date.valueOf(today));
    }

    public void markReminded(Collection<UUID> savedJobIds) {
        for (UUID id : savedJobIds) {
            jdbcTemplate.update("UPDATE saved_jobs SET follow_up_reminded_on = follow_up_on WHERE id = ?", id);
        }
    }

    private static Timestamp ts(OffsetDateTime at) {
        return Timestamp.from(at.toInstant());
    }
}
