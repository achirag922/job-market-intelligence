package com.jmip.repository;

import com.jmip.repository.projection.SkillPeriodRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * Reads and rebuilds the historical skill demand snapshots.
 *
 * <p>Plain JDBC rather than JPA: the rebuild is one set-based statement that the database
 * should execute by itself, and loading rows into a persistence context only to write
 * them straight back would be slower and no clearer.
 */
@Repository
public class SkillTrendRepository {

    private static final Logger log = LoggerFactory.getLogger(SkillTrendRepository.class);

    /**
     * Recomputes every period from the postings themselves.
     *
     * <p>Postings with no date cannot belong to a period and are excluded; they are still
     * counted by the non-historical analytics, which do not need a date.
     */
    private static final String REBUILD = """
            WITH monthly_totals AS (
                SELECT date_trunc('month', posted_date)::date AS period_start,
                       count(*) AS total_jobs
                FROM jobs
                WHERE posted_date IS NOT NULL
                GROUP BY 1
            ),
            monthly_skills AS (
                SELECT date_trunc('month', j.posted_date)::date AS period_start,
                       js.skill_id,
                       count(*) AS job_count
                FROM jobs j
                JOIN job_skills js ON js.job_id = j.id
                WHERE j.posted_date IS NOT NULL
                GROUP BY 1, 2
            )
            INSERT INTO skill_demand_snapshot (period_start, skill_id, job_count, total_jobs)
            SELECT ms.period_start, ms.skill_id, ms.job_count, mt.total_jobs
            FROM monthly_skills ms
            JOIN monthly_totals mt ON mt.period_start = ms.period_start
            """;

    private static final String READ_WINDOW = """
            SELECT sds.period_start, sds.skill_id, s.name AS skill_name, s.category,
                   sds.job_count, sds.total_jobs
            FROM skill_demand_snapshot sds
            JOIN skills s ON s.id = sds.skill_id
            WHERE sds.period_start >= ?
            ORDER BY sds.period_start ASC, sds.job_count DESC
            """;

    private final JdbcTemplate jdbcTemplate;

    public SkillTrendRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Rebuilds the whole history from the current postings.
     *
     * <p>A full delete and reinsert, in one transaction. It is idempotent, which matters
     * because the ETL re-runs, and it is the only way to drop a period that no longer has
     * any postings. Readers keep seeing the previous history until the transaction
     * commits, so the table is never observably empty.
     *
     * @return how many snapshot rows the history now holds
     */
    @Transactional
    public int rebuild() {
        jdbcTemplate.update("DELETE FROM skill_demand_snapshot");
        int written = jdbcTemplate.update(REBUILD);
        log.info("Rebuilt skill demand history: {} snapshot rows across {} periods",
                written, countPeriods());
        return written;
    }

    /** Distinct periods, newest first. Used to size a trend window to the data that exists. */
    public List<LocalDate> findPeriods() {
        return jdbcTemplate.queryForList(
                "SELECT DISTINCT period_start FROM skill_demand_snapshot ORDER BY period_start DESC",
                LocalDate.class);
    }

    public List<SkillPeriodRow> findFromPeriod(LocalDate earliestPeriod) {
        return jdbcTemplate.query(READ_WINDOW, (rs, rowNum) -> new SkillPeriodRow(
                rs.getObject("period_start", LocalDate.class),
                rs.getLong("skill_id"),
                rs.getString("skill_name"),
                rs.getString("category"),
                rs.getInt("job_count"),
                rs.getInt("total_jobs")), earliestPeriod);
    }

    public int countSnapshots() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM skill_demand_snapshot", Integer.class);
        return count == null ? 0 : count;
    }

    private int countPeriods() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(DISTINCT period_start) FROM skill_demand_snapshot", Integer.class);
        return count == null ? 0 : count;
    }
}
