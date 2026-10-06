package com.jmip.integration;

import com.jmip.repository.SkillTrendRepository;
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

import java.time.LocalDate;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * V8.8: market trends from a known history. Postings exist in January to March and May to
 * July 2026 (no April at all). Backend Developer: 2, 3, 4, then 6, 7, 8 a month, in Berlin; the
 * earlier ones on-site with Java and Kotlin at 50-60k EUR, the later ones remote with Java and
 * Docker at 60-70k EUR. Data Analyst: 2 a month, hybrid, in Paris, with SQL and no salary.
 */
@SpringBootTest(properties = {"jmip.ai.provider=stub", "jmip.alerts.enabled=false"})
@AutoConfigureMockMvc
@Testcontainers
class MarketTrendsIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine");

    private static final String USER = "reader@example.test";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private SkillTrendRepository skillTrendRepository;

    private long nextId = 1;

    @BeforeEach
    void seed() {
        jdbcTemplate.execute("TRUNCATE skill_demand_snapshot, job_skills, jobs, skills, companies, locations RESTART IDENTITY CASCADE");
        jdbcTemplate.update("INSERT INTO companies (id, name) VALUES (1, 'Acme Systems')");
        jdbcTemplate.update("INSERT INTO locations (id, city, country) VALUES (1, 'Berlin', 'Germany'), (2, 'Paris', 'France')");
        jdbcTemplate.update("INSERT INTO skills (id, name, category) VALUES (1, 'Java', 'LANGUAGE'), (2, 'Kotlin', 'LANGUAGE'), "
                + "(3, 'Docker', 'PLATFORM'), (4, 'SQL', 'LANGUAGE')");
        int[] months = {1, 2, 3, 5, 6, 7};
        int[] backend = {2, 3, 4, 6, 7, 8};
        for (int i = 0; i < months.length; i++) {
            boolean recent = months[i] >= 5;
            for (int n = 0; n < backend[i]; n++) {
                long id = job("Backend Developer", 1, recent ? "Build services, fully remote." : "Build services on-site.",
                        months[i], recent ? "60000" : "50000", recent ? "70000" : "60000");
                skills(id, 1, recent ? 3 : 2);
            }
            for (int n = 0; n < 2; n++) {
                skills(job("Data Analyst", 2, "Analyse data in a hybrid team.", months[i], null, null), 4);
            }
        }
        skillTrendRepository.rebuild();
    }

    @Test
    @DisplayName("a role's demand, share, skills, salary, locations and work modes, each with the periods compared")
    void roleTrends() throws Exception {
        mockMvc.perform(get("/api/market/trends").param("category", "Backend Developer").param("months", "12")
                        .with(user(USER).roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.period.fromMonth").value("2025-08-01"))
                .andExpect(jsonPath("$.period.toMonth").value("2026-07-01"))
                .andExpect(jsonPath("$.period.coveredMonths").value(6))
                .andExpect(jsonPath("$.period.monthsWithoutData", hasSize(6)))
                .andExpect(jsonPath("$.period.latestPostedDate").exists())
                .andExpect(jsonPath("$.volume", hasSize(12)))
                // April has no postings at all: a gap, not a zero.
                .andExpect(jsonPath("$.volume[8].month").value("2026-04-01"))
                .andExpect(jsonPath("$.volume[8].postings").doesNotExist())
                .andExpect(jsonPath("$.volume[5].postings").value(2))
                .andExpect(jsonPath("$.volume[5].allPostings").value(4))
                .andExpect(jsonPath("$.volume[5].sharePercentage").value(50.0))
                .andExpect(jsonPath("$.volumeTrend.direction").value("INCREASING"))
                .andExpect(jsonPath("$.volumeTrend.change").value(133.3))
                .andExpect(jsonPath("$.volumeTrend.earlierFrom").value("2026-01-01"))
                .andExpect(jsonPath("$.volumeTrend.earlierTo").value("2026-03-01"))
                .andExpect(jsonPath("$.volumeTrend.recentFrom").value("2026-05-01"))
                .andExpect(jsonPath("$.volumeTrend.recentTo").value("2026-07-01"))
                .andExpect(jsonPath("$.shareTrend.change").value(17.8))
                .andExpect(jsonPath("$.shareTrend.unit").value("PERCENTAGE_POINTS"))
                .andExpect(jsonPath("$.skills.source").value("Postings in this role, by posting month"))
                .andExpect(jsonPath("$.skills.growing[*].skill", contains("Docker")))
                .andExpect(jsonPath("$.skills.declining[*].skill", contains("Kotlin")))
                .andExpect(jsonPath("$.salary.currency").value("EUR"))
                .andExpect(jsonPath("$.salary.trend.change").value(18.2))
                .andExpect(jsonPath("$.salary.series", hasSize(6)))
                .andExpect(jsonPath("$.locations[0].location").value("Berlin, Germany"))
                .andExpect(jsonPath("$.locations[0].trend.direction").value("INCREASING"))
                .andExpect(jsonPath("$.workModeTrends[0].mode").value("REMOTE"))
                .andExpect(jsonPath("$.workModeTrends[0].trend.change").value(100.0))
                .andExpect(jsonPath("$.workModeTrends[2].mode").value("ON_SITE"))
                .andExpect(jsonPath("$.workModeTrends[2].trend.direction").value("DECREASING"))
                .andExpect(jsonPath("$.forecast.status").value("ESTIMATE"))
                .andExpect(jsonPath("$.forecast.slopePerMonth").value(1.0))
                .andExpect(jsonPath("$.forecast.rSquared").value(1.0))
                .andExpect(jsonPath("$.forecast.estimates[*].estimatedPostings", contains(9, 10, 11)))
                .andExpect(jsonPath("$.forecast.estimates[0].month").value("2026-08-01"))
                .andExpect(jsonPath("$.forecast.label").value(org.hamcrest.Matchers.startsWith("Estimate, not a prediction")));
    }

    @Test
    @DisplayName("a steady role is stable; all roles use the stored skill history and have no share trend")
    void stableAndAllRoles() throws Exception {
        mockMvc.perform(get("/api/market/trends").param("category", "Data Analyst").with(user(USER).roles("USER")))
                .andExpect(jsonPath("$.volumeTrend.direction").value("STABLE"))
                .andExpect(jsonPath("$.volumeTrend.change").value(0.0))
                .andExpect(jsonPath("$.salary").doesNotExist())
                .andExpect(jsonPath("$.forecast.slopePerMonth").value(0.0))
                .andExpect(jsonPath("$.forecast.estimates[*].estimatedPostings", contains(2, 2, 2)));

        mockMvc.perform(get("/api/market/trends").with(user(USER).roles("USER")))
                .andExpect(jsonPath("$.category").doesNotExist())
                .andExpect(jsonPath("$.shareTrend").doesNotExist())
                .andExpect(jsonPath("$.volumeTrend.change").value(80.0))
                .andExpect(jsonPath("$.skills.source").value("Stored monthly skill history (all postings)"))
                .andExpect(jsonPath("$.skills.growing[*].skill", contains("Docker", "Java")))
                .andExpect(jsonPath("$.skills.declining[*].skill", contains("Kotlin", "SQL")));
    }

    @Test
    @DisplayName("too little history or an empty role gives Insufficient data rather than a number")
    void insufficientData() throws Exception {
        mockMvc.perform(get("/api/market/trends").param("category", "Backend Developer").param("months", "2")
                        .with(user(USER).roles("USER")))
                .andExpect(jsonPath("$.period.coveredMonths").value(2))
                .andExpect(jsonPath("$.volumeTrend.change").value(14.3))
                .andExpect(jsonPath("$.forecast.status").value("INSUFFICIENT_DATA"))
                .andExpect(jsonPath("$.forecast.note").value(org.hamcrest.Matchers.containsString("at least 6 months")))
                .andExpect(jsonPath("$.forecast.estimates", hasSize(0)));

        mockMvc.perform(get("/api/market/trends").param("category", "QA Engineer").with(user(USER).roles("USER")))
                .andExpect(jsonPath("$.volumeTrend.direction").value("INSUFFICIENT_DATA"))
                .andExpect(jsonPath("$.shareTrend.direction").value("INSUFFICIENT_DATA"))
                .andExpect(jsonPath("$.forecast.status").value("INSUFFICIENT_DATA"))
                .andExpect(jsonPath("$.skills.growing", hasSize(0)))
                .andExpect(jsonPath("$.salary").doesNotExist());
    }

    @Test
    @DisplayName("the period is validated and the endpoint needs a signed-in user")
    void validationAndAuthorization() throws Exception {
        mockMvc.perform(get("/api/market/trends").param("months", "1").with(user(USER).roles("USER")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/market/trends").param("months", "40").with(user(USER).roles("USER")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/market/trends")).andExpect(status().isUnauthorized());
    }

    private long job(String category, long locationId, String description, int month, String minSalary, String maxSalary) {
        long id = nextId++;
        jdbcTemplate.update("""
                INSERT INTO jobs (id, title, company_id, location_id, description, source, source_url, content_fingerprint,
                                  posted_date, job_category, classification_confidence, classified_at,
                                  salary_min, salary_max, currency)
                VALUES (?, ?, 1, ?, ?, 'itest', ?, ?, ?, ?, 0.9, now(), ?::numeric, ?::numeric, ?)
                """, id, category, locationId, description, "https://example.invalid/" + id, String.format("%064d", id),
                LocalDate.of(2026, month, 10), category, minSalary, maxSalary, minSalary == null ? null : "EUR");
        return id;
    }

    private void skills(long jobId, long... skillIds) {
        for (long skillId : skillIds) {
            jdbcTemplate.update("INSERT INTO job_skills (job_id, skill_id) VALUES (?, ?)", jobId, skillId);
        }
    }
}
