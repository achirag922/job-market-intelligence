package com.jmip.repository;

import com.jmip.dto.notification.NotificationDtos.Notification;
import com.jmip.dto.notification.NotificationDtos.Preferences;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** V9.16: notifications and their preferences, always keyed by the owner's id. */
@Repository
public class NotificationRepository {

    /** Older notifications beyond this many are dropped. */
    public static final int KEPT = 200;

    /** A notification still to be created, if its key is new. */
    public record Draft(String type, String key, String title, String body, String link) {
    }

    public record JobMatchGroup(UUID alertId, String alertName, LocalDate day, int jobs) {
    }

    public record Dated(UUID id, String title, String company, LocalDate on) {
    }

    private final JdbcTemplate jdbcTemplate;

    public NotificationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // ------------------------------------------------------------------ inbox

    public List<Notification> list(UUID userId, Collection<String> types, boolean unreadOnly, int limit) {
        if (types.isEmpty()) {
            return List.of();
        }
        return jdbcTemplate.query("SELECT id, type, title, body, link, created_at, read_at FROM notifications "
                        + "WHERE user_id = ? AND type = ANY (?)" + (unreadOnly ? " AND read_at IS NULL" : "")
                        + " ORDER BY created_at DESC, id LIMIT ?",
                (rs, row) -> new Notification(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getObject(6, OffsetDateTime.class), rs.getObject(7, OffsetDateTime.class)),
                userId, types.toArray(new String[0]), limit);
    }

    public int unread(UUID userId, Collection<String> types) {
        if (types.isEmpty()) {
            return 0;
        }
        Integer count = jdbcTemplate.queryForObject("SELECT count(*) FROM notifications WHERE user_id = ? AND type = ANY (?) "
                + "AND read_at IS NULL", Integer.class, userId, types.toArray(new String[0]));
        return count == null ? 0 : count;
    }

    public boolean markRead(UUID id, UUID userId, OffsetDateTime at) {
        return jdbcTemplate.update("UPDATE notifications SET read_at = coalesce(read_at, ?) WHERE id = ? AND user_id = ?",
                ts(at), id, userId) == 1;
    }

    public int markAllRead(UUID userId, OffsetDateTime at) {
        return jdbcTemplate.update("UPDATE notifications SET read_at = ? WHERE user_id = ? AND read_at IS NULL", ts(at), userId);
    }

    /** Creates the drafts whose keys are new; existing ones (read or not) are left alone. */
    public void insertNew(UUID userId, List<Draft> drafts, OffsetDateTime at) {
        for (Draft d : drafts) {
            jdbcTemplate.update("""
                    INSERT INTO notifications (id, user_id, type, dedupe_key, title, body, link, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (user_id, dedupe_key) DO NOTHING
                    """, UUID.randomUUID(), userId, d.type(), d.key(), d.title(), d.body(), d.link(), ts(at));
        }
        jdbcTemplate.update("""
                DELETE FROM notifications WHERE user_id = ? AND id NOT IN (
                    SELECT id FROM notifications WHERE user_id = ? ORDER BY created_at DESC LIMIT ?)
                """, userId, userId, KEPT);
    }

    // ------------------------------------------------------------------ preferences

    public Preferences preferences(UUID userId) {
        return jdbcTemplate.query("SELECT job_matches, follow_ups, interviews, learning, career FROM notification_preferences "
                        + "WHERE user_id = ?",
                (rs, row) -> new Preferences(rs.getBoolean(1), rs.getBoolean(2), rs.getBoolean(3), rs.getBoolean(4), rs.getBoolean(5)),
                userId).stream().findFirst().orElse(new Preferences(true, true, true, true, true));
    }

    public void savePreferences(UUID userId, Preferences p) {
        jdbcTemplate.update("""
                INSERT INTO notification_preferences (user_id, job_matches, follow_ups, interviews, learning, career)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (user_id) DO UPDATE SET job_matches = EXCLUDED.job_matches, follow_ups = EXCLUDED.follow_ups,
                    interviews = EXCLUDED.interviews, learning = EXCLUDED.learning, career = EXCLUDED.career
                """, userId, p.jobMatches(), p.followUps(), p.interviews(), p.learning(), p.career());
    }

    public Optional<OffsetDateTime> refreshedAt(UUID userId) {
        return jdbcTemplate.query("SELECT refreshed_at FROM notification_preferences WHERE user_id = ?",
                (rs, row) -> rs.getObject(1, OffsetDateTime.class), userId).stream().filter(java.util.Objects::nonNull).findFirst();
    }

    public void touchRefreshed(UUID userId, OffsetDateTime at) {
        jdbcTemplate.update("""
                INSERT INTO notification_preferences (user_id, refreshed_at) VALUES (?, ?)
                ON CONFLICT (user_id) DO UPDATE SET refreshed_at = EXCLUDED.refreshed_at
                """, userId, ts(at));
    }

    // ------------------------------------------------------------------ sources (existing data only)

    /** New job-alert matches (V8.4), grouped per alert and day, from the last two weeks. */
    public List<JobMatchGroup> recentAlertMatches(UUID userId, LocalDate since) {
        return jdbcTemplate.query("""
                SELECT a.id, a.name, n.created_at::date, count(*) FROM job_alert_notifications n
                  JOIN job_alerts a ON a.id = n.alert_id
                 WHERE n.user_id = ? AND n.created_at >= ?
                 GROUP BY a.id, a.name, n.created_at::date
                """, (rs, row) -> new JobMatchGroup(rs.getObject(1, UUID.class), rs.getString(2),
                rs.getObject(3, LocalDate.class), rs.getInt(4)), userId, Date.valueOf(since));
    }

    /** Follow-ups (V8.5) due by {@code until} on open applications. */
    public List<Dated> followUps(UUID userId, LocalDate until) {
        return jdbcTemplate.query("""
                SELECT s.id, j.title, c.name, s.follow_up_on FROM saved_jobs s JOIN jobs j ON j.id = s.job_id
                  JOIN companies c ON c.id = j.company_id
                 WHERE s.user_id = ? AND s.follow_up_on <= ? AND s.status NOT IN ('REJECTED', 'WITHDRAWN')
                """, (rs, row) -> new Dated(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                rs.getObject(4, LocalDate.class)), userId, Date.valueOf(until));
    }

    /** Applications at the interview stage (V7.2), dated by when they got there. */
    public List<Dated> interviewStage(UUID userId) {
        return jdbcTemplate.query("""
                SELECT s.id, j.title, c.name, s.updated_at::date FROM saved_jobs s JOIN jobs j ON j.id = s.job_id
                  JOIN companies c ON c.id = j.company_id
                 WHERE s.user_id = ? AND s.status = 'INTERVIEW'
                """, (rs, row) -> new Dated(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                rs.getObject(4, LocalDate.class)), userId);
    }

    /** Unfinished learning items (V9.5) whose target date is by {@code until}. */
    public List<Dated> learningDue(UUID userId, LocalDate until) {
        return jdbcTemplate.query("""
                SELECT id, topic, skill_name, target_date FROM learning_items
                 WHERE user_id = ? AND status <> 'COMPLETED' AND target_date IS NOT NULL AND target_date <= ?
                """, (rs, row) -> new Dated(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                rs.getObject(4, LocalDate.class)), userId, Date.valueOf(until));
    }

    private static Timestamp ts(OffsetDateTime at) {
        return Timestamp.from(at.toInstant());
    }
}
