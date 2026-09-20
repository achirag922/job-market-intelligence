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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exercises every endpoint against a real PostgreSQL, through the whole stack: controller,
 * service, repository, Flyway-migrated schema.
 *
 * <p>The fixture is four postings chosen so that each expected number can be checked by
 * hand: two companies, two locations, three skills, and one remote posting with no
 * location at all.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ApiIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void seed() {
        jdbcTemplate.execute("TRUNCATE job_skills, jobs, skills, companies, locations RESTART IDENTITY CASCADE");

        jdbcTemplate.update("INSERT INTO companies (id, name, industry, website) VALUES "
                + "(1, 'Acme Systems', 'Software', 'https://acme.example.invalid'), "
                + "(2, 'Globex Data', 'Data & Analytics', null)");
        jdbcTemplate.update("INSERT INTO locations (id, city, state, country) VALUES "
                + "(1, 'Austin', 'Texas', 'United States'), (2, 'Berlin', null, 'Germany')");
        jdbcTemplate.update("INSERT INTO skills (id, name, category) VALUES "
                + "(1, 'Java', 'LANGUAGE'), (2, 'Python', 'LANGUAGE'), (3, 'SQL', 'LANGUAGE')");

        insertJob(1, "Senior Backend Engineer", 1, 1, "FULL_TIME", 5, 9,
                "150000", "190000", "USD", "2026-08-13");
        insertJob(2, "Data Engineer", 2, 2, "FULL_TIME", 3, 6,
                "70000", "95000", "EUR", "2026-07-02");
        insertJob(3, "Remote Site Reliability Engineer", 1, null, "CONTRACT", 4, null,
                null, null, null, "2026-09-01");
        insertJob(4, "Junior Analyst", 2, 2, "PART_TIME", 1, 2,
                null, null, null, "2026-06-15");

        jdbcTemplate.update("INSERT INTO job_skills (job_id, skill_id) VALUES "
                + "(1, 1), (1, 3), (2, 2), (2, 3), (3, 1)");
    }

    @Test
    @DisplayName("the browser frontend origin is allowed through CORS")
    void allowsConfiguredOrigin() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .options("/api/analytics/overview")
                        .header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
    }

    @Test
    @DisplayName("an origin that is not configured is refused")
    void refusesUnknownOrigin() throws Exception {
        // Exact-origin matching, not a wildcard: any other site must not be able to read
        // this API through a visitor's browser.
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .options("/api/analytics/overview")
                        .header("Origin", "http://evil.example.invalid")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("an unmapped path is 404, not 500")
    void unmappedPathIsNotFound() throws Exception {
        // The catch-all handler must not swallow Spring's own routing failures.
        mockMvc.perform(get("/"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));

        mockMvc.perform(get("/api/nope"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    @DisplayName("an unsupported method is 405, not 500")
    void unsupportedMethodIsMethodNotAllowed() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/jobs"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.status").value(405));
    }

    @Test
    @DisplayName("GET /api/jobs returns newest first with the paging envelope")
    void listsJobsNewestFirst() throws Exception {
        mockMvc.perform(get("/api/jobs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(4))
                .andExpect(jsonPath("$.content.length()").value(4))
                .andExpect(jsonPath("$.content[0].title").value("Remote Site Reliability Engineer"))
                .andExpect(jsonPath("$.content[3].title").value("Junior Analyst"))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.first").value(true))
                .andExpect(jsonPath("$.last").value(true));
    }

    @Test
    @DisplayName("undated postings sort last, not first, on the default newest-first order")
    void undatedPostingsSortLast() {
        jdbcTemplate.update("UPDATE jobs SET posted_date = null WHERE id = 4");

        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() ->
                mockMvc.perform(get("/api/jobs"))
                        .andExpect(status().isOk())
                        // PostgreSQL would put the undated row first on a plain DESC sort.
                        .andExpect(jsonPath("$.content[0].title").value("Remote Site Reliability Engineer"))
                        .andExpect(jsonPath("$.content[3].title").value("Junior Analyst"))
                        .andExpect(jsonPath("$.content[3].postedDate").doesNotExist())
                        .andExpect(jsonPath("$.totalElements").value(4)));
    }

    @Test
    @DisplayName("GET /api/jobs paginates")
    void paginatesJobs() throws Exception {
        mockMvc.perform(get("/api/jobs").param("page", "0").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.last").value(false));

        mockMvc.perform(get("/api/jobs").param("page", "1").param("size", "2"))
                .andExpect(jsonPath("$.first").value(false))
                .andExpect(jsonPath("$.last").value(true));
    }

    @Test
    @DisplayName("GET /api/jobs sorts by an allowed field and rejects any other")
    void sortsAndRejectsUnknownSort() throws Exception {
        mockMvc.perform(get("/api/jobs").param("sort", "title,asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].title").value("Data Engineer"));

        mockMvc.perform(get("/api/jobs").param("sort", "description,asc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Cannot sort by")));
    }

    @Test
    @DisplayName("GET /api/jobs filters by title, company, location, skill and employment type")
    void filtersJobs() throws Exception {
        mockMvc.perform(get("/api/jobs").param("title", "engineer"))
                .andExpect(jsonPath("$.totalElements").value(3));

        mockMvc.perform(get("/api/jobs").param("company", "globex"))
                .andExpect(jsonPath("$.totalElements").value(2));

        mockMvc.perform(get("/api/jobs").param("location", "Germany"))
                .andExpect(jsonPath("$.totalElements").value(2));

        mockMvc.perform(get("/api/jobs").param("skill", "java"))
                .andExpect(jsonPath("$.totalElements").value(2));

        mockMvc.perform(get("/api/jobs").param("employmentType", "part_time"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].title").value("Junior Analyst"));
    }

    @Test
    @DisplayName("GET /api/jobs combines filters with AND")
    void combinesFilters() throws Exception {
        mockMvc.perform(get("/api/jobs").param("skill", "SQL").param("company", "Acme"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].title").value("Senior Backend Engineer"));
    }

    @Test
    @DisplayName("a skill filter does not duplicate jobs that have several skills")
    void skillFilterDoesNotDuplicateRows() throws Exception {
        // Job 1 has two skills. Without DISTINCT the join would return it twice.
        mockMvc.perform(get("/api/jobs").param("title", "Senior Backend"))
                .andExpect(jsonPath("$.totalElements").value(1));
        mockMvc.perform(get("/api/jobs").param("skill", "SQL"))
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    @DisplayName("a remote posting is returned with no location rather than a fake one")
    void remoteJobHasNoLocation() throws Exception {
        mockMvc.perform(get("/api/jobs/3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Remote Site Reliability Engineer"))
                .andExpect(jsonPath("$.location").doesNotExist())
                .andExpect(jsonPath("$.salary").doesNotExist())
                .andExpect(jsonPath("$.experience.min").value(4))
                .andExpect(jsonPath("$.experience.max").doesNotExist());
    }

    @Test
    @DisplayName("GET /api/jobs/{id} returns the full record, and 404 when absent")
    void returnsJobDetail() throws Exception {
        mockMvc.perform(get("/api/jobs/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Senior Backend Engineer"))
                .andExpect(jsonPath("$.company.name").value("Acme Systems"))
                .andExpect(jsonPath("$.location.displayName").value("Austin, Texas, United States"))
                .andExpect(jsonPath("$.salary.currency").value("USD"))
                .andExpect(jsonPath("$.skills.length()").value(2));

        mockMvc.perform(get("/api/jobs/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    @DisplayName("GET /api/skills lists and filters skills")
    void listsSkills() throws Exception {
        mockMvc.perform(get("/api/skills"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.content[0].name").value("Java"));

        mockMvc.perform(get("/api/skills").param("name", "py"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].name").value("Python"));
    }

    @Test
    @DisplayName("GET /api/skills/top ranks by demand and honours the limit")
    void returnsTopSkills() throws Exception {
        // Java and SQL are on two postings each, Python on one.
        mockMvc.perform(get("/api/skills/top"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].skill").value("Java"))
                .andExpect(jsonPath("$[0].jobCount").value(2))
                .andExpect(jsonPath("$[0].percentageOfJobs").value(50.0))
                .andExpect(jsonPath("$[2].skill").value("Python"))
                .andExpect(jsonPath("$[2].percentageOfJobs").value(25.0));

        mockMvc.perform(get("/api/skills/top").param("limit", "2"))
                .andExpect(jsonPath("$.length()").value(2));

        mockMvc.perform(get("/api/skills/top").param("limit", "0"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /api/companies lists, filters and returns detail with a job count")
    void listsCompanies() throws Exception {
        mockMvc.perform(get("/api/companies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].name").value("Acme Systems"));

        mockMvc.perform(get("/api/companies").param("name", "globex"))
                .andExpect(jsonPath("$.totalElements").value(1));

        mockMvc.perform(get("/api/companies/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Acme Systems"))
                .andExpect(jsonPath("$.jobCount").value(2));

        mockMvc.perform(get("/api/companies/999"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /api/locations lists and filters locations")
    void listsLocations() throws Exception {
        mockMvc.perform(get("/api/locations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].country").value("Germany"))
                .andExpect(jsonPath("$.content[0].displayName").value("Berlin, Germany"));

        mockMvc.perform(get("/api/locations").param("country", "united"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].city").value("Austin"));
    }

    @Test
    @DisplayName("GET /api/analytics/overview returns the headline counts")
    void returnsOverview() throws Exception {
        mockMvc.perform(get("/api/analytics/overview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalJobs").value(4))
                .andExpect(jsonPath("$.totalCompanies").value(2))
                .andExpect(jsonPath("$.totalSkills").value(3))
                .andExpect(jsonPath("$.totalLocations").value(2));
    }

    @Test
    @DisplayName("GET /api/analytics/skills ranks skills with their share of postings")
    void returnsSkillAnalytics() throws Exception {
        // The rows are nested under "skills" alongside the scope they were measured over.
        mockMvc.perform(get("/api/analytics/skills"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope.totalJobsInScope").value(4))
                .andExpect(jsonPath("$.skills.totalElements").value(3))
                .andExpect(jsonPath("$.skills.content[0].skill").value("Java"))
                .andExpect(jsonPath("$.skills.content[0].jobCount").value(2))
                .andExpect(jsonPath("$.skills.content[0].percentageOfJobs").value(50.0))
                .andExpect(jsonPath("$.skills.content[0].rank").value(1))
                .andExpect(jsonPath("$.skills.content[1].skill").value("SQL"));
    }

    @Test
    @DisplayName("GET /api/analytics/locations ranks locations and excludes remote postings")
    void returnsLocationAnalytics() throws Exception {
        mockMvc.perform(get("/api/analytics/locations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].location.country").value("Germany"))
                .andExpect(jsonPath("$.content[0].jobCount").value(2))
                .andExpect(jsonPath("$.content[1].location.city").value("Austin"))
                .andExpect(jsonPath("$.content[1].jobCount").value(1));
    }

    @Test
    @DisplayName("GET /api/analytics/companies ranks companies by posting count")
    void returnsCompanyAnalytics() throws Exception {
        mockMvc.perform(get("/api/analytics/companies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].company.name").value("Acme Systems"))
                .andExpect(jsonPath("$.content[0].jobCount").value(2))
                .andExpect(jsonPath("$.content[1].jobCount").value(2));
    }

    private void insertJob(long id, String title, long companyId, Integer locationId, String employmentType,
                           Integer experienceMin, Integer experienceMax, String salaryMin, String salaryMax,
                           String currency, String postedDate) {
        jdbcTemplate.update("""
                        INSERT INTO jobs (id, title, company_id, location_id, description, employment_type,
                                          experience_min, experience_max, salary_min, salary_max, currency,
                                          posted_date, source, source_url, content_fingerprint)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::numeric, ?::numeric, ?, ?::date, 'itest', ?, ?)
                        """,
                id, title, companyId, locationId, "Description for " + title, employmentType,
                experienceMin, experienceMax, salaryMin, salaryMax, currency, postedDate,
                "https://example.invalid/jobs/" + id, String.format("%064d", id));
    }
}
