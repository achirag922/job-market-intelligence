package com.jmip.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * V7.9: how many SQL statements the main read endpoints issue, over a realistic data set
 * (60 jobs, 12 skills, a user with a resume, a goal and 10 saved jobs). Counts every statement
 * the pool hands out, JPA and JdbcTemplate alike. The ceilings guard against N+1 regressions;
 * the printed table is the before/after measurement.
 */
@SpringBootTest(properties = {"jmip.ai.provider=stub", "jmip.snapshot.refresh-on-startup=false"})
@AutoConfigureMockMvc
@Testcontainers
class QueryCountIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine");

    static final AtomicInteger STATEMENTS = new AtomicInteger();

    /** Wraps the application's DataSource so every prepared or plain statement is counted. */
    @TestConfiguration
    static class CountingDataSourceConfiguration {
        @Bean
        static BeanPostProcessor countingDataSource() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String name) {
                    return bean instanceof DataSource dataSource && !(bean instanceof CountingDataSource)
                            ? new CountingDataSource(dataSource) : bean;
                }
            };
        }
    }

    static class CountingDataSource extends DelegatingDataSource {
        CountingDataSource(DataSource target) {
            super(target);
        }

        @Override
        public Connection getConnection() throws SQLException {
            Connection connection = super.getConnection();
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                    (proxy, method, args) -> {
                        if (method.getName().startsWith("prepare") || method.getName().equals("createStatement")) {
                            STATEMENTS.incrementAndGet();
                        }
                        try {
                            return method.invoke(connection, args);
                        } catch (java.lang.reflect.InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
        }
    }

    private static final String ALICE = "alice@example.com";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID resumeId;
    private UUID goalId;

    @BeforeEach
    void seed() {
        jdbcTemplate.execute("TRUNCATE learning_resources, learning_items, interview_questions, interview_sessions, "
                + "saved_job_status_events, portfolios, career_goal_skill_progress, career_goal_skills, career_goals, saved_jobs, resume_skills, "
                + "resumes, job_skills, jobs, skills, companies, locations, users, skill_demand_snapshot RESTART IDENTITY CASCADE");
        UUID alice = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO users (id, full_name, email, password_hash, role, email_verified_at) "
                + "VALUES (?, 'Alice', ?, '{bcrypt}x', 'USER', now())", alice, ALICE);
        for (int i = 1; i <= 5; i++) {
            jdbcTemplate.update("INSERT INTO companies (id, name) VALUES (?, ?)", i, "Company " + i);
            jdbcTemplate.update("INSERT INTO locations (id, city, country) VALUES (?, ?, 'Germany')", i, "City " + i);
        }
        for (int i = 1; i <= 12; i++) {
            jdbcTemplate.update("INSERT INTO skills (id, name, category) VALUES (?, ?, 'OTHER')", i, "Skill " + i);
        }
        for (int id = 1; id <= 60; id++) {
            String category = id % 2 == 0 ? "Backend Developer" : "Data Engineer";
            jdbcTemplate.update("""
                    INSERT INTO jobs (id, title, company_id, location_id, description, source, source_url, content_fingerprint,
                                      experience_min, salary_min, salary_max, currency, posted_date,
                                      job_category, classification_confidence, classified_at)
                    VALUES (?, ?, ?, ?, 'posting', 'perf', ?, ?, ?, 50000, 70000, 'EUR', ?, ?, 0.9, now())
                    """, id, "Engineer " + id, id % 5 + 1, id % 5 + 1, "https://example.invalid/" + id,
                    String.format("%064d", id), id % 9, java.sql.Date.valueOf(java.time.LocalDate.of(2026, 9, 1)
                            .minusMonths(id % 6)), category);
            for (int s = 0; s < 3; s++) {
                jdbcTemplate.update("INSERT INTO job_skills (job_id, skill_id) VALUES (?, ?)", id, (id + s * 4) % 12 + 1);
            }
        }
        resumeId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO resumes (id, user_id, original_file_name, stored_file_name, content_type, file_size_bytes,
                                     processing_status, processed_at, title, is_default, updated_at)
                VALUES (?, ?, 'cv.pdf', ?, 'application/pdf', 100, 'COMPLETED', now(), 'Main CV', TRUE, now())
                """, resumeId, alice, resumeId + ".pdf");
        for (int s = 1; s <= 3; s++) {
            jdbcTemplate.update("INSERT INTO resume_skills (resume_id, skill_id) VALUES (?, ?)", resumeId, s);
        }
        goalId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO career_goals (id, user_id, target_role, target_category, status, created_at, updated_at) "
                + "VALUES (?, ?, 'Backend Engineer', 'Backend Developer', 'ACTIVE', now(), now())", goalId, alice);
        for (int job = 1; job <= 10; job++) {
            String state = new String[]{"SAVED", "APPLIED", "INTERVIEW", "REJECTED", "OFFER"}[job % 5];
            jdbcTemplate.update("INSERT INTO saved_jobs (id, user_id, job_id, status, saved_at, applied_at, updated_at) "
                            + "VALUES (?, ?, ?, ?, now(), CASE WHEN ? = 'SAVED' THEN NULL ELSE now() END, now())",
                    UUID.randomUUID(), alice, job, state, state);
        }
        // V9.9: the V8/V9 data the newer endpoints read: status history, 10 completed interviews with
        // 3 evaluated answers each, 8 learning items with 2 resources each, and a portfolio.
        jdbcTemplate.update("INSERT INTO saved_job_status_events (saved_job_id, user_id, status, changed_at) "
                + "SELECT id, user_id, status, now() - interval '3 days' FROM saved_jobs");
        for (int i = 0; i < 10; i++) {
            UUID session = UUID.randomUUID();
            jdbcTemplate.update("""
                    INSERT INTO interview_sessions (id, user_id, job_id, job_title, company_name, status, summary, average_score,
                                                    created_at, completed_at)
                    VALUES (?, ?, 1, 'Engineer 1', 'Company 2', 'COMPLETED', 'Done', 3.0, now() - make_interval(days => ?), now() - make_interval(days => ?))
                    """, session, alice, i + 1, i);
            for (int q = 1; q <= 3; q++) {
                jdbcTemplate.update("""
                        INSERT INTO interview_questions (session_id, position, category, question, answer, answered_at,
                                                         feedback_status, relevance, completeness, clarity, evaluated_at)
                        VALUES (?, ?, ?, 'Question', 'Answer', now(), 'EVALUATED', 3, 3, 3, now())
                        """, session, q, q == 3 ? "BEHAVIORAL" : "TECHNICAL");
            }
        }
        for (int i = 1; i <= 8; i++) {
            UUID item = UUID.randomUUID();
            jdbcTemplate.update("""
                    INSERT INTO learning_items (id, user_id, goal_id, skill_id, skill_name, topic, priority, status, progress,
                                                created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, 'Topic', 'MEDIUM', 'NOT_STARTED', 0, now(), now())
                    """, item, alice, goalId, i + 3, "Skill " + (i + 3));
            for (int r = 0; r < 2; r++) {
                jdbcTemplate.update("INSERT INTO learning_resources (id, item_id, user_id, title, url, type, created_at) "
                        + "VALUES (?, ?, ?, 'Docs', 'https://example.invalid/docs', 'DOCUMENTATION', now())", UUID.randomUUID(), item, alice);
            }
        }
        jdbcTemplate.update("INSERT INTO portfolios (user_id, slug, display_name, visibility, content, sections, created_at, updated_at, published_at) "
                + "VALUES (?, 'alice', 'Alice', 'PUBLIC', '{}'::jsonb, '{}'::jsonb, now(), now(), now())", alice);
    }

    @Test
    @DisplayName("statements per request stay bounded, whatever the page or list size")
    void statementCounts() throws Exception {
        Map<String, String> endpoints = new LinkedHashMap<>();
        endpoints.put("job search, page of 20", "/api/jobs?size=20");
        endpoints.put("job search, filtered", "/api/jobs?size=20&category=Backend Developer&skill=Skill 1");
        endpoints.put("resume list", "/api/resumes");
        endpoints.put("recommendations", "/api/resumes/" + resumeId + "/recommendations?limit=20");
        endpoints.put("saved jobs (10)", "/api/saved-jobs");
        endpoints.put("career goals", "/api/career-goals");
        endpoints.put("roadmap", "/api/career-goals/" + goalId + "/roadmap");
        endpoints.put("market skills", "/api/market/skills?category=Backend Developer");
        endpoints.put("dashboard", "/api/dashboard");
        endpoints.put("personalized feed", "/api/jobs/personalized?size=20");
        endpoints.put("applications", "/api/applications");
        endpoints.put("application insights", "/api/applications/insights");
        endpoints.put("interviews (10)", "/api/interviews");
        endpoints.put("learning plan (8)", "/api/learning");
        endpoints.put("my analytics, all", "/api/dashboard/analytics?range=ALL");
        endpoints.put("portfolio", "/api/portfolio");
        endpoints.put("public profile", "/api/public/profiles/alice");

        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Map.Entry<String, String> endpoint : endpoints.entrySet()) {
            mockMvc.perform(get(endpoint.getValue()).with(user(ALICE).roles("USER"))).andExpect(status().isOk()); // warm up
            STATEMENTS.set(0);
            long started = System.nanoTime();
            mockMvc.perform(get(endpoint.getValue()).with(user(ALICE).roles("USER"))).andExpect(status().isOk());
            long millis = (System.nanoTime() - started) / 1_000_000;
            counts.put(endpoint.getKey(), STATEMENTS.get());
            System.out.printf("QUERY-COUNT %-26s %3d statements %5d ms%n", endpoint.getKey(), STATEMENTS.get(), millis);
        }

        // Ceilings well above today's counts but far below one-per-row: an N+1 would break them.
        assertThat(counts.get("job search, page of 20")).isLessThanOrEqualTo(6);
        assertThat(counts.get("saved jobs (10)")).isLessThanOrEqualTo(6);
        assertThat(counts.get("recommendations")).isLessThanOrEqualTo(8);
        // 38 before V7.9 (a user lookup per service call, market window read per view), 25 after.
        assertThat(counts.get("dashboard")).isLessThanOrEqualTo(28);
        // V9.9: the V8/V9 endpoints. My analytics was 37 (a question query per completed interview,
        // the current resume matched twice) and is 24; none of these grows with the rows it reads.
        assertThat(counts.get("my analytics, all")).isLessThanOrEqualTo(28);
        assertThat(counts.get("interviews (10)")).isLessThanOrEqualTo(4);
        assertThat(counts.get("learning plan (8)")).isLessThanOrEqualTo(14);
        assertThat(counts.get("personalized feed")).isLessThanOrEqualTo(16);
        assertThat(counts.get("applications")).isLessThanOrEqualTo(9);
        assertThat(counts.get("application insights")).isLessThanOrEqualTo(10);
        assertThat(counts.get("portfolio")).isLessThanOrEqualTo(3);
        assertThat(counts.get("public profile")).isLessThanOrEqualTo(3);
    }
}
