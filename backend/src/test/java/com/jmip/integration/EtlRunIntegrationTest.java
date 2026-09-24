package com.jmip.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The monitoring API over real Spring Batch tables, seeded the way Spring Batch writes them.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class EtlRunIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clear() {
        jdbcTemplate.execute("TRUNCATE batch_job_instance, batch_job_execution, batch_step_execution, "
                + "etl_run_metrics CASCADE");
    }

    @Test
    @DisplayName("lists runs newest first, summing step counters and joining the ETL's own counts")
    void listsRuns() throws Exception {
        seedHistory();

        mockMvc.perform(get("/api/etl/runs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.content[*].executionId").value(contains(3, 2, 1)))
                .andExpect(jsonPath("$.content[2].outcome").value("SUCCEEDED"))
                .andExpect(jsonPath("$.content[2].recordsRead").value(10))
                .andExpect(jsonPath("$.content[2].recordsProcessed").value(7))
                .andExpect(jsonPath("$.content[2].recordsWritten").value(7))
                .andExpect(jsonPath("$.content[2].recordsLoaded").value(5))
                .andExpect(jsonPath("$.content[2].duplicates").value(2))
                .andExpect(jsonPath("$.content[2].rejected").value(3))
                .andExpect(jsonPath("$.content[2].durationMillis").value(12000))
                .andExpect(jsonPath("$.content[1].outcome").value("FAILED"))
                .andExpect(jsonPath("$.content[1].exitMessage").value("Boom"))
                // No etl_run_metrics row: unknown, not zero.
                .andExpect(jsonPath("$.content[0].outcome").value("RUNNING"))
                .andExpect(jsonPath("$.content[0].recordsLoaded").doesNotExist());

        mockMvc.perform(get("/api/etl/runs").param("page", "1").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].executionId").value(contains(1)))
                .andExpect(jsonPath("$.totalPages").value(2));

        mockMvc.perform(get("/api/etl/runs").param("job", "reprocessJobPostings"))
                .andExpect(jsonPath("$.content[*].executionId").value(contains(2)));
    }

    @Test
    @DisplayName("latest returns the newest run")
    void latest() throws Exception {
        seedHistory();

        mockMvc.perform(get("/api/etl/runs/latest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.executionId").value(3))
                .andExpect(jsonPath("$.outcome").value("RUNNING"));
        mockMvc.perform(get("/api/etl/runs/latest").param("job", "ingestJobPostings"))
                .andExpect(jsonPath("$.executionId").value(3));
    }

    @Test
    @DisplayName("an empty history is an empty page, and latest is a 404")
    void emptyHistory() throws Exception {
        mockMvc.perform(get("/api/etl/runs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", empty()))
                .andExpect(jsonPath("$.totalElements").value(0));
        mockMvc.perform(get("/api/etl/runs/latest"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("No ETL run has been recorded yet"));
    }

    @Test
    @DisplayName("rejects out-of-range paging")
    void validation() throws Exception {
        mockMvc.perform(get("/api/etl/runs").param("size", "51")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/etl/runs").param("page", "-1")).andExpect(status().isBadRequest());
    }

    private void seedHistory() {
        jdbcTemplate.update("INSERT INTO batch_job_instance (job_instance_id, version, job_name, job_key) VALUES "
                + "(1, 0, 'ingestJobPostings', 'a'), (2, 0, 'reprocessJobPostings', 'b'), "
                + "(3, 0, 'ingestJobPostings', 'c')");
        execution(1, 1, "COMPLETED", "2026-09-20 10:00:00", "2026-09-20 10:00:12", null);
        execution(2, 2, "FAILED", "2026-09-21 10:00:00", "2026-09-21 10:00:01", "Boom");
        execution(3, 3, "STARTED", "2026-09-22 10:00:00", null, null);

        // Two steps on run 1, so the counters must be summed: 10 read, 7 written, 3 skipped.
        step(1, 1, 6, 4, 1, 1, 0);
        step(2, 1, 4, 3, 0, 1, 0);
        step(3, 2, 1, 0, 0, 0, 0);
        step(4, 3, 4, 0, 0, 0, 0);
        jdbcTemplate.update("INSERT INTO etl_run_metrics (job_execution_id, records_loaded, duplicates_skipped, "
                + "skill_links_created) VALUES (1, 5, 2, 9), (2, 0, 0, 0)");
    }

    private void execution(long id, long instanceId, String status, String start, String end, String message) {
        jdbcTemplate.update("""
                        INSERT INTO batch_job_execution (job_execution_id, version, job_instance_id, create_time,
                                                         start_time, end_time, status, exit_code, exit_message)
                        VALUES (?, 1, ?, ?::timestamp, ?::timestamp, ?::timestamp, ?, ?, ?)
                        """,
                id, instanceId, start, start, end, status, status, message);
    }

    private void step(long id, long executionId, long read, long written, long readSkips, long processSkips,
                      long writeSkips) {
        jdbcTemplate.update("""
                        INSERT INTO batch_step_execution (step_execution_id, version, step_name, job_execution_id,
                                                          create_time, read_count, write_count, read_skip_count,
                                                          process_skip_count, write_skip_count)
                        VALUES (?, 1, ?, ?, now(), ?, ?, ?, ?, ?)
                        """,
                id, "step" + id, executionId, read, written, readSkips, processSkips, writeSkips);
    }
}
