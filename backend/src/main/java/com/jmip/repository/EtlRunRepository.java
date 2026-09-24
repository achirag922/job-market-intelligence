package com.jmip.repository;

import com.jmip.repository.projection.EtlRunRow;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads ETL run history straight from the Spring Batch job repository tables.
 *
 * <p>The backend does not depend on Spring Batch, and has no need to: the tables are a
 * documented, stable schema, and reading them is all monitoring requires. The ETL's own
 * counters are joined in from {@code etl_run_metrics}. Step counters are summed per
 * execution in one query, so a page of runs is one round trip regardless of step count.
 */
@Repository
public class EtlRunRepository {

    /** Enough of a failure message to recognise it; the full trace stays in the ETL log. */
    private static final int EXIT_MESSAGE_LIMIT = 500;

    private static final String SELECT = """
            SELECT e.job_execution_id, i.job_name, e.status, e.exit_code,
                   left(e.exit_message, %d) AS exit_message, e.start_time, e.end_time,
                   coalesce(sum(s.read_count), 0) AS read_count,
                   coalesce(sum(s.write_count), 0) AS write_count,
                   coalesce(sum(s.read_skip_count + s.process_skip_count + s.write_skip_count), 0) AS skip_count,
                   m.records_loaded, m.duplicates_skipped
              FROM batch_job_execution e
              JOIN batch_job_instance i ON i.job_instance_id = e.job_instance_id
              LEFT JOIN batch_step_execution s ON s.job_execution_id = e.job_execution_id
              LEFT JOIN etl_run_metrics m ON m.job_execution_id = e.job_execution_id
            """.formatted(EXIT_MESSAGE_LIMIT);

    private static final String GROUP_AND_ORDER = """
             GROUP BY e.job_execution_id, i.job_name, m.records_loaded, m.duplicates_skipped
             ORDER BY e.job_execution_id DESC
             LIMIT ? OFFSET ?
            """;

    private static final RowMapper<EtlRunRow> ROW_MAPPER = EtlRunRepository::mapRow;

    private final JdbcTemplate jdbcTemplate;

    public EtlRunRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * @param jobName only runs of this job, or every job when null
     * @return newest first
     */
    public List<EtlRunRow> findRuns(String jobName, int limit, long offset) {
        List<Object> args = new ArrayList<>();
        String where = "";
        if (jobName != null) {
            where = " WHERE i.job_name = ?";
            args.add(jobName);
        }
        args.add(limit);
        args.add(offset);
        return jdbcTemplate.query(SELECT + where + GROUP_AND_ORDER, ROW_MAPPER, args.toArray());
    }

    public long countRuns(String jobName) {
        Long count = jobName == null
                ? jdbcTemplate.queryForObject("SELECT count(*) FROM batch_job_execution", Long.class)
                : jdbcTemplate.queryForObject("""
                        SELECT count(*) FROM batch_job_execution e
                          JOIN batch_job_instance i ON i.job_instance_id = e.job_instance_id
                         WHERE i.job_name = ?
                        """, Long.class, jobName);
        return count == null ? 0 : count;
    }

    private static EtlRunRow mapRow(ResultSet rs, int rowNumber) throws SQLException {
        return new EtlRunRow(
                rs.getLong("job_execution_id"),
                rs.getString("job_name"),
                rs.getString("status"),
                rs.getString("exit_code"),
                rs.getString("exit_message"),
                toLocalDateTime(rs.getTimestamp("start_time")),
                toLocalDateTime(rs.getTimestamp("end_time")),
                rs.getLong("read_count"),
                rs.getLong("write_count"),
                rs.getLong("skip_count"),
                rs.getObject("records_loaded", Long.class),
                rs.getObject("duplicates_skipped", Long.class));
    }

    private static LocalDateTime toLocalDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }
}
