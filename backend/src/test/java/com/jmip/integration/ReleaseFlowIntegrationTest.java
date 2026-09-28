package com.jmip.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import com.jmip.testsupport.PdfFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * V7.10 release validation: the whole user journey through the real sign-in, session and CSRF
 * checks — signup, email code, login, dashboard, search, save and track, resume upload and
 * analysis, recommendations, goal and roadmap, market, copilot, resume deletion, logout — with
 * a second account checked for isolation along the way, and the schema checked afterwards.
 */
@SpringBootTest(properties = {"jmip.ai.provider=stub", "jmip.verification.delivery=log",
        "jmip.security.password.bcrypt-strength=4"})
@AutoConfigureMockMvc
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
class ReleaseFlowIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine");

    @TempDir
    static Path storage;

    @DynamicPropertySource
    static void resumeStorage(DynamicPropertyRegistry registry) {
        registry.add("jmip.resume.storage.directory", () -> storage.toString());
    }

    private static final String EMAIL = "release.tester@example.com";
    private static final String PASSWORD = "violet otter lantern";
    private static final String BOB = "bob@example.com";
    private static final Pattern CODE = Pattern.compile("Verification code: (\\d{6})");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private MockHttpSession session;
    private String csrf;

    @BeforeEach
    void seed() {
        jdbcTemplate.execute("TRUNCATE career_goal_skill_progress, career_goal_skills, career_goals, saved_jobs, resume_skills, "
                + "resumes, job_skills, jobs, skills, companies, locations, users RESTART IDENTITY CASCADE");
        jdbcTemplate.update("INSERT INTO users (id, full_name, email, password_hash, role, email_verified_at) "
                + "VALUES (?, 'Bob', ?, '{bcrypt}x', 'USER', now())", UUID.randomUUID(), BOB);
        jdbcTemplate.update("INSERT INTO companies (id, name) VALUES (1, 'Acme Systems')");
        jdbcTemplate.update("INSERT INTO locations (id, city, country) VALUES (1, 'Berlin', 'Germany')");
        jdbcTemplate.update("INSERT INTO skills (id, name, category) VALUES (1, 'Java', 'LANGUAGE'), (2, 'Docker', 'PLATFORM'), "
                + "(3, 'Kubernetes', 'PLATFORM')");
        for (int id = 1; id <= 3; id++) {
            jdbcTemplate.update("""
                    INSERT INTO jobs (id, title, company_id, location_id, description, source, source_url, content_fingerprint,
                                      salary_min, salary_max, currency, posted_date, job_category, classification_confidence, classified_at)
                    VALUES (?, ?, 1, 1, 'Remote-friendly backend role', 'release', ?, ?, 50000, 70000, 'EUR', ?::date,
                            'Backend Developer', 0.9, now())
                    """, id, "Backend Engineer " + id, "https://example.invalid/" + id, String.format("%064d", id),
                    "2026-0" + (6 + id) + "-10");
        }
        jdbcTemplate.update("INSERT INTO job_skills (job_id, skill_id) VALUES (1, 1), (1, 2), (2, 1), (2, 3), (3, 1)");
    }

    @Test
    @DisplayName("signup to logout, with ownership, CSRF and deletion enforced at every step")
    void fullUserJourney(CapturedOutput output) throws Exception {
        // Signup, email code, login.
        String credentials = new ObjectMapper().writeValueAsString(
                Map.of("fullName", "Release Tester", "email", EMAIL, "password", PASSWORD));
        mockMvc.perform(json(post("/api/auth/signup"), credentials)).andExpect(status().isCreated());
        mockMvc.perform(json(post("/api/auth/login"), credentials)).andExpect(status().isForbidden()); // not verified yet
        Matcher code = CODE.matcher(output.getOut());
        assertThat(code.find()).as("a verification code was issued").isTrue();
        mockMvc.perform(json(post("/api/auth/verify-email"), "{\"email\":\"" + EMAIL + "\",\"code\":\"" + code.group(1) + "\"}"))
                .andExpect(status().isNoContent());
        var login = mockMvc.perform(json(post("/api/auth/login"), credentials)).andExpect(status().isOk()).andReturn();
        session = (MockHttpSession) login.getRequest().getSession(false);
        csrf = JsonPath.read(login.getResponse().getContentAsString(), "$.csrfToken");
        mockMvc.perform(signedIn(get("/api/auth/me"))).andExpect(jsonPath("$.user.email").value(EMAIL));

        // Dashboard for a new account, then job search.
        mockMvc.perform(signedIn(get("/api/dashboard"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.applications.total").value(0))
                .andExpect(jsonPath("$.resume.available").value(false));
        mockMvc.perform(signedIn(get("/api/jobs").param("size", "5"))).andExpect(jsonPath("$.content", hasSize(3)));

        // Save a job and track the application. A write without the CSRF token is refused.
        mockMvc.perform(signedIn(post("/api/jobs/1/save")).header("X-CSRF-TOKEN", "")).andExpect(status().isForbidden());
        String savedId = JsonPath.read(mockMvc.perform(write(post("/api/jobs/1/save"))).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.id");
        mockMvc.perform(write(json(patch("/api/saved-jobs/" + savedId + "/status"), "{\"status\":\"APPLIED\"}")))
                .andExpect(jsonPath("$.status").value("APPLIED"));

        // Resume upload, analysis against a job, recommendations.
        MockMultipartFile pdf = new MockMultipartFile("file", "cv.pdf", MediaType.APPLICATION_PDF_VALUE,
                PdfFixtures.singlePage(List.of("Release Tester", "Backend developer with Java in production")));
        String resumeId = JsonPath.read(mockMvc.perform(write(multipart("/api/resumes").file(pdf)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
        mockMvc.perform(signedIn(get("/api/resumes/" + resumeId + "/analyze-job/1")))
                .andExpect(jsonPath("$.matchPercentage").value(50.0))
                .andExpect(jsonPath("$.missingSkills[0].name").value("Docker"));
        mockMvc.perform(signedIn(get("/api/resumes/" + resumeId + "/recommendations")))
                .andExpect(jsonPath("$[0].jobId").value(3));

        // Career goal, roadmap and progress, market view.
        String goalId = JsonPath.read(mockMvc.perform(write(json(post("/api/career-goals"),
                        "{\"targetRole\":\"Backend Engineer\",\"targetCategory\":\"Backend Developer\"}")))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
        Integer firstSkill = JsonPath.read(mockMvc.perform(signedIn(get("/api/career-goals/" + goalId + "/roadmap")))
                .andExpect(jsonPath("$.coveredSkills[0].name").value("Java"))
                .andReturn().getResponse().getContentAsString(), "$.roadmap[0].skillId");
        mockMvc.perform(write(json(put("/api/career-goals/" + goalId + "/roadmap/skills/" + firstSkill), "{\"status\":\"IN_PROGRESS\"}")))
                .andExpect(status().isOk());
        mockMvc.perform(signedIn(get("/api/market/skills").param("category", "Backend Developer")))
                .andExpect(jsonPath("$.topSkills[0].skill").value("Java"));

        // The copilot answers from this account's data.
        mockMvc.perform(write(json(post("/api/assistant/query"), "{\"question\":\"Show me my application progress.\"}")))
                .andExpect(jsonPath("$.intent").value("APPLICATION_PROGRESS"))
                .andExpect(jsonPath("$.data[1].jobs").value(1));
        mockMvc.perform(signedIn(get("/api/dashboard")))
                .andExpect(jsonPath("$.applications.applied").value(1))
                .andExpect(jsonPath("$.careerGoal.targetRole").value("Backend Engineer"))
                .andExpect(jsonPath("$.skills.inProgress", hasSize(1)));

        // Another account sees none of it.
        mockMvc.perform(get("/api/resumes/" + resumeId).with(user(BOB).roles("USER"))).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/career-goals/" + goalId).with(user(BOB).roles("USER"))).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/saved-jobs").with(user(BOB).roles("USER"))).andExpect(jsonPath("$", hasSize(0)));
        mockMvc.perform(get("/api/dashboard").with(user(BOB).roles("USER"))).andExpect(jsonPath("$.applications.total").value(0));

        // Deleting the resume removes its skills and its stored file.
        mockMvc.perform(write(delete("/api/resumes/" + resumeId))).andExpect(status().isNoContent());
        assertThat(count("SELECT count(*) FROM resume_skills WHERE resume_id = ?::uuid", resumeId)).isZero();
        try (var files = Files.list(storage)) {
            assertThat(files.filter(Files::isRegularFile).count()).as("stored resume files").isZero();
        }

        // Logout ends the session.
        mockMvc.perform(write(post("/api/auth/logout"))).andExpect(status().isNoContent());
        mockMvc.perform(signedIn(get("/api/dashboard"))).andExpect(status().isUnauthorized());

        // Nothing secret reached the log.
        assertThat(output.getOut()).doesNotContain(PASSWORD).doesNotContain(csrf);
    }

    @Test
    @DisplayName("the schema came from all migrations in order, and deleting an account leaves no orphans")
    void schemaAndCascades() throws Exception {
        List<String> versions = jdbcTemplate.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank", String.class);
        assertThat(versions).containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14", "15");

        UUID bob = jdbcTemplate.queryForObject("SELECT id FROM users WHERE email = ?", UUID.class, BOB);
        mockMvc.perform(write(post("/api/jobs/2/save")).with(user(BOB).roles("USER"))).andExpect(status().isCreated());
        mockMvc.perform(json(post("/api/career-goals"), "{\"targetRole\":\"B\",\"targetCategory\":\"Backend Developer\"}")
                .with(user(BOB).roles("USER"))).andExpect(status().isCreated());
        mockMvc.perform(json(post("/api/job-alerts"), "{\"name\":\"B\",\"skill\":\"Java\",\"frequency\":\"DAILY\"}")
                .with(user(BOB).roles("USER"))).andExpect(status().isCreated());

        jdbcTemplate.update("DELETE FROM users WHERE id = ?", bob);

        for (String table : new String[]{"saved_jobs", "career_goals", "job_alerts", "resumes", "email_verification_codes"}) {
            assertThat(count("SELECT count(*) FROM " + table + " WHERE user_id = ?", bob)).as(table).isZero();
        }
        assertThat(count("SELECT count(*) FROM career_goal_skill_progress p LEFT JOIN career_goals g ON g.id = p.goal_id "
                + "WHERE g.id IS NULL")).isZero();
        assertThat(count("SELECT count(*) FROM job_skills js LEFT JOIN jobs j ON j.id = js.job_id WHERE j.id IS NULL")).isZero();
    }

    // ------------------------------------------------------------------ helpers

    /** The browser's view: the session cookie is presented, so the CSRF rule applies. */
    private MockHttpServletRequestBuilder signedIn(MockHttpServletRequestBuilder request) {
        return request.session(session).with(r -> {
            r.setRequestedSessionId(session.getId());
            r.setRequestedSessionIdValid(true);
            return r;
        });
    }

    private MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder request) {
        if (session == null) {
            return request;
        }
        return signedIn(request).header("X-CSRF-TOKEN", csrf);
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private long count(String sql, Object... args) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }
}
