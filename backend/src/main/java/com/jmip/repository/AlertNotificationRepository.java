package com.jmip.repository;

import com.jmip.entity.AlertFrequency;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** V8.4: due alerts and the record of every job each alert has sent. */
@Repository
public class AlertNotificationRepository {

    /** An alert that is due, with where to send it. The email address is used for delivery only. */
    public record DueAlert(UUID alertId, UUID userId, String email, String fullName, String name,
                           AlertFrequency frequency, OffsetDateTime since) {
    }

    /** A recorded job for an alert, still to be sent or already sent. */
    public record Notification(long id, long jobId, BigDecimal matchPercentage, String status, int attempts,
                               OffsetDateTime createdAt, OffsetDateTime sentAt) {
    }

    private final JdbcTemplate jdbcTemplate;

    public AlertNotificationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Active alerts of verified accounts whose frequency has come round. The hour of slack
     * keeps an hourly schedule from drifting a daily alert to every 25 hours.
     */
    public List<DueAlert> findDue(OffsetDateTime now) {
        Timestamp at = Timestamp.from(now.toInstant());
        return jdbcTemplate.query("""
                SELECT a.id, a.user_id, u.email, u.full_name, a.name, a.frequency,
                       COALESCE(a.last_processed_at, a.created_at) AS since
                  FROM job_alerts a JOIN users u ON u.id = a.user_id
                 WHERE a.active AND u.email_verified_at IS NOT NULL
                   AND (a.last_processed_at IS NULL
                        OR a.last_processed_at <= ?::timestamptz - CASE a.frequency WHEN 'DAILY' THEN interval '23 hours'
                                                                        ELSE interval '167 hours' END)
                 ORDER BY since
                """, (rs, row) -> new DueAlert(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                rs.getString(4), rs.getString(5), AlertFrequency.valueOf(rs.getString(6)),
                rs.getObject(7, OffsetDateTime.class)), at);
    }

    /** Jobs this alert has already recorded, so they are never matched again. */
    public Set<Long> recordedJobIds(UUID alertId, Collection<Long> jobIds) {
        if (jobIds.isEmpty()) {
            return Set.of();
        }
        List<Object> args = new java.util.ArrayList<>();
        args.add(alertId);
        args.addAll(jobIds);
        return new java.util.HashSet<>(jdbcTemplate.queryForList("SELECT job_id FROM job_alert_notifications "
                + "WHERE alert_id = ? AND job_id IN (" + String.join(", ", Collections.nCopies(jobIds.size(), "?")) + ")",
                Long.class, args.toArray()));
    }

    /** Records a job as pending for the alert; the unique key makes a second record impossible. */
    public void recordPending(UUID alertId, UUID userId, long jobId, Double matchPercentage) {
        jdbcTemplate.update("""
                INSERT INTO job_alert_notifications (alert_id, user_id, job_id, match_percentage)
                VALUES (?, ?, ?, ?) ON CONFLICT (alert_id, job_id) DO NOTHING
                """, alertId, userId, jobId, matchPercentage == null ? null : BigDecimal.valueOf(matchPercentage));
    }

    public void markProcessed(UUID alertId, OffsetDateTime until) {
        jdbcTemplate.update("UPDATE job_alerts SET last_processed_at = ? WHERE id = ?", Timestamp.from(until.toInstant()), alertId);
    }

    /** What the next digest for this alert must carry: pending jobs, and failed ones still worth retrying. */
    public List<Notification> unsent(UUID alertId, int maxAttempts, int limit) {
        return jdbcTemplate.query("""
                SELECT id, job_id, match_percentage, status, attempts, created_at, sent_at
                  FROM job_alert_notifications
                 WHERE alert_id = ? AND status <> 'SENT' AND attempts < ?
                 ORDER BY match_percentage DESC NULLS LAST, created_at, id
                 LIMIT ?
                """, this::notification, alertId, maxAttempts, limit);
    }

    public void markSent(Collection<Long> ids) {
        update("UPDATE job_alert_notifications SET status = 'SENT', sent_at = now(), attempts = attempts + 1, "
                + "last_error = NULL WHERE id IN (%s)", ids, null);
    }

    public void markFailed(Collection<Long> ids, String errorType) {
        update("UPDATE job_alert_notifications SET status = 'FAILED', attempts = attempts + 1, last_error = ? "
                + "WHERE id IN (%s)", ids, errorType.length() > 100 ? errorType.substring(0, 100) : errorType);
    }

    /** The latest jobs an alert recorded, for its owner's view. The caller has checked ownership. */
    public List<com.jmip.dto.alert.JobAlertNotificationResponse> recent(UUID alertId, int limit) {
        return jdbcTemplate.query("""
                SELECT n.job_id, j.title, c.name, n.match_percentage, n.status, n.created_at, n.sent_at
                  FROM job_alert_notifications n
                  JOIN jobs j ON j.id = n.job_id JOIN companies c ON c.id = j.company_id
                 WHERE n.alert_id = ?
                 ORDER BY n.created_at DESC, n.id DESC LIMIT ?
                """, (rs, row) -> new com.jmip.dto.alert.JobAlertNotificationResponse(rs.getLong(1), rs.getString(2),
                rs.getString(3), rs.getBigDecimal(4), rs.getString(5), rs.getObject(6, OffsetDateTime.class),
                rs.getObject(7, OffsetDateTime.class)), alertId, limit);
    }

    private void update(String sql, Collection<Long> ids, String firstArg) {
        if (ids.isEmpty()) {
            return;
        }
        List<Object> args = new java.util.ArrayList<>();
        if (firstArg != null) {
            args.add(firstArg);
        }
        args.addAll(ids);
        jdbcTemplate.update(sql.formatted(String.join(", ", Collections.nCopies(ids.size(), "?"))), args.toArray());
    }

    private Notification notification(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new Notification(rs.getLong(1), rs.getLong(2), rs.getBigDecimal(3), rs.getString(4), rs.getInt(5),
                rs.getObject(6, OffsetDateTime.class), rs.getObject(7, OffsetDateTime.class));
    }

    /** The user's current resume skills (default first, else the latest processed); empty without one. */
    public Set<Long> currentResumeSkillIds(UUID userId) {
        return new java.util.HashSet<>(jdbcTemplate.queryForList("""
                SELECT rs.skill_id FROM resume_skills rs
                 WHERE rs.resume_id = (SELECT r.id FROM resumes r
                                        WHERE r.user_id = ? AND r.processing_status = 'COMPLETED'
                                        ORDER BY r.is_default DESC, r.uploaded_at DESC LIMIT 1)
                """, Long.class, userId));
    }
}
