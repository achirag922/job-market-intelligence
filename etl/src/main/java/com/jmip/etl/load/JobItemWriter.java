package com.jmip.etl.load;

import com.jmip.etl.model.JobClassification;
import com.jmip.etl.model.TransformedJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes one chunk of postings, together with the reference rows and skill links they
 * need.
 *
 * <p>Duplicate handling happens here rather than earlier, because the database is the only
 * place that can answer the question correctly under concurrency. Three layers apply:
 *
 * <ol>
 *   <li>Records repeated <em>within</em> the chunk are collapsed by fingerprint in memory.</li>
 *   <li>Records already present from an earlier chunk or an earlier run are found by a
 *       single lookup on {@code content_fingerprint}.</li>
 *   <li>Anything that still races through is stopped by {@code ON CONFLICT DO NOTHING},
 *       which also covers the separate {@code (source, source_url)} unique index.</li>
 * </ol>
 *
 * <p>A duplicate never creates a second job row, and never creates a second
 * {@code job_skills} link: the skill insert is itself {@code ON CONFLICT DO NOTHING}
 * against the composite primary key.
 *
 * <p>The whole chunk is one transaction, managed by Spring Batch.
 */
@Component
public class JobItemWriter implements ItemWriter<TransformedJob> {

    private static final Logger log = LoggerFactory.getLogger(JobItemWriter.class);

    private static final String INSERT_JOB = """
            INSERT INTO jobs (title, company_id, location_id, description, employment_type,
                              experience_min, experience_max, salary_min, salary_max, currency,
                              posted_date, source, source_url, content_fingerprint,
                              job_category, classification_confidence, classified_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT DO NOTHING
            """;

    private static final String INSERT_CLASSIFICATION_SIGNAL = """
            INSERT INTO job_classification_signals (job_id, signal_type, signal_value, weight)
            VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING
            """;

    private static final String INSERT_JOB_SKILL = """
            INSERT INTO job_skills (job_id, skill_id) VALUES (?, ?) ON CONFLICT DO NOTHING
            """;

    private final JdbcTemplate jdbcTemplate;
    private final ReferenceDataCache referenceData;
    private final EtlMetrics metrics;

    public JobItemWriter(JdbcTemplate jdbcTemplate, ReferenceDataCache referenceData, EtlMetrics metrics) {
        this.jdbcTemplate = jdbcTemplate;
        this.referenceData = referenceData;
        this.metrics = metrics;
    }

    @Override
    public void write(Chunk<? extends TransformedJob> chunk) {
        List<? extends TransformedJob> items = chunk.getItems();
        if (items.isEmpty()) {
            return;
        }

        // Layer 1: collapse repeats inside this chunk, keeping the first occurrence.
        Map<String, TransformedJob> byFingerprint = new LinkedHashMap<>();
        for (TransformedJob job : items) {
            byFingerprint.putIfAbsent(job.contentFingerprint(), job);
        }
        int repeatsWithinChunk = items.size() - byFingerprint.size();

        referenceData.ensureFor(byFingerprint.values());

        // Layer 2: which of these already exist?
        Map<String, Long> existing = findExistingJobIds(byFingerprint.keySet());
        List<TransformedJob> toInsert = byFingerprint.values().stream()
                .filter(job -> !existing.containsKey(job.contentFingerprint()))
                .toList();

        if (!toInsert.isEmpty()) {
            insertJobs(toInsert);
        }

        // Re-read so that rows just written, and any stopped by layer 3, are accounted for.
        Map<String, Long> jobIds = findExistingJobIds(byFingerprint.keySet());

        long loaded = jobIds.size() - existing.size();
        long duplicates = items.size() - loaded;
        metrics.recordJobsLoaded(loaded);
        metrics.recordDuplicates(duplicates);

        int blockedBySourceUrl = toInsert.size() - (int) loaded;
        if (blockedBySourceUrl > 0) {
            log.debug("{} records in this chunk were rejected by the (source, source_url) unique index",
                    blockedBySourceUrl);
        }
        if (repeatsWithinChunk > 0) {
            log.debug("{} records in this chunk repeated another record in the same chunk", repeatsWithinChunk);
        }

        linkSkills(byFingerprint.values(), jobIds);
        linkClassificationSignals(byFingerprint.values(), jobIds);
    }

    private Map<String, Long> findExistingJobIds(java.util.Collection<String> fingerprints) {
        if (fingerprints.isEmpty()) {
            return Map.of();
        }
        String sql = "SELECT id, content_fingerprint FROM jobs WHERE content_fingerprint IN ("
                + String.join(", ", Collections.nCopies(fingerprints.size(), "?")) + ")";
        Map<String, Long> found = new HashMap<>();
        jdbcTemplate.query(sql,
                (RowCallbackHandler) rs -> found.put(rs.getString("content_fingerprint"), rs.getLong("id")),
                fingerprints.toArray());
        return found;
    }

    private void insertJobs(List<TransformedJob> jobs) {
        jdbcTemplate.batchUpdate(INSERT_JOB, jobs, jobs.size(), (PreparedStatement ps, TransformedJob job) -> {
            ps.setString(1, job.title());
            setLong(ps, 2, referenceData.companyId(job));
            setLong(ps, 3, referenceData.locationId(job));
            ps.setString(4, job.description());
            ps.setString(5, job.employmentType());
            setInteger(ps, 6, job.experienceMin());
            setInteger(ps, 7, job.experienceMax());
            setBigDecimal(ps, 8, job.salaryMin());
            setBigDecimal(ps, 9, job.salaryMax());
            ps.setString(10, job.currency());
            if (job.postedDate() == null) {
                ps.setNull(11, Types.DATE);
            } else {
                ps.setDate(11, Date.valueOf(job.postedDate()));
            }
            ps.setString(12, job.source());
            ps.setString(13, job.sourceUrl());
            ps.setString(14, job.contentFingerprint());
            // The schema requires category, confidence and timestamp together or none of
            // them, so all three move as a unit.
            if (job.classification() == null) {
                ps.setNull(15, Types.VARCHAR);
                ps.setNull(16, Types.NUMERIC);
                ps.setNull(17, Types.TIMESTAMP_WITH_TIMEZONE);
            } else {
                ps.setString(15, job.classification().category());
                ps.setBigDecimal(16, BigDecimal.valueOf(job.classification().confidence()));
                ps.setTimestamp(17, java.sql.Timestamp.from(java.time.Instant.now()));
            }
        });
    }

    /**
     * Records why each posting was classified as it was.
     *
     * <p>{@code ON CONFLICT DO NOTHING} against the composite key, so re-ingesting the
     * same posting cannot duplicate its evidence.
     */
    private void linkClassificationSignals(java.util.Collection<TransformedJob> jobs, Map<String, Long> jobIds) {
        List<Object[]> rows = new ArrayList<>();
        for (TransformedJob job : jobs) {
            Long jobId = jobIds.get(job.contentFingerprint());
            if (jobId == null || job.classification() == null) {
                continue;
            }
            for (JobClassification.Signal signal : job.classification().signals()) {
                rows.add(new Object[]{jobId, signal.type().name(), signal.value(), signal.weight()});
            }
        }
        if (rows.isEmpty()) {
            return;
        }
        jdbcTemplate.batchUpdate(INSERT_CLASSIFICATION_SIGNAL, rows, rows.size(),
                (PreparedStatement ps, Object[] row) -> {
                    ps.setLong(1, (Long) row[0]);
                    ps.setString(2, (String) row[1]);
                    ps.setString(3, (String) row[2]);
                    ps.setBigDecimal(4, BigDecimal.valueOf((Double) row[3]));
                });
    }

    private void linkSkills(java.util.Collection<TransformedJob> jobs, Map<String, Long> jobIds) {
        List<long[]> links = new ArrayList<>();
        for (TransformedJob job : jobs) {
            Long jobId = jobIds.get(job.contentFingerprint());
            if (jobId == null) {
                continue;
            }
            for (String skill : job.skills()) {
                Long skillId = referenceData.skillId(skill);
                if (skillId == null) {
                    log.warn("Skill '{}' has no id after reference resolution, link skipped", skill);
                    continue;
                }
                links.add(new long[]{jobId, skillId});
            }
        }
        if (links.isEmpty()) {
            return;
        }
        int[][] written = jdbcTemplate.batchUpdate(INSERT_JOB_SKILL, links, links.size(),
                (PreparedStatement ps, long[] link) -> {
                    ps.setLong(1, link[0]);
                    ps.setLong(2, link[1]);
                });
        long created = 0;
        for (int[] batch : written) {
            for (int rows : batch) {
                if (rows > 0) {
                    created += rows;
                }
            }
        }
        metrics.recordSkillLinks(created);
    }

    private static void setLong(PreparedStatement ps, int index, Long value) throws java.sql.SQLException {
        if (value == null) {
            ps.setNull(index, Types.BIGINT);
        } else {
            ps.setLong(index, value);
        }
    }

    private static void setInteger(PreparedStatement ps, int index, Integer value) throws java.sql.SQLException {
        if (value == null) {
            ps.setNull(index, Types.SMALLINT);
        } else {
            ps.setInt(index, value);
        }
    }

    private static void setBigDecimal(PreparedStatement ps, int index, java.math.BigDecimal value)
            throws java.sql.SQLException {
        if (value == null) {
            ps.setNull(index, Types.NUMERIC);
        } else {
            ps.setBigDecimal(index, value);
        }
    }
}
