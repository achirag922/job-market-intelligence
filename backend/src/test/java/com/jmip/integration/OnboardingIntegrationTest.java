package com.jmip.integration;

import com.jmip.testsupport.PdfFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * V9.12: first-time onboarding. Alice and Bob are new accounts (no onboarding row); Carol stands for
 * an account that existed before V9.12, which the migration marks COMPLETED. The resume step goes
 * through the existing upload and parser; the goal step through the existing career-goal endpoint.
 */
@SpringBootTest(properties = {"jmip.ai.provider=stub", "jmip.alerts.enabled=false"})
@AutoConfigureMockMvc
@Testcontainers
class OnboardingIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine");

    @TempDir
    static Path storageDirectory;

    @DynamicPropertySource
    static void resumeStorage(DynamicPropertyRegistry registry) {
        registry.add("jmip.resume.storage.directory", () -> storageDirectory.toString());
    }

    private static final String ALICE = "alice@example.test";
    private static final String BOB = "bob@example.test";
    private static final String CAROL = "carol@example.test";
    private static final String PROFILE = "{\"targetRole\": \"Backend Engineer\", \"yearsExperience\": 4, \"skills\": [\"Java\", \"Docker\"]}";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void seed() {
        jdbcTemplate.execute("TRUNCATE user_onboarding, career_goal_skills, career_goals, match_preferences, resume_skills, "
                + "resumes, job_skills, jobs, skills, companies, users RESTART IDENTITY CASCADE");
        account(ALICE);
        account(BOB);
        UUID carol = account(CAROL);
        // What V28 does for every account that already existed.
        jdbcTemplate.update("INSERT INTO user_onboarding (user_id, status, completed_at, created_at, updated_at) "
                + "VALUES (?, 'COMPLETED', now(), now(), now())", carol);
        jdbcTemplate.update("INSERT INTO companies (id, name) VALUES (1, 'Acme')");
        jdbcTemplate.update("INSERT INTO skills (id, name, category) VALUES (1, 'Java', 'LANGUAGE'), (2, 'Docker', 'PLATFORM')");
        jdbcTemplate.update("""
                INSERT INTO jobs (id, title, company_id, description, source, source_url, content_fingerprint,
                                  job_category, classification_confidence, classified_at)
                VALUES (1, 'Backend Engineer', 1, 'Java and Docker', 'itest', 'https://example.invalid/1', ?,
                        'Backend Developer', 0.9, now())
                """, String.format("%064d", 1));
        jdbcTemplate.update("INSERT INTO job_skills (job_id, skill_id) VALUES (1, 1), (1, 2)");
    }

    @Test
    @DisplayName("a new user goes profile → resume → preferences → career goal → complete, reusing the existing systems")
    void fullJourney() throws Exception {
        progress(ALICE)
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.nextStep").value("PROFILE"))
                .andExpect(jsonPath("$.completedSteps").value(0))
                .andExpect(jsonPath("$.totalSteps").value(4));
        // An existing preference set elsewhere must survive onboarding.
        send(put("/api/match-preferences"), ALICE, "{\"excludedCompanies\": [\"Evil Corp\"]}").andExpect(status().isOk());

        send(put("/api/onboarding/profile"), ALICE, PROFILE)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.steps.profile").value(true))
                .andExpect(jsonPath("$.nextStep").value("RESUME"))
                .andExpect(jsonPath("$.profile.targetRole").value("Backend Engineer"))
                .andExpect(jsonPath("$.profile.yearsExperience").value(4))
                .andExpect(jsonPath("$.profile.skills", contains("Java", "Docker")));

        // The existing upload and parser; onboarding only notices that a processed resume now exists.
        MockMultipartFile pdf = new MockMultipartFile("file", "cv.pdf", MediaType.APPLICATION_PDF_VALUE,
                PdfFixtures.singlePage(List.of("Alice Example", "Skills:", "Java", "Docker")));
        mockMvc.perform(multipart("/api/resumes").file(pdf).with(user(ALICE).roles("USER")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.skills[*].name", hasItem("Java")));
        String afterResume = progress(ALICE)
                .andExpect(jsonPath("$.steps.resume").value(true))
                .andExpect(jsonPath("$.nextStep").value("PREFERENCES"))
                .andReturn().getResponse().getContentAsString();
        assertThat(afterResume).doesNotContain("Alice Example", "cv.pdf");

        send(put("/api/onboarding/preferences"), ALICE, """
                {"preferredLocation": "Berlin", "workMode": "REMOTE", "minSalary": 60000, "salaryCurrency": "EUR",
                 "preferredCategories": ["Backend Developer"]}""")
                .andExpect(jsonPath("$.steps.preferences").value(true))
                .andExpect(jsonPath("$.nextStep").value("CAREER_GOAL"))
                .andExpect(jsonPath("$.preferences.workMode").value("REMOTE"));
        mockMvc.perform(get("/api/match-preferences").with(user(ALICE).roles("USER")))
                .andExpect(jsonPath("$.yearsExperience").value(4))
                .andExpect(jsonPath("$.preferredSkills", contains("Java", "Docker")))
                .andExpect(jsonPath("$.preferredLocation").value("Berlin"))
                .andExpect(jsonPath("$.minSalary").value(60000))
                .andExpect(jsonPath("$.preferredCategories", contains("Backend Developer")))
                .andExpect(jsonPath("$.excludedCompanies", contains("Evil Corp")));

        send(post("/api/career-goals"), ALICE, "{\"targetRole\": \"Backend Engineer\", \"targetCategory\": \"Backend Developer\", "
                + "\"targetExperience\": \"2-5\", \"targetSkills\": [\"Docker\"]}").andExpect(status().isCreated());
        progress(ALICE)
                .andExpect(jsonPath("$.steps.careerGoal").value(true))
                .andExpect(jsonPath("$.nextStep").value("DONE"))
                .andExpect(jsonPath("$.completedSteps").value(4))
                .andExpect(jsonPath("$.status").value("PENDING"));

        send(post("/api/onboarding/complete"), ALICE, null)
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.completedAt").exists());
        // Skipping afterwards never undoes a completion.
        send(post("/api/onboarding/skip"), ALICE, null).andExpect(jsonPath("$.status").value("COMPLETED"));
        // The personalized feed now runs on the onboarding answers.
        mockMvc.perform(get("/api/jobs/personalized").with(user(ALICE).roles("USER"))).andExpect(status().isOk());
    }

    @Test
    @DisplayName("each step validates its input and nothing is saved when it is invalid")
    void validation() throws Exception {
        send(put("/api/onboarding/profile"), ALICE, "{\"targetRole\": \" \", \"yearsExperience\": 4, \"skills\": [\"Java\"]}")
                .andExpect(status().isBadRequest());
        send(put("/api/onboarding/profile"), ALICE, "{\"targetRole\": \"Engineer\", \"skills\": [\"Java\"]}")
                .andExpect(status().isBadRequest());
        send(put("/api/onboarding/profile"), ALICE, "{\"targetRole\": \"Engineer\", \"yearsExperience\": 61, \"skills\": [\"Java\"]}")
                .andExpect(status().isBadRequest());
        send(put("/api/onboarding/profile"), ALICE, "{\"targetRole\": \"Engineer\", \"yearsExperience\": 3, \"skills\": []}")
                .andExpect(status().isBadRequest());
        send(put("/api/onboarding/preferences"), ALICE, "{}").andExpect(status().isBadRequest());
        send(put("/api/onboarding/preferences"), ALICE, "{\"minSalary\": 50000}").andExpect(status().isBadRequest());
        send(put("/api/onboarding/preferences"), ALICE, "{\"workMode\": \"SOMETIMES\"}").andExpect(status().isBadRequest());
        progress(ALICE).andExpect(jsonPath("$.completedSteps").value(0)).andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    @DisplayName("onboarding can be skipped and finished later, keeping what was already saved")
    void skipAndResume() throws Exception {
        send(put("/api/onboarding/profile"), BOB, PROFILE).andExpect(status().isOk());
        send(post("/api/onboarding/skip"), BOB, null)
                .andExpect(jsonPath("$.status").value("SKIPPED"))
                .andExpect(jsonPath("$.skippedAt").exists())
                .andExpect(jsonPath("$.steps.profile").value(true))
                .andExpect(jsonPath("$.nextStep").value("RESUME"));
        // Later: the next step continues where Bob stopped, and completion works after a skip.
        send(put("/api/onboarding/preferences"), BOB, "{\"workMode\": \"HYBRID\"}")
                .andExpect(jsonPath("$.status").value("SKIPPED"))
                .andExpect(jsonPath("$.completedSteps").value(2));
        send(post("/api/onboarding/complete"), BOB, null).andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    @DisplayName("progress is the caller's own, no user id is accepted, and existing accounts are not sent through onboarding")
    void ownershipAndExistingUsers() throws Exception {
        UUID alice = jdbcTemplate.queryForObject("SELECT id FROM users WHERE email = ?", UUID.class, ALICE);
        send(put("/api/onboarding/profile"), BOB, "{\"userId\": \"" + alice + "\", \"targetRole\": \"Data Engineer\", "
                + "\"yearsExperience\": 2, \"skills\": [\"Java\"]}").andExpect(status().isOk());
        progress(ALICE).andExpect(jsonPath("$.steps.profile").value(false)).andExpect(jsonPath("$.profile.targetRole").doesNotExist());
        progress(BOB).andExpect(jsonPath("$.profile.targetRole").value("Data Engineer"));
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM user_onboarding WHERE user_id = ?", Long.class, alice)).isZero();

        mockMvc.perform(get("/api/onboarding")).andExpect(status().isUnauthorized());
        mockMvc.perform(put("/api/onboarding/profile").contentType(MediaType.APPLICATION_JSON).content(PROFILE))
                .andExpect(status().isUnauthorized());

        progress(CAROL).andExpect(jsonPath("$.status").value("COMPLETED")).andExpect(jsonPath("$.completedAt").exists());
        mockMvc.perform(get("/api/dashboard").with(user(CAROL).roles("USER"))).andExpect(status().isOk());
    }

    private ResultActions progress(String email) throws Exception {
        return mockMvc.perform(get("/api/onboarding").with(user(email).roles("USER"))).andExpect(status().isOk());
    }

    private ResultActions send(MockHttpServletRequestBuilder request, String email, String json) throws Exception {
        MockHttpServletRequestBuilder signedIn = request.with(user(email).roles("USER"));
        return mockMvc.perform(json == null ? signedIn : signedIn.contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private UUID account(String email) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO users (id, full_name, email, password_hash, role, email_verified_at)
                VALUES (?, 'Test', ?, '{bcrypt}not-a-real-hash', 'USER', now())
                """, id, email);
        return id;
    }
}
