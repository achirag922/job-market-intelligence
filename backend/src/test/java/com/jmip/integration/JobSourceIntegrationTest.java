package com.jmip.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** V8.1: job sources in the database, the read-only source API, and sources in ETL run monitoring. */
@SpringBootTest(properties = "jmip.ai.provider=stub")
@AutoConfigureMockMvc
@Testcontainers
@WithMockUser(roles = "USER")
class JobSourceIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long boardA;

    @BeforeEach
    void seed() {
        jdbcTemplate.execute("TRUNCATE batch_job_instance, batch_job_execution, batch_step_execution, etl_run_metrics, "
                + "job_sources, job_skills, jobs, companies CASCADE");
        jdbcTemplate.update("INSERT INTO companies (id, name) VALUES (1, 'Acme Systems')");
        // Inserted without a source id, as any loader might: the database links each to its source.
        job(1, "board-a");
        job(2, "board-a");
        job(3, "board-b");
        boardA = jdbcTemplate.queryForObject("SELECT id FROM job_sources WHERE code = 'board-a'", Long.class);
        jdbcTemplate.update("UPDATE job_sources SET source_type = 'FILE_CSV', last_run_execution_id = 1, last_ingested_at = now() "
                + "WHERE code = 'board-a'");

        jdbcTemplate.update("INSERT INTO batch_job_instance (job_instance_id, version, job_name, job_key) "
                + "VALUES (1, 1, 'ingestJobPostings', 'k1')");
        jdbcTemplate.update("""
                INSERT INTO batch_job_execution (job_execution_id, version, job_instance_id, create_time, start_time,
                                                 end_time, status, exit_code)
                VALUES (1, 1, 1, '2026-09-20 10:00:00', '2026-09-20 10:00:00', '2026-09-20 10:00:05', 'COMPLETED', 'COMPLETED')
                """);
        jdbcTemplate.update("INSERT INTO etl_run_metrics (job_execution_id, records_loaded, duplicates_skipped, skill_links_created, "
                + "feed_name, feed_type, connector) VALUES (1, 3, 1, 0, 'two-boards.csv', 'FILE_CSV', 'file')");
        jdbcTemplate.update("INSERT INTO etl_run_sources (job_execution_id, source_id, records_loaded, records_seen_again) "
                + "SELECT 1, id, CASE code WHEN 'board-a' THEN 2 ELSE 1 END, CASE code WHEN 'board-a' THEN 1 ELSE 0 END "
                + "FROM job_sources");
    }

    @Test
    @DisplayName("every posting is linked to its source by the database, with first and last seen set")
    void jobsAreLinked() {
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM jobs j JOIN job_sources s ON s.id = j.source_id "
                + "AND s.code = j.source WHERE j.first_seen_at IS NOT NULL AND j.last_seen_at IS NOT NULL", Long.class))
                .isEqualTo(3);
        assertThat(jdbcTemplate.queryForList("SELECT code FROM job_sources ORDER BY code", String.class))
                .containsExactly("board-a", "board-b");
    }

    @Test
    @DisplayName("a source with postings cannot be deleted, and a code is registered once")
    void constraints() {
        assertThatThrownBy(() -> jdbcTemplate.update("DELETE FROM job_sources WHERE id = ?", boardA))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("INSERT INTO job_sources (code, name) VALUES ('board-a', 'Again')"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("sources are listed with their type, status and posting count, and nothing internal")
    void listSources() throws Exception {
        mockMvc.perform(get("/api/job-sources"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].code", contains("board-a", "board-b")))
                .andExpect(jsonPath("$[0].sourceType").value("FILE_CSV"))
                .andExpect(jsonPath("$[0].active").value(true))
                .andExpect(jsonPath("$[0].jobCount").value(2))
                .andExpect(jsonPath("$[0].lastRunExecutionId").value(1))
                .andExpect(jsonPath("$[0].recentRuns").doesNotExist())
                .andExpect(content().string(not(containsString("/"))));
    }

    @Test
    @DisplayName("one source shows the runs that met it, with Spring Batch's status and the feed name")
    void sourceDetail() throws Exception {
        mockMvc.perform(get("/api/job-sources/" + boardA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("board-a"))
                .andExpect(jsonPath("$.recentRuns", hasSize(1)))
                .andExpect(jsonPath("$.recentRuns[0].executionId").value(1))
                .andExpect(jsonPath("$.recentRuns[0].batchStatus").value("COMPLETED"))
                .andExpect(jsonPath("$.recentRuns[0].feedName").value("two-boards.csv"))
                .andExpect(jsonPath("$.recentRuns[0].recordsLoaded").value(2))
                .andExpect(jsonPath("$.recentRuns[0].recordsSeenAgain").value(1));
        mockMvc.perform(get("/api/job-sources/99999")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("ETL run monitoring says which feed each run read and what it did per source")
    void runsShowSources() throws Exception {
        mockMvc.perform(get("/api/etl/runs"))
                .andExpect(jsonPath("$.content[0].feedName").value("two-boards.csv"))
                .andExpect(jsonPath("$.content[0].feedType").value("FILE_CSV"))
                .andExpect(jsonPath("$.content[0].expired").value(0))
                .andExpect(jsonPath("$.content[0].connector").value("file"))
                .andExpect(jsonPath("$.content[0].sources[*].code", contains("board-a", "board-b")))
                .andExpect(jsonPath("$.content[0].sources[0].recordsLoaded").value(2));
        mockMvc.perform(get("/api/etl/runs/latest"))
                .andExpect(jsonPath("$.sources", hasSize(2)));
    }

    @Test
    @DisplayName("the source API is read-only")
    void readOnly() throws Exception {
        mockMvc.perform(post("/api/job-sources")).andExpect(status().isMethodNotAllowed());
        mockMvc.perform(delete("/api/job-sources/" + boardA)).andExpect(status().isMethodNotAllowed());
    }

    @Test
    @WithAnonymousUser
    @DisplayName("sources need a signed-in user")
    void requiresSignIn() throws Exception {
        mockMvc.perform(get("/api/job-sources")).andExpect(status().isUnauthorized());
    }

    private void job(long id, String source) {
        jdbcTemplate.update("""
                INSERT INTO jobs (id, title, company_id, description, source, source_url, content_fingerprint)
                VALUES (?, 'Engineer', 1, 'posting', ?, ?, ?)
                """, id, source, "https://" + source + ".example/" + id, String.format("%064d", id));
    }
}
