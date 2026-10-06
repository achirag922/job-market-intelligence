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
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * V9.2: the personalized feed and preference-aware search. Alice has a Java resume, an active
 * "Backend Developer" goal, applied to job 4 and saved job 8, and prefers Data Analyst roles, Docker,
 * Berlin and remote work, excluding Evil Corp and Paris. Bob has nothing.
 *
 * <p>Jobs: 1 Backend (Java, Docker; Berlin; remote), 2 Data Analyst (SQL; Berlin; on-site), 3 Backend at
 * Evil Corp, 4 Backend (applied), 5 Backend (inactive), 6 Frontend (React; seen 60 days ago), 7 Backend
 * in Paris, 8 Backend (Java; saved; seen 30 days ago).
 */
@SpringBootTest(properties = {"jmip.ai.provider=stub", "jmip.alerts.enabled=false"})
@AutoConfigureMockMvc
@Testcontainers
class PersonalizedFeedIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine");

    private static final String ALICE = "alice@example.test";
    private static final String BOB = "bob@example.test";
    private static final String ALICE_PREFERENCES = """
            {"preferredLocation": "Berlin", "workMode": "REMOTE", "preferredCategories": ["Data Analyst"],
             "preferredSkills": ["Docker"], "excludedCompanies": ["Evil Corp"], "excludedLocations": ["Paris"]}
            """;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void seed() throws Exception {
        jdbcTemplate.execute("TRUNCATE career_goals, saved_jobs, match_preferences, resume_skills, resumes, job_skills, jobs, "
                + "skills, companies, locations, users RESTART IDENTITY CASCADE");
        UUID alice = account(ALICE);
        account(BOB);
        jdbcTemplate.update("INSERT INTO companies (id, name) VALUES (1, 'Acme'), (2, 'Evil Corp'), (3, 'Beta')");
        jdbcTemplate.update("INSERT INTO locations (id, city, country) VALUES (1, 'Berlin', 'Germany'), (2, 'Paris', 'France')");
        jdbcTemplate.update("INSERT INTO skills (id, name, category) VALUES (1, 'Java', 'LANGUAGE'), (2, 'Docker', 'PLATFORM'), "
                + "(3, 'SQL', 'LANGUAGE'), (4, 'React', 'FRAMEWORK')");
        job(1, "Backend Developer", "Backend Developer", 1, 1, "Fully remote team.", true, 0);
        job(2, "Data Analyst", "Data Analyst", 3, 1, "On-site in Berlin.", true, 0);
        job(3, "Backend Developer", "Backend Developer", 2, 1, "Build services.", true, 0);
        job(4, "Java Developer", "Backend Developer", 1, 1, "Build services.", true, 0);
        job(5, "Old Role", "Backend Developer", 1, 1, "Build services.", false, 0);
        job(6, "Frontend Developer", "Frontend Developer", 3, 1, "Build interfaces.", true, 60);
        job(7, "Platform Engineer", "Backend Developer", 3, 2, "Build services.", true, 0);
        job(8, "Backend Engineer", "Backend Developer", 3, 1, "Build services.", true, 30);
        jdbcTemplate.update("INSERT INTO job_skills (job_id, skill_id) VALUES (1, 1), (1, 2), (2, 3), (3, 1), (4, 1), (5, 1), "
                + "(6, 4), (7, 1), (8, 1)");
        UUID resume = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO resumes (id, user_id, original_file_name, stored_file_name, content_type, file_size_bytes,
                                     processing_status, processed_at, title, is_default, updated_at)
                VALUES (?, ?, 'cv.pdf', ?, 'application/pdf', 100, 'COMPLETED', now(), 'CV', TRUE, now())
                """, resume, alice, resume + ".pdf");
        jdbcTemplate.update("INSERT INTO resume_skills (resume_id, skill_id) VALUES (?, 1)", resume);
        jdbcTemplate.update("""
                INSERT INTO career_goals (id, user_id, target_role, target_category, status, created_at, updated_at)
                VALUES (?, ?, 'Backend Developer', 'Backend Developer', 'ACTIVE', now(), now())
                """, UUID.randomUUID(), alice);
        tracked(alice, 4, "APPLIED");
        tracked(alice, 8, "SAVED");
        savePreferences(ALICE, ALICE_PREFERENCES).andExpect(status().isOk());
    }

    @Test
    @DisplayName("the feed ranks by the V8.3 match plus goal, role, skill, freshness and history signals, with reasons")
    void ranking() throws Exception {
        mockMvc.perform(get("/api/jobs/personalized").with(user(ALICE).roles("USER")))
                .andExpect(status().isOk())
                // Applied (4), inactive (5), excluded company (3) and excluded location (7) are left out.
                .andExpect(jsonPath("$.jobs[*].job.id", contains(8, 1, 2, 6)))
                // Job 8: match (skills 100, Berlin, goal 100, role 0, preferred skills 0) = 88.9, + history 4.
                .andExpect(jsonPath("$.jobs[0].matchPercentage").value(88.9))
                .andExpect(jsonPath("$.jobs[0].priority").value(92.9))
                .andExpect(jsonPath("$.jobs[0].saved").value(true))
                .andExpect(jsonPath("$.jobs[0].reasons[*].text", hasItem("Strong skill match (1 of 1 skills)")))
                .andExpect(jsonPath("$.jobs[0].reasons[*].text", hasItem("You saved this job")))
                // Job 1: match (skills 50, Berlin, remote, goal 100, role 0, Docker 100) = 65.0, + new 5 + history 4.
                .andExpect(jsonPath("$.jobs[1].matchPercentage").value(65.0))
                .andExpect(jsonPath("$.jobs[1].priority").value(74.0))
                .andExpect(jsonPath("$.jobs[1].reasons[*].text", containsInAnyOrder(
                        "Partial skill match (1 of 2 skills)", "Missing 1 required skill", "Preferred location",
                        "Remote preference matched", "Matches your career goal: Backend Developer",
                        "Not one of your preferred roles", "Has your preferred skill: Docker", "New in the last 7 days",
                        "Similar to jobs you applied to")))
                .andExpect(jsonPath("$.jobs[1].breakdown.careerGoal.status").value("MATCH"))
                .andExpect(jsonPath("$.jobs[1].breakdown.missingRequiredSkills", contains("Docker")))
                .andExpect(jsonPath("$.jobs[1].breakdown.skills.status").value("PARTIAL"))
                .andExpect(jsonPath("$.jobs[2].reasons[*].text", hasItem("Matches target role: Data Analyst")))
                .andExpect(jsonPath("$.jobs[2].reasons[*].text", hasItem("Different role from your career goal")))
                .andExpect(jsonPath("$.jobs[2].reasons[*].text", hasItem("Work mode differs from your preference")))
                .andExpect(jsonPath("$.jobs[3].reasons[*].text", hasItem("Missing 1 required skill")))
                .andExpect(jsonPath("$.context.resume").value(true))
                .andExpect(jsonPath("$.context.careerGoal").value("Backend Developer"))
                .andExpect(jsonPath("$.context.excludedApplied").value(1))
                .andExpect(jsonPath("$.context.excludedByPreference").value(2));
    }

    @Test
    @DisplayName("another user's feed and preferences are their own")
    void crossUser() throws Exception {
        mockMvc.perform(get("/api/jobs/personalized").with(user(BOB).roles("USER")))
                .andExpect(jsonPath("$.context.resume").value(false))
                .andExpect(jsonPath("$.note").exists())
                .andExpect(jsonPath("$.jobs", hasSize(7)))
                .andExpect(jsonPath("$.jobs[*].job.id", hasItem(3)))
                .andExpect(jsonPath("$.jobs[0].matchPercentage").doesNotExist());
        mockMvc.perform(get("/api/match-preferences").with(user(BOB).roles("USER")))
                .andExpect(jsonPath("$.preferredCategories", hasSize(0)));

        savePreferences(BOB, "{\"excludedCompanies\": [\"Acme\"]}").andExpect(status().isOk());
        mockMvc.perform(get("/api/match-preferences").with(user(ALICE).roles("USER")))
                .andExpect(jsonPath("$.excludedCompanies", contains("Evil Corp")));
        mockMvc.perform(get("/api/jobs/personalized")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/jobs/personalized").param("limit", "0").with(user(ALICE).roles("USER")))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("preferences are saved, returned, validated and cleared")
    void preferences() throws Exception {
        mockMvc.perform(get("/api/match-preferences").with(user(ALICE).roles("USER")))
                .andExpect(jsonPath("$.workMode").value("REMOTE"))
                .andExpect(jsonPath("$.preferredCategories", contains("Data Analyst")))
                .andExpect(jsonPath("$.preferredSkills", contains("Docker")))
                .andExpect(jsonPath("$.excludedLocations", contains("Paris")));
        savePreferences(ALICE, "{\"preferredSkills\": [" + String.join(",",
                java.util.stream.IntStream.range(0, 21).mapToObj(i -> "\"s" + i + "\"").toList()) + "]}")
                .andExpect(status().isBadRequest());
        savePreferences(ALICE, "{\"preferredCategories\": [\" Data Analyst \", \"Data Analyst\", \"\"]}")
                .andExpect(jsonPath("$.preferredCategories", contains("Data Analyst")))
                .andExpect(jsonPath("$.excludedCompanies", hasSize(0)));
    }

    @Test
    @DisplayName("search can opt in to preferences, a filter in the request wins, and plain search is unchanged")
    void search() throws Exception {
        mockMvc.perform(get("/api/jobs").param("usePreferences", "true").with(user(ALICE).roles("USER")))
                .andExpect(jsonPath("$.content[*].id", contains(2)));
        mockMvc.perform(get("/api/jobs").param("usePreferences", "true").param("category", "Backend Developer")
                        .with(user(ALICE).roles("USER")))
                .andExpect(jsonPath("$.content[*].id", hasItem(1)))
                .andExpect(jsonPath("$.content[*].id", not(hasItem(3))))
                .andExpect(jsonPath("$.content[*].id", not(hasItem(7))));
        mockMvc.perform(get("/api/jobs").param("category", "Backend Developer").with(user(ALICE).roles("USER")))
                .andExpect(jsonPath("$.totalElements").value(6))
                .andExpect(jsonPath("$.content[*].id", hasItem(3)));
    }

    private ResultActions savePreferences(String email, String json) throws Exception {
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

    private void tracked(UUID owner, long jobId, String status) {
        jdbcTemplate.update("""
                INSERT INTO saved_jobs (id, user_id, job_id, status, saved_at, applied_at, updated_at)
                VALUES (?, ?, ?, ?, now(), CASE WHEN ? = 'SAVED' THEN NULL ELSE now() END, now())
                """, UUID.randomUUID(), owner, jobId, status, status);
    }

    private void job(long id, String title, String category, long companyId, long locationId, String description,
                     boolean active, int daysAgo) {
        jdbcTemplate.update("""
                INSERT INTO jobs (id, title, company_id, location_id, description, source, source_url, content_fingerprint,
                                  job_category, classification_confidence, classified_at, active, deactivated_at,
                                  first_seen_at, last_seen_at, posted_date)
                VALUES (?, ?, ?, ?, ?, 'itest', ?, ?, ?, 0.9, now(), ?, CASE WHEN ? THEN NULL ELSE now() END,
                        now() - make_interval(days => ?), now(), current_date - ?)
                """, id, title, companyId, locationId, description, "https://example.invalid/" + id, String.format("%064d", id),
                category, active, active, daysAgo, daysAgo);
    }
}
