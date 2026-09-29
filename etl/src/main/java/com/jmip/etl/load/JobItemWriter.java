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
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Writes one chunk of postings, together with the reference rows and skill links they
 * need.
 *
 * <p>Duplicate handling happens here rather than earlier, because the database is the only
 * place that can answer the question correctly under concurrency. Three layers apply:
 *
 * <ol>
 *   <li>Records repeated <em>within</em> the chunk are collapsed in memory.</li>
 *   <li>Records already present from an earlier chunk or an earlier run are found by their
 *       identity keys (V8.2): the source's own job id first, then the source URL, then
 *       {@code content_fingerprint}. A match updates the existing job's last-seen metadata
 *       instead of creating a row.</li>
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
                              job_category, classification_confidence, classified_at,
                              source_job_id, last_seen_run_id, expires_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT DO NOTHING
            """;

    private static final String INSERT_CLASSIFICATION_SIGNAL = """
            INSERT INTO job_classification_signals (job_id, signal_type, signal_value, weight)
            VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING
            """;

    private static final String INSERT_JOB_SKILL = """
            INSERT INTO job_skills (job_id, skill_id) VALUES (?, ?) ON CONFLICT DO NOTHING
            """;

    private static final String FINGERPRINT_KEY = "fp\u0000";

    private final JdbcTemplate jdbcTemplate;
    private final ReferenceDataCache referenceData;
    private final EtlMetrics metrics;
    private final JobSourceRegistry sources;
    private final Clock clock;

    public JobItemWriter(JdbcTemplate jdbcTemplate, ReferenceDataCache referenceData, EtlMetrics metrics,
                         JobSourceRegistry sources, Clock clock) {
        this.clock = clock;
        this.jdbcTemplate = jdbcTemplate;
        this.referenceData = referenceData;
        this.metrics = metrics;
        this.sources = sources;
    }

    @Override
    public void write(Chunk<? extends TransformedJob> chunk) {
        List<? extends TransformedJob> items = chunk.getItems();
        if (items.isEmpty()) {
            return;
        }

        // Layer 1: collapse repeats inside this chunk, keeping the first occurrence. V8.2: a
        // record repeats another when it shares any of its identity keys.
        List<TransformedJob> unique = new ArrayList<>();
        Set<String> keysSeen = new HashSet<>();
        for (TransformedJob job : items) {
            List<String> keys = identityKeys(job);
            if (keys.stream().noneMatch(keysSeen::contains)) {
                unique.add(job);
            }
            keysSeen.addAll(keys);
        }
        int repeatsWithinChunk = items.size() - unique.size();

        referenceData.ensureFor(unique);

        // Layer 2: which of these already exist?
        Map<String, Long> before = findExistingJobIds(unique);
        List<TransformedJob> toInsert = unique.stream().filter(job -> resolve(job, before) == null).toList();

        if (!toInsert.isEmpty()) {
            insertJobs(toInsert);
        }

        // Re-read so that rows just written, and any stopped by layer 3, are accounted for.
        Map<String, Long> after = toInsert.isEmpty() ? before : findExistingJobIds(unique);
        Set<Long> existingIds = new HashSet<>(before.values());
        Map<String, Long> jobIds = new HashMap<>();
        List<TransformedJob> seenAgain = new ArrayList<>();
        List<TransformedJob> inserted = new ArrayList<>();
        for (TransformedJob job : unique) {
            Long id = resolve(job, after);
            if (id == null) {
                continue;
            }
            jobIds.put(job.contentFingerprint(), id);
            if (resolve(job, before) != null) {
                seenAgain.add(job);
            } else if (!existingIds.contains(id)) {
                inserted.add(job);
            }
        }

        long loaded = inserted.size();
        long duplicates = items.size() - loaded;
        metrics.recordJobsLoaded(loaded);
        metrics.recordDuplicates(duplicates);
        recordSeen(inserted, seenAgain, jobIds);
        updateStatus(unique, seenAgain, jobIds);

        int blockedByUniqueIndex = toInsert.size() - (int) loaded;
        if (blockedByUniqueIndex > 0) {
            log.debug("{} records in this chunk were stopped by a unique index", blockedByUniqueIndex);
        }
        if (repeatsWithinChunk > 0) {
            log.debug("{} records in this chunk repeated another record in the same chunk", repeatsWithinChunk);
        }

        linkSkills(unique, jobIds);
        linkClassificationSignals(unique, jobIds);
    }

    /**
     * V8.2: the keys that identify a posting, strongest first: the source's own job id, then
     * the source's URL, then the content fingerprint (normalised title, company, location and
     * posted date, so a shared title alone never makes two postings the same).
     */
    private static List<String> identityKeys(TransformedJob job) {
        List<String> keys = new ArrayList<>(3);
        if (job.sourceJobId() != null) {
            keys.add(sourceJobKey(job.source(), job.sourceJobId()));
        }
        if (job.sourceUrl() != null) {
            keys.add(sourceUrlKey(job.source(), job.sourceUrl()));
        }
        keys.add(FINGERPRINT_KEY + job.contentFingerprint());
        return keys;
    }

    /** The existing job this posting is, by its strongest key that matches; null when new. */
    private static Long resolve(TransformedJob job, Map<String, Long> idsByKey) {
        for (String key : identityKeys(job)) {
            Long id = idsByKey.get(key);
            if (id != null) {
                return id;
            }
        }
        return null;
    }

    private static String sourceJobKey(String source, String sourceJobId) {
        return "id\u0000" + source + "\u0000" + sourceJobId;
    }

    private static String sourceUrlKey(String source, String sourceUrl) {
        return "url\u0000" + source + "\u0000" + sourceUrl;
    }

    /**
     * V8.1: postings that were already loaded are marked as seen by this run, and each source's
     * new and repeated postings are counted. V8.2: a later expiry date from the source is kept.
     */
    private void recordSeen(List<TransformedJob> inserted, List<TransformedJob> seenAgain, Map<String, Long> jobIds) {
        Map<String, Long> newRows = new HashMap<>();
        Map<String, Long> again = new HashMap<>();
        inserted.forEach(job -> newRows.merge(job.source(), 1L, Long::sum));
        seenAgain.forEach(job -> again.merge(job.source(), 1L, Long::sum));
        newRows.forEach(sources::recordLoaded);
        again.forEach(sources::recordSeenAgain);
        if (seenAgain.isEmpty()) {
            return;
        }
        Long runId = sources.runId();
        jdbcTemplate.batchUpdate("""
                UPDATE jobs SET last_seen_at = now(), last_seen_run_id = ?, expires_at = COALESCE(?, expires_at)
                 WHERE id = ?
                """, seenAgain, seenAgain.size(), (PreparedStatement ps, TransformedJob job) -> {
            setLong(ps, 1, runId);
            setDate(ps, 2, job.expiresAt());
            ps.setLong(3, jobIds.get(job.contentFingerprint()));
        });
    }

    /**
     * V8.2: a posting its source marks closed becomes inactive (counted as expired); one the
     * source lists as open again is reactivated, unless its expiry date has passed.
     */
    private void updateStatus(List<TransformedJob> jobs, List<TransformedJob> seenAgain, Map<String, Long> jobIds) {
        List<Long> closed = jobs.stream().filter(TransformedJob::closed)
                .map(job -> jobIds.get(job.contentFingerprint())).filter(java.util.Objects::nonNull).toList();
        if (!closed.isEmpty()) {
            metrics.recordExpired(jdbcTemplate.update("UPDATE jobs SET active = FALSE, deactivated_at = now() "
                    + "WHERE active AND id IN (" + placeholders(closed.size()) + ")", closed.toArray()));
        }
        List<Object> reopened = new ArrayList<>();
        reopened.add(Date.valueOf(LocalDate.now(clock)));
        seenAgain.stream().filter(job -> !job.closed()).forEach(job -> reopened.add(jobIds.get(job.contentFingerprint())));
        if (reopened.size() > 1) {
            jdbcTemplate.update("UPDATE jobs SET active = TRUE, deactivated_at = NULL "
                    + "WHERE NOT active AND (expires_at IS NULL OR expires_at >= ?) AND id IN ("
                    + placeholders(reopened.size() - 1) + ")", reopened.toArray());
        }
    }

    /** Existing job ids by identity key, one indexed query per kind of key. */
    private Map<String, Long> findExistingJobIds(List<TransformedJob> jobs) {
        Map<String, Long> found = new HashMap<>();
        List<Object> fingerprints = new ArrayList<>();
        List<Object> byJobId = new ArrayList<>();
        List<Object> byUrl = new ArrayList<>();
        for (TransformedJob job : jobs) {
            fingerprints.add(job.contentFingerprint());
            if (job.sourceJobId() != null) {
                byJobId.add(job.source());
                byJobId.add(job.sourceJobId());
            }
            if (job.sourceUrl() != null) {
                byUrl.add(job.source());
                byUrl.add(job.sourceUrl());
            }
        }
        if (!byJobId.isEmpty()) {
            jdbcTemplate.query("SELECT j.id, v.code, v.key FROM (VALUES " + pairs(byJobId.size() / 2) + ") AS v(code, key) "
                            + "JOIN job_sources s ON s.code = v.code "
                            + "JOIN jobs j ON j.source_id = s.id AND j.source_job_id = v.key WHERE j.source_job_id IS NOT NULL",
                    (RowCallbackHandler) rs -> found.put(sourceJobKey(rs.getString(2), rs.getString(3)), rs.getLong(1)),
                    byJobId.toArray());
        }
        if (!byUrl.isEmpty()) {
            jdbcTemplate.query("SELECT j.id, v.code, v.key FROM (VALUES " + pairs(byUrl.size() / 2) + ") AS v(code, key) "
                            + "JOIN jobs j ON j.source = v.code AND j.source_url = v.key WHERE j.source_url IS NOT NULL",
                    (RowCallbackHandler) rs -> found.put(sourceUrlKey(rs.getString(2), rs.getString(3)), rs.getLong(1)),
                    byUrl.toArray());
        }
        jdbcTemplate.query("SELECT id, content_fingerprint FROM jobs WHERE content_fingerprint IN ("
                        + placeholders(fingerprints.size()) + ")",
                (RowCallbackHandler) rs -> found.put(FINGERPRINT_KEY + rs.getString(2), rs.getLong(1)),
                fingerprints.toArray());
        return found;
    }

    private static String placeholders(int count) {
        return String.join(", ", Collections.nCopies(count, "?"));
    }

    private static String pairs(int count) {
        return String.join(", ", Collections.nCopies(count, "(?, ?)"));
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
            ps.setString(18, job.sourceJobId());
            setLong(ps, 19, sources.runId());
            setDate(ps, 20, job.expiresAt());
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

    private static void setDate(PreparedStatement ps, int index, LocalDate value) throws java.sql.SQLException {
        if (value == null) {
            ps.setNull(index, Types.DATE);
        } else {
            ps.setDate(index, Date.valueOf(value));
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
