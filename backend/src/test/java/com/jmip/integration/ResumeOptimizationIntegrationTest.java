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

import java.util.UUID;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * V8.6: resume optimisation against one job. Job 1 (Senior Backend Engineer, 5+ years) lists
 * Java, Docker and Kubernetes and talks about microservices and APIs. Alice's first version
 * has Java; her second has Java and Docker; her third has no stored text.
 */
@SpringBootTest(properties = {"jmip.ai.provider=stub", "jmip.alerts.enabled=false"})
@AutoConfigureMockMvc
@Testcontainers
class ResumeOptimizationIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine");

    private static final String ALICE = "alice@example.test";
    private static final String BOB = "bob@example.test";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID first;
    private UUID second;
    private UUID noText;

    @BeforeEach
    void seed() {
        jdbcTemplate.execute("TRUNCATE match_preferences, resume_skills, resumes, job_skills, jobs, skills, companies, "
                + "locations, users RESTART IDENTITY CASCADE");
        UUID alice = account(ALICE);
        account(BOB);
        jdbcTemplate.update("INSERT INTO companies (id, name) VALUES (1, 'Acme Systems')");
        jdbcTemplate.update("INSERT INTO skills (id, name, category) VALUES (1, 'Java', 'LANGUAGE'), (2, 'Docker', 'PLATFORM'), "
                + "(3, 'Kubernetes', 'PLATFORM')");
        jdbcTemplate.update("""
                INSERT INTO jobs (id, title, company_id, description, source, source_url, content_fingerprint, experience_min)
                VALUES (1, 'Senior Backend Engineer', 1,
                        'Build microservices and REST APIs. Deploy microservices on Kubernetes. Own monitoring for microservices and APIs.',
                        'itest', 'https://example.invalid/1', ?, 5)
                """, String.format("%064d", 1));
        jdbcTemplate.update("INSERT INTO job_skills (job_id, skill_id) VALUES (1, 1), (1, 2), (1, 3)");
        first = resume(alice, "v1", "Summary\nBackend engineer building APIs.\nSkills\nJava\nExperience\nAcme 2020-2024: microservices.", 1);
        second = resume(alice, "v2", "Skills\nJava, Docker\nExperience\nSenior engineer running microservices.", 1, 2);
        noText = resume(alice, "v3", null, 1);
    }

    @Test
    @DisplayName("the match, skills, keywords, sections and suggestions come only from the resume and the posting")
    void optimize() throws Exception {
        mockMvc.perform(get("/api/resumes/" + first + "/optimize/1").with(user(ALICE).roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.skillMatchPercentage").value(33.3))
                .andExpect(jsonPath("$.overallMatchPercentage").value(33.3))
                .andExpect(jsonPath("$.breakdown.skills.status").value("PARTIAL"))
                .andExpect(jsonPath("$.matchedSkills[*].name", contains("Java")))
                .andExpect(jsonPath("$.missingSkills[*].name", contains("Docker", "Kubernetes")))
                .andExpect(jsonPath("$.presentKeywords[*].term", hasItem("microservices")))
                .andExpect(jsonPath("$.presentKeywords[*].term", hasItem("backend")))
                .andExpect(jsonPath("$.missingKeywords[*].term", hasItem("senior")))
                // Skills are compared as skills, never counted again as keywords.
                .andExpect(jsonPath("$.missingKeywords[*].term", not(hasItem("kubernetes"))))
                .andExpect(jsonPath("$.sectionsFound", contains("Summary", "Skills", "Experience")))
                .andExpect(jsonPath("$.sectionsMissing", hasItem("Education")))
                .andExpect(jsonPath("$.requiredExperience").exists())
                .andExpect(jsonPath("$.experienceGap").exists())
                .andExpect(jsonPath("$.suggestions[*].area", hasItem("SKILLS")))
                .andExpect(jsonPath("$.suggestions[*].area", hasItem("KEYWORDS")))
                .andExpect(jsonPath("$.disclaimer").exists());
    }

    @Test
    @DisplayName("without stored text, keywords and sections are marked unavailable rather than guessed")
    void noStoredText() throws Exception {
        mockMvc.perform(get("/api/resumes/" + noText + "/optimize/1").with(user(ALICE).roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.skillMatchPercentage").value(33.3))
                .andExpect(jsonPath("$.keywordNote").exists())
                .andExpect(jsonPath("$.presentKeywords").doesNotExist())
                .andExpect(jsonPath("$.sectionsFound").doesNotExist())
                .andExpect(jsonPath("$.suggestions[*].area", not(hasItem("SECTIONS"))));
    }

    @Test
    @DisplayName("two versions against one job show the skill changes, the match change and the terms gained and lost")
    void compareVersions() throws Exception {
        mockMvc.perform(get("/api/resumes/compare-for-job").with(user(ALICE).roles("USER"))
                        .param("resumeId1", first.toString()).param("resumeId2", second.toString()).param("jobId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.versions.skillsAdded[*].name", contains("Docker")))
                .andExpect(jsonPath("$.first.skillMatchPercentage").value(33.3))
                .andExpect(jsonPath("$.second.skillMatchPercentage").value(66.7))
                .andExpect(jsonPath("$.skillChange").value(33.4))
                .andExpect(jsonPath("$.overallChange").value(33.4))
                .andExpect(jsonPath("$.keywordsGained", contains("senior")))
                .andExpect(jsonPath("$.keywordsLost", contains("apis", "backend")));
        mockMvc.perform(get("/api/resumes/compare-for-job").with(user(ALICE).roles("USER"))
                        .param("resumeId1", first.toString()).param("resumeId2", first.toString()).param("jobId", "1"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("another account's resume cannot be optimised or compared")
    void ownership() throws Exception {
        mockMvc.perform(get("/api/resumes/" + first + "/optimize/1").with(user(BOB).roles("USER")))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/resumes/compare-for-job").with(user(BOB).roles("USER"))
                        .param("resumeId1", first.toString()).param("resumeId2", second.toString()).param("jobId", "1"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/resumes/" + first + "/optimize/1")).andExpect(status().isUnauthorized());
    }

    private UUID account(String email) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO users (id, full_name, email, password_hash, role, email_verified_at)
                VALUES (?, 'Test', ?, '{bcrypt}not-a-real-hash', 'USER', now())
                """, id, email);
        return id;
    }

    private UUID resume(UUID owner, String label, String text, long... skillIds) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO resumes (id, user_id, original_file_name, stored_file_name, content_type, file_size_bytes,
                                     processing_status, processed_at, extracted_text, title, version_label, is_default, updated_at)
                VALUES (?, ?, 'cv.pdf', ?, 'application/pdf', 100, 'COMPLETED', now(), ?, 'CV', ?, FALSE, now())
                """, id, owner, id + ".pdf", text, label);
        for (long skillId : skillIds) {
            jdbcTemplate.update("INSERT INTO resume_skills (resume_id, skill_id) VALUES (?, ?)", id, skillId);
        }
        return id;
    }
}
