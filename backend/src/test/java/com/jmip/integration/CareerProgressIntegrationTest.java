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
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * V9.13: career progress from existing data. Alice has preferences, an active Backend Developer goal,
 * a resume with Java and Docker, two learning items (one done), two completed practice interviews
 * (3.0 today and 4.5 a week ago), two saved jobs (one applied) and a published three-section portfolio.
 * Bob has nothing yet.
 */
@SpringBootTest(properties = {"jmip.ai.provider=stub", "jmip.alerts.enabled=false"})
@AutoConfigureMockMvc
@Testcontainers
class CareerProgressIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine");

    private static final String ALICE = "alice@example.test";
    private static final String BOB = "bob@example.test";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID goal;

    @BeforeEach
    void seed() {
        jdbcTemplate.execute("TRUNCATE portfolios, learning_resources, learning_items, interview_questions, interview_sessions, "
                + "saved_job_status_events, saved_jobs, career_goal_skill_progress, career_goal_skills, career_goals, "
                + "match_preferences, resume_skills, resumes, job_skills, jobs, skills, companies, users RESTART IDENTITY CASCADE");
        UUID alice = account(ALICE);
        account(BOB);
        jdbcTemplate.update("INSERT INTO companies (id, name) VALUES (1, 'Acme')");
        jdbcTemplate.update("INSERT INTO skills (id, name, category) VALUES (1, 'Java', 'LANGUAGE'), (2, 'Docker', 'PLATFORM'), "
                + "(3, 'Kubernetes', 'PLATFORM')");
        for (int id = 1; id <= 3; id++) {
            jdbcTemplate.update("""
                    INSERT INTO jobs (id, title, company_id, description, source, source_url, content_fingerprint,
                                      job_category, classification_confidence, classified_at)
                    VALUES (?, 'Backend Engineer', 1, 'posting', 'itest', ?, ?, 'Backend Developer', 0.9, now())
                    """, id, "https://example.invalid/" + id, String.format("%064d", id));
        }
        jdbcTemplate.update("INSERT INTO job_skills (job_id, skill_id) VALUES (1, 1), (1, 2), (2, 2), (2, 3), (3, 2)");

        mockMvc_preferences(alice);
        goal = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO career_goals (id, user_id, target_role, target_category, status, created_at, updated_at)
                VALUES (?, ?, 'Backend Engineer', 'Backend Developer', 'ACTIVE', now() - interval '20 days', now())
                """, goal, alice);
        UUID resume = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO resumes (id, user_id, original_file_name, stored_file_name, content_type, file_size_bytes,
                                     processing_status, processed_at, title, is_default, updated_at)
                VALUES (?, ?, 'cv.pdf', ?, 'application/pdf', 100, 'COMPLETED', now() - interval '30 days', 'CV', TRUE, now())
                """, resume, alice, resume + ".pdf");
        jdbcTemplate.update("INSERT INTO resume_skills (resume_id, skill_id) VALUES (?, 1), (?, 2)", resume, resume);
        jdbcTemplate.update("""
                INSERT INTO learning_items (id, user_id, skill_id, skill_name, topic, priority, status, progress,
                                            created_at, updated_at, started_at, completed_at)
                VALUES (?, ?, 3, 'Kubernetes', 'Basics', 'HIGH', 'COMPLETED', 100, now() - interval '10 days', now(),
                        now() - interval '9 days', now() - interval '2 days'),
                       (?, ?, NULL, 'Go', 'Tour', 'LOW', 'IN_PROGRESS', 40, now() - interval '3 days', now(),
                        now() - interval '3 days', NULL)
                """, UUID.randomUUID(), alice, UUID.randomUUID(), alice);
        practice(alice, 0, "3.0");
        practice(alice, 7, "4.5");
        UUID s1 = saved(alice, 1, "APPLIED", 4);
        UUID s2 = saved(alice, 2, "SAVED", 2);
        event(alice, s1, "SAVED", 4);
        event(alice, s1, "APPLIED", 3);
        event(alice, s2, "SAVED", 2);
        jdbcTemplate.update("""
                INSERT INTO portfolios (user_id, slug, display_name, visibility, content, sections, created_at, updated_at, published_at)
                VALUES (?, 'alice', 'Alice', 'PUBLIC',
                        '{"about": "I build services.", "skills": ["Java"], "experience": [{"title": "Engineer", "company": "Acme", "current": true}]}'::jsonb,
                        '{}'::jsonb, now() - interval '5 days', now(), now() - interval '1 day')
                """, alice);
    }

    @Test
    @DisplayName("the readiness score is the sum of transparent, capped components computed from existing data")
    void readinessFromExistingData() throws Exception {
        String body = progress(ALICE)
                .andExpect(jsonPath("$.readiness.components", hasSize(7)))
                .andExpect(jsonPath("$.readiness.components[?(@.key == 'profile')].points", contains(15)))
                .andExpect(jsonPath("$.readiness.components[?(@.key == 'resume')].points", contains(11)))
                .andExpect(jsonPath("$.readiness.components[?(@.key == 'learning')].points", contains(8)))
                .andExpect(jsonPath("$.readiness.components[?(@.key == 'interviews')].points", contains(11)))
                .andExpect(jsonPath("$.readiness.components[?(@.key == 'jobSearch')].points", contains(5)))
                .andExpect(jsonPath("$.readiness.components[?(@.key == 'portfolio')].points", contains(10)))
                .andExpect(jsonPath("$.readiness.components[?(@.key == 'skills')].detail", contains(org.hamcrest.Matchers.containsString("roadmap skills covered"))))
                .andExpect(jsonPath("$.progress.target.role").value("Backend Engineer"))
                .andExpect(jsonPath("$.progress.target.nextSkills", hasItem("Kubernetes")))
                .andExpect(jsonPath("$.progress.learning.completed").value(1))
                .andExpect(jsonPath("$.progress.learning.inProgress").value(1))
                .andExpect(jsonPath("$.progress.learning.averageProgress").value(70.0))
                .andExpect(jsonPath("$.progress.interviews.completed").value(2))
                .andExpect(jsonPath("$.progress.interviews.averageScore").value(3.8))
                .andExpect(jsonPath("$.progress.applications.saved").value(2))
                .andExpect(jsonPath("$.progress.applications.applied").value(1))
                .andReturn().getResponse().getContentAsString();
        List<Integer> points = JsonPath.read(body, "$.readiness.components[*].points");
        int score = JsonPath.read(body, "$.readiness.score");
        assertThat(score).isEqualTo(points.stream().mapToInt(Integer::intValue).sum()).isBetween(60, 85);
        assertThat(body).doesNotContain(goal.toString(), ALICE, "\"id\"", "userId");
    }

    @Test
    @DisplayName("achievements are one-time, dated where the data records it, and the next milestones say how to earn them")
    void achievementsAndStreaks() throws Exception {
        progress(ALICE)
                .andExpect(jsonPath("$.achievements[?(@.key == 'career-goal')].achieved", contains(true)))
                .andExpect(jsonPath("$.achievements[?(@.key == 'profile')].achieved", contains(true)))
                .andExpect(jsonPath("$.achievements[?(@.key == 'resume')].achievedOn").exists())
                .andExpect(jsonPath("$.achievements[?(@.key == 'first-saved-job')].achieved", contains(true)))
                .andExpect(jsonPath("$.achievements[?(@.key == 'first-application')].achieved", contains(true)))
                .andExpect(jsonPath("$.achievements[?(@.key == 'first-practice')].achieved", contains(true)))
                .andExpect(jsonPath("$.achievements[?(@.key == 'strong-practice')].achieved", contains(true)))
                .andExpect(jsonPath("$.achievements[?(@.key == 'first-skill')].achieved", contains(true)))
                .andExpect(jsonPath("$.achievements[?(@.key == 'portfolio-published')].achieved", contains(true)))
                .andExpect(jsonPath("$.achievements[?(@.key == 'learning-five')].achieved", contains(false)))
                .andExpect(jsonPath("$.achievements[?(@.key == 'learning-five')].progress", contains("1 of 5 completed")))
                .andExpect(jsonPath("$.achievements[?(@.key == 'first-offer')].achievedOn").isEmpty())
                .andExpect(jsonPath("$.nextMilestones[0].key").value("first-interview"))
                .andExpect(jsonPath("$.nextMilestones[1].key").value("first-offer"))
                .andExpect(jsonPath("$.nextMilestones[2].key").value("learning-five"))
                // Practice today and a week ago: two consecutive weeks, this one included.
                .andExpect(jsonPath("$.streaks[?(@.key == 'interviews')].currentWeeks", contains(2)))
                .andExpect(jsonPath("$.streaks[?(@.key == 'interviews')].activeThisWeek", contains(true)))
                .andExpect(jsonPath("$.streaks[?(@.key == 'jobSearch')].lastActiveOn").exists());
    }

    @Test
    @DisplayName("an account with no data yet scores zero, earns nothing and is shown where to start; progress is the caller's own")
    void emptyDataAndOwnership() throws Exception {
        progress(BOB)
                .andExpect(jsonPath("$.readiness.score").value(0))
                .andExpect(jsonPath("$.readiness.level").value("Getting started"))
                .andExpect(jsonPath("$.readiness.components[*].points", everyItem(is(0))))
                .andExpect(jsonPath("$.readiness.components[*].hint", hasSize(7)))
                .andExpect(jsonPath("$.progress.target").doesNotExist())
                .andExpect(jsonPath("$.progress.applications.saved").value(0))
                .andExpect(jsonPath("$.progress.interviews.averageScore").doesNotExist())
                .andExpect(jsonPath("$.achievements[*].achieved", everyItem(is(false))))
                .andExpect(jsonPath("$.nextMilestones[*].key", contains("career-goal", "profile", "resume")))
                .andExpect(jsonPath("$.streaks[*].currentWeeks", everyItem(is(0))));
        mockMvc.perform(get("/api/career-progress")).andExpect(status().isUnauthorized());
        // The existing roadmap is unaffected by being read for progress.
        mockMvc.perform(get("/api/career-goals/" + goal + "/roadmap").with(user(ALICE).roles("USER"))).andExpect(status().isOk());
        mockMvc.perform(get("/api/career-goals/" + goal + "/roadmap").with(user(BOB).roles("USER"))).andExpect(status().isNotFound());
    }

    private ResultActions progress(String email) throws Exception {
        return mockMvc.perform(get("/api/career-progress").with(user(email).roles("USER"))).andExpect(status().isOk());
    }

    private void mockMvc_preferences(UUID user) {
        jdbcTemplate.update("INSERT INTO match_preferences (user_id, years_experience, work_mode, preferred_skills, updated_at) "
                + "VALUES (?, 4, 'REMOTE', ARRAY['Java'], now())", user);
    }

    private void practice(UUID user, int daysAgo, String score) {
        jdbcTemplate.update("""
                INSERT INTO interview_sessions (id, user_id, job_id, job_title, company_name, status, summary, average_score,
                                                created_at, completed_at)
                VALUES (?, ?, 1, 'Backend Engineer', 'Acme', 'COMPLETED', 'Done', ?::numeric, now() - make_interval(days => ?),
                        now() - make_interval(days => ?))
                """, UUID.randomUUID(), user, score, daysAgo, daysAgo);
    }

    private UUID saved(UUID user, long job, String status, int daysAgo) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO saved_jobs (id, user_id, job_id, status, saved_at, applied_at, updated_at)
                VALUES (?, ?, ?, ?, now() - make_interval(days => ?), CASE WHEN ? = 'SAVED' THEN NULL ELSE now() END, now())
                """, id, user, job, status, daysAgo, status);
        return id;
    }

    private void event(UUID user, UUID savedJob, String status, int daysAgo) {
        jdbcTemplate.update("INSERT INTO saved_job_status_events (saved_job_id, user_id, status, changed_at) "
                + "VALUES (?, ?, ?, now() - make_interval(days => ?))", savedJob, user, status, daysAgo);
    }

    private UUID account(String email) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO users (id, full_name, email, password_hash, role, email_verified_at)
                VALUES (?, 'Test', ?, '{bcrypt}not-a-real-hash', 'USER', now())
                """, id, email);
        return id;
    }
}
