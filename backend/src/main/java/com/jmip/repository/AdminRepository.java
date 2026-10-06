package com.jmip.repository;

import com.jmip.dto.admin.AdminDtos.Activity;
import com.jmip.dto.admin.AdminDtos.Current;
import com.jmip.dto.admin.AdminDtos.Jobs;
import com.jmip.dto.admin.AdminDtos.ReasonCount;
import com.jmip.dto.admin.AdminDtos.SourceQuality;
import com.jmip.dto.admin.AdminDtos.Totals;
import com.jmip.dto.admin.AdminDtos.UserDetail;
import com.jmip.dto.admin.AdminDtos.UserSummary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * V8.9: read-only platform aggregates for the admin area, over the tables the application and
 * the ETL already fill. Every query is fixed; values are always bound. No column that holds a
 * credential, a code, resume text or a raw rejected record is ever selected.
 */
@Repository
public class AdminRepository {

    /** The ingestion job's name in Spring Batch (the reprocessing job is left out of data quality). */
    static final String INGEST_JOB = "ingestJobPostings";
    static final int REASON_LENGTH = 200;

    private static final RowMapper<UserSummary> USER = (rs, row) -> new UserSummary(rs.getObject("id", UUID.class),
            rs.getString("email"), rs.getString("full_name"), rs.getString("role"), rs.getBoolean("verified"),
            rs.getObject("created_at", OffsetDateTime.class), rs.getObject("updated_at", OffsetDateTime.class));

    private static final String USER_COLUMNS =
            "u.id, u.email, u.full_name, u.role, u.email_verified_at IS NOT NULL AS verified, u.created_at, u.updated_at";

    private final JdbcTemplate jdbcTemplate;

    public AdminRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public record UserCounts(long total, long verified, long admins, long newSince) {
    }

    public UserCounts userCounts(OffsetDateTime since) {
        return jdbcTemplate.queryForObject("""
                SELECT count(*) AS total, count(*) FILTER (WHERE email_verified_at IS NOT NULL) AS verified,
                       count(*) FILTER (WHERE role = 'ADMIN') AS admins, count(*) FILTER (WHERE created_at >= ?) AS new_since
                  FROM users
                """, (rs, row) -> new UserCounts(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4)), ts(since));
    }

    /** Accounts that saved, uploaded or changed something of their own since then. */
    public long activeUsers(OffsetDateTime since) {
        Timestamp at = ts(since);
        Long count = jdbcTemplate.queryForObject("""
                SELECT count(DISTINCT user_id) FROM (
                    SELECT user_id FROM saved_jobs WHERE updated_at >= ?
                    UNION ALL SELECT user_id FROM resumes WHERE user_id IS NOT NULL AND updated_at >= ?
                    UNION ALL SELECT user_id FROM job_alerts WHERE updated_at >= ?
                    UNION ALL SELECT user_id FROM career_goals WHERE updated_at >= ?
                    UNION ALL SELECT user_id FROM interview_sessions WHERE created_at >= ?
                    UNION ALL SELECT user_id FROM match_preferences WHERE updated_at >= ?
                ) activity
                """, Long.class, at, at, at, at, at, at);
        return count == null ? 0 : count;
    }

    public Jobs jobs(OffsetDateTime sevenDaysAgo, OffsetDateTime thirtyDaysAgo) {
        return jdbcTemplate.queryForObject("""
                SELECT count(*), count(*) FILTER (WHERE active), count(*) FILTER (WHERE NOT active),
                       count(*) FILTER (WHERE first_seen_at >= ?), count(*) FILTER (WHERE first_seen_at >= ?),
                       max(posted_date)
                  FROM jobs
                """, (rs, row) -> new Jobs(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5),
                rs.getObject(6, LocalDate.class)), ts(sevenDaysAgo), ts(thirtyDaysAgo));
    }

    /** Spring Batch keeps its times as server-local timestamps without a zone. */
    public long failedRunsSince(LocalDateTime since) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM batch_job_execution WHERE status = 'FAILED' AND start_time >= ?", Long.class,
                Timestamp.valueOf(since));
        return count == null ? 0 : count;
    }

    public Activity activity(int days, OffsetDateTime since) {
        Timestamp at = ts(since);
        return jdbcTemplate.queryForObject("""
                SELECT (SELECT count(*) FROM users WHERE created_at >= ?),
                       (SELECT count(*) FROM resumes WHERE uploaded_at >= ?),
                       (SELECT count(*) FROM saved_jobs WHERE saved_at >= ?),
                       (SELECT count(*) FROM saved_jobs WHERE applied_at >= ?),
                       (SELECT count(*) FROM interview_sessions WHERE created_at >= ?),
                       (SELECT count(*) FROM job_alert_notifications WHERE status = 'SENT' AND sent_at >= ?)
                """, (rs, row) -> new Activity(days, rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4),
                rs.getLong(5), rs.getLong(6)), at, at, at, at, at, at);
    }

    // ------------------------------------------------------------------ data quality

    public Totals totals() {
        return jdbcTemplate.queryForObject("""
                SELECT count(DISTINCT e.job_execution_id),
                       coalesce(sum(s.read_count), 0),
                       coalesce(sum(s.read_count - s.read_skip_count - s.process_skip_count - s.write_skip_count), 0),
                       coalesce(sum(s.read_skip_count + s.process_skip_count + s.write_skip_count), 0),
                       (SELECT coalesce(sum(m.records_loaded), 0) FROM etl_run_metrics m
                          JOIN batch_job_execution me ON me.job_execution_id = m.job_execution_id
                          JOIN batch_job_instance mi ON mi.job_instance_id = me.job_instance_id WHERE mi.job_name = ?),
                       (SELECT coalesce(sum(m.duplicates_skipped), 0) FROM etl_run_metrics m
                          JOIN batch_job_execution me ON me.job_execution_id = m.job_execution_id
                          JOIN batch_job_instance mi ON mi.job_instance_id = me.job_instance_id WHERE mi.job_name = ?),
                       (SELECT coalesce(sum(m.jobs_expired), 0) FROM etl_run_metrics m)
                  FROM batch_job_execution e
                  JOIN batch_job_instance i ON i.job_instance_id = e.job_instance_id
                  LEFT JOIN batch_step_execution s ON s.job_execution_id = e.job_execution_id
                 WHERE i.job_name = ?
                """, (rs, row) -> new Totals(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5),
                rs.getLong(6), rs.getLong(7)), INGEST_JOB, INGEST_JOB, INGEST_JOB);
    }

    public Current current(LocalDate today) {
        return jdbcTemplate.queryForObject("""
                SELECT count(*), count(*) FILTER (WHERE active), count(*) FILTER (WHERE NOT active),
                       count(*) FILTER (WHERE NOT active AND expires_at < ?),
                       count(*) FILTER (WHERE NOT active AND (expires_at IS NULL OR expires_at >= ?))
                  FROM jobs
                """, (rs, row) -> new Current(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5)),
                Date.valueOf(today), Date.valueOf(today));
    }

    /** The reason text only, shortened; the rejected record's raw input is never read. */
    public List<ReasonCount> topRejectionReasons(int limit) {
        return jdbcTemplate.query("""
                SELECT left(rejection_reason, ?) AS reason, count(*) FROM etl_rejected_record
                 GROUP BY 1 ORDER BY 2 DESC, 1 LIMIT ?
                """, (rs, row) -> new ReasonCount(rs.getString(1), rs.getLong(2)), REASON_LENGTH, limit);
    }

    public long rejectedRecords() {
        Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM etl_rejected_record", Long.class);
        return count == null ? 0 : count;
    }

    public List<SourceQuality> sources() {
        return jdbcTemplate.query("""
                SELECT s.id, s.code, s.name, s.source_type, s.active, s.last_ingested_at,
                       (SELECT count(*) FROM jobs j WHERE j.source_id = s.id),
                       (SELECT count(*) FROM jobs j WHERE j.source_id = s.id AND j.active),
                       (SELECT count(*) FROM jobs j WHERE j.source_id = s.id AND NOT j.active),
                       (SELECT coalesce(sum(r.records_loaded), 0) FROM etl_run_sources r WHERE r.source_id = s.id),
                       (SELECT coalesce(sum(r.records_seen_again), 0) FROM etl_run_sources r WHERE r.source_id = s.id)
                  FROM job_sources s ORDER BY s.code
                """, (rs, row) -> new SourceQuality(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getBoolean(5), rs.getObject(6, OffsetDateTime.class), rs.getLong(7), rs.getLong(8), rs.getLong(9),
                rs.getLong(10), rs.getLong(11)));
    }

    // ------------------------------------------------------------------ users

    public record UserPage(List<UserSummary> users, long total) {
    }

    /** Newest first, filtered by name or email, role and verification. */
    public UserPage users(String query, String role, Boolean verified, int limit, long offset) {
        List<String> where = new ArrayList<>(List.of("TRUE"));
        List<Object> args = new ArrayList<>();
        if (query != null) {
            String pattern = "%" + query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            where.add("(u.email ILIKE ? OR u.full_name ILIKE ?)");
            args.add(pattern);
            args.add(pattern);
        }
        if (role != null) {
            where.add("u.role = ?");
            args.add(role);
        }
        if (verified != null) {
            where.add(verified ? "u.email_verified_at IS NOT NULL" : "u.email_verified_at IS NULL");
        }
        String filter = String.join(" AND ", where);
        Long total = jdbcTemplate.queryForObject("SELECT count(*) FROM users u WHERE " + filter, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(limit);
        pageArgs.add(offset);
        List<UserSummary> users = jdbcTemplate.query("SELECT " + USER_COLUMNS + " FROM users u WHERE " + filter
                + " ORDER BY u.created_at DESC, u.id LIMIT ? OFFSET ?", USER, pageArgs.toArray());
        return new UserPage(users, total == null ? 0 : total);
    }

    public Optional<UserDetail> user(UUID id, String note) {
        List<UserSummary> found = jdbcTemplate.query("SELECT " + USER_COLUMNS + " FROM users u WHERE u.id = ?", USER, id);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(jdbcTemplate.queryForObject("""
                SELECT (SELECT count(*) FROM resumes WHERE user_id = ?),
                       (SELECT count(*) FROM saved_jobs WHERE user_id = ?),
                       (SELECT count(*) FROM saved_jobs WHERE user_id = ? AND status <> 'SAVED'),
                       (SELECT count(*) FROM job_alerts WHERE user_id = ?),
                       (SELECT count(*) FROM interview_sessions WHERE user_id = ?),
                       (SELECT count(*) FROM career_goals WHERE user_id = ?),
                       greatest((SELECT max(updated_at) FROM saved_jobs WHERE user_id = ?),
                                (SELECT max(updated_at) FROM resumes WHERE user_id = ?),
                                (SELECT max(updated_at) FROM job_alerts WHERE user_id = ?),
                                (SELECT max(updated_at) FROM career_goals WHERE user_id = ?),
                                (SELECT max(created_at) FROM interview_sessions WHERE user_id = ?))
                """, (rs, row) -> new UserDetail(found.get(0), rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4),
                rs.getLong(5), rs.getLong(6), rs.getObject(7, OffsetDateTime.class), note),
                id, id, id, id, id, id, id, id, id, id, id));
    }

    private static Timestamp ts(OffsetDateTime at) {
        return Timestamp.from(at.toInstant());
    }
}
