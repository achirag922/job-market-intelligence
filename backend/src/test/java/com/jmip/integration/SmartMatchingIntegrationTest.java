package com.jmip.integration;

import com.jayway.jsonpath.JsonPath;
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

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * V9.3: one engine everywhere. Alice (Java resume, active Backend Developer goal with Docker on its
 * roadmap). Job 1 asks for Java and says Kubernetes is a plus; job 2 is a DevOps role with Docker.
 */
@SpringBootTest(properties = {"jmip.ai.provider=stub", "jmip.alerts.enabled=false"})
@AutoConfigureMockMvc
@Testcontainers
class SmartMatchingIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine");

    private static final String ALICE = "alice@example.test";
    private static final String BOB = "bob@example.test";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID resume;

    @BeforeEach
    void seed() {
        jdbcTemplate.execute("TRUNCATE career_goal_skills, career_goals, match_preferences, resume_skills, resumes, job_skills, "
                + "jobs, skills, companies, users RESTART IDENTITY CASCADE");
        UUID alice = account(ALICE);
        account(BOB);
        jdbcTemplate.update("INSERT INTO companies (id, name) VALUES (1, 'Acme')");
        jdbcTemplate.update("INSERT INTO skills (id, name, category) VALUES (1, 'Java', 'LANGUAGE'), (2, 'Kubernetes', 'PLATFORM'), "
                + "(3, 'Docker', 'PLATFORM')");
        job(1, "Backend Developer", "Backend Developer", "Build services in Java. Kubernetes is a plus.");
        job(2, "DevOps Engineer", "DevOps Engineer", "Run Docker and Java services.");
        jdbcTemplate.update("INSERT INTO job_skills (job_id, skill_id) VALUES (1, 1), (1, 2), (2, 1), (2, 3)");
        resume = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO resumes (id, user_id, original_file_name, stored_file_name, content_type, file_size_bytes,
                                     processing_status, processed_at, title, is_default, updated_at)
                VALUES (?, ?, 'cv.pdf', ?, 'application/pdf', 100, 'COMPLETED', now(), 'CV', TRUE, now())
                """, resume, alice, resume + ".pdf");
        jdbcTemplate.update("INSERT INTO resume_skills (resume_id, skill_id) VALUES (?, 1)", resume);
        UUID goal = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO career_goals (id, user_id, target_role, target_category, status, created_at, updated_at)
                VALUES (?, ?, 'Backend Developer', 'Backend Developer', 'ACTIVE', now(), now())
                """, goal, alice);
        jdbcTemplate.update("INSERT INTO career_goal_skills (goal_id, skill_id) VALUES (?, 3)", goal);
    }

    @Test
    @DisplayName("the match weighs required skills, aligns with the goal, and explains every dimension")
    void breakdown() throws Exception {
        mockMvc.perform(get("/api/resumes/" + resume + "/match/1").with(user(ALICE).roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.breakdown.skills.score").value(100.0))
                .andExpect(jsonPath("$.breakdown.optionalSkills", contains("Kubernetes")))
                .andExpect(jsonPath("$.breakdown.missingRequiredSkills").isEmpty())
                .andExpect(jsonPath("$.breakdown.careerGoal.status").value("MATCH"))
                .andExpect(jsonPath("$.breakdown.role.status").value("UNAVAILABLE"))
                // skills 100*60 + goal 100*10 over 70
                .andExpect(jsonPath("$.breakdown.overallPercentage").value(100.0))
                .andExpect(jsonPath("$.breakdown.reasons[0]").value(org.hamcrest.Matchers.startsWith("Skills (weight 60)")));
        mockMvc.perform(get("/api/resumes/" + resume + "/match/2").with(user(ALICE).roles("USER")))
                .andExpect(jsonPath("$.breakdown.careerGoal.status").value("PARTIAL"))
                .andExpect(jsonPath("$.breakdown.careerGoal.detail").value("1 of its 2 skills are on your Backend Developer roadmap"))
                .andExpect(jsonPath("$.breakdown.missingRequiredSkills", contains("Docker")))
                // skills 50*60 + goal 50*10 over 70
                .andExpect(jsonPath("$.breakdown.overallPercentage").value(50.0));
    }

    @Test
    @DisplayName("recommendations, the personalized feed and resume analysis give the same match")
    void consistency() throws Exception {
        String recommendations = mockMvc.perform(get("/api/resumes/" + resume + "/recommendations").with(user(ALICE).roles("USER")))
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<Integer>>read(recommendations, "$[*].jobId")).containsExactly(1, 2);
        assertThat(JsonPath.<List<Double>>read(recommendations, "$[*].overallMatchPercentage")).containsExactly(100.0, 50.0);

        String feed = mockMvc.perform(get("/api/jobs/personalized").with(user(ALICE).roles("USER")))
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<Double>>read(feed, "$.jobs[?(@.job.id == 1)].matchPercentage")).containsExactly(100.0);
        assertThat(JsonPath.<List<Double>>read(feed, "$.jobs[?(@.job.id == 2)].matchPercentage")).containsExactly(50.0);

        mockMvc.perform(get("/api/resumes/" + resume + "/optimize/1").with(user(ALICE).roles("USER")))
                .andExpect(jsonPath("$.overallMatchPercentage").value(100.0))
                .andExpect(jsonPath("$.breakdown.optionalSkills", contains("Kubernetes")));
    }

    @Test
    @DisplayName("another user can neither match against the resume nor see its goal in their own feed")
    void ownership() throws Exception {
        mockMvc.perform(get("/api/resumes/" + resume + "/match/1").with(user(BOB).roles("USER"))).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/jobs/personalized").with(user(BOB).roles("USER")))
                .andExpect(jsonPath("$.context.careerGoal").doesNotExist())
                .andExpect(jsonPath("$.jobs[0].breakdown").doesNotExist());
    }

    private UUID account(String email) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO users (id, full_name, email, password_hash, role, email_verified_at)
                VALUES (?, 'Test', ?, '{bcrypt}not-a-real-hash', 'USER', now())
                """, id, email);
        return id;
    }

    private void job(long id, String title, String category, String description) {
        jdbcTemplate.update("""
                INSERT INTO jobs (id, title, company_id, description, source, source_url, content_fingerprint,
                                  job_category, classification_confidence, classified_at)
                VALUES (?, ?, 1, ?, 'itest', ?, ?, ?, 0.9, now())
                """, id, title, description, "https://example.invalid/" + id, String.format("%064d", id), category);
    }
}
