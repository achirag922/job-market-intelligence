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
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * V7.5: market aggregates against five known postings, so every figure can be checked by hand.
 *
 * <pre>
 * id category           company location  exp  salary              posted      wording
 * 1  Backend Developer  Acme    Berlin    3    60000-80000 EUR     2026-08-10  hybrid
 * 2  Backend Developer  Acme    Berlin    1    50000-70000 EUR     2026-09-05  on-site
 * 3  Backend Developer  Beta    (none)    6    90000-120000 USD    2026-09-12  fully remote
 * 4  Data Engineer      Beta    Warsaw    -    40000-50000 EUR     2026-07-20  -
 * 5  Data Engineer      Beta    Warsaw    2    -                   (none)      -
 * </pre>
 */
@SpringBootTest(properties = "jmip.ai.provider=stub")
@AutoConfigureMockMvc
@Testcontainers
@WithMockUser
class MarketIntelligenceIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private SkillTrendRepository skillTrendRepository;

    @BeforeEach
    void seed() {
        jdbcTemplate.execute("TRUNCATE skill_demand_snapshot, job_skills, jobs, skills, companies, locations RESTART IDENTITY CASCADE");
        jdbcTemplate.update("INSERT INTO companies (id, name, industry) VALUES (1, 'Acme', 'Software'), (2, 'Beta', 'Retail')");
        jdbcTemplate.update("INSERT INTO locations (id, city, country) VALUES (1, 'Berlin', 'Germany'), (2, 'Warsaw', 'Poland')");
        jdbcTemplate.update("INSERT INTO skills (id, name, category) VALUES (1, 'Java', 'LANGUAGE'), (2, 'Python', 'LANGUAGE'), (3, 'Docker', 'PLATFORM')");
        job(1, "Backend Developer", 1, 1L, 3, 60000, 80000, "EUR", "2026-08-10", "Java role. This role is hybrid.");
        job(2, "Backend Developer", 1, 1L, 1, 50000, 70000, "EUR", "2026-09-05", "Java and Docker, on-site in Berlin.");
        job(3, "Backend Developer", 2, null, 6, 90000, 120000, "USD", "2026-09-12", "This role is fully remote.");
        job(4, "Data Engineer", 2, 2L, null, 40000, 50000, "EUR", "2026-07-20", "Python pipelines.");
        job(5, "Data Engineer", 2, 2L, 2, null, null, null, null, "Python work.");
        jdbcTemplate.update("INSERT INTO job_skills (job_id, skill_id) VALUES (1, 1), (2, 1), (2, 3), (3, 1), (4, 2), (5, 2)");
    }

    @Test
    @DisplayName("salary: per currency and never pooled, with sample size, reliability, categories and months")
    void salary() throws Exception {
        mockMvc.perform(get("/api/market/salary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope.postings").value(5))
                .andExpect(jsonPath("$.scope.latestPostingInData").value("2026-09-12"))
                .andExpect(jsonPath("$.postingsWithSalary").value(4))
                .andExpect(jsonPath("$.byCurrency[0].currency").value("EUR"))
                .andExpect(jsonPath("$.byCurrency[0].postings").value(3))
                .andExpect(jsonPath("$.byCurrency[0].averageMin").value(50000))
                .andExpect(jsonPath("$.byCurrency[0].averageMax").value(66667))
                .andExpect(jsonPath("$.byCurrency[0].lowestMin").value(40000.0))
                .andExpect(jsonPath("$.byCurrency[0].highestMax").value(80000.0))
                .andExpect(jsonPath("$.byCurrency[0].reliable").value(true))
                .andExpect(jsonPath("$.byCurrency[1].currency").value("USD"))
                .andExpect(jsonPath("$.byCurrency[1].reliable").value(false))
                .andExpect(jsonPath("$.byCategory", hasSize(3)))
                .andExpect(jsonPath("$.trend[*].month", contains("2026-07-01", "2026-08-01", "2026-09-01", "2026-09-01")))
                .andExpect(jsonPath("$.notes", hasItem(containsString("never converted or combined"))))
                .andExpect(jsonPath("$.notes", hasItem(containsString("1 posting(s) have no posting date"))));

        mockMvc.perform(get("/api/market/salary").param("category", "Backend Developer"))
                .andExpect(jsonPath("$.byCurrency[0].postings").value(2))
                .andExpect(jsonPath("$.byCurrency[0].averageMin").value(55000))
                .andExpect(jsonPath("$.byCurrency[0].reliable").value(false));
    }

    @Test
    @DisplayName("locations: demand by location, stated vs not stated, and the location filter")
    void locations() throws Exception {
        mockMvc.perform(get("/api/market/locations"))
                .andExpect(jsonPath("$.locationStated").value(4))
                .andExpect(jsonPath("$.locationNotStated").value(1))
                .andExpect(jsonPath("$.topLocations[*].location", contains("Berlin, Germany", "Warsaw, Poland")))
                .andExpect(jsonPath("$.topLocations[0].percentageOfPostings").value(40.0));

        mockMvc.perform(get("/api/market/locations").param("location", "pol"))
                .andExpect(jsonPath("$.scope.postings").value(2))
                .andExpect(jsonPath("$.topLocations[*].location", contains("Warsaw, Poland")))
                .andExpect(jsonPath("$.topLocations[0].percentageOfPostings").value(100.0));
        mockMvc.perform(get("/api/market/locations").param("category", "Backend Developer"))
                .andExpect(jsonPath("$.topLocations[*].location", contains("Berlin, Germany")))
                .andExpect(jsonPath("$.locationNotStated").value(1));
    }

    @Test
    @DisplayName("remote work: modes read from the wording, shares, and a month series with quiet months as zero")
    void remote() throws Exception {
        mockMvc.perform(get("/api/market/remote"))
                .andExpect(jsonPath("$.distribution[*].mode", contains("REMOTE", "HYBRID", "ON_SITE", "NOT_STATED")))
                .andExpect(jsonPath("$.distribution[*].postings", contains(1, 1, 1, 2)))
                .andExpect(jsonPath("$.distribution[3].percentageOfPostings").value(40.0))
                .andExpect(jsonPath("$.trend", hasSize(3)))
                .andExpect(jsonPath("$.trend[0].month").value("2026-07-01"))
                .andExpect(jsonPath("$.trend[0].notStated").value(1))
                .andExpect(jsonPath("$.trend[1].hybrid").value(1))
                .andExpect(jsonPath("$.trend[2].total").value(2))
                .andExpect(jsonPath("$.trend[2].remote").value(1))
                .andExpect(jsonPath("$.trend[2].onSite").value(1))
                .andExpect(jsonPath("$.method", containsString("description")));
    }

    @Test
    @DisplayName("companies: top hiring companies and a per-month series for each")
    void companies() throws Exception {
        mockMvc.perform(get("/api/market/companies"))
                .andExpect(jsonPath("$.topCompanies[*].company", contains("Beta", "Acme")))
                .andExpect(jsonPath("$.topCompanies[0].postings").value(3))
                .andExpect(jsonPath("$.topCompanies[0].percentageOfPostings").value(60.0))
                .andExpect(jsonPath("$.trend[0].company").value("Beta"))
                .andExpect(jsonPath("$.trend[0].points[*].postings", contains(1, 0, 1)))
                .andExpect(jsonPath("$.trend[1].points[*].postings", contains(0, 1, 1)))
                .andExpect(jsonPath("$.notes", hasItem(containsString("not a company's total hiring"))));

        mockMvc.perform(get("/api/market/companies").param("category", "Data Engineer"))
                .andExpect(jsonPath("$.topCompanies[*].company", contains("Beta")))
                .andExpect(jsonPath("$.topCompanies[0].percentageOfPostings").value(100.0));
    }

    @Test
    @DisplayName("skills: current demand, and the trend from the stored skill history only when unfiltered")
    void skills() throws Exception {
        skillTrendRepository.rebuild();

        mockMvc.perform(get("/api/market/skills"))
                .andExpect(jsonPath("$.topSkills[*].skill", contains("Java", "Python", "Docker")))
                .andExpect(jsonPath("$.topSkills[0].percentageOfPostings").value(60.0))
                .andExpect(jsonPath("$.topSkills[0].rank").value(1))
                .andExpect(jsonPath("$.trend.window.periods").value(3))
                .andExpect(jsonPath("$.trend.window.toPeriod").value("2026-09-01"))
                .andExpect(jsonPath("$.trend.trends[*].skill", contains("Java")));

        mockMvc.perform(get("/api/market/skills").param("category", "Data Engineer"))
                .andExpect(jsonPath("$.topSkills[*].skill", contains("Python")))
                .andExpect(jsonPath("$.trend").doesNotExist())
                .andExpect(jsonPath("$.notes", hasItem(containsString("Clear the category"))));
    }

    @Test
    @DisplayName("experience and period filters narrow every view, and the period ends at the newest posting")
    void filters() throws Exception {
        mockMvc.perform(get("/api/market/companies").param("experience", "2-5"))
                .andExpect(jsonPath("$.scope.postings").value(2))
                .andExpect(jsonPath("$.scope.experience").value("2–5 years"));
        mockMvc.perform(get("/api/market/salary").param("experience", "unspecified"))
                .andExpect(jsonPath("$.scope.postings").value(1))
                .andExpect(jsonPath("$.byCurrency[0].averageMin").value(40000));

        mockMvc.perform(get("/api/market/remote").param("months", "1"))
                .andExpect(jsonPath("$.scope.from").value(LocalDate.of(2026, 9, 1).toString()))
                .andExpect(jsonPath("$.scope.postings").value(2))
                .andExpect(jsonPath("$.trend", hasSize(1)))
                .andExpect(jsonPath("$.notes", hasItem(containsString("not enough dated history"))));
    }

    @Test
    @DisplayName("an empty selection returns zeros and says so, never invented figures")
    void emptySelection() throws Exception {
        mockMvc.perform(get("/api/market/salary").param("category", "Astronaut"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope.postings").value(0))
                .andExpect(jsonPath("$.postingsWithSalary").value(0))
                .andExpect(jsonPath("$.byCurrency", hasSize(0)))
                .andExpect(jsonPath("$.trend", hasSize(0)))
                .andExpect(jsonPath("$.notes", hasItem(containsString("No posting in this selection states a salary"))));
        mockMvc.perform(get("/api/market/remote").param("category", "Astronaut"))
                .andExpect(jsonPath("$.distribution[*].percentageOfPostings", contains(0.0, 0.0, 0.0, 0.0)))
                .andExpect(jsonPath("$.trend", hasSize(0)));

        jdbcTemplate.execute("TRUNCATE job_skills, jobs CASCADE");
        mockMvc.perform(get("/api/market/skills"))
                .andExpect(jsonPath("$.topSkills", hasSize(0)))
                .andExpect(jsonPath("$.notes", hasItem(containsString("No postings match"))));
        mockMvc.perform(get("/api/market/companies").param("months", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope.from").doesNotExist());
    }

    @Test
    @DisplayName("invalid filters are refused")
    void invalidFilters() throws Exception {
        mockMvc.perform(get("/api/market/salary").param("experience", "10+")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/market/skills").param("months", "0")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/market/locations").param("months", "37")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/market/companies").param("location", "x".repeat(201))).andExpect(status().isBadRequest());
    }

    @Test
    @WithAnonymousUser
    @DisplayName("market views need a signed-in user")
    void requiresSignIn() throws Exception {
        for (String path : new String[]{"salary", "locations", "remote", "companies", "skills"}) {
            mockMvc.perform(get("/api/market/" + path)).andExpect(status().isUnauthorized());
        }
    }

    private void job(long id, String category, long companyId, Long locationId, Integer experienceMin, Integer salaryMin,
                     Integer salaryMax, String currency, String posted, String description) {
        jdbcTemplate.update("""
                INSERT INTO jobs (id, title, company_id, location_id, description, source, source_url, content_fingerprint,
                                  experience_min, salary_min, salary_max, currency, posted_date,
                                  job_category, classification_confidence, classified_at)
                VALUES (?, 'Engineer', ?, ?, ?, 'itest', ?, ?, ?, ?, ?, ?, ?::date, ?, 0.9, now())
                """, id, companyId, locationId, description, "https://example.invalid/" + id, String.format("%064d", id),
                experienceMin, salaryMin, salaryMax, currency, posted, category);
    }
}
