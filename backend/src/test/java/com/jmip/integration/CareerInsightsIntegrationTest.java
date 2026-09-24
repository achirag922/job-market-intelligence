package com.jmip.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /api/resumes/{id}/career-insights} against a real PostgreSQL, through the
 * real category analytics, trend and recommendation services.
 */
@SpringBootTest(properties = "jmip.ai.provider=stub")
@AutoConfigureMockMvc
@Testcontainers
// V6.10.3: the API requires a signed-in USER; these tests exercise behaviour behind that.
@WithMockUser(roles = "USER")
class CareerInsightsIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine");

    private static final UUID RESUME_ID = UUID.fromString("7a6c1d2e-0000-4000-8000-000000000001");
    private static final UUID PENDING_RESUME_ID = UUID.fromString("7a6c1d2e-0000-4000-8000-000000000002");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void seed() {
        jdbcTemplate.execute("TRUNCATE resume_skills, resumes, skill_demand_snapshot, job_skills, jobs, skills, "
                + "companies, locations RESTART IDENTITY CASCADE");

        jdbcTemplate.update("INSERT INTO companies (id, name) VALUES (1, 'Acme Systems')");
        jdbcTemplate.update("INSERT INTO skills (id, name, category) VALUES "
                + "(1, 'Python', 'LANGUAGE'), (2, 'SQL', 'DATA'), (3, 'Spark', 'DATA'), (4, 'Java', 'LANGUAGE')");

        // Data Engineer: Python in 3 of 4 postings, SQL and Spark in 2 of 4 each.
        job(1, "Data Engineer", 1, 2, 3);
        job(2, "Data Engineer", 1, 2);
        job(4, "Data Engineer", 3);
        job(5, "Data Engineer", 1);
        job(3, "Backend Developer", 4, 1);

        resume(RESUME_ID, "COMPLETED");
        jdbcTemplate.update("INSERT INTO resume_skills (resume_id, skill_id) VALUES (?, 1), (?, 4)",
                RESUME_ID, RESUME_ID);
        resume(PENDING_RESUME_ID, "PROCESSING");
    }

    @Test
    @DisplayName("reports strong skills, gaps and the category's recommended jobs from stored data")
    void insightsForRequestedCategory() throws Exception {
        mockMvc.perform(get("/api/resumes/{id}/career-insights", RESUME_ID).param("category", "Data Engineer"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targetCategory").value("Data Engineer"))
                .andExpect(jsonPath("$.categorySource").value("REQUESTED"))
                .andExpect(jsonPath("$.resumeSkills[*].name").value(contains("Java", "Python")))
                .andExpect(jsonPath("$.highDemandSkills", hasSize(3)))
                .andExpect(jsonPath("$.highDemandSkills[0].skill").value("Python"))
                .andExpect(jsonPath("$.highDemandSkills[0].percentageOfJobs").value(75.0))
                .andExpect(jsonPath("$.highDemandSkills[0].onResume").value(true))
                .andExpect(jsonPath("$.strongSkills[*].skill").value(contains("Python")))
                .andExpect(jsonPath("$.skillGaps[*].skill").value(containsInAnyOrder("SQL", "Spark")))
                .andExpect(jsonPath("$.skillGaps[*].percentageOfJobs").value(contains(50.0, 50.0)))
                .andExpect(jsonPath("$.focusAreas[*].skill").value(containsInAnyOrder("SQL", "Spark")))
                // No snapshot history, so no trend can be claimed.
                .andExpect(jsonPath("$.trendingSkills", empty()))
                // V6.3 ranking (100% job 5, 50% job 2, 33.3% job 1), keeping only Data Engineer.
                .andExpect(jsonPath("$.recommendedJobs[*].jobId").value(contains(5, 2, 1)))
                .andExpect(jsonPath("$.recommendedJobs[0].matchPercentage").value(100.0))
                .andExpect(jsonPath("$.summary").doesNotExist());
    }

    @Test
    @DisplayName("without a category, the top recommendation's category is the target")
    void categoryFromTopRecommendation() throws Exception {
        mockMvc.perform(get("/api/resumes/{id}/career-insights", RESUME_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targetCategory").value("Backend Developer"))
                .andExpect(jsonPath("$.categorySource").value("TOP_RECOMMENDATION"))
                .andExpect(jsonPath("$.recommendedJobs[*].jobId").value(contains(3)));
    }

    @Test
    @DisplayName("summary=true adds the V5 generator's description alongside the figures")
    void summaryOnRequest() throws Exception {
        mockMvc.perform(get("/api/resumes/{id}/career-insights", RESUME_ID)
                        .param("category", "Data Engineer").param("summary", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary").isNotEmpty())
                .andExpect(jsonPath("$.strongSkills", hasSize(1)));
    }

    @Test
    @DisplayName("unknown resume and unknown category are 404s; an unprocessed resume is rejected")
    void errors() throws Exception {
        mockMvc.perform(get("/api/resumes/{id}/career-insights", UUID.randomUUID()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/resumes/{id}/career-insights", RESUME_ID).param("category", "Astronaut"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/resumes/{id}/career-insights", RESUME_ID).param("category", "x".repeat(51)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/resumes/{id}/career-insights", PENDING_RESUME_ID))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("a resume with no skills and no category gets empty sections and a note")
    void emptyResume() throws Exception {
        jdbcTemplate.update("DELETE FROM resume_skills");

        mockMvc.perform(get("/api/resumes/{id}/career-insights", RESUME_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targetCategory").doesNotExist())
                .andExpect(jsonPath("$.resumeSkills", empty()))
                .andExpect(jsonPath("$.skillGaps", empty()))
                .andExpect(jsonPath("$.recommendedJobs", empty()))
                .andExpect(jsonPath("$.note").isNotEmpty());
    }

    private void job(long id, String category, long... skillIds) {
        jdbcTemplate.update("""
                        INSERT INTO jobs (id, title, company_id, description, source, source_url,
                                          content_fingerprint, job_category, classification_confidence,
                                          classified_at)
                        VALUES (?, ?, 1, 'Description', 'itest', ?, ?, ?, 0.9, now())
                        """,
                id, category + " " + id, "https://example.invalid/jobs/" + id, String.format("%064d", id), category);
        for (long skillId : skillIds) {
            jdbcTemplate.update("INSERT INTO job_skills (job_id, skill_id) VALUES (?, ?)", id, skillId);
        }
    }

    private void resume(UUID id, String status) {
        jdbcTemplate.update("""
                        INSERT INTO resumes (id, original_file_name, stored_file_name, content_type,
                                             file_size_bytes, processing_status)
                        VALUES (?, 'resume.pdf', ?, 'application/pdf', 100, ?)
                        """,
                id, id + ".pdf", status);
    }
}
