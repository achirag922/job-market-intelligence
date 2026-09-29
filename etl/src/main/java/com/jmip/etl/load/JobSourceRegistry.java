package com.jmip.etl.load;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * V8.1: the job sources one ETL run meets, and what it did with each.
 *
 * <p>A source is registered the first time a run sees its label, typed by the feed that
 * introduced it (a JSON or CSV file today, an API later). Registration is an upsert, so
 * running the same feed twice finds the same source. Counts are kept per source label and
 * written at the end of the run next to the V6.5 run metrics; status and timing stay in
 * Spring Batch's own tables.
 */
@Component
public class JobSourceRegistry implements JobSourceStatus {

    private static final Logger log = LoggerFactory.getLogger(JobSourceRegistry.class);

    private static final String UPSERT_SOURCE = """
            INSERT INTO job_sources (code, name, source_type) VALUES (?, ?, ?)
            ON CONFLICT (code) DO UPDATE
                SET source_type = CASE WHEN job_sources.source_type = 'OTHER' THEN EXCLUDED.source_type
                                       ELSE job_sources.source_type END
            RETURNING active
            """;

    private final JdbcTemplate jdbcTemplate;
    private final Map<String, Boolean> activeByCode = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> loaded = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> seenAgain = new ConcurrentHashMap<>();
    private volatile Long runId;
    private volatile String feedName;
    private volatile String feedType;

    public JobSourceRegistry(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Starts a run. Only the file name is kept, never its path. */
    public void beginRun(long executionId, String inputFile) {
        activeByCode.clear();
        loaded.clear();
        seenAgain.clear();
        runId = executionId;
        if (inputFile == null || inputFile.isBlank()) {
            feedName = null;
            feedType = null;
        } else {
            Path file = Path.of(inputFile).getFileName();
            feedName = file == null ? null : file.toString();
            feedType = typeOf(feedName);
        }
    }

    static String typeOf(String fileName) {
        String name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        if (name.endsWith(".json")) {
            return "FILE_JSON";
        }
        return name.endsWith(".csv") || name.endsWith(".tsv") ? "FILE_CSV" : "OTHER";
    }

    /** Registers the source on first sight in this run, and says whether it is active. */
    @Override
    public boolean isActive(String sourceCode) {
        return activeByCode.computeIfAbsent(sourceCode, code -> {
            Boolean active = jdbcTemplate.queryForObject(UPSERT_SOURCE, Boolean.class, code, code,
                    feedType == null ? "OTHER" : feedType);
            return Boolean.TRUE.equals(active);
        });
    }

    public void recordLoaded(String sourceCode, long count) {
        if (count > 0) {
            loaded.computeIfAbsent(sourceCode, code -> new AtomicLong()).addAndGet(count);
        }
    }

    public void recordSeenAgain(String sourceCode, long count) {
        if (count > 0) {
            seenAgain.computeIfAbsent(sourceCode, code -> new AtomicLong()).addAndGet(count);
        }
    }

    public Long runId() {
        return runId;
    }

    public String feedName() {
        return feedName;
    }

    public String feedType() {
        return feedType;
    }

    /** One row per source the run met, and each source's last-ingested time and run. */
    public void finishRun() {
        if (runId == null) {
            return;
        }
        for (String code : activeByCode.keySet()) {
            long newRows = loaded.getOrDefault(code, new AtomicLong()).get();
            long again = seenAgain.getOrDefault(code, new AtomicLong()).get();
            jdbcTemplate.update("""
                    INSERT INTO etl_run_sources (job_execution_id, source_id, records_loaded, records_seen_again)
                    SELECT ?, id, ?, ? FROM job_sources WHERE code = ?
                    ON CONFLICT (job_execution_id, source_id) DO UPDATE
                        SET records_loaded = EXCLUDED.records_loaded, records_seen_again = EXCLUDED.records_seen_again
                    """, runId, newRows, again, code);
            if (Boolean.TRUE.equals(activeByCode.get(code))) {
                jdbcTemplate.update("UPDATE job_sources SET last_ingested_at = now(), last_run_execution_id = ? WHERE code = ?",
                        runId, code);
            }
        }
        log.info("etl.sources executionId={} sources={} loaded={} seenAgain={}", runId, activeByCode.keySet(), loaded, seenAgain);
    }
}
