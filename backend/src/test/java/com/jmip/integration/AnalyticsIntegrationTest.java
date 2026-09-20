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
 * Analytics against a real PostgreSQL, over a fixture small enough that every expected
 * number can be worked out by hand.
 *
 * <p>Eight postings:
 * <pre>
 * id title                     company  location  exp  posted      skills
 *  1 Senior Backend Engineer   Acme     Austin      5  2026-08-13  Java, SQL
 *  2 Backend Engineer          Acme     Austin      3  2026-07-02  Java
 *  3 Junior Backend Engineer   Globex   Berlin      1  2026-06-10  Java, Python
 *  4 Data Engineer             Globex   Berlin      4  2026-08-20  Python, SQL
 *  5 Senior Data Engineer      Globex   Berlin      9  2026-09-01  Python
 *  6 Remote SRE                Acme     (none)     12  2026-09-05  Java
 *  7 Data Analyst              Initech  Austin   null  2026-05-01  SQL
 *  8 Data Analyst (Remote)     Initech  Austin      2  null        (none)
 * </pre>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class AnalyticsIntegrationTest {

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

        jdbcTemplate.update("INSERT INTO companies (id, name, industry) VALUES "
                + "(1, 'Acme Systems', 'Software'), (2, 'Globex Data', 'Data & Analytics'), "
                + "(3, 'Initech Cloud', 'Cloud Services')");
        jdbcTemplate.update("INSERT INTO locations (id, city, state, country) VALUES "
                + "(1, 'Austin', 'Texas', 'United States'), (2, 'Berlin', null, 'Germany')");
        jdbcTemplate.update("INSERT INTO skills (id, name, category) VALUES "
                + "(1, 'Java', 'LANGUAGE'), (2, 'Python', 'LANGUAGE'), (3, 'SQL', 'LANGUAGE')");

        job(1, "Senior Backend Engineer", 1, 1, 5, "2026-08-13");
        job(2, "Backend Engineer", 1, 1, 3, "2026-07-02");
        job(3, "Junior Backend Engineer", 2, 2, 1, "2026-06-10");
        job(4, "Data Engineer", 2, 2, 4, "2026-08-20");
        job(5, "Senior Data Engineer", 2, 2, 9, "2026-09-01");
        job(6, "Remote SRE", 1, null, 12, "2026-09-05");
        job(7, "Data Analyst", 3, 1, null, "2026-05-01");
        job(8, "Data Analyst (Remote)", 3, 1, 2, null);

        jdbcTemplate.update("INSERT INTO job_skills (job_id, skill_id) VALUES "
                + "(1,1),(1,3),(2,1),(3,1),(3,2),(4,2),(4,3),(5,2),(6,1),(7,3)");
    }

    // ---------------------------------------------------------------- skill analytics

    @Test
    @DisplayName("skills are ranked, counted and given a share of the postings in scope")
    void rankedSkillDemand() throws Exception {
        // Java 4 postings, Python 3, SQL 3, over 8 postings total.
        mockMvc.perform(get("/api/analytics/skills"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope.totalJobsInScope").value(8))
                .andExpect(jsonPath("$.skills.content[0].skill").value("Java"))
                .andExpect(jsonPath("$.skills.content[0].jobCount").value(4))
                .andExpect(jsonPath("$.skills.content[0].percentageOfJobs").value(50.0))
                .andExpect(jsonPath("$.skills.content[0].rank").value(1))
                .andExpect(jsonPath("$.skills.content[1].rank").value(2))
                .andExpect(jsonPath("$.skills.content[2].rank").value(3))
                .andExpect(jsonPath("$.skills.totalElements").value(3));
    }

    @Test
    @DisplayName("percentages use the filtered total, not the global one")
    void percentagesUseFilteredTotal() throws Exception {
        // Berlin has 3 postings. Python is on all 3, so it is 100% of Berlin, not 37.5%
        // of the database. Using the global total here would understate every figure.
        mockMvc.perform(get("/api/analytics/skills").param("location", "Berlin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope.totalJobsInScope").value(3))
                .andExpect(jsonPath("$.scope.location").value("Berlin"))
                .andExpect(jsonPath("$.skills.content[0].skill").value("Python"))
                .andExpect(jsonPath("$.skills.content[0].jobCount").value(3))
                .andExpect(jsonPath("$.skills.content[0].percentageOfJobs").value(100.0));
    }

    @Test
    @DisplayName("the location filter matches city, state or country")
    void locationFilterMatchesAnyPart() throws Exception {
        mockMvc.perform(get("/api/analytics/skills").param("location", "Germany"))
                .andExpect(jsonPath("$.scope.totalJobsInScope").value(3));
        mockMvc.perform(get("/api/analytics/skills").param("location", "Texas"))
                .andExpect(jsonPath("$.scope.totalJobsInScope").value(4));
        mockMvc.perform(get("/api/analytics/skills").param("location", "austin"))
                .andExpect(jsonPath("$.scope.totalJobsInScope").value(4));
    }

    @Test
    @DisplayName("the date range filter is inclusive at both ends")
    void dateRangeFilterIsInclusive() throws Exception {
        // Postings dated 2026-08-13 and 2026-08-20 fall inside this range.
        mockMvc.perform(get("/api/analytics/skills")
                        .param("fromDate", "2026-08-13")
                        .param("toDate", "2026-08-20"))
                .andExpect(jsonPath("$.scope.totalJobsInScope").value(2))
                .andExpect(jsonPath("$.scope.fromDate").value("2026-08-13"));

        mockMvc.perform(get("/api/analytics/skills").param("fromDate", "2026-09-01"))
                .andExpect(jsonPath("$.scope.totalJobsInScope").value(2));
    }

    @Test
    @DisplayName("a posting with no date is excluded by any date filter")
    void undatedPostingExcludedByDateFilter() throws Exception {
        // Job 8 has no posted date and cannot satisfy a range; without a filter it counts.
        mockMvc.perform(get("/api/analytics/skills"))
                .andExpect(jsonPath("$.scope.totalJobsInScope").value(8));
        mockMvc.perform(get("/api/analytics/skills").param("fromDate", "2020-01-01"))
                .andExpect(jsonPath("$.scope.totalJobsInScope").value(7));
    }

    @Test
    @DisplayName("the title filter narrows the scope")
    void titleFilterNarrowsScope() throws Exception {
        mockMvc.perform(get("/api/analytics/skills").param("title", "data"))
                .andExpect(jsonPath("$.scope.totalJobsInScope").value(4))
                .andExpect(jsonPath("$.scope.title").value("data"));
    }

    @Test
    @DisplayName("filters combine")
    void filtersCombine() throws Exception {
        // Berlin AND title containing "engineer" leaves jobs 3, 4 and 5.
        mockMvc.perform(get("/api/analytics/skills")
                        .param("location", "Berlin")
                        .param("title", "engineer"))
                .andExpect(jsonPath("$.scope.totalJobsInScope").value(3));
    }

    @Test
    @DisplayName("a filter matching nothing reports zeroes rather than failing")
    void emptyScopeIsHandled() throws Exception {
        mockMvc.perform(get("/api/analytics/skills").param("location", "Atlantis"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope.totalJobsInScope").value(0))
                .andExpect(jsonPath("$.skills.content.length()").value(0));
    }

    @Test
    @DisplayName("a remote posting still counts towards the unfiltered total")
    void remotePostingCountsWhenUnfiltered() throws Exception {
        // Job 6 has no location. An inner join would silently drop it from the total.
        mockMvc.perform(get("/api/analytics/skills"))
                .andExpect(jsonPath("$.scope.totalJobsInScope").value(8));
    }

    // ----------------------------------------------------------- experience analytics

    @Test
    @DisplayName("experience bands count every posting, including those with no requirement")
    void experienceDistribution() throws Exception {
        // 0–2: job 3 (1 year).
        // 2–5: jobs 2 (3), 4 (4) and 8 (2) — 2 years lands here, not in the band below,
        //      because the bands are half open.
        // 5–8: job 1 (5). 8+: jobs 5 (9) and 6 (12). Not specified: job 7.
        mockMvc.perform(get("/api/analytics/experience"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalJobs").value(8))
                .andExpect(jsonPath("$.buckets.length()").value(5))
                .andExpect(jsonPath("$.buckets[0].bucket").value("ZERO_TO_TWO"))
                .andExpect(jsonPath("$.buckets[0].jobCount").value(1))
                .andExpect(jsonPath("$.buckets[0].percentageOfJobs").value(12.5))
                .andExpect(jsonPath("$.buckets[1].bucket").value("TWO_TO_FIVE"))
                .andExpect(jsonPath("$.buckets[1].jobCount").value(3))
                .andExpect(jsonPath("$.buckets[1].percentageOfJobs").value(37.5))
                .andExpect(jsonPath("$.buckets[2].jobCount").value(1))
                .andExpect(jsonPath("$.buckets[3].bucket").value("EIGHT_PLUS"))
                .andExpect(jsonPath("$.buckets[3].jobCount").value(2))
                .andExpect(jsonPath("$.buckets[4].bucket").value("UNSPECIFIED"))
                .andExpect(jsonPath("$.buckets[4].jobCount").value(1));
    }

    @Test
    @DisplayName("the bands reconcile exactly to the total")
    void experienceBandsReconcile() throws Exception {
        String body = mockMvc.perform(get("/api/analytics/experience"))
                .andReturn().getResponse().getContentAsString();
        var root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);

        long summed = 0;
        for (var bucket : root.get("buckets")) {
            summed += bucket.get("jobCount").asLong();
        }
        // If a posting fell through every band, this is where it would show up.
        org.assertj.core.api.Assertions.assertThat(summed).isEqualTo(root.get("totalJobs").asLong());
    }

    // -------------------------------------------------------------- company analytics

    @Test
    @DisplayName("companies are ranked with counts and shares")
    void companyDemand() throws Exception {
        // Acme 3, Globex 3, Initech 2, of 8.
        mockMvc.perform(get("/api/analytics/companies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].company.name").value("Acme Systems"))
                .andExpect(jsonPath("$.content[0].jobCount").value(3))
                .andExpect(jsonPath("$.content[0].percentageOfJobs").value(37.5))
                .andExpect(jsonPath("$.content[0].rank").value(1))
                .andExpect(jsonPath("$.content[2].company.name").value("Initech Cloud"))
                .andExpect(jsonPath("$.content[2].jobCount").value(2))
                .andExpect(jsonPath("$.content[2].rank").value(3));
    }

    @Test
    @DisplayName("company skills are a share of that company, not of all postings")
    void skillsPerCompany() throws Exception {
        // Java is on all 3 Acme postings: 100% of Acme, though only 50% of the database.
        mockMvc.perform(get("/api/analytics/companies/1/skills"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].skill").value("Java"))
                .andExpect(jsonPath("$[0].jobCount").value(3))
                .andExpect(jsonPath("$[0].percentageOfJobs").value(100.0))
                .andExpect(jsonPath("$[0].rank").value(1));
    }

    @Test
    @DisplayName("the company skills limit is honoured and validated")
    void companySkillsLimit() throws Exception {
        mockMvc.perform(get("/api/analytics/companies/2/skills").param("limit", "1"))
                .andExpect(jsonPath("$.length()").value(1));
        mockMvc.perform(get("/api/analytics/companies/2/skills").param("limit", "0"))
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------- location analytics

    @Test
    @DisplayName("locations are ranked, and remote postings are counted nowhere")
    void locationDemand() throws Exception {
        // Austin 4, Berlin 3. The remote posting belongs to neither, so the shares
        // deliberately fall short of 100.
        mockMvc.perform(get("/api/analytics/locations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].location.city").value("Austin"))
                .andExpect(jsonPath("$.content[0].jobCount").value(4))
                .andExpect(jsonPath("$.content[0].percentageOfJobs").value(50.0))
                .andExpect(jsonPath("$.content[0].rank").value(1))
                .andExpect(jsonPath("$.content[1].location.city").value("Berlin"))
                .andExpect(jsonPath("$.content[1].jobCount").value(3))
                .andExpect(jsonPath("$.content[1].percentageOfJobs").value(37.5));
    }

    @Test
    @DisplayName("top skills by city are a share of that city")
    void skillsPerLocation() throws Exception {
        // Berlin: Python on all 3, Java on 1, SQL on 1.
        mockMvc.perform(get("/api/analytics/locations/2/skills"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].skill").value("Python"))
                .andExpect(jsonPath("$[0].jobCount").value(3))
                .andExpect(jsonPath("$[0].percentageOfJobs").value(100.0));
    }

    @Test
    @DisplayName("job titles by city are grouped before counting")
    void titlesPerLocation() throws Exception {
        // Austin holds "Senior Backend Engineer", "Backend Engineer", "Data Analyst" and
        // "Data Analyst (Remote)": two backend engineer roles and two data analyst roles.
        mockMvc.perform(get("/api/analytics/locations/1/titles"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].jobCount").value(2))
                .andExpect(jsonPath("$[0].percentageOfJobs").value(50.0))
                .andExpect(jsonPath("$[1].jobCount").value(2));
    }

    // ---------------------------------------------------------------- title analytics

    @Test
    @DisplayName("similar titles are grouped, ranked and traceable to their variants")
    void titleDemand() throws Exception {
        // Backend Engineer groups jobs 1, 2 and 3; Data Engineer groups 4 and 5;
        // Data Analyst groups 7 and 8; Remote SRE stands alone.
        mockMvc.perform(get("/api/analytics/titles"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(4))
                .andExpect(jsonPath("$.content[0].title").value("Backend Engineer"))
                .andExpect(jsonPath("$.content[0].jobCount").value(3))
                .andExpect(jsonPath("$.content[0].percentageOfJobs").value(37.5))
                .andExpect(jsonPath("$.content[0].rank").value(1))
                .andExpect(jsonPath("$.content[0].variants.length()").value(3))
                .andExpect(jsonPath("$.content[0].variants[0]").value("Backend Engineer"));
    }

    @Test
    @DisplayName("each title group reports the skills it asks for")
    void titleSkills() throws Exception {
        // All three backend engineer postings mention Java.
        mockMvc.perform(get("/api/analytics/titles"))
                .andExpect(jsonPath("$.content[0].topSkills[0].skill").value("Java"))
                .andExpect(jsonPath("$.content[0].topSkills[0].jobCount").value(3))
                .andExpect(jsonPath("$.content[0].topSkills[0].percentageOfTitleJobs").value(100.0));
    }

    @Test
    @DisplayName("title groups paginate")
    void titleDemandPaginates() throws Exception {
        mockMvc.perform(get("/api/analytics/titles").param("size", "2"))
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.last").value(false));

        mockMvc.perform(get("/api/analytics/titles").param("page", "1").param("size", "2"))
                .andExpect(jsonPath("$.content[0].rank").value(3))
                .andExpect(jsonPath("$.last").value(true));
    }

    private void job(long id, String title, long companyId, Integer locationId,
                     Integer experienceMin, String postedDate) {
        jdbcTemplate.update("""
                        INSERT INTO jobs (id, title, company_id, location_id, description, employment_type,
                                          experience_min, posted_date, source, source_url, content_fingerprint)
                        VALUES (?, ?, ?, ?, ?, 'FULL_TIME', ?, ?::date, 'itest', ?, ?)
                        """,
                id, title, companyId, locationId, "Description for " + title,
                experienceMin, postedDate, "https://example.invalid/jobs/" + id,
                String.format("%064d", id));
    }
}
