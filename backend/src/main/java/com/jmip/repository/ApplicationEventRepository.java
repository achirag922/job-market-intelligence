package com.jmip.repository;

import com.jmip.entity.ApplicationStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** V8.5: the status history of saved jobs, always written and read by owner id. */
@Repository
public class ApplicationEventRepository {

    /** One status a saved job reached, and when. */
    public record Event(UUID savedJobId, ApplicationStatus status, OffsetDateTime changedAt, boolean backfilled) {
    }

    private final JdbcTemplate jdbcTemplate;

    public ApplicationEventRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void record(UUID savedJobId, UUID userId, ApplicationStatus status, OffsetDateTime at) {
        jdbcTemplate.update("INSERT INTO saved_job_status_events (saved_job_id, user_id, status, changed_at) VALUES (?, ?, ?, ?)",
                savedJobId, userId, status.name(), Timestamp.from(at.toInstant()));
    }

    public List<Event> findByUser(UUID userId) {
        return jdbcTemplate.query("""
                SELECT saved_job_id, status, changed_at, backfilled FROM saved_job_status_events
                 WHERE user_id = ? ORDER BY changed_at, id
                """, (rs, row) -> new Event(rs.getObject(1, UUID.class), ApplicationStatus.valueOf(rs.getString(2)),
                rs.getObject(3, OffsetDateTime.class), rs.getBoolean(4)), userId);
    }
}
