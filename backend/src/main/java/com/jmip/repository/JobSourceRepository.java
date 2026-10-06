package com.jmip.repository;

import com.jmip.dto.etl.JobSourceResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * V8.1: read-only access to the job sources the ETL registers. Sources are created and
 * updated by the ETL; the one write here is an admin's on/off switch (V8.9).
 */
@Repository
public class JobSourceRepository {

    private static final String SELECT = """
            SELECT s.id, s.code, s.name, s.source_type, s.active, s.created_at, s.last_ingested_at,
                   s.last_run_execution_id, count(j.id) AS job_count
              FROM job_sources s LEFT JOIN jobs j ON j.source_id = s.id
            """;

    private static final RowMapper<JobSourceResponse> ROW = (rs, row) -> new JobSourceResponse(
            rs.getLong("id"), rs.getString("code"), rs.getString("name"), rs.getString("source_type"),
            rs.getBoolean("active"), rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("last_ingested_at", OffsetDateTime.class), rs.getObject("last_run_execution_id", Long.class),
            rs.getLong("job_count"), null);

    private final JdbcTemplate jdbcTemplate;

    public JobSourceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<JobSourceResponse> findAll() {
        return jdbcTemplate.query(SELECT + " GROUP BY s.id ORDER BY s.code", ROW);
    }

    public Optional<JobSourceResponse> findById(long id) {
        return jdbcTemplate.query(SELECT + " WHERE s.id = ? GROUP BY s.id", ROW, id).stream().findFirst();
    }

    /**
     * V8.9: an admin turns a source on or off; the ETL then rejects an inactive source's records
     * (V8.1). The only write here, and it changes nothing but the flag.
     *
     * @return whether the source exists
     */
    public boolean setActive(long id, boolean active) {
        return jdbcTemplate.update("UPDATE job_sources SET active = ? WHERE id = ?", active, id) == 1;
    }

    /** The latest runs that met the source, with Spring Batch's own status and timing. */
    public List<JobSourceResponse.Run> findRecentRuns(long sourceId, int limit) {
        return jdbcTemplate.query("""
                SELECT rs.job_execution_id, e.status, e.start_time, e.end_time, m.feed_name,
                       rs.records_loaded, rs.records_seen_again
                  FROM etl_run_sources rs
                  JOIN batch_job_execution e ON e.job_execution_id = rs.job_execution_id
                  LEFT JOIN etl_run_metrics m ON m.job_execution_id = rs.job_execution_id
                 WHERE rs.source_id = ?
                 ORDER BY rs.job_execution_id DESC
                 LIMIT ?
                """, (rs, row) -> new JobSourceResponse.Run(rs.getLong(1), rs.getString(2), local(rs.getTimestamp(3)),
                local(rs.getTimestamp(4)), rs.getString(5), rs.getLong(6), rs.getLong(7)), sourceId, limit);
    }

    private static java.time.LocalDateTime local(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }
}
