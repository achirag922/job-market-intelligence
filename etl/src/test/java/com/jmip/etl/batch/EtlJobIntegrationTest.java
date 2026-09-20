package com.jmip.etl.batch;

import com.jmip.etl.load.EtlMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.test.JobLauncherTestUtils;
import org.springframework.batch.test.context.SpringBatchTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the real job, against a real PostgreSQL, over a small dataset whose expected
 * outcome is known exactly.
 *
 * <p>The ten input records are built so that every path is exercised: five distinct jobs
 * load, two are duplicates (one re-ingested from the same URL, one mirrored from another
 * source), and three are rejected for different reasons.
 */
@SpringBootTest
@SpringBatchTest
@Testcontainers
@TestPropertySource(properties = {
        // The job is launched explicitly by the test, not on context startup.
        "spring.batch.job.enabled=false",
        "jmip.etl.chunk-size=4"
})
class EtlJobIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine");

    @Autowired
    private JobLauncherTestUtils jobLauncherTestUtils;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EtlMetrics metrics;

    /**
     * Two jobs exist since V4, so the launcher cannot choose one by type. Naming the
     * ingestion job here keeps this test about ingestion.
     */
    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("ingestJobPostingsJob")
    private org.springframework.batch.core.Job ingestJob;

    /**
     * The container is shared by every test in the class, so each one starts from an
     * empty set of ingested rows. Spring Batch's own metadata is left alone.
     */
    @BeforeEach
    void clearIngestedData() {
        jobLauncherTestUtils.setJob(ingestJob);
        jdbcTemplate.execute(
                "TRUNCATE job_skills, jobs, skills, companies, locations, etl_rejected_record RESTART IDENTITY CASCADE");
    }

    @Test
    @DisplayName("ingests the sample dataset, rejecting bad records and collapsing duplicates")
    void ingestsSampleDataset() throws Exception {
        JobExecution execution = jobLauncherTestUtils.launchJob(jobParameters());

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        StepExecution step = execution.getStepExecutions().iterator().next();
        assertThat(step.getReadCount()).as("records read").isEqualTo(10);
        assertThat(step.getProcessSkipCount()).as("records rejected").isEqualTo(3);

        assertThat(count("jobs")).as("distinct jobs loaded").isEqualTo(5);
        assertThat(metrics.jobsLoaded()).isEqualTo(5);
        assertThat(metrics.duplicatesSkipped()).as("duplicates detected").isEqualTo(2);
    }

    @Test
    @DisplayName("creates companies once, reusing them across postings")
    void createsCompaniesOnce() throws Exception {
        jobLauncherTestUtils.launchJob(jobParameters());

        // Acme appears three times across the input but is one company row, and the two
        // companies that only appear on rejected records are never created.
        assertThat(count("companies")).isEqualTo(4);
        assertThat(names("SELECT name FROM companies ORDER BY name"))
                .containsExactly("Acme Systems", "Globex Data", "Initech Cloud", "Umbrella Health");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM jobs j JOIN companies c ON c.id = j.company_id WHERE c.name = 'Acme Systems'",
                Integer.class)).isEqualTo(2);
    }

    @Test
    @DisplayName("creates locations once, and leaves remote postings without one")
    void createsLocationsOnce() throws Exception {
        jobLauncherTestUtils.launchJob(jobParameters());

        assertThat(count("locations")).isEqualTo(3);
        assertThat(names("SELECT country FROM locations ORDER BY country"))
                .containsExactly("Germany", "India", "United States");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM jobs WHERE location_id IS NULL", Integer.class))
                .as("the remote posting has no location").isEqualTo(1);
    }

    @Test
    @DisplayName("extracts skills and links them without duplication")
    void extractsAndLinksSkills() throws Exception {
        jobLauncherTestUtils.launchJob(jobParameters());

        assertThat(names("SELECT name FROM skills ORDER BY name"))
                .contains("Java", "Spring Boot", "Kafka", "Docker", "Python", "SQL",
                        "Airflow", "Kubernetes", "Terraform", "AWS", "React", "Node.js");

        // Skills only mentioned in rejected records never reach the database.
        assertThat(names("SELECT name FROM skills")).doesNotContain("Selenium", "Jenkins");

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM job_skills", Integer.class))
                .isPositive();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM (SELECT job_id, skill_id FROM job_skills GROUP BY job_id, skill_id "
                        + "HAVING count(*) > 1) duplicated", Integer.class))
                .as("no duplicated job to skill links").isZero();

        List<Map<String, Object>> backendSkills = jdbcTemplate.queryForList(
                "SELECT s.name FROM job_skills js "
                        + "JOIN jobs j ON j.id = js.job_id JOIN skills s ON s.id = js.skill_id "
                        + "WHERE j.title = 'Senior Backend Engineer'");
        assertThat(backendSkills).extracting(row -> row.get("name"))
                .containsExactlyInAnyOrder("Java", "Spring Boot", "Kafka", "Docker");
    }

    @Test
    @DisplayName("records every rejection with its reason and the original input")
    void recordsRejections() throws Exception {
        JobExecution execution = jobLauncherTestUtils.launchJob(jobParameters());

        List<Map<String, Object>> rejections = jdbcTemplate.queryForList(
                "SELECT rejection_reason, original_record, job_name, job_execution_id "
                        + "FROM etl_rejected_record ORDER BY id");

        assertThat(rejections).hasSize(3);
        assertThat(rejections).extracting(row -> (String) row.get("rejection_reason"))
                .anySatisfy(reason -> assertThat(reason).contains("missing job title"))
                .anySatisfy(reason -> assertThat(reason).contains("missing company"))
                .anySatisfy(reason -> assertThat(reason).contains("invalid salary"));

        assertThat(rejections).allSatisfy(row -> {
            assertThat((String) row.get("original_record")).as("the raw record is kept").isNotBlank();
            assertThat(row.get("job_name")).isEqualTo(BatchConfiguration.JOB_NAME);
            assertThat(row.get("job_execution_id")).isEqualTo(execution.getId());
        });
    }

    @Test
    @DisplayName("re-running the same file loads nothing further")
    void rerunIsIdempotent() throws Exception {
        jobLauncherTestUtils.launchJob(jobParameters());
        int afterFirstRun = count("jobs");
        int skillLinksAfterFirstRun = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM job_skills", Integer.class);

        JobExecution second = jobLauncherTestUtils.launchJob(jobParameters());

        assertThat(second.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(count("jobs")).as("no new jobs on re-run").isEqualTo(afterFirstRun);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM job_skills", Integer.class))
                .as("no new skill links on re-run").isEqualTo(skillLinksAfterFirstRun);
        assertThat(metrics.duplicatesSkipped()).as("every record is a duplicate second time round")
                .isEqualTo(7);
    }

    @Test
    @DisplayName("parsed values survive the round trip into the database")
    void parsedValuesArePersisted() throws Exception {
        jobLauncherTestUtils.launchJob(jobParameters());

        Map<String, Object> job = jdbcTemplate.queryForMap(
                "SELECT title, employment_type, experience_min, experience_max, salary_min, salary_max, "
                        + "currency, posted_date, source FROM jobs WHERE title = 'Senior Backend Engineer'");

        assertThat(job.get("employment_type")).isEqualTo("FULL_TIME");
        assertThat(((Number) job.get("experience_min")).intValue()).isEqualTo(5);
        assertThat(((Number) job.get("experience_max")).intValue()).isEqualTo(9);
        assertThat(((java.math.BigDecimal) job.get("salary_min"))).isEqualByComparingTo("150000");
        assertThat(((java.math.BigDecimal) job.get("salary_max"))).isEqualByComparingTo("190000");
        assertThat(((String) job.get("currency")).trim()).isEqualTo("USD");
        assertThat(job.get("posted_date").toString()).isEqualTo("2026-08-13");
    }

    private JobParameters jobParameters() throws IOException {
        return new JobParametersBuilder()
                .addString("inputFile", new ClassPathResource("sample-jobs.json").getFile().getAbsolutePath())
                .addLong("run.id", System.nanoTime())
                .toJobParameters();
    }

    private int count(String table) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private List<String> names(String sql) {
        return jdbcTemplate.queryForList(sql, String.class);
    }
}
