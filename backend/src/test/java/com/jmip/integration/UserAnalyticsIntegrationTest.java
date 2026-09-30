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
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * V9.8: career analytics over time. Jobs: 1 asks Java and Docker, 2 Docker and Kubernetes, 3 Java.
 * Alice has two resume versions ("CV v1" 40 days ago with Java; "CV v2", current, 5 days ago with
 * Java and Docker), three saved jobs with status histories, two completed interviews, three
 * learning items and a published portfolio. Bob has nothing.
 */
@SpringBootTest(properties = {"jmip.ai.provider=stub", "jmip.alerts.enabled=false"})
@AutoConfigureMockMvc
@Testcontainers
class UserAnalyticsIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine");

    private static final String ALICE = "alice@example.test";
    private static final String BOB = "bob@example.test";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID alice;

    @BeforeEach
    void seed() {
        jdbcTemplate.execute("TRUNCATE portfolios, learning_resources, learning_items, interview_questions, interview_sessions, "
                + "saved_job_status_events, saved_jobs, resume_skills, resumes, job_skills, jobs, skills, companies, users "
                + "RESTART IDENTITY CASCADE");
        alice = account(ALICE);
        account(BOB);
        jdbcTemplate.update("INSERT INTO companies (id, name) VALUES (1, 'Acme')");
        jdbcTemplate.update("INSERT INTO skills (id, name, category) VALUES (1, 'Java', 'LANGUAGE'), (2, 'Docker', 'PLATFORM'), "
                + "(3, 'Kubernetes', 'PLATFORM')");
        for (int id = 1; id <= 3; id++) {
            jdbcTemplate.update("""
                    INSERT INTO jobs (id, title, company_id, description, source, source_url, content_fingerprint)
                    VALUES (?, ?, 1, 'posting', 'itest', ?, ?)
                    """, id, "Engineer " + id, "https://example.invalid/" + id, String.format("%064d", id));
        }
        jdbcTemplate.update("INSERT INTO job_skills (job_id, skill_id) VALUES (1, 1), (1, 2), (2, 2), (2, 3), (3, 1)");

        resume("CV v1", 40, false, 1L);
        resume("CV v2", 5, true, 1L, 2L);

        UUID s1 = saved(1, "INTERVIEW", 10);
        UUID s2 = saved(2, "APPLIED", 3);
        UUID s3 = saved(3, "OFFER", 60);
        event(s1, "SAVED", 10);
        event(s1, "APPLIED", 9);
        event(s1, "INTERVIEW", 2);
        event(s2, "SAVED", 3);
        event(s2, "APPLIED", 1);
        event(s3, "SAVED", 60);
        event(s3, "APPLIED", 59);
        event(s3, "INTERVIEW", 50);
        event(s3, "OFFER", 45);

        interview(20, new int[][]{{2, 2, 2}}, new String[]{"TECHNICAL"});
        interview(2, new int[][]{{4, 4, 4}, {3, 3, 3}}, new String[]{"TECHNICAL", "BEHAVIORAL"});

        jdbcTemplate.update("""
                INSERT INTO learning_items (id, user_id, skill_id, skill_name, topic, priority, status, progress,
                                            created_at, updated_at, started_at, completed_at)
                VALUES (?, ?, 3, 'Kubernetes', 'Basics', 'HIGH', 'COMPLETED', 100, now() - interval '9 days', now(),
                        now() - interval '8 days', now() - interval '4 days'),
                       (?, ?, 2, 'Docker', 'Compose', 'MEDIUM', 'IN_PROGRESS', 30, now() - interval '2 days', now(),
                        now() - interval '1 day', NULL),
                       (?, ?, NULL, 'Rust', 'Book', 'LOW', 'NOT_STARTED', 0, now() - interval '1 day', now(), NULL, NULL)
                """, UUID.randomUUID(), alice, UUID.randomUUID(), alice, UUID.randomUUID(), alice);
        jdbcTemplate.update("""
                INSERT INTO portfolios (user_id, slug, display_name, visibility, content, sections, created_at, updated_at, published_at)
                VALUES (?, 'alice', 'Alice', 'PUBLIC', '{}'::jsonb, '{}'::jsonb, now() - interval '3 days', now(), now())
                """, alice);
    }

    @Test
    @DisplayName("30 days: activity, funnel, current statuses, skill gaps, interviews, learning and factual insights")
    void thirtyDays() throws Exception {
        analytics(ALICE, "30D")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.range").value("30D"))
                .andExpect(jsonPath("$.bucket").value("DAY"))
                .andExpect(jsonPath("$.activity", hasSize(30)))
                .andExpect(jsonPath("$.kpis.jobsSaved").value(2))
                .andExpect(jsonPath("$.kpis.applications").value(2))
                .andExpect(jsonPath("$.kpis.interviews").value(1))
                .andExpect(jsonPath("$.kpis.offers").value(0))
                .andExpect(jsonPath("$.kpis.averageMatch").value(83.3))
                .andExpect(jsonPath("$.funnel.applied").value(2))
                .andExpect(jsonPath("$.funnel.interviewed").value(1))
                .andExpect(jsonPath("$.funnel.applyToInterviewRate").value(50.0))
                .andExpect(jsonPath("$.funnel.interviewToOfferRate").value(0.0))
                .andExpect(jsonPath("$.statusBreakdown.INTERVIEW").value(1))
                .andExpect(jsonPath("$.statusBreakdown.APPLIED").value(1))
                .andExpect(jsonPath("$.statusBreakdown.OFFER").value(0))
                .andExpect(jsonPath("$.resumeTrend", hasSize(1)))
                .andExpect(jsonPath("$.missingSkills[0].skill").value("Kubernetes"))
                .andExpect(jsonPath("$.missingSkills[0].jobs").value(1))
                .andExpect(jsonPath("$.interviews.completed").value(2))
                .andExpect(jsonPath("$.interviews.firstScore").value(2.0))
                .andExpect(jsonPath("$.interviews.latestScore").value(3.5))
                .andExpect(jsonPath("$.interviews.averageScore").value(2.8))
                .andExpect(jsonPath("$.interviews.sessions[1].technical").value(4.0))
                .andExpect(jsonPath("$.interviews.sessions[1].behavioral").value(3.0))
                .andExpect(jsonPath("$.interviews.sessions[0].behavioral").doesNotExist())
                .andExpect(jsonPath("$.learning.items").value(3))
                .andExpect(jsonPath("$.learning.completed").value(1))
                .andExpect(jsonPath("$.learning.inProgress").value(1))
                .andExpect(jsonPath("$.learning.notStarted").value(1))
                .andExpect(jsonPath("$.learning.startedInRange").value(2))
                .andExpect(jsonPath("$.learning.completedInRange").value(1))
                .andExpect(jsonPath("$.learning.completionRate").value(33.3))
                .andExpect(jsonPath("$.learning.targetSkills").doesNotExist())
                .andExpect(jsonPath("$.portfolio.exists").value(true))
                .andExpect(jsonPath("$.portfolio.visibility").value("PUBLIC"))
                .andExpect(jsonPath("$.insights", hasItem("You applied to 2 jobs in this period, up from 1 in the period before.")))
                .andExpect(jsonPath("$.insights", hasItem("1 of 2 applications started in this period reached an interview "
                        + "(50.0%), and 0 of those an offer (0.0%).")))
                .andExpect(jsonPath("$.insights", hasItem("Kubernetes is missing from your current resume in 1 of the 2 jobs "
                        + "you saved in this period.")))
                .andExpect(jsonPath("$.insights", hasItem("Your interview score went from 2.0 to 3.5 out of 5 across 2 "
                        + "completed interviews.")))
                .andExpect(jsonPath("$.insights", hasItem("1 of 3 learning items are completed (33.3%); 1 completed in this period.")))
                .andExpect(jsonPath("$.notes[0]", startsWith("Job search activity is measured")));
    }

    @Test
    @DisplayName("ranges filter every series: 7 days, 90 days with resume versions, and all time")
    void ranges() throws Exception {
        analytics(ALICE, "7D")
                .andExpect(jsonPath("$.activity", hasSize(7)))
                .andExpect(jsonPath("$.kpis.jobsSaved").value(1))
                .andExpect(jsonPath("$.kpis.applications").value(1))
                .andExpect(jsonPath("$.kpis.interviews").value(1))
                .andExpect(jsonPath("$.funnel.applied").value(1))
                .andExpect(jsonPath("$.funnel.applyToInterviewRate").value(0.0))
                .andExpect(jsonPath("$.funnel.interviewToOfferRate").doesNotExist())
                .andExpect(jsonPath("$.interviews.completed").value(1));

        analytics(ALICE, "90d")
                .andExpect(jsonPath("$.range").value("90D"))
                .andExpect(jsonPath("$.bucket").value("WEEK"))
                .andExpect(jsonPath("$.resumeTrend[*].title", contains("CV v1", "CV v2")))
                .andExpect(jsonPath("$.resumeTrend[0].averageMatch").value(50.0))
                .andExpect(jsonPath("$.resumeTrend[0].missingSkills").value(2))
                .andExpect(jsonPath("$.resumeTrend[1].averageMatch").value(83.3))
                .andExpect(jsonPath("$.resumeTrend[1].missingSkills").value(1))
                .andExpect(jsonPath("$.resumeTrend[1].skills").value(2))
                .andExpect(jsonPath("$.resumeTrend[1].jobsCompared").value(3))
                .andExpect(jsonPath("$.insights", hasItem("Average skill match with your saved jobs rose from 50.0% "
                        + "(“CV v1”) to 83.3% (“CV v2”).")));

        analytics(ALICE, "ALL")
                .andExpect(jsonPath("$.bucket").value("WEEK"))
                .andExpect(jsonPath("$.from").exists())
                .andExpect(jsonPath("$.kpis.offers").value(1))
                .andExpect(jsonPath("$.funnel.applied").value(3))
                .andExpect(jsonPath("$.funnel.interviewed").value(2))
                .andExpect(jsonPath("$.funnel.offers").value(1))
                .andExpect(jsonPath("$.funnel.applyToInterviewRate").value(66.7))
                .andExpect(jsonPath("$.funnel.interviewToOfferRate").value(50.0));

        analytics(ALICE, "2W").andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("analytics are the caller's own; without data every figure is empty, never invented")
    void ownershipAndEmptyData() throws Exception {
        analytics(BOB, "ALL")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.from").doesNotExist())
                .andExpect(jsonPath("$.activity", hasSize(0)))
                .andExpect(jsonPath("$.kpis.jobsSaved").value(0))
                .andExpect(jsonPath("$.kpis.averageMatch").doesNotExist())
                .andExpect(jsonPath("$.kpis.averageInterviewScore").doesNotExist())
                .andExpect(jsonPath("$.funnel.applied").value(0))
                .andExpect(jsonPath("$.funnel.applyToInterviewRate").doesNotExist())
                .andExpect(jsonPath("$.resumeTrend", hasSize(0)))
                .andExpect(jsonPath("$.missingSkills", hasSize(0)))
                .andExpect(jsonPath("$.interviews.completed").value(0))
                .andExpect(jsonPath("$.learning.items").value(0))
                .andExpect(jsonPath("$.learning.completionRate").doesNotExist())
                .andExpect(jsonPath("$.portfolio.exists").value(false))
                .andExpect(jsonPath("$.insights", hasSize(0)));
        analytics(BOB, "30D").andExpect(jsonPath("$.activity", hasSize(30)))
                .andExpect(jsonPath("$.activity[*].applied", org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is(0))));
        mockMvc.perform(get("/api/dashboard/analytics")).andExpect(status().isUnauthorized());
    }

    private ResultActions analytics(String email, String range) throws Exception {
        return mockMvc.perform(get("/api/dashboard/analytics").param("range", range).with(user(email).roles("USER")));
    }

    private void resume(String title, int daysAgo, boolean isDefault, Long... skills) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO resumes (id, user_id, original_file_name, stored_file_name, content_type, file_size_bytes,
                                     processing_status, uploaded_at, processed_at, title, is_default, updated_at)
                VALUES (?, ?, 'cv.pdf', ?, 'application/pdf', 100, 'COMPLETED', now() - make_interval(days => ?),
                        now() - make_interval(days => ?), ?, ?, now())
                """, id, alice, id + ".pdf", daysAgo, daysAgo, title, isDefault);
        for (Long skill : skills) {
            jdbcTemplate.update("INSERT INTO resume_skills (resume_id, skill_id) VALUES (?, ?)", id, skill);
        }
    }

    private UUID saved(long jobId, String status, int daysAgo) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO saved_jobs (id, user_id, job_id, status, saved_at, applied_at, updated_at)
                VALUES (?, ?, ?, ?, now() - make_interval(days => ?), CASE WHEN ? = 'SAVED' THEN NULL ELSE now() END, now())
                """, id, alice, jobId, status, daysAgo, status);
        return id;
    }

    private void event(UUID savedJob, String status, int daysAgo) {
        jdbcTemplate.update("INSERT INTO saved_job_status_events (saved_job_id, user_id, status, changed_at) "
                + "VALUES (?, ?, ?, now() - make_interval(days => ?))", savedJob, alice, status, daysAgo);
    }

    private void interview(int daysAgo, int[][] scores, String[] categories) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO interview_sessions (id, user_id, job_id, job_title, company_name, status, summary, created_at, completed_at)
                VALUES (?, ?, 1, 'Engineer 1', 'Acme', 'COMPLETED', 'Done', now() - make_interval(days => ?),
                        now() - make_interval(days => ?))
                """, id, alice, daysAgo, daysAgo);
        for (int i = 0; i < scores.length; i++) {
            jdbcTemplate.update("""
                    INSERT INTO interview_questions (session_id, position, category, question, answer, answered_at,
                                                     feedback_status, relevance, completeness, clarity, evaluated_at)
                    VALUES (?, ?, ?, 'Question', 'Answer', now(), 'EVALUATED', ?, ?, ?, now())
                    """, id, i + 1, categories[i], scores[i][0], scores[i][1], scores[i][2]);
        }
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
