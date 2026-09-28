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
        jdbcTemplate.execute("TRUNCATE career_goal_skill_progress, career_goal_skills, career_goals, saved_jobs, resume_skills, "
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
    }
}
