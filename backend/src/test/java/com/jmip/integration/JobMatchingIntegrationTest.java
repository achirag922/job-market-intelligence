package com.jmip.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.hamcrest.Matchers.contains;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * V8.3: match preferences are the signed-in user's own, and the match and recommendations
 * score and rank with them. Alice's resume has Java and Docker; job 1 (Berlin, remote,
 * 3–5 years, up to 70000 EUR) and job 2 (Paris, on-site, no salary) both list the same skills.
 */
@SpringBootTest(properties = "jmip.ai.provider=stub")
@AutoConfigureMockMvc
@Testcontainers
class JobMatchingIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine");

    private static final String ALICE = "alice@example.test";
    private static final String BOB = "bob@example.test";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID aliceResume;

    @BeforeEach
    void seed() {
        jdbcTemplate.execute("TRUNCATE match_preferences, resume_skills, resumes, job_skills, jobs, skills, companies, "
                + "locations, users RESTART IDENTITY CASCADE");
        UUID alice = account(ALICE);
        account(BOB);
        jdbcTemplate.update("INSERT INTO companies (id, name) VALUES (1, 'Acme Systems')");
        jdbcTemplate.update("INSERT INTO locations (id, city, country) VALUES (1, 'Berlin', 'Germany'), (2, 'Paris', 'France')");
        jdbcTemplate.update("INSERT INTO skills (id, name, category) VALUES (1, 'Java', 'LANGUAGE'), (2, 'Docker', 'PLATFORM')");
        job(1, 1, "Build services. Fully remote.", 3, 5, "50000", "70000", "EUR");
        job(2, 2, "Build services in our on-site team.", null, null, null, null, null);
        jdbcTemplate.update("INSERT INTO job_skills (job_id, skill_id) VALUES (1, 1), (1, 2), (2, 1), (2, 2)");
        aliceResume = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO resumes (id, user_id, original_file_name, stored_file_name, content_type, file_size_bytes,
                                     processing_status, processed_at, title, is_default, updated_at)
                VALUES (?, ?, 'cv.pdf', ?, 'application/pdf', 100, 'COMPLETED', now(), 'Main CV', TRUE, now())
                """, aliceResume, alice, aliceResume + ".pdf");
        jdbcTemplate.update("INSERT INTO resume_skills (resume_id, skill_id) VALUES (?, 1), (?, 2)", aliceResume, aliceResume);
    }

    @Test
    @DisplayName("without preferences the breakdown marks every other dimension unavailable and the score is the skill match")
    void noPreferences() throws Exception {
        mockMvc.perform(get("/api/match-preferences").with(user(ALICE).roles("USER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.workMode").doesNotExist());
        mockMvc.perform(get("/api/resumes/" + aliceResume + "/match/1").with(user(ALICE).roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matchPercentage").value(100.0))
                .andExpect(jsonPath("$.breakdown.overallPercentage").value(100.0))
                .andExpect(jsonPath("$.breakdown.skills.status").value("MATCH"))
                .andExpect(jsonPath("$.breakdown.experience.status").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.breakdown.salary.status").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.breakdown.salary.score").doesNotExist());
        mockMvc.perform(get("/api/resumes/" + aliceResume + "/recommendations").with(user(ALICE).roles("USER")))
                .andExpect(jsonPath("$[*].jobId", contains(1, 2)));
    }

    @Test
    @DisplayName("saved preferences score every dimension and re-rank recommendations")
    void preferencesRank() throws Exception {
        save(ALICE, """
                {"yearsExperience": 4, "preferredLocation": "Paris, France", "workMode": "ON_SITE",
                 "minSalary": 60000, "salaryCurrency": "EUR"}
                """).andExpect(status().isOk()).andExpect(jsonPath("$.workMode").value("ON_SITE"));

        mockMvc.perform(get("/api/resumes/" + aliceResume + "/match/1").with(user(ALICE).roles("USER")))
                .andExpect(jsonPath("$.breakdown.experience.status").value("MATCH"))
                .andExpect(jsonPath("$.breakdown.location.status").value("NO_MATCH"))
                .andExpect(jsonPath("$.breakdown.workMode.status").value("NO_MATCH"))
                .andExpect(jsonPath("$.breakdown.salary.status").value("MATCH"))
                // (100*60 + 100*15 + 0*10 + 0*10 + 100*5) / 100
                .andExpect(jsonPath("$.breakdown.overallPercentage").value(80.0));
        mockMvc.perform(get("/api/resumes/" + aliceResume + "/match/2").with(user(ALICE).roles("USER")))
                .andExpect(jsonPath("$.breakdown.experience.status").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.breakdown.salary.detail").value("The posting states no salary"))
                .andExpect(jsonPath("$.breakdown.overallPercentage").value(100.0));
        mockMvc.perform(get("/api/resumes/" + aliceResume + "/recommendations").with(user(ALICE).roles("USER")))
                .andExpect(jsonPath("$[*].jobId", contains(2, 1)))
                .andExpect(jsonPath("$[0].overallMatchPercentage").value(100.0))
                .andExpect(jsonPath("$[1].overallMatchPercentage").value(80.0))
                .andExpect(jsonPath("$[1].matchPercentage").value(100.0));
    }

    @Test
    @DisplayName("preferences are the signed-in user's own, validated, and changed only with the CSRF token")
    void ownershipAndValidation() throws Exception {
        save(ALICE, "{\"workMode\": \"REMOTE\"}").andExpect(status().isOk());
        mockMvc.perform(get("/api/match-preferences").with(user(BOB).roles("USER")))
                .andExpect(jsonPath("$.workMode").doesNotExist());
        // Bob cannot score against Alice's resume at all.
        mockMvc.perform(get("/api/resumes/" + aliceResume + "/match/1").with(user(BOB).roles("USER")))
                .andExpect(status().isNotFound());

        save(ALICE, "{\"workMode\": \"SOMETIMES\"}").andExpect(status().isBadRequest());
        save(ALICE, "{\"minSalary\": 50000}").andExpect(status().isBadRequest());
        save(ALICE, "{\"yearsExperience\": -1}").andExpect(status().isBadRequest());

        mockMvc.perform(put("/api/match-preferences").with(user(ALICE).roles("USER"))
                        .with(request -> {
                            request.setRequestedSessionId("session");
                            request.setRequestedSessionIdValid(true);
                            return request;
                        })
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/match-preferences")).andExpect(status().isUnauthorized());
    }

    private org.springframework.test.web.servlet.ResultActions save(String email, String json) throws Exception {
        return mockMvc.perform(put("/api/match-preferences").with(user(email).roles("USER"))
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private UUID account(String email) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO users (id, full_name, email, password_hash, role, email_verified_at)
                VALUES (?, 'Test', ?, '{bcrypt}not-a-real-hash', 'USER', now())
                """, id, email);
        return id;
    }

    private void job(long id, long locationId, String description, Integer minYears, Integer maxYears,
                     String minSalary, String maxSalary, String currency) {
        jdbcTemplate.update("""
                INSERT INTO jobs (id, title, company_id, location_id, description, source, source_url, content_fingerprint,
                                  experience_min, experience_max, salary_min, salary_max, currency)
                VALUES (?, 'Java Developer', 1, ?, ?, 'itest', ?, ?, ?, ?, ?::numeric, ?::numeric, ?)
                """, id, locationId, description, "https://example.invalid/" + id, String.format("%064d", id),
                minYears, maxYears, minSalary, maxSalary, currency);
    }
}
