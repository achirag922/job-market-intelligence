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
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * V7.7: the personal dashboard, end to end.
 *
 * <p>Alice: default resume with Java, an ACTIVE Backend Developer goal with Docker IN_PROGRESS,
 * saved jobs 1 (APPLIED, with a private note), 2 (SAVED), 4 (REJECTED). Bob: resume with
 * Python, job 3 saved as INTERVIEW, no goal. Carol: nothing.
 */
@SpringBootTest(properties = "jmip.ai.provider=stub")
@AutoConfigureMockMvc
@Testcontainers
class DashboardIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine");

    private static final String ALICE = "alice@example.com";
    private static final String BOB = "bob@example.com";
    private static final String CAROL = "carol@example.com";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID alicesGoal;

    @BeforeEach
    void seed() {
        jdbcTemplate.execute("TRUNCATE career_goal_skill_progress, career_goal_skills, career_goals, saved_jobs, resume_skills, "
                + "resumes, job_skills, jobs, skills, companies, locations, users RESTART IDENTITY CASCADE");
        UUID alice = account(ALICE);
        UUID bob = account(BOB);
        account(CAROL);
        jdbcTemplate.update("INSERT INTO companies (id, name) VALUES (1, 'Acme Systems')");
        jdbcTemplate.update("INSERT INTO locations (id, city, country) VALUES (1, 'Berlin', 'Germany')");
        jdbcTemplate.update("INSERT INTO skills (id, name, category) VALUES (1, 'Java', 'LANGUAGE'), (2, 'Docker', 'PLATFORM'), "
                + "(3, 'Kubernetes', 'PLATFORM'), (4, 'Python', 'LANGUAGE')");
        job(1, "Platform Engineer", "Backend Developer", "2026-08-10", "EUR");
        job(2, "Cloud Engineer", "Backend Developer", "2026-09-05", "EUR");
        job(3, "Java Developer", "Backend Developer", "2026-09-12", null);
        job(4, "Data Engineer", "Data Engineer", "2026-09-01", null);
        jdbcTemplate.update("INSERT INTO job_skills (job_id, skill_id) VALUES (1, 1), (1, 2), (2, 1), (2, 3), (3, 1), (4, 4)");

        resume(alice, 1L, "Main CV");
        resume(bob, 4L, "Bob CV");
        alicesGoal = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO career_goals (id, user_id, target_role, target_category, status, created_at, updated_at)
                VALUES (?, ?, 'Backend Engineer', 'Backend Developer', 'ACTIVE', now(), now())
                """, alicesGoal, alice);
        jdbcTemplate.update("INSERT INTO career_goal_skill_progress (goal_id, skill_id, status, updated_at) "
                + "VALUES (?, 2, 'IN_PROGRESS', now())", alicesGoal);
        saved(alice, 1, "APPLIED", "PRIVATE-NOTE salary expectations");
        saved(alice, 2, "SAVED", null);
        saved(alice, 4, "REJECTED", null);
        saved(bob, 3, "INTERVIEW", null);
    }

    @Test
    @DisplayName("the dashboard brings every section together from the user's own data")
    void fullDashboard() throws Exception {
        dashboard(ALICE)
                // Resume overview
                .andExpect(jsonPath("$.resume.available").value(true))
                .andExpect(jsonPath("$.resume.current.title").value("Main CV"))
                .andExpect(jsonPath("$.resume.current.skillCount").value(1))
                .andExpect(jsonPath("$.resume.resumeCount").value(1))
                .andExpect(jsonPath("$.resume.matchSummary.jobsCompared").value(3))
                .andExpect(jsonPath("$.resume.matchSummary.topMatchPercentage").value(100.0))
                .andExpect(jsonPath("$.resume.matchSummary.averageMatchPercentage").value(66.7))
                .andExpect(jsonPath("$.resume.missingSkillsForGoal", contains("Docker", "Kubernetes")))
                // Skill progress
                .andExpect(jsonPath("$.skills.currentSkills[*].name", contains("Java")))
                .andExpect(jsonPath("$.skills.inProgress", contains("Docker")))
                .andExpect(jsonPath("$.skills.completed", hasSize(0)))
                .andExpect(jsonPath("$.skills.notStarted").value(1))
                .andExpect(jsonPath("$.skills.roadmapPercentComplete").value(33.3))
                // Recommendations
                .andExpect(jsonPath("$.recommendations.count").value(3))
                .andExpect(jsonPath("$.recommendations.topJobs[0].title").value("Java Developer"))
                .andExpect(jsonPath("$.recommendations.averageMatchPercentage").value(66.7))
                // Applications
                .andExpect(jsonPath("$.applications.total").value(3))
                .andExpect(jsonPath("$.applications.saved").value(1))
                .andExpect(jsonPath("$.applications.applied").value(1))
                .andExpect(jsonPath("$.applications.interview").value(0))
                .andExpect(jsonPath("$.applications.rejected").value(1))
                .andExpect(jsonPath("$.applications.funnel[*].stage", contains("SAVED", "APPLIED", "INTERVIEW", "OFFER")))
                .andExpect(jsonPath("$.applications.funnel[*].jobs", contains(1, 1, 0, 0)))
                .andExpect(jsonPath("$.applications.recent", hasSize(3)))
                .andExpect(jsonPath("$.applications.recent[0].notes").doesNotExist())
                // Career goal
                .andExpect(jsonPath("$.careerGoal.targetRole").value("Backend Engineer"))
                .andExpect(jsonPath("$.careerGoal.activeGoals").value(1))
                .andExpect(jsonPath("$.careerGoal.progress.percentComplete").value(33.3))
                .andExpect(jsonPath("$.careerGoal.topMissingSkills[*].skill", contains("Docker", "Kubernetes")))
                .andExpect(jsonPath("$.careerGoal.topMissingSkills[0].status").value("IN_PROGRESS"))
                // Market, for the goal's category
                .andExpect(jsonPath("$.market.available").value(true))
                .andExpect(jsonPath("$.market.category").value("Backend Developer"))
                .andExpect(jsonPath("$.market.scope.postings").value(3))
                .andExpect(jsonPath("$.market.topSkills[0].skill").value("Java"))
                .andExpect(jsonPath("$.market.topLocations[0].location").value("Berlin, Germany"))
                .andExpect(jsonPath("$.market.workModes", hasSize(4)))
                .andExpect(jsonPath("$.market.salaries[0].currency").value("EUR"))
                .andExpect(jsonPath("$.market.salaries[0].reliable").value(false))
                .andExpect(jsonPath("$.market.topCompanies[0].company").value("Acme Systems"))
                .andExpect(jsonPath("$.market.skillTrend").doesNotExist())
                .andExpect(jsonPath("$.market.notes", hasItem(containsString("not per category"))));
    }

    @Test
    @DisplayName("each user sees only their own resume, goal and applications")
    void dataIsolation() throws Exception {
        dashboard(BOB)
                .andExpect(jsonPath("$.resume.current.title").value("Bob CV"))
                .andExpect(jsonPath("$.applications.total").value(1))
                .andExpect(jsonPath("$.applications.interview").value(1))
                .andExpect(jsonPath("$.applications.recent[*].title", contains("Java Developer")))
                .andExpect(jsonPath("$.careerGoal.available").value(false))
                .andExpect(jsonPath("$.recommendations.topJobs[*].title", contains("Data Engineer")));
        dashboard(ALICE)
                .andExpect(jsonPath("$.applications.recent[*].title", not(hasItem("Java Developer"))))
                .andExpect(jsonPath("$.applications.interview").value(0));

        // Another user's goal cannot be selected.
        mockMvc.perform(get("/api/dashboard").param("goalId", alicesGoal.toString()).with(user(BOB).roles("USER")))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/dashboard").param("goalId", alicesGoal.toString()).with(user(ALICE).roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.careerGoal.goalId").value(alicesGoal.toString()));
    }

    @Test
    @DisplayName("a user with nothing yet gets every section, each explaining what is missing")
    void emptyUser() throws Exception {
        dashboard(CAROL)
                .andExpect(jsonPath("$.resume.available").value(false))
                .andExpect(jsonPath("$.resume.current").doesNotExist())
                .andExpect(jsonPath("$.resume.note", containsString("Upload a resume")))
                .andExpect(jsonPath("$.skills.available").value(false))
                .andExpect(jsonPath("$.skills.currentSkills", hasSize(0)))
                .andExpect(jsonPath("$.recommendations.available").value(false))
                .andExpect(jsonPath("$.recommendations.count").value(0))
                .andExpect(jsonPath("$.applications.available").value(false))
                .andExpect(jsonPath("$.applications.total").value(0))
                .andExpect(jsonPath("$.applications.funnel[*].jobs", contains(0, 0, 0, 0)))
                .andExpect(jsonPath("$.applications.note", containsString("Save jobs")))
                .andExpect(jsonPath("$.careerGoal.available").value(false))
                .andExpect(jsonPath("$.careerGoal.note", containsString("Create a career goal")))
                // Without a goal the market section covers all postings, and says so.
                .andExpect(jsonPath("$.market.available").value(true))
                .andExpect(jsonPath("$.market.category").doesNotExist())
                .andExpect(jsonPath("$.market.scope.postings").value(4))
                .andExpect(jsonPath("$.market.notes", hasItem(containsString("all postings"))));
    }

    @Test
    @DisplayName("a resume without a goal still shows skills and matches; the goal parts say what is missing")
    void resumeWithoutGoal() throws Exception {
        dashboard(BOB)
                .andExpect(jsonPath("$.resume.available").value(true))
                .andExpect(jsonPath("$.resume.missingSkillsForGoal", hasSize(0)))
                .andExpect(jsonPath("$.resume.note", containsString("Create a career goal")))
                .andExpect(jsonPath("$.skills.available").value(true))
                .andExpect(jsonPath("$.skills.currentSkills[*].name", contains("Python")))
                .andExpect(jsonPath("$.skills.roadmapPercentComplete").doesNotExist());
    }

    @Test
    @DisplayName("when the market has no postings the section is marked unavailable instead of showing zeros as data")
    void marketUnavailable() throws Exception {
        jdbcTemplate.execute("TRUNCATE saved_jobs, job_skills, jobs CASCADE");

        dashboard(CAROL)
                .andExpect(jsonPath("$.market.available").value(false))
                .andExpect(jsonPath("$.market.topSkills", hasSize(0)))
                .andExpect(jsonPath("$.market.salaries", hasSize(0)))
                .andExpect(jsonPath("$.market.notes", hasItem(containsString("no postings"))));
        dashboard(ALICE)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.market.available").value(false))
                .andExpect(jsonPath("$.recommendations.count").value(0));
    }

    @Test
    @DisplayName("the dashboard needs a signed-in user")
    void requiresSignIn() throws Exception {
        mockMvc.perform(get("/api/dashboard")).andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------ helpers

    private ResultActions dashboard(String email) throws Exception {
        return mockMvc.perform(get("/api/dashboard").with(user(email).roles("USER"))).andExpect(status().isOk());
    }

    private UUID account(String email) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO users (id, full_name, email, password_hash, role, email_verified_at)
                VALUES (?, 'Test', ?, '{bcrypt}not-a-real-hash', 'USER', now())
                """, id, email);
        return id;
    }

    private void job(long id, String title, String category, String posted, String currency) {
        jdbcTemplate.update("""
                INSERT INTO jobs (id, title, company_id, location_id, description, source, source_url, content_fingerprint,
                                  salary_min, salary_max, currency, posted_date, job_category, classification_confidence, classified_at)
                VALUES (?, ?, 1, 1, 'posting', 'itest', ?, ?, ?, ?, ?, ?::date, ?, 0.9, now())
                """, id, title, "https://example.invalid/" + id, String.format("%064d", id),
                currency == null ? null : 50000, currency == null ? null : 70000, currency, posted, category);
    }

    private void resume(UUID owner, long skillId, String title) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO resumes (id, user_id, original_file_name, stored_file_name, content_type, file_size_bytes,
                                     processing_status, processed_at, title, is_default, updated_at)
                VALUES (?, ?, 'cv.pdf', ?, 'application/pdf', 100, 'COMPLETED', now(), ?, TRUE, now())
                """, id, owner, id + ".pdf", title);
        jdbcTemplate.update("INSERT INTO resume_skills (resume_id, skill_id) VALUES (?, ?)", id, skillId);
    }

    private void saved(UUID owner, long jobId, String status, String notes) {
        jdbcTemplate.update("""
                INSERT INTO saved_jobs (id, user_id, job_id, status, notes, saved_at, applied_at, updated_at)
                VALUES (?, ?, ?, ?, ?, now(), CASE WHEN ? = 'SAVED' THEN NULL ELSE now() END, now())
                """, UUID.randomUUID(), owner, jobId, status, notes, status);
    }
}
