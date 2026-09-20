package com.jmip.etl.batch;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V4 reprocessing of postings already in the database.
 *
 * <p>The fixture is four postings inserted with no skills and no category — the state of
 * every row that existed before V4. Reprocessing has to classify them and extract their
 * skills from the descriptions alone.
 */
@SpringBootTest
@SpringBatchTest
@Testcontainers
@TestPropertySource(properties = {
        "spring.batch.job.enabled=false",
        "jmip.etl.chunk-size=2"
})
class JobReprocessingIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine");

    @Autowired
    private JobLauncherTestUtils jobLauncherTestUtils;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    @Qualifier("reprocessJobPostingsJob")
    private Job reprocessJob;

    @BeforeEach
    void seed() {
        jobLauncherTestUtils.setJob(reprocessJob);
        jdbcTemplate.execute("TRUNCATE job_classification_signals, job_skills, jobs, skills, "
                + "companies, locations RESTART IDENTITY CASCADE");

        jdbcTemplate.update("INSERT INTO companies (id, name) VALUES (1, 'Acme Systems')");

        job(1, "Senior Backend Engineer",
                "Build microservices and RESTful APIs with Spring Boot and Kafka.");
        job(2, "Frontend Engineer",
                "Build the user interface with React and TypeScript. Strong JS required.");
        job(3, "Data Engineer",
                "Own the data pipeline. Python, SQL, Spark and Airflow on a daily basis.");
        job(4, "Office Coordinator",
                "Keep the office running. Order supplies and greet visitors.");
    }

    @Test
    @DisplayName("reprocessing classifies postings that had no category")
    void classifiesExistingPostings() throws Exception {
        JobExecution execution = jobLauncherTestUtils.launchJob(parameters());

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(category(1)).isEqualTo("Backend Developer");
        assertThat(category(2)).isEqualTo("Frontend Developer");
        assertThat(category(3)).isEqualTo("Data Engineer");
        // Nothing recognisable: recorded as Other rather than forced into a category.
        assertThat(category(4)).isEqualTo("Other");
    }

    @Test
    @DisplayName("reprocessing extracts skills from the stored descriptions")
    void extractsSkillsFromDescriptions() throws Exception {
        jobLauncherTestUtils.launchJob(parameters());

        // "RESTful APIs" resolves to the REST API skill, and "Spring Boot" to itself.
        assertThat(skillsOf(1)).contains("Spring Boot", "Kafka", "REST API", "Microservices");
        // "JS" resolves to JavaScript through the alias.
        assertThat(skillsOf(2)).contains("React", "TypeScript", "JavaScript");
        assertThat(skillsOf(3)).contains("Python", "SQL", "Spark", "Airflow");
        assertThat(skillsOf(4)).isEmpty();
    }

    @Test
    @DisplayName("every classification records the evidence behind it")
    void recordsClassificationSignals() throws Exception {
        jobLauncherTestUtils.launchJob(parameters());

        List<String> signals = jdbcTemplate.queryForList(
                "SELECT signal_value FROM job_classification_signals WHERE job_id = 1 ORDER BY weight DESC",
                String.class);

        assertThat(signals).isNotEmpty();
        assertThat(signals).contains("backend engineer");
        // An unclassifiable posting has no evidence to record.
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM job_classification_signals WHERE job_id = 4", Integer.class))
                .isZero();
    }

    @Test
    @DisplayName("reprocessing is safe to run repeatedly")
    void isIdempotent() throws Exception {
        jobLauncherTestUtils.launchJob(parameters());
        int skillsAfterFirst = count("job_skills");
        int signalsAfterFirst = count("job_classification_signals");
        String categoryAfterFirst = category(1);

        jobLauncherTestUtils.launchJob(parameters());
        jobLauncherTestUtils.launchJob(parameters());

        // The dictionary and rules will change and the corpus will be reprocessed often,
        // so a re-run must produce the same state rather than accumulate rows.
        assertThat(count("job_skills")).isEqualTo(skillsAfterFirst);
        assertThat(count("job_classification_signals")).isEqualTo(signalsAfterFirst);
        assertThat(category(1)).isEqualTo(categoryAfterFirst);
        assertThat(count("jobs")).isEqualTo(4);
    }

    @Test
    @DisplayName("reprocessing creates no duplicate skills or relationships")
    void createsNoDuplicates() throws Exception {
        jobLauncherTestUtils.launchJob(parameters());
        jobLauncherTestUtils.launchJob(parameters());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM (SELECT lower(name) FROM skills GROUP BY lower(name) "
                        + "HAVING count(*) > 1) duplicated", Integer.class))
                .as("no duplicate skill rows").isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM (SELECT job_id, skill_id FROM job_skills "
                        + "GROUP BY job_id, skill_id HAVING count(*) > 1) duplicated", Integer.class))
                .as("no duplicate job-skill links").isZero();
    }

    @Test
    @DisplayName("stale results are cleared, so a narrowed rule does not leave residue")
    void clearsStaleResults() throws Exception {
        // A skill link and a signal that the current rules would never produce.
        jdbcTemplate.update("INSERT INTO skills (id, name, category) VALUES (999, 'COBOL', 'LANGUAGE')");
        jdbcTemplate.update("INSERT INTO job_skills (job_id, skill_id) VALUES (1, 999)");
        jdbcTemplate.update("INSERT INTO job_classification_signals "
                + "(job_id, signal_type, signal_value, weight) VALUES (1, 'SKILL', 'COBOL', 2.0)");

        jobLauncherTestUtils.launchJob(parameters());

        assertThat(skillsOf(1)).doesNotContain("COBOL");
        assertThat(jdbcTemplate.queryForList(
                "SELECT signal_value FROM job_classification_signals WHERE job_id = 1", String.class))
                .doesNotContain("COBOL");
    }

    @Test
    @DisplayName("the original descriptions are never modified")
    void leavesDescriptionsAlone() throws Exception {
        String before = jdbcTemplate.queryForObject(
                "SELECT description FROM jobs WHERE id = 1", String.class);

        jobLauncherTestUtils.launchJob(parameters());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT description FROM jobs WHERE id = 1", String.class)).isEqualTo(before);
    }

    private JobParameters parameters() {
        return new JobParametersBuilder().addLong("run.id", System.nanoTime()).toJobParameters();
    }

    private String category(long jobId) {
        return jdbcTemplate.queryForObject(
                "SELECT job_category FROM jobs WHERE id = ?", String.class, jobId);
    }

    private List<String> skillsOf(long jobId) {
        return jdbcTemplate.queryForList(
                "SELECT s.name FROM job_skills js JOIN skills s ON s.id = js.skill_id "
                        + "WHERE js.job_id = ? ORDER BY s.name", String.class, jobId);
    }

    private int count(String table) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private void job(long id, String title, String description) {
        jdbcTemplate.update("""
                        INSERT INTO jobs (id, title, company_id, description, source, source_url,
                                          content_fingerprint)
                        VALUES (?, ?, 1, ?, 'itest', ?, ?)
                        """,
                id, title, description, "https://example.invalid/jobs/" + id,
                String.format("%064d", id));
    }
}
