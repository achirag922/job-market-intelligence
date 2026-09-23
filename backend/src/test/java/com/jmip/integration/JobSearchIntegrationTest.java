package com.jmip.integration;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import jakarta.persistence.EntityManagerFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * V6.2 job search against a real PostgreSQL.
 *
 * <p>Real database, not mocks, because the parts worth testing here are the parts a mock
 * would fake: how PostgreSQL orders nulls, whether a LIKE wildcard in the input is
 * escaped, whether a left join keeps remote postings in a text search.
 *
 * <pre>
 * id title                        company  location  type       exp    salary          currency posted     skills
 *  1 Senior Java Backend Engineer Acme     Bengaluru FULL_TIME  5–9    1,800,000–2,400,000 INR  2026-08-10 Java, Spring Boot
 *  2 Java Developer               Acme     Bengaluru FULL_TIME  2–4    900,000–1,200,000   INR  2026-07-01 Java, SQL
 *  3 Data Engineer                Globex   Berlin    FULL_TIME  3–6    70,000–95,000       EUR  2026-06-15 Python, SQL
 *  4 Backend Engineer             Globex   Austin    CONTRACT   2–5    150,000–190,000     USD  2026-09-01 Java, Spring Boot
 *  5 Remote Platform Engineer     Initech  (none)    FULL_TIME  8–     (none)                   (none)     Kubernetes
 *  6 Junior Analyst               Initech  Berlin    PART_TIME  (none) 40,000 (min only)   EUR  2026-05-20 SQL
 *  7 Growth 50% Marketer          Acme     Austin    FULL_TIME  1–2    (none)                   2026-04-01 (none)
 * </pre>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
class JobSearchIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @BeforeEach
    void seed() {
        jdbcTemplate.execute("TRUNCATE job_classification_signals, job_skills, jobs, skills, "
                + "companies, locations RESTART IDENTITY CASCADE");

        jdbcTemplate.update("INSERT INTO companies (id, name, industry) VALUES "
                + "(1, 'Acme Systems', 'Software'), (2, 'Globex Data', 'Data'), "
                + "(3, 'Initech', 'Software')");
        jdbcTemplate.update("INSERT INTO locations (id, city, state, country) VALUES "
                + "(1, 'Bengaluru', 'Karnataka', 'India'), (2, 'Berlin', null, 'Germany'), "
                + "(3, 'Austin', 'Texas', 'United States')");
        jdbcTemplate.update("INSERT INTO skills (id, name, category) VALUES "
                + "(1, 'Java', 'LANGUAGE'), (2, 'Spring Boot', 'FRAMEWORK'), (3, 'SQL', 'LANGUAGE'), "
                + "(4, 'Python', 'LANGUAGE'), (5, 'Kubernetes', 'PLATFORM')");

        job(1, "Senior Java Backend Engineer", 1, 1, "FULL_TIME", 5, 9, "1800000", "2400000", "INR",
                "2026-08-10", "Backend Developer", "Own our payments platform.");
        job(2, "Java Developer", 1, 1, "FULL_TIME", 2, 4, "900000", "1200000", "INR",
                "2026-07-01", "Backend Developer", "Maintain internal services.");
        job(3, "Data Engineer", 2, 2, "FULL_TIME", 3, 6, "70000", "95000", "EUR",
                "2026-06-15", "Data Engineer", "Build pipelines with Spark.");
        job(4, "Backend Engineer", 2, 3, "CONTRACT", 2, 5, "150000", "190000", "USD",
                "2026-09-01", "Backend Developer", "Scale the API layer.");
        job(5, "Remote Platform Engineer", 3, null, "FULL_TIME", 8, null, null, null, null,
                null, "DevOps Engineer", "Run Kubernetes clusters, fully remote.");
        job(6, "Junior Analyst", 3, 2, "PART_TIME", null, null, "40000", null, "EUR",
                "2026-05-20", "Data Analyst", "Reporting in SQL.");
        job(7, "Growth 50% Marketer", 1, 3, "FULL_TIME", 1, 2, null, null, null,
                "2026-04-01", null, "Campaigns.");

        jdbcTemplate.update("INSERT INTO job_skills (job_id, skill_id) VALUES "
                + "(1,1),(1,2),(2,1),(2,3),(3,4),(3,3),(4,1),(4,2),(5,5),(6,3)");
    }

    // ------------------------------------------------------------------ text search

    @Test
    @DisplayName("a multi-word search requires every word, each matching any field")
    void multiWordSearchMatchesAcrossFields() throws Exception {
        // "java" is in two titles and one skill; "spring" only ever appears as a skill.
        // Both words must be somewhere, so job 2 (Java, no Spring) drops out.
        search("q=java spring")
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[*].id", containsInAnyOrder(1, 4)));
    }

    @Test
    @DisplayName("search is case-insensitive and tolerates messy whitespace")
    void searchNormalisesInput() throws Exception {
        search("q=  JAVA    Developer  ")
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(2));
    }

    @Test
    @DisplayName("search reads company, location and description as well as title")
    void searchCoversEveryField() throws Exception {
        search("q=globex").andExpect(jsonPath("$.totalElements").value(2));
        search("q=karnataka").andExpect(jsonPath("$.totalElements").value(2));
        search("q=pipelines").andExpect(jsonPath("$.content[0].id").value(3));
    }

    @Test
    @DisplayName("a text search keeps postings that have no location")
    void searchKeepsUnlocatedPostings() throws Exception {
        // An inner join to location would silently drop job 5 from every search.
        search("q=kubernetes")
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(5));
    }

    @Test
    @DisplayName("a % in the search means a percent sign, not 'match anything'")
    void searchEscapesWildcards() throws Exception {
        // Unescaped, "50%" is the pattern "%50%%" — and "%" alone would match every row.
        search("q=50%").andExpect(jsonPath("$.content[*].id", contains(7)));
        search("q=%").andExpect(jsonPath("$.totalElements").value(1));
    }

    // -------------------------------------------------------------------- filters

    @Test
    @DisplayName("experience filters on the same half-open bands as the distribution")
    void experienceUsesTheSharedBands() throws Exception {
        // 2–5 means a minimum of 2, 3 or 4. Job 1 asks for 5, so it is in 5–8, not here.
        search("experience=2-5")
                .andExpect(jsonPath("$.content[*].id", containsInAnyOrder(2, 3, 4)));
        search("experience=8+").andExpect(jsonPath("$.content[*].id", contains(5)));
        search("experience=unspecified").andExpect(jsonPath("$.content[*].id", contains(6)));
    }

    @Test
    @DisplayName("a salary range overlaps posting ranges, within one currency")
    void salaryOverlapsWithinCurrency() throws Exception {
        // Looking for at least 1,000,000 INR: job 1 (1.8–2.4m) clearly, and job 2
        // (0.9–1.2m) too, because the top of its band reaches the floor.
        search("currency=INR&salaryMin=1000000")
                .andExpect(jsonPath("$.content[*].id", containsInAnyOrder(1, 2)));
        // A ceiling of 1,000,000 keeps only postings whose band starts beneath it.
        search("currency=INR&salaryMax=1000000")
                .andExpect(jsonPath("$.content[*].id", contains(2)));
    }

    @Test
    @DisplayName("a posting stating only a minimum salary is not treated as zero")
    void salaryMinimumOnlyIsTakenAtItsFigure() throws Exception {
        // Job 6 states "40,000" with no maximum. It must match "at least 30,000", and must
        // not match "at least 50,000" — its only known figure is below that.
        search("currency=EUR&salaryMin=30000")
                .andExpect(jsonPath("$.content[*].id", containsInAnyOrder(3, 6)));
        search("currency=EUR&salaryMin=50000")
                .andExpect(jsonPath("$.content[*].id", contains(3)));
    }

    @Test
    @DisplayName("postings without a salary never satisfy a salary filter")
    void missingSalaryIsNotZero() throws Exception {
        // A ceiling of 1 would match every "zero" salary if missing meant zero.
        search("currency=USD&salaryMax=1").andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    @DisplayName("a salary bound without a currency is refused, not guessed")
    void salaryWithoutCurrencyIsRejected() throws Exception {
        search("salaryMin=100000")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("needs a currency")));
    }

    @Test
    @DisplayName("an inverted salary range is refused")
    void invertedSalaryRangeIsRejected() throws Exception {
        search("currency=USD&salaryMin=200000&salaryMax=100000").andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("location presence filters on whether a place is named — not on 'remote'")
    void locationPresence() throws Exception {
        search("locationStated=false").andExpect(jsonPath("$.content[*].id", contains(5)));
        search("locationStated=true").andExpect(jsonPath("$.totalElements").value(6));
    }

    @Test
    @DisplayName("every filter combines with AND")
    void filtersCombine() throws Exception {
        search("category=Backend Developer&skill=Java&location=Bengaluru&experience=2-5")
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(2));
    }

    @Test
    @DisplayName("a combination nothing satisfies is an empty page, not an error")
    void emptyResult() throws Exception {
        search("category=Data Engineer&location=Austin")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.content.length()").value(0));
    }

    @Test
    @DisplayName("an unknown experience band is a 400")
    void invalidExperienceIsRejected() throws Exception {
        search("experience=3-7").andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------- ordering

    @Test
    @DisplayName("newest first puts undated postings last, not first")
    void newestPutsUndatedLast() throws Exception {
        // PostgreSQL sorts nulls first on DESC; job 5 has no date and must not lead.
        search("order=newest")
                .andExpect(jsonPath("$.content[0].id").value(4))
                .andExpect(jsonPath("$.content[6].id").value(5));
    }

    @Test
    @DisplayName("oldest first still puts undated postings last")
    void oldestPutsUndatedLast() throws Exception {
        search("order=oldest")
                .andExpect(jsonPath("$.content[0].id").value(7))
                .andExpect(jsonPath("$.content[6].id").value(5));
    }

    @Test
    @DisplayName("salary high-to-low ranks within a currency")
    void salaryHighToLow() throws Exception {
        search("currency=INR&order=salary-high")
                .andExpect(jsonPath("$.content[*].id", contains(1, 2)));
        search("currency=INR&order=salary-low")
                .andExpect(jsonPath("$.content[*].id", contains(2, 1)));
    }

    @Test
    @DisplayName("salary ordering without a currency is refused")
    void salaryOrderNeedsCurrency() throws Exception {
        // 2,400,000 INR is not more than 190,000 USD, and a sort cannot pretend it is.
        search("order=salary-high")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("currency")));
    }

    @Test
    @DisplayName("relevance ranks a title match above a description match")
    void relevanceWeighsTitleOverDescription() throws Exception {
        // "engineer" is in the title of 1, 3, 4, 5 and in no description; "platform" is in
        // job 5's title and job 1's description. Title beats description for "platform".
        search("q=platform&order=relevance")
                .andExpect(jsonPath("$.content[0].id").value(5))
                .andExpect(jsonPath("$.content[1].id").value(1));
    }

    @Test
    @DisplayName("relevance with no search text falls back to newest rather than an arbitrary order")
    void relevanceWithoutQueryIsNewest() throws Exception {
        search("order=relevance").andExpect(jsonPath("$.content[0].id").value(4));
    }

    @Test
    @DisplayName("title and company orderings are alphabetical and case-insensitive")
    void alphabeticalOrderings() throws Exception {
        search("order=title").andExpect(jsonPath("$.content[0].title").value("Backend Engineer"));
        search("order=company").andExpect(jsonPath("$.content[0].company.name").value("Acme Systems"));
    }

    @Test
    @DisplayName("an unknown order is a 400 that names the valid ones")
    void unknownOrderIsRejected() throws Exception {
        search("order=popularity")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("salary-high")));
    }

    @Test
    @DisplayName("the legacy sort parameter still works unchanged")
    void legacySortStillWorks() throws Exception {
        search("sort=title,asc").andExpect(jsonPath("$.content[0].title").value("Backend Engineer"));
    }

    // ------------------------------------------------------------------ paging

    @Test
    @DisplayName("pages partition the results with no repeats across pages")
    void paginationPartitions() throws Exception {
        String first = search("order=newest&size=3&page=0")
                .andExpect(jsonPath("$.totalElements").value(7))
                .andExpect(jsonPath("$.totalPages").value(3))
                .andReturn().getResponse().getContentAsString();
        String second = search("order=newest&size=3&page=1")
                .andReturn().getResponse().getContentAsString();

        java.util.List<Integer> a = com.jayway.jsonpath.JsonPath.read(first, "$.content[*].id");
        java.util.List<Integer> b = com.jayway.jsonpath.JsonPath.read(second, "$.content[*].id");
        assertThat(a).doesNotContainAnyElementsOf(b);
    }

    // ------------------------------------------------------------------ queries

    @Test
    @DisplayName("a page of results costs a fixed number of queries, not one per job")
    void noNPlusOne() throws Exception {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        search("q=engineer&order=relevance&size=20").andExpect(status().isOk());

        // Page, count, and one batch for every job's skills. Company and location come in
        // with the page. Seven rows or seven hundred, this does not grow.
        assertThat(statistics.getPrepareStatementCount()).isLessThanOrEqualTo(3);
    }

    // ------------------------------------------------------------------ options

    @Test
    @DisplayName("salary currencies come from the postings, with their ranges")
    void salaryCurrenciesFromData() throws Exception {
        mockMvc.perform(get("/api/jobs/salary-currencies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].currency", containsInAnyOrder("INR", "EUR", "USD")));
    }

    // ----------------------------------------------------------------- helpers

    /**
     * Runs a search from "key=value&amp;key=value", setting each as a request parameter.
     *
     * <p>Parameters rather than a query string, because {@code get(String)} treats its
     * argument as a URI template and encodes it again: a pre-encoded space arrives as the
     * literal text "%20". Parameters bypass URL encoding, so the values below are exactly
     * what the server sees — a raw "%", a raw "+".
     */
    private ResultActions search(String query) throws Exception {
        var request = get("/api/jobs");
        for (String pair : query.split("&")) {
            int equals = pair.indexOf('=');
            request = request.param(pair.substring(0, equals), pair.substring(equals + 1));
        }
        return mockMvc.perform(request);
    }

    private void job(long id, String title, long companyId, Integer locationId, String type,
                     Integer experienceMin, Integer experienceMax, String salaryMin, String salaryMax,
                     String currency, String postedDate, String category, String description) {
        jdbcTemplate.update("""
                        INSERT INTO jobs (id, title, company_id, location_id, description, source,
                                          source_url, content_fingerprint, employment_type,
                                          experience_min, experience_max, salary_min, salary_max,
                                          currency, posted_date, job_category,
                                          classification_confidence, classified_at)
                        VALUES (?, ?, ?, ?, ?, 'itest', ?, ?, ?, ?, ?, ?::numeric, ?::numeric, ?,
                                ?::date, ?, CASE WHEN ?::text IS NULL THEN NULL ELSE 70.0 END,
                                CASE WHEN ?::text IS NULL THEN NULL ELSE now() END)
                        """,
                id, title, companyId, locationId, description,
                "https://example.invalid/jobs/" + id, String.format("%064d", id), type,
                experienceMin, experienceMax, salaryMin, salaryMax, currency, postedDate,
                category, category, category);
    }
}
