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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * V4 category analytics against a real PostgreSQL.
 *
 * <p>Eight postings, with categories and evidence written directly, because classification
 * itself happens in the ETL module and is tested there. What is under test here is the
 * schema, the queries and the API on top of them.
 *
 * <pre>
 * id category            company  location  skills
 *  1 Backend Developer   Acme     Austin    Java, Spring Boot
 *  2 Backend Developer   Acme     Austin    Java, Kafka
 *  3 Backend Developer   Globex   Berlin    Java
 *  4 Data Engineer       Globex   Berlin    Python, SQL, Spark
 *  5 Data Engineer       Globex   Berlin    Python, Spark
 *  6 DevOps Engineer     Acme     (remote)  Kubernetes
 *  7 (unclassified)      Acme     Austin    Java
 *  8 Backend Developer   Acme     Austin    (no skills)
 * </pre>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class CategoryAnalyticsIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void seed() {
        jdbcTemplate.execute("TRUNCATE job_classification_signals, job_skills, jobs, skills, "
                + "companies, locations RESTART IDENTITY CASCADE");

        jdbcTemplate.update("INSERT INTO companies (id, name, industry) VALUES "
                + "(1, 'Acme Systems', 'Software'), (2, 'Globex Data', 'Data & Analytics')");
        jdbcTemplate.update("INSERT INTO locations (id, city, state, country) VALUES "
                + "(1, 'Austin', 'Texas', 'United States'), (2, 'Berlin', null, 'Germany')");
        jdbcTemplate.update("INSERT INTO skills (id, name, category) VALUES "
                + "(1, 'Java', 'LANGUAGE'), (2, 'Spring Boot', 'FRAMEWORK'), (3, 'Kafka', 'DATA'), "
                + "(4, 'Python', 'LANGUAGE'), (5, 'SQL', 'LANGUAGE'), (6, 'Spark', 'DATA'), "
                + "(7, 'Kubernetes', 'PLATFORM')");

        job(1, "Senior Backend Engineer", 1, 1, "Backend Developer", "85.0");
        job(2, "Backend Engineer", 1, 1, "Backend Developer", "70.0");
        job(3, "Java Developer", 2, 2, "Backend Developer", "60.0");
        job(4, "Data Engineer", 2, 2, "Data Engineer", "90.0");
        job(5, "Senior Data Engineer", 2, 2, "Data Engineer", "80.0");
        job(6, "Site Reliability Engineer", 1, null, "DevOps Engineer", "75.0");
        job(7, "Office Coordinator", 1, 1, null, null);
        job(8, "Backend Engineer II", 1, 1, "Backend Developer", "55.0");

        jdbcTemplate.update("INSERT INTO job_skills (job_id, skill_id) VALUES "
                + "(1,1),(1,2),(2,1),(2,3),(3,1),(4,4),(4,5),(4,6),(5,4),(5,6),(6,7),(7,1)");

        jdbcTemplate.update("INSERT INTO job_classification_signals "
                + "(job_id, signal_type, signal_value, weight) VALUES "
                + "(1, 'TITLE', 'backend engineer', 5.0), "
                + "(1, 'SKILL', 'Java', 2.0), "
                + "(1, 'SKILL', 'Spring Boot', 2.0), "
                + "(1, 'DESCRIPTION', 'rest apis', 1.0)");
    }

    // ------------------------------------------------------------ category distribution

    @Test
    @DisplayName("categories are ranked with counts and shares of the classified postings")
    void categoryDistribution() throws Exception {
        // Seven postings are classified: Backend 4, Data 2, DevOps 1. The eighth has no
        // category and is not counted, so the shares describe the classified corpus.
        mockMvc.perform(get("/api/analytics/job-categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].category").value("Backend Developer"))
                .andExpect(jsonPath("$[0].jobCount").value(4))
                .andExpect(jsonPath("$[0].percentageOfJobs").value(57.1))
                .andExpect(jsonPath("$[0].rank").value(1))
                .andExpect(jsonPath("$[1].category").value("Data Engineer"))
                .andExpect(jsonPath("$[1].jobCount").value(2))
                .andExpect(jsonPath("$[2].category").value("DevOps Engineer"))
                .andExpect(jsonPath("$[2].rank").value(3));
    }

    @Test
    @DisplayName("an unclassified posting is left out rather than counted as a category")
    void unclassifiedPostingsExcluded() throws Exception {
        // Job 7 has no category. Were it counted, the shares would be of 8 not 7.
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM jobs WHERE job_category IS NULL", Integer.class)).isEqualTo(1);

        mockMvc.perform(get("/api/analytics/job-categories"))
                .andExpect(jsonPath("$[*].category").value(
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("Other"))));
    }

    // ------------------------------------------------------------- per-category detail

    @Test
    @DisplayName("category skills are a share of that category, not of all postings")
    void skillsForCategory() throws Exception {
        // Java is on 3 of the 4 backend postings: 75% of backend, though only 50% overall.
        mockMvc.perform(get("/api/analytics/category/skills").param("category", "Backend Developer"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].skill").value("Java"))
                .andExpect(jsonPath("$[0].jobCount").value(3))
                .andExpect(jsonPath("$[0].percentageOfJobs").value(75.0))
                .andExpect(jsonPath("$[0].rank").value(1));
    }

    @Test
    @DisplayName("the data engineering category surfaces its own skills")
    void skillsForDataCategory() throws Exception {
        mockMvc.perform(get("/api/analytics/category/skills").param("category", "Data Engineer"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].skill").value(
                        org.hamcrest.Matchers.containsInAnyOrder("Python", "Spark", "SQL")))
                .andExpect(jsonPath("$[0].jobCount").value(2));
    }

    @Test
    @DisplayName("category locations are ranked, and remote postings belong to none")
    void locationsForCategory() throws Exception {
        mockMvc.perform(get("/api/analytics/category/locations").param("category", "Backend Developer"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].location.city").value("Austin"))
                .andExpect(jsonPath("$[0].jobCount").value(3))
                .andExpect(jsonPath("$[1].location.city").value("Berlin"));

        // The only DevOps posting is remote, so it has no location to be counted under.
        mockMvc.perform(get("/api/analytics/category/locations").param("category", "DevOps Engineer"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("category companies are ranked by how many postings they have in it")
    void companiesForCategory() throws Exception {
        mockMvc.perform(get("/api/analytics/category/companies").param("category", "Backend Developer"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].company.name").value("Acme Systems"))
                .andExpect(jsonPath("$[0].jobCount").value(3))
                .andExpect(jsonPath("$[0].percentageOfJobs").value(75.0))
                .andExpect(jsonPath("$[1].company.name").value("Globex Data"));
    }

    @Test
    @DisplayName("a category name containing a slash is addressable")
    void categoryNameWithSlashIsAddressable() throws Exception {
        // "QA / Automation Engineer" is a configured category name. While the name was a
        // path segment this request was a 400 from the servlet container, which rejects an
        // encoded slash before any handler sees it. As a query parameter the name is a
        // value like any other. MockMvc does not run that container check, so this test
        // pins the endpoint shape rather than reproducing the original failure.
        job(9, "QA Automation Engineer", 1, 1, "QA / Automation Engineer", "65.0");
        jdbcTemplate.update("INSERT INTO job_skills (job_id, skill_id) VALUES (9, 1)");

        mockMvc.perform(get("/api/analytics/category/skills")
                        .param("category", "QA / Automation Engineer"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].skill").value("Java"))
                .andExpect(jsonPath("$[0].jobCount").value(1));
    }

    @Test
    @DisplayName("an unknown category is a 404 rather than an empty list")
    void unknownCategoryIsNotFound() throws Exception {
        // An empty list would be indistinguishable from a real category with no postings.
        mockMvc.perform(get("/api/analytics/category/skills").param("category", "Astronaut"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
        mockMvc.perform(get("/api/analytics/category/locations").param("category", "Astronaut"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/analytics/category/companies").param("category", "Astronaut"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("the category limit is honoured and validated")
    void limitIsValidated() throws Exception {
        mockMvc.perform(get("/api/analytics/category/skills").param("category", "Backend Developer")
                        .param("limit", "1"))
                .andExpect(jsonPath("$.length()").value(1));
        mockMvc.perform(get("/api/analytics/category/skills").param("category", "Backend Developer")
                        .param("limit", "0"))
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------ job integration

    @Test
    @DisplayName("job search can be filtered by category, and paging still works")
    void filtersJobsByCategory() throws Exception {
        mockMvc.perform(get("/api/jobs").param("category", "Backend Developer"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(4));

        // Case-insensitive, like the other filters.
        mockMvc.perform(get("/api/jobs").param("category", "backend developer"))
                .andExpect(jsonPath("$.totalElements").value(4));

        mockMvc.perform(get("/api/jobs").param("category", "Data Engineer").param("size", "1"))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.content.length()").value(1));
    }

    @Test
    @DisplayName("the category filter combines with the existing filters")
    void categoryCombinesWithOtherFilters() throws Exception {
        mockMvc.perform(get("/api/jobs")
                        .param("category", "Backend Developer")
                        .param("company", "Globex"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].title").value("Java Developer"));

        mockMvc.perform(get("/api/jobs")
                        .param("category", "Backend Developer")
                        .param("skill", "Kafka"))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    @DisplayName("job listings carry their category")
    void listingsCarryCategory() throws Exception {
        mockMvc.perform(get("/api/jobs").param("category", "Data Engineer"))
                .andExpect(jsonPath("$.content[0].category").value("Data Engineer"));
    }

    @Test
    @DisplayName("job details explain the category with the signals behind it")
    void jobDetailExplainsCategory() throws Exception {
        mockMvc.perform(get("/api/jobs/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.classification.category").value("Backend Developer"))
                .andExpect(jsonPath("$.classification.confidence").value(85.0))
                .andExpect(jsonPath("$.classification.signals.length()").value(4))
                // Strongest evidence first, so the explanation leads with what mattered.
                .andExpect(jsonPath("$.classification.signals[0].type").value("TITLE"))
                .andExpect(jsonPath("$.classification.signals[0].value").value("backend engineer"))
                .andExpect(jsonPath("$.classification.signals[0].weight").value(5.0));
    }

    @Test
    @DisplayName("an unclassified job simply has no classification field")
    void unclassifiedJobHasNoClassification() throws Exception {
        mockMvc.perform(get("/api/jobs/7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.classification").doesNotExist());
    }

    @Test
    @DisplayName("existing V1 to V3 behaviour is unaffected")
    void existingBehaviourUnaffected() throws Exception {
        mockMvc.perform(get("/api/jobs"))
                .andExpect(jsonPath("$.totalElements").value(8));
        mockMvc.perform(get("/api/analytics/overview"))
                .andExpect(jsonPath("$.totalJobs").value(8))
                .andExpect(jsonPath("$.totalSkills").value(7));
        mockMvc.perform(get("/api/skills/top"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].skill").value("Java"));
    }

    private void job(long id, String title, long companyId, Integer locationId,
                     String category, String confidence) {
        jdbcTemplate.update("""
                        INSERT INTO jobs (id, title, company_id, location_id, description, source,
                                          source_url, content_fingerprint, job_category,
                                          classification_confidence, classified_at)
                        VALUES (?, ?, ?, ?, ?, 'itest', ?, ?, ?, ?::numeric,
                                CASE WHEN ?::text IS NULL THEN NULL ELSE now() END)
                        """,
                id, title, companyId, locationId, "Description for " + title,
                "https://example.invalid/jobs/" + id, String.format("%064d", id),
                category, confidence, category);
    }
}
