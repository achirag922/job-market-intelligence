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
import org.springframework.core.io.ClassPathResource;
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

/**
 * V8.1: job sources and ingestion metadata, through the one pipeline every feed uses.
 * The JSON sample has two sources ('itest', 'itest-mirror'); the CSV feeds are written here.
 */
@SpringBootTest
@SpringBatchTest
@Testcontainers
@TestPropertySource(properties = {"spring.batch.job.enabled=false", "jmip.etl.chunk-size=4"})
class JobSourceIngestionIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine");

    @TempDir
    static Path feeds;

    private static final String CSV_HEADER = "title,company,location,description,employmentType,postedDate,source,sourceUrl,sourceJobId\n";

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
    @DisplayName("the existing JSON feed registers its sources and records who, what and when for every posting")
    void existingFeed() throws Exception {
        Run run = launch(new ClassPathResource("sample-jobs.json").getFile().getAbsolutePath());

        assertThat(run.status()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(count("SELECT count(*) FROM jobs")).as("same 5 postings as before V8.1").isEqualTo(5);
        List<Map<String, Object>> sources = jdbcTemplate.queryForList(
                "SELECT code, source_type, active, last_run_execution_id FROM job_sources ORDER BY code");
        assertThat(sources).extracting(row -> row.get("code")).containsExactly("itest", "itest-mirror");
        assertThat(sources).allSatisfy(row -> {
            assertThat(row.get("source_type")).isEqualTo("FILE_JSON");
            assertThat(row.get("active")).isEqualTo(true);
        });
        // Every posting points at the source its label names, and carries its run and times.
        assertThat(count("SELECT count(*) FROM jobs j JOIN job_sources s ON s.id = j.source_id AND s.code = j.source "
                + "WHERE j.last_seen_run_id = ? AND j.first_seen_at IS NOT NULL", run.id())).isEqualTo(5);
        // The run says which feed it read (the name, never the path) and what each source added.
        assertThat(jdbcTemplate.queryForMap("SELECT feed_name, feed_type FROM etl_run_metrics WHERE job_execution_id = ?", run.id()))
                .containsEntry("feed_name", "sample-jobs.json").containsEntry("feed_type", "FILE_JSON");
        assertThat(count("SELECT sum(records_loaded) FROM etl_run_sources WHERE job_execution_id = ?", run.id())).isEqualTo(5);
    }

    @Test
    @DisplayName("running a feed again loads nothing new, keeps first-seen and moves last-seen to the new run")
    void seenAgain() throws Exception {
        String sample = new ClassPathResource("sample-jobs.json").getFile().getAbsolutePath();
        Run first = launch(sample);
        Object firstSeen = jdbcTemplate.queryForObject("SELECT min(first_seen_at) FROM jobs", Object.class);

        Run second = launch(sample);

        assertThat(count("SELECT count(*) FROM jobs")).isEqualTo(5);
        assertThat(jdbcTemplate.queryForObject("SELECT min(first_seen_at) FROM jobs", Object.class)).isEqualTo(firstSeen);
        assertThat(count("SELECT count(*) FROM jobs WHERE last_seen_run_id = ?", second.id())).isEqualTo(5);
        assertThat(count("SELECT sum(records_loaded) FROM etl_run_sources WHERE job_execution_id = ?", second.id())).isZero();
        assertThat(count("SELECT sum(records_seen_again) FROM etl_run_sources WHERE job_execution_id = ?", second.id()))
                .isGreaterThanOrEqualTo(5);
        assertThat(first.id()).isNotEqualTo(second.id());
    }

    @Test
    @DisplayName("a CSV feed with two sources goes through the same pipeline, normalised, with the sources' own ids")
    void multipleSourcesFromCsv() throws Exception {
        Path csv = feed("two-boards.csv", CSV_HEADER
                + "\"  Senior   Java   Developer \",Acme Systems,\"Berlin, Germany\",\"Build Java services on Kubernetes.\",full time,2026-09-01,board-a,https://a.example/1,A-1\n"
                + "Data Engineer,Beta Retail,\"Warsaw, Poland\",\"Pipelines in Python and SQL.\",Contract,2026-09-02,board-b,https://b.example/9,B-9\n");

        Run run = launch(csv.toString());

        assertThat(run.status()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(jdbcTemplate.queryForList("SELECT code FROM job_sources WHERE source_type = 'FILE_CSV' ORDER BY code", String.class))
                .containsExactly("board-a", "board-b");
        Map<String, Object> java = jdbcTemplate.queryForMap(
                "SELECT j.title, j.employment_type, j.source_job_id, s.code FROM jobs j JOIN job_sources s ON s.id = j.source_id "
                        + "WHERE j.source_job_id = 'A-1'");
        assertThat(java).containsEntry("title", "Senior Java Developer").containsEntry("employment_type", "FULL_TIME")
                .containsEntry("code", "board-a");
        assertThat(jdbcTemplate.queryForMap("SELECT feed_name, feed_type FROM etl_run_metrics WHERE job_execution_id = ?", run.id()))
                .containsEntry("feed_name", "two-boards.csv").containsEntry("feed_type", "FILE_CSV");
        assertThat(count("SELECT count(*) FROM etl_run_sources WHERE job_execution_id = ? AND records_loaded = 1", run.id()))
                .isEqualTo(2);
    }

    @Test
    @DisplayName("one posting per source id: a second record with the same source and id is not loaded again")
    void sourceJobIdIsUnique() throws Exception {
        Path csv = feed("repeat-ids.csv", CSV_HEADER
                + "Backend Engineer,Acme Systems,\"Berlin, Germany\",\"Java and Docker.\",Full time,2026-09-01,board-a,https://a.example/1,A-1\n"
                + "Backend Engineer II,Acme Systems,\"Berlin, Germany\",\"Java, Docker, Kafka.\",Full time,2026-09-03,board-a,https://a.example/2,A-1\n");

        launch(csv.toString());

        assertThat(count("SELECT count(*) FROM jobs WHERE source_job_id = 'A-1'")).isEqualTo(1);
    }

    @Test
    @DisplayName("records from an inactive source are rejected with a reason, and nothing of theirs is loaded")
    void inactiveSource() throws Exception {
        jdbcTemplate.update("INSERT INTO job_sources (code, name, source_type, active) VALUES ('board-off', 'Paused board', 'FILE_CSV', FALSE)");
        Path csv = feed("paused.csv", CSV_HEADER
                + "Backend Engineer,Acme Systems,\"Berlin, Germany\",\"Java and Docker.\",Full time,2026-09-01,board-off,https://off.example/1,X-1\n"
                + "Data Engineer,Beta Retail,\"Warsaw, Poland\",\"Python and SQL.\",Contract,2026-09-02,board-b,https://b.example/9,B-9\n");

        launch(csv.toString());

        assertThat(count("SELECT count(*) FROM jobs j JOIN job_sources s ON s.id = j.source_id WHERE s.code = 'board-off'")).isZero();
        assertThat(count("SELECT count(*) FROM jobs j JOIN job_sources s ON s.id = j.source_id WHERE s.code = 'board-b'")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForList("SELECT rejection_reason FROM etl_rejected_record", String.class))
                .anySatisfy(reasons -> assertThat(reasons).contains("source 'board-off' is inactive"));
        assertThat(jdbcTemplate.queryForObject("SELECT last_ingested_at FROM job_sources WHERE code = 'board-off'", Object.class))
                .as("an inactive source is not marked as ingested").isNull();
    }

    // ------------------------------------------------------------------ helpers

    /** Not a JobExecution: @SpringBatchTest would try to call any method returning one to build a job scope. */
    private record Run(Long id, BatchStatus status) {
    }

    private Run launch(String inputFile) throws Exception {
        JobParameters parameters = new JobParametersBuilder()
                .addString("inputFile", inputFile)
                .addLong("run.id", System.nanoTime())
                .toJobParameters();
        JobExecution execution = jobLauncherTestUtils.launchJob(parameters);
        return new Run(execution.getId(), execution.getStatus());
    }

    private static Path feed(String name, String content) throws Exception {
        Path file = feeds.resolve(name);
        Files.writeString(file, content);
        return file;
    }

    private long count(String sql, Object... args) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }
}
