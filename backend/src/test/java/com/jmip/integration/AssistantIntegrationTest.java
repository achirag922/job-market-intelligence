package com.jmip.integration;

import com.jmip.ai.AiClient;
import com.jmip.testsupport.ScriptedAiClient;
import com.jmip.testsupport.MockUserAccount;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The V5 assistant against a real PostgreSQL, with a scripted provider.
 *
 * <p>What is under test is everything between the model and the database: that each intent
 * reaches the service that answers it, that the figures returned are the ones the ordinary
 * endpoints return, and that a question naming something that does not exist is refused
 * rather than answered with a wrong filter.
 *
 * <p>The provider is scripted rather than real. Tests must not depend on a credential, a
 * network, or a model's mood, and the cases that matter most here — a hostile intent, a
 * fabricated entity — cannot be requested from a real one.
 *
 * <pre>
 * id category           company  location    skills                salary
 *  1 Backend Developer  Acme     Austin      Java, Spring Boot     120000 USD
 *  2 Backend Developer  Acme     Austin      Java, Kafka           130000 USD
 *  3 Backend Developer  Globex   Bengaluru   Java                  1800000 INR
 *  4 Data Engineer      Globex   Bengaluru   Python, SQL, Spark    140000 USD
 *  5 Data Engineer      Globex   Bengaluru   Python, Spark         150000 USD
 *  6 DevOps Engineer    Acme     (remote)    Kubernetes            (none)
 * </pre>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = "jmip.assistant.min-salary-sample=2")
// V6.10.3: the API requires a signed-in USER; these tests exercise behaviour behind that.
@WithMockUser(roles = "USER")
class AssistantIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine");

    /**
     * Replaces the provider for this context. A real one would make the suite
     * non-deterministic and would need a key nobody should require to run the tests.
     */
    @TestConfiguration
    static class ScriptedAiConfiguration {

        /**
         * One bean, primary, so it wins the {@link AiClient} injection while still being
         * injectable by its own type for scripting. A second bean of the interface type
         * would leave two primaries and no winner.
         */
        @Bean
        @Primary
        ScriptedAiClient scriptedAiClient() {
            return new ScriptedAiClient();
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ScriptedAiClient ai;

    /** The account behind @WithMockUser; resumes seeded here belong to it. */
    private java.util.UUID ownerId;

    @BeforeEach
    void seed() {
        ownerId = MockUserAccount.ensure(jdbcTemplate);
        ai.respondingWithIntent("""
                {"intent":"GENERAL_JOB_MARKET","entities":{},"timeRange":null,"limit":null}""")
          .respondingWithAnswer("A description of the retrieved rows.");

        jdbcTemplate.execute("TRUNCATE job_classification_signals, job_skills, jobs, skills, "
                + "companies, locations RESTART IDENTITY CASCADE");

        jdbcTemplate.update("INSERT INTO companies (id, name, industry) VALUES "
                + "(1, 'Acme Systems', 'Software'), (2, 'Globex Data', 'Data & Analytics')");
        jdbcTemplate.update("INSERT INTO locations (id, city, state, country) VALUES "
                + "(1, 'Austin', 'Texas', 'United States'), "
                + "(2, 'Bengaluru', 'Karnataka', 'India')");
        jdbcTemplate.update("INSERT INTO skills (id, name, category) VALUES "
                + "(1, 'Java', 'LANGUAGE'), (2, 'Spring Boot', 'FRAMEWORK'), (3, 'Kafka', 'DATA'), "
                + "(4, 'Python', 'LANGUAGE'), (5, 'SQL', 'LANGUAGE'), (6, 'Spark', 'DATA'), "
                + "(7, 'Kubernetes', 'PLATFORM')");

        job(1, "Senior Backend Engineer", 1, 1, "Backend Developer", "120000", "USD");
        job(2, "Backend Engineer", 1, 1, "Backend Developer", "130000", "USD");
        job(3, "Java Developer", 2, 2, "Backend Developer", "1800000", "INR");
        job(4, "Data Engineer", 2, 2, "Data Engineer", "140000", "USD");
        job(5, "Senior Data Engineer", 2, 2, "Data Engineer", "150000", "USD");
        job(6, "Site Reliability Engineer", 1, null, "DevOps Engineer", null, null);

        jdbcTemplate.update("INSERT INTO job_skills (job_id, skill_id) VALUES "
                + "(1,1),(1,2),(2,1),(2,3),(3,1),(4,4),(4,5),(4,6),(5,4),(5,6),(6,7)");
    }

    // ------------------------------------------------------------------ routing

    @Test
    @DisplayName("a skill-demand question reaches the category skill analytics")
    void routesSkillDemand() throws Exception {
        intent("SKILL_DEMAND", """
                {"jobCategory":"Backend Developer"}""");

        // Java is on all three backend postings: 100% of that category.
        query("what skills do backend developers need")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.intent").value("SKILL_DEMAND"))
                .andExpect(jsonPath("$.grounded").value(true))
                .andExpect(jsonPath("$.data[0].skill").value("Java"))
                .andExpect(jsonPath("$.data[0].jobCount").value(3))
                .andExpect(jsonPath("$.data[0].percentageOfJobs").value(100.0))
                .andExpect(jsonPath("$.visualization.type").value("BAR"));
    }

    @Test
    @DisplayName("a category-demand question returns the distribution as a pie")
    void routesCategoryDemand() throws Exception {
        intent("JOB_CATEGORY_DEMAND", "{}");

        query("how are jobs split across categories")
                .andExpect(jsonPath("$.data[0].category").value("Backend Developer"))
                .andExpect(jsonPath("$.data[0].jobCount").value(3))
                .andExpect(jsonPath("$.visualization.type").value("PIE"));
    }

    @Test
    @DisplayName("a company question within a category reaches the category company analytics")
    void routesCompanyDemand() throws Exception {
        intent("COMPANY_DEMAND", """
                {"jobCategory":"Data Engineer"}""");

        query("who is hiring data engineers")
                .andExpect(jsonPath("$.data[0].company.name").value("Globex Data"))
                .andExpect(jsonPath("$.data[0].jobCount").value(2))
                .andExpect(jsonPath("$.visualization.type").value("BAR"));
    }

    @Test
    @DisplayName("a location question about a skill reaches the skill location analytics")
    void routesLocationDemandForSkill() throws Exception {
        // "Which cities have the most Java jobs?" — a skill, not a category.
        intent("LOCATION_DEMAND", """
                {"skill":"Java"}""");

        query("which cities have the most java jobs")
                .andExpect(jsonPath("$.data[0].location.city").value("Austin"))
                .andExpect(jsonPath("$.data[0].jobCount").value(2))
                .andExpect(jsonPath("$.data[1].location.city").value("Bengaluru"));
    }

    @Test
    @DisplayName("a job search returns postings as a table, capped")
    void routesJobSearch() throws Exception {
        intent("JOB_SEARCH", """
                {"skill":"Java","location":"Austin"}""");

        query("show me java jobs in austin")
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.visualization.type").value("TABLE"))
                .andExpect(jsonPath("$.data[0].title").exists());
    }

    @Test
    @DisplayName("a skill comparison returns both sides with their own counts")
    void routesSkillComparison() throws Exception {
        intent("SKILL_COMPARISON", """
                {"skill":"Java","secondSkill":"Python"}""");

        query("compare java and python")
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].skill").value("Java"))
                .andExpect(jsonPath("$.data[0].jobCount").value(3))
                .andExpect(jsonPath("$.data[1].skill").value("Python"))
                .andExpect(jsonPath("$.data[1].jobCount").value(2));
    }

    @Test
    @DisplayName("a category comparison reports counts and skills, and no recommendation")
    void routesCategoryComparison() throws Exception {
        intent("CATEGORY_COMPARISON", """
                {"jobCategory":"Backend Developer","secondJobCategory":"Data Engineer"}""");

        query("compare backend developer and data engineer")
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].category").value("Backend Developer"))
                .andExpect(jsonPath("$.data[0].jobCount").value(3))
                .andExpect(jsonPath("$.data[0].topSkills[0]").value("Java"))
                .andExpect(jsonPath("$.data[1].jobCount").value(2));
    }

    @Test
    @DisplayName("a general question returns the dataset overview")
    void routesGeneralQuestion() throws Exception {
        intent("GENERAL_JOB_MARKET", "{}");

        query("tell me about this job market")
                .andExpect(jsonPath("$.data[0].totalJobs").value(6))
                .andExpect(jsonPath("$.visualization.type").value("NONE"));
    }

    // ------------------------------------------------------------------ salary

    @Test
    @DisplayName("salaries are grouped by currency and never pooled across them")
    void reportsSalaryPerCurrency() throws Exception {
        intent("SALARY_ANALYSIS", "{}");

        // Four USD postings and one INR. The INR row is below the sample floor and is
        // dropped; the USD row must not have absorbed it.
        query("what do these jobs pay")
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].currency").value("USD"))
                .andExpect(jsonPath("$.data[0].jobCount").value(4))
                .andExpect(jsonPath("$.note").value(containsString("never combined")));
    }

    @Test
    @DisplayName("a scope with too little salary data says so instead of guessing")
    void refusesThinSalaryData() throws Exception {
        intent("SALARY_ANALYSIS", """
                {"jobCategory":"DevOps Engineer"}""");

        query("what do devops engineers earn")
                .andExpect(jsonPath("$.data.length()").value(0))
                .andExpect(jsonPath("$.note").value(containsString("does not have enough salary data")));
    }

    // ------------------------------------------------------------- validation

    @Test
    @DisplayName("a question about a skill that does not exist is refused, not answered")
    void refusesUnknownSkill() throws Exception {
        intent("LOCATION_DEMAND", """
                {"skill":"Cobol"}""");

        query("which cities have cobol jobs")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.grounded").value(false))
                .andExpect(jsonPath("$.data.length()").value(0))
                .andExpect(jsonPath("$.answer").value(containsString("Cobol")));
    }

    @Test
    @DisplayName("an intent outside the enum is refused and nothing is queried")
    void refusesUnknownIntent() throws Exception {
        intent("EXECUTE_SQL", """
                {"title":"select * from jobs"}""");

        query("drop the jobs table")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.grounded").value(false))
                .andExpect(jsonPath("$.intent").value("UNSUPPORTED"))
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    @DisplayName("SQL in an entity is treated as a name, found to not exist, and refused")
    void treatsSqlAsAName() throws Exception {
        intent("SKILL_TREND", """
                {"skill":"Java'; DROP TABLE jobs; --"}""");

        query("trend for java")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.grounded").value(false));

        // The point of the assertion: the table is still there.
        org.assertj.core.api.Assertions.assertThat(
                jdbcTemplate.queryForObject("SELECT count(*) FROM jobs", Integer.class)).isEqualTo(6);
    }

    @Test
    @DisplayName("a limit the model inflates is clamped to the configured ceiling")
    void clampsInflatedLimit() throws Exception {
        ai.respondingWithIntent("""
                {"intent":"SKILL_DEMAND","entities":{},"timeRange":null,"limit":100000}""");

        query("every skill you have")
                .andExpect(jsonPath("$.data.length()").value(lessThanOrEqualTo(25)));
    }

    // --------------------------------------------------------------- resume

    @Test
    @DisplayName("a resume question with no resume asks for one")
    void requiresResume() throws Exception {
        // Since V7.6 a question without a selected resume uses the account's default one, so
        // this account must have none at all (other tests here upload resumes for it).
        jdbcTemplate.update("DELETE FROM resumes WHERE user_id = ?", ownerId);
        intent("SKILL_GAP", """
                {"jobCategory":"Data Engineer"}""");

        query("what skills am I missing for data engineer jobs")
                .andExpect(jsonPath("$.grounded").value(false))
                .andExpect(jsonPath("$.answer").value(containsString("upload or select a resume")));
    }

    @Test
    @DisplayName("a skill gap against a category is the category's skills minus the resume's")
    void answersSkillGapForCategory() throws Exception {
        // The resume has Python; Data Engineer postings also want SQL and Spark. The gap
        // is composed from V3's extracted skills and V4's category skills — no second
        // matching algorithm.
        String resumeId = uploadResume("Jane Doe", "Experienced with Python and Airflow.");
        intent("SKILL_GAP", """
                {"jobCategory":"Data Engineer"}""");

        queryAs("what skills am I missing for data engineer jobs", resumeId)
                .andExpect(jsonPath("$.grounded").value(true))
                .andExpect(jsonPath("$.data[*].skill")
                        .value(org.hamcrest.Matchers.hasItems("SQL", "Spark")))
                .andExpect(jsonPath("$.data[*].skill")
                        .value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("Python"))));
    }

    @Test
    @DisplayName("a resume match against a posting reuses V3's match verbatim")
    void answersResumeMatchForJob() throws Exception {
        String resumeId = uploadResume("Jane Doe", "Experienced with Java and Spring Boot.");
        intent("RESUME_MATCH", "{}");

        // Job 1 wants Java and Spring Boot; the resume has both.
        mockMvc.perform(post("/api/assistant/query")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"how well does my resume match\",\"resumeId\":\""
                                + resumeId + "\",\"jobId\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.grounded").value(true))
                .andExpect(jsonPath("$.data[0].matchPercentage").value(100.0))
                .andExpect(jsonPath("$.data[0].jobCategory").value("Backend Developer"));
    }

    @Test
    @DisplayName("a resume match with no job named asks which job")
    void resumeMatchNeedsAJob() throws Exception {
        String resumeId = uploadResume("Jane Doe", "Java.");
        intent("RESUME_MATCH", "{}");

        queryAs("how well does my resume match", resumeId)
                .andExpect(jsonPath("$.data.length()").value(0))
                .andExpect(jsonPath("$.note").value(containsString("Which job")));
    }

    // ----------------------------------------------------------------- secrets

    @Test
    @DisplayName("no response carries provider configuration")
    void neverExposesProviderConfiguration() throws Exception {
        intent("JOB_CATEGORY_DEMAND", "{}");

        String body = query("how are jobs split")
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(body)
                .doesNotContain("apiKey").doesNotContain("api-key").doesNotContain("sk-ant");
    }

    @Test
    @DisplayName("the supported intents are listed, without the internal marker")
    void listsSupportedIntents() throws Exception {
        mockMvc.perform(get("/api/assistant/intents"))
                .andExpect(status().isOk())
                // 12 market intents plus the 8 V7.6 career-copilot intents.
                .andExpect(jsonPath("$.length()").value(20))
                .andExpect(content().string(containsString("SKILL_DEMAND")))
                .andExpect(content().string(containsString("APPLICATION_PROGRESS")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("UNSUPPORTED"))));
    }

    @Test
    @DisplayName("a blank question is a 400, unlike a question that cannot be answered")
    void rejectsBlankQuestion() throws Exception {
        mockMvc.perform(post("/api/assistant/query")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    // ----------------------------------------------------------------- helpers

    private static org.hamcrest.Matcher<Integer> lessThanOrEqualTo(int max) {
        return org.hamcrest.Matchers.lessThanOrEqualTo(max);
    }

    private void intent(String name, String entitiesJson) {
        ai.respondingWithIntent(
                "{\"intent\":\"" + name + "\",\"entities\":" + entitiesJson
                        + ",\"timeRange\":null,\"limit\":null}");
    }

    private org.springframework.test.web.servlet.ResultActions query(String question) throws Exception {
        return mockMvc.perform(post("/api/assistant/query")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"question\":\"" + question + "\"}"));
    }

    private org.springframework.test.web.servlet.ResultActions queryAs(String question, String resumeId)
            throws Exception {
        return mockMvc.perform(post("/api/assistant/query")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"question\":\"" + question + "\",\"resumeId\":\"" + resumeId + "\"}"));
    }

    /** Uploads a real PDF through the V3 endpoint and returns its id. */
    private String uploadResume(String name, String body) throws Exception {
        byte[] pdf = com.jmip.testsupport.PdfFixtures.singlePage(java.util.List.of(name, body));
        String response = mockMvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .multipart("/api/resumes")
                                .file(new org.springframework.mock.web.MockMultipartFile(
                                        "file", "resume.pdf",
                                        org.springframework.http.MediaType.APPLICATION_PDF_VALUE, pdf)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        return com.jayway.jsonpath.JsonPath.read(response, "$.id");
    }

    private void job(long id, String title, long companyId, Integer locationId,
                     String category, String salaryMin, String currency) {
        jdbcTemplate.update("""
                        INSERT INTO jobs (id, title, company_id, location_id, description, source,
                                          source_url, content_fingerprint, job_category,
                                          classification_confidence, classified_at,
                                          salary_min, currency)
                        VALUES (?, ?, ?, ?, ?, 'itest', ?, ?, ?, 75.0,
                                CASE WHEN ?::text IS NULL THEN NULL ELSE now() END,
                                ?::numeric, ?)
                        """,
                id, title, companyId, locationId, "Description for " + title,
                "https://example.invalid/jobs/" + id, String.format("%064d", id),
                category, category, salaryMin, currency);
    }

}
