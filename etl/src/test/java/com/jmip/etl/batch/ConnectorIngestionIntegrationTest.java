package com.jmip.etl.batch;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.test.JobLauncherTestUtils;
import org.springframework.batch.test.context.SpringBatchTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V9.1: two connectors through one pipeline. The file connector reads the existing JSON sample
 * as before; the mock sample connector reads its bundled board feed of 7 records: 5 new, 1
 * without an employer (rejected by the common validation), and 1 repost of SB-1001 (a duplicate
 * by source job id, caught by the common deduplication).
 */
@SpringBootTest
@SpringBatchTest
@Testcontainers
@TestPropertySource(properties = {"spring.batch.job.enabled=false", "jmip.etl.chunk-size=4"})
class ConnectorIngestionIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine");

    @Autowired
    private JobLauncherTestUtils jobLauncherTestUtils;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    @Qualifier("ingestJobPostingsJob")
    private org.springframework.batch.core.Job ingestJob;

    @BeforeEach
    void clear() {
        jobLauncherTestUtils.setJob(ingestJob);
        jdbcTemplate.execute("TRUNCATE job_sources, job_skills, jobs, skills, companies, locations, etl_rejected_record "
                + "RESTART IDENTITY CASCADE");
    }

    @Test
    @DisplayName("without a connector named, the file connector reads the input file exactly as before")
    void fileConnectorByDefault() throws Exception {
        Run run = launch(new ClassPathResource("sample-jobs.json").getFile().getAbsolutePath(), null);

        assertThat(run.status()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(count("SELECT count(*) FROM jobs")).isEqualTo(5);
        assertThat(metrics(run)).containsEntry("connector", "file").containsEntry("feed_name", "sample-jobs.json")
                .containsEntry("feed_type", "FILE_JSON").containsEntry("records_loaded", 5L);
    }

    @Test
    @DisplayName("the sample connector's records are validated, deduplicated and loaded with their source metadata")
    void sampleConnector() throws Exception {
        Run run = launch(null, "sample");

        assertThat(run.status()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(count("SELECT sum(read_count) FROM batch_step_execution WHERE job_execution_id = ?", run.id())).isEqualTo(7);
        assertThat(count("SELECT sum(process_skip_count) FROM batch_step_execution WHERE job_execution_id = ?", run.id()))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT rejection_reason FROM etl_rejected_record", String.class))
                .contains("missing company");
        assertThat(metrics(run)).containsEntry("connector", "sample").containsEntry("feed_name", "sample-postings.json")
                .containsEntry("feed_type", "SAMPLE").containsEntry("records_loaded", 5L).containsEntry("duplicates_skipped", 1L);

        assertThat(jdbcTemplate.queryForMap("SELECT source_type, last_run_execution_id FROM job_sources WHERE code = 'sample-board'"))
                .containsEntry("source_type", "OTHER").containsEntry("last_run_execution_id", run.id());
        Map<String, Object> java = jdbcTemplate.queryForMap("""
                SELECT j.title, j.source, j.source_url, j.employment_type, j.description, j.first_seen_at IS NOT NULL AS seen,
                       j.last_seen_run_id, s.code
                  FROM jobs j JOIN job_sources s ON s.id = j.source_id WHERE j.source_job_id = 'SB-1001'
                """);
        assertThat(java).containsEntry("title", "Senior Java Engineer").containsEntry("source", "sample-board")
                .containsEntry("source_url", "https://sample-board.invalid/jobs/SB-1001")
                .containsEntry("employment_type", "FULL_TIME").containsEntry("seen", true)
                .containsEntry("last_seen_run_id", run.id()).containsEntry("code", "sample-board");
        assertThat((String) java.get("description")).contains("Workplace: remote.");
        assertThat(count("SELECT count(*) FROM jobs WHERE source = 'sample-board' AND source_job_id IS NOT NULL")).isEqualTo(5);
        assertThat(count("SELECT sum(records_loaded) FROM etl_run_sources WHERE job_execution_id = ?", run.id())).isEqualTo(5);

        // A second run finds everything again: nothing new, first-seen kept.
        Run again = launch(null, "sample");
        assertThat(metrics(again)).containsEntry("records_loaded", 0L).containsEntry("connector", "sample");
        assertThat(count("SELECT count(*) FROM jobs")).isEqualTo(5);
        assertThat(count("SELECT count(*) FROM jobs WHERE last_seen_run_id = ?", again.id())).isEqualTo(5);
    }

    @Test
    @DisplayName("an unknown connector fails the run with a clear message and loads nothing")
    void unknownConnector() throws Exception {
        JobExecution execution = jobLauncherTestUtils.launchJob(new JobParametersBuilder()
                .addString("connector", "ftp").addLong("run.id", System.nanoTime()).toJobParameters());

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(execution.getAllFailureExceptions()).anySatisfy(failure ->
                assertThat(failure).hasStackTraceContaining("Unknown connector 'ftp'; available: file, sample"));
        assertThat(count("SELECT count(*) FROM jobs")).isZero();
    }

    // ------------------------------------------------------------------ helpers

    /** Not a JobExecution: @SpringBatchTest would try to call any method returning one to build a job scope. */
    private record Run(Long id, BatchStatus status) {
    }

    private Run launch(String inputFile, String connector) throws Exception {
        JobParametersBuilder parameters = new JobParametersBuilder().addLong("run.id", System.nanoTime());
        if (inputFile != null) {
            parameters.addString("inputFile", inputFile);
        }
        if (connector != null) {
            parameters.addString("connector", connector);
        }
        JobExecution execution = jobLauncherTestUtils.launchJob(parameters.toJobParameters());
        return new Run(execution.getId(), execution.getStatus());
    }

    private Map<String, Object> metrics(Run run) {
        return jdbcTemplate.queryForMap("SELECT connector, feed_name, feed_type, records_loaded, duplicates_skipped "
                + "FROM etl_run_metrics WHERE job_execution_id = ?", run.id());
    }

    private long count(String sql, Object... args) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }
}
