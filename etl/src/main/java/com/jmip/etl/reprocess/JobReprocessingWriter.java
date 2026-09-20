package com.jmip.etl.reprocess;

import com.jmip.etl.load.EtlMetrics;
import com.jmip.etl.load.ReferenceDataCache;
import com.jmip.etl.model.JobClassification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Writes reprocessing results back over the existing rows.
 *
 * <p>Safe to run repeatedly, which matters because the dictionary and the classification
 * rules will change and the whole corpus will be reprocessed each time. Every write is
 * either an update in place or a delete-then-insert of rows this job owns, so a second
 * run produces exactly the same database state as the first.
 *
 * <p>Skill links and classification signals are cleared for the postings in the chunk
 * before being rewritten. Adding without removing would leave behind skills and evidence
 * that the current rules no longer produce — the history of every rule the project has
 * ever had, accumulated in the data.
 *
 * <p>All of it is batched per chunk: a handful of statements for a hundred postings
 * rather than a round trip each.
 */
@Component
public class JobReprocessingWriter implements ItemWriter<ReprocessedJob> {

    private static final Logger log = LoggerFactory.getLogger(JobReprocessingWriter.class);

    private static final String UPDATE_CLASSIFICATION = """
            UPDATE jobs SET job_category = ?, classification_confidence = ?, classified_at = ?
            WHERE id = ?
            """;

    private static final String INSERT_JOB_SKILL = """
            INSERT INTO job_skills (job_id, skill_id) VALUES (?, ?) ON CONFLICT DO NOTHING
            """;

    private static final String INSERT_SIGNAL = """
            INSERT INTO job_classification_signals (job_id, signal_type, signal_value, weight)
            VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING
            """;

    private final JdbcTemplate jdbcTemplate;
    private final ReferenceDataCache referenceData;
    private final EtlMetrics metrics;

    public JobReprocessingWriter(JdbcTemplate jdbcTemplate, ReferenceDataCache referenceData, EtlMetrics metrics) {
        this.jdbcTemplate = jdbcTemplate;
        this.referenceData = referenceData;
        this.metrics = metrics;
    }

    @Override
    public void write(Chunk<? extends ReprocessedJob> chunk) {
        List<ReprocessedJob> jobs = List.copyOf(chunk.getItems());
        if (jobs.isEmpty()) {
            return;
        }

        // One call creates whatever skills the improved dictionary now recognises, and
        // populates the cache for the id lookups below.
        Set<String> allSkills = new LinkedHashSet<>();
        jobs.forEach(job -> allSkills.addAll(job.skills()));
        referenceData.ensureSkillsByName(allSkills);

        List<Long> jobIds = jobs.stream().map(ReprocessedJob::id).toList();
        clearOwnedRows(jobIds);

        updateClassifications(jobs);
        insertSkillLinks(jobs);
        insertSignals(jobs);

        metrics.recordJobsLoaded(jobs.size());
        log.debug("Reprocessed {} postings", jobs.size());
    }

    /** Removes the rows this job rewrites, so a re-run cannot accumulate stale results. */
    private void clearOwnedRows(List<Long> jobIds) {
        String placeholders = String.join(", ", Collections.nCopies(jobIds.size(), "?"));
        Object[] ids = jobIds.toArray();
        jdbcTemplate.update("DELETE FROM job_skills WHERE job_id IN (" + placeholders + ")", ids);
        jdbcTemplate.update(
                "DELETE FROM job_classification_signals WHERE job_id IN (" + placeholders + ")", ids);
    }

    private void updateClassifications(List<ReprocessedJob> jobs) {
        Timestamp classifiedAt = Timestamp.from(Instant.now());
        jdbcTemplate.batchUpdate(UPDATE_CLASSIFICATION, jobs, jobs.size(),
                (PreparedStatement ps, ReprocessedJob job) -> {
                    JobClassification classification = job.classification();
                    ps.setString(1, classification.category());
                    ps.setBigDecimal(2, BigDecimal.valueOf(classification.confidence()));
                    ps.setTimestamp(3, classifiedAt);
                    ps.setLong(4, job.id());
                });
    }

    private void insertSkillLinks(List<ReprocessedJob> jobs) {
        List<long[]> links = new ArrayList<>();
        for (ReprocessedJob job : jobs) {
            for (String skill : job.skills()) {
                Long skillId = referenceData.skillId(skill);
                if (skillId == null) {
                    log.warn("Skill '{}' has no id after resolution, link skipped", skill);
                    continue;
                }
                links.add(new long[]{job.id(), skillId});
            }
        }
        if (links.isEmpty()) {
            return;
        }
        jdbcTemplate.batchUpdate(INSERT_JOB_SKILL, links, links.size(),
                (PreparedStatement ps, long[] link) -> {
                    ps.setLong(1, link[0]);
                    ps.setLong(2, link[1]);
                });
    }

    private void insertSignals(List<ReprocessedJob> jobs) {
        List<Object[]> rows = new ArrayList<>();
        for (ReprocessedJob job : jobs) {
            for (JobClassification.Signal signal : job.classification().signals()) {
                rows.add(new Object[]{job.id(), signal.type().name(), signal.value(), signal.weight()});
            }
        }
        if (rows.isEmpty()) {
            return;
        }
        jdbcTemplate.batchUpdate(INSERT_SIGNAL, rows, rows.size(),
                (PreparedStatement ps, Object[] row) -> {
                    ps.setLong(1, (Long) row[0]);
                    ps.setString(2, (String) row[1]);
                    ps.setString(3, (String) row[2]);
                    ps.setBigDecimal(4, BigDecimal.valueOf((Double) row[3]));
                });
    }
}
