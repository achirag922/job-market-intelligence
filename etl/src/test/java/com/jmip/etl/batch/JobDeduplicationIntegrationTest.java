package com.jmip.etl.batch;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.test.JobLauncherTestUtils;
import org.springframework.batch.test.context.SpringBatchTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** V8.2: which records count as the same posting, which are rejected, and when jobs expire. */
@SpringBootTest
@SpringBatchTest
@Testcontainers
@TestPropertySource(properties = {"spring.batch.job.enabled=false", "jmip.etl.chunk-size=4"})
class JobDeduplicationIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine");

    @TempDir
    static Path feeds;

    private static final String HEADER =
            "title,company,location,description,employmentType,postedDate,source,sourceUrl,sourceJobId,expiresAt,status\n";

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
    @DisplayName("the source's own job id wins: a reworded posting with a new URL is the same job, seen again")
    void sourceJobIdFirst() throws Exception {
        launch("id-1.csv", "Java Developer,Acme,\"Berlin, Germany\",Java services.,Full time,2026-03-01,board-a,https://a.example/1,A-1,,\n");
        Run second = launch("id-2.csv",
                "Senior Java Developer,Acme,\"Berlin, Germany\",Java services.,Full time,2026-03-02,board-a,https://a.example/1-new,A-1,,\n");

        assertThat(count("SELECT count(*) FROM jobs")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM jobs WHERE source_job_id = 'A-1' AND last_seen_run_id = ?", second.id())).isEqualTo(1);
        assertThat(metrics(second)).containsEntry("records_loaded", 0L).containsEntry("duplicates_skipped", 1L);
    }

    @Test
    @DisplayName("without an id, the same source URL is the same job, and is marked seen by the later run")
    void sourceUrlNext() throws Exception {
        launch("url-1.csv", "Data Engineer,Beta,\"Warsaw, Poland\",Pipelines.,Contract,2026-03-01,board-b,https://b.example/9,,,\n");
        Run second = launch("url-2.csv", "Data Engineer II,Beta,\"Warsaw, Poland\",Pipelines.,Contract,2026-03-01,board-b,https://b.example/9,,,\n");

        assertThat(count("SELECT count(*) FROM jobs")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM jobs WHERE last_seen_run_id = ?", second.id())).isEqualTo(1);
    }

    @Test
    @DisplayName("the same posting on two boards is one job; a shared title alone is not a duplicate")
    void fingerprintAcrossSources() throws Exception {
        Run run = launch("boards.csv",
                "QA Engineer,Gamma,\"Paris, France\",Testing.,Full time,2026-03-05,board-a,https://a.example/q,,,\n"
                        + "QA Engineer,Gamma,\"Paris, France\",\"Testing, reworded.\",Full time,2026-03-05,board-b,https://b.example/q,,,\n"
                        + "QA Engineer,Delta,\"Paris, France\",Testing.,Full time,2026-03-05,board-a,https://a.example/q2,,,\n"
                        + "QA Engineer,Gamma,\"Lyon, France\",Testing.,Full time,2026-03-05,board-a,https://a.example/q3,,,\n");

        assertThat(run.status()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(jdbcTemplate.queryForList("SELECT source_url FROM jobs ORDER BY source_url", String.class))
                .containsExactly("https://a.example/q", "https://a.example/q2", "https://a.example/q3");
        assertThat(metrics(run)).containsEntry("records_loaded", 3L).containsEntry("duplicates_skipped", 1L);
    }

    @Test
    @DisplayName("records missing required fields, or with unreadable or contradictory ones, are rejected with a reason; missing optional fields are fine")
    void dataQuality() throws Exception {
        Run run = launch("quality.csv",
                "Backend Developer,Epsilon,\"Madrid, Spain\",,Full time,2026-03-01,board-a,,,,\n"
                        + "Backend Developer,,\"Madrid, Spain\",APIs.,Full time,2026-03-01,board-a,,,,\n"
                        + "Frontend Developer,Epsilon,\"Madrid, Spain\",React.,Full time,2026-03-10,board-a,,,2026-03-01,\n"
                        + "Mobile Developer,Epsilon,\"Madrid, Spain\",Kotlin.,Full time,2026-03-01,board-a,,,,maybe\n"
                        + "Support Engineer,Epsilon,,Helping customers.,,,board-a,,,,\n");

        assertThat(run.status()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(jdbcTemplate.queryForList("SELECT title FROM jobs", String.class)).containsExactly("Support Engineer");
        assertThat(String.join(" | ", jdbcTemplate.queryForList("SELECT rejection_reason FROM etl_rejected_record", String.class)))
                .contains("missing description", "missing company",
                        "expiry date 2026-03-01 is before the posted date 2026-03-10", "invalid status: 'maybe'");
        assertThat(count("SELECT sum(process_skip_count) FROM batch_step_execution WHERE job_execution_id = ?", run.id()))
                .isEqualTo(4);
    }

    @Test
    @DisplayName("jobs past their source's expiry date are marked expired at the end of the run, never deleted")
    void expiry() throws Exception {
        Run run = launch("expiry.csv",
                "Old Role,Zeta,\"Rome, Italy\",Was open.,Full time,2026-01-10,board-a,,Z-1,2026-02-01,\n"
                        + "Open Role,Zeta,\"Rome, Italy\",Still open.,Full time,2026-01-10,board-a,,Z-2,2099-12-31,\n"
                        + "Undated Role,Zeta,\"Rome, Italy\",No expiry given.,Full time,2026-01-10,board-a,,Z-3,,\n");

        List<Map<String, Object>> jobs = jdbcTemplate.queryForList(
                "SELECT source_job_id, active, deactivated_at IS NOT NULL AS deactivated FROM jobs ORDER BY source_job_id");
        assertThat(jobs).extracting(row -> row.get("active")).containsExactly(false, true, true);
        assertThat(jobs.get(0)).containsEntry("deactivated", true);
        assertThat(metrics(run)).containsEntry("records_loaded", 3L).containsEntry("jobs_expired", 1L);
    }

    @Test
    @DisplayName("a posting its source marks closed is made inactive, and reopened when the source lists it as open again")
    void closedBySource() throws Exception {
        String row = "Ops Engineer,Eta,\"Oslo, Norway\",Keep it running.,Full time,2026-03-01,board-a,,E-1,,";
        launch("open.csv", row + "open\n");
        Run closed = launch("closed.csv", row + "filled\n");

        assertThat(jdbcTemplate.queryForObject("SELECT active FROM jobs WHERE source_job_id = 'E-1'", Boolean.class)).isFalse();
        assertThat(metrics(closed)).containsEntry("jobs_expired", 1L).containsEntry("records_loaded", 0L);

        launch("reopened.csv", row + "\n");
        assertThat(jdbcTemplate.queryForObject("SELECT active FROM jobs WHERE source_job_id = 'E-1'", Boolean.class)).isTrue();
        assertThat(count("SELECT count(*) FROM jobs")).isEqualTo(1);
    }

    // ------------------------------------------------------------------ helpers

    /** Not a JobExecution: @SpringBatchTest would try to call any method returning one to build a job scope. */
    private record Run(Long id, BatchStatus status) {
    }

    private Run launch(String name, String rows) throws Exception {
        Path file = feeds.resolve(name);
        Files.writeString(file, HEADER + rows);
        JobParameters parameters = new JobParametersBuilder()
                .addString("inputFile", file.toString())
                .addLong("run.id", System.nanoTime())
                .toJobParameters();
        JobExecution execution = jobLauncherTestUtils.launchJob(parameters);
        return new Run(execution.getId(), execution.getStatus());
    }

    private Map<String, Object> metrics(Run run) {
        return jdbcTemplate.queryForMap("SELECT records_loaded, duplicates_skipped, jobs_expired FROM etl_run_metrics "
                + "WHERE job_execution_id = ?", run.id());
    }

    private long count(String sql, Object... args) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }
}
