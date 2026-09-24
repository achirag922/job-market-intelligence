package com.jmip.etl.load;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Persists the {@link EtlMetrics} counters of a finished run to {@code etl_run_metrics},
 * keyed by the Spring Batch job execution id, so the monitoring API can report them next
 * to what Spring Batch already records.
 */
@Repository
public class EtlRunMetricsRepository {

    private static final String UPSERT = """
            INSERT INTO etl_run_metrics (job_execution_id, records_loaded, duplicates_skipped, skill_links_created)
            VALUES (?, ?, ?, ?)
            ON CONFLICT (job_execution_id) DO UPDATE
               SET records_loaded = EXCLUDED.records_loaded,
                   duplicates_skipped = EXCLUDED.duplicates_skipped,
                   skill_links_created = EXCLUDED.skill_links_created,
                   recorded_at = now()
            """;

    private final JdbcTemplate jdbcTemplate;

    public EtlRunMetricsRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void save(long jobExecutionId, EtlMetrics metrics) {
        jdbcTemplate.update(UPSERT, jobExecutionId, metrics.jobsLoaded(), metrics.duplicatesSkipped(),
                metrics.skillLinksCreated());
    }
}
