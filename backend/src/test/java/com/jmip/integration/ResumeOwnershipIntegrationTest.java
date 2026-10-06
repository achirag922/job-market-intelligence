package com.jmip.integration;

import com.jayway.jsonpath.JsonPath;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * V6.10.4: a resume, and everything derived from it, belongs to the account that uploaded it.
 * Alice and Bob are real accounts; requests act as them the way a session would.
 */
@SpringBootTest(properties = "jmip.ai.provider=stub")
@AutoConfigureMockMvc
@Testcontainers
class ResumeOwnershipIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine");

    @TempDir
    static Path storageDirectory;

    @DynamicPropertySource
    static void resumeStorage(DynamicPropertyRegistry registry) {
        registry.add("jmip.resume.storage.directory", () -> storageDirectory.toString());
    }

    private static final String ALICE = "alice@example.com";
    private static final String BOB = "bob@example.com";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID aliceId;

    @BeforeEach
    void seed() {
        jdbcTemplate.execute("TRUNCATE resume_skills, resumes, job_skills, jobs, skills, companies, locations, users "
                + "RESTART IDENTITY CASCADE");
        aliceId = account(ALICE);
        account(BOB);

        jdbcTemplate.update("INSERT INTO companies (id, name) VALUES (1, 'Acme Systems')");
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
    @DisplayName("a new upload belongs to the signed-in account, whatever the request claims")
    void uploadIsLinkedToTheSession() throws Exception {
        String resumeId = upload(ALICE);

        assertThat(jdbcTemplate.queryForObject("SELECT user_id FROM resumes WHERE id = ?::uuid", UUID.class, resumeId))
                .isEqualTo(aliceId);

        // A userId sent by the client is ignored; the session decides.
        UUID bobId = jdbcTemplate.queryForObject("SELECT id FROM users WHERE email = ?", UUID.class, BOB);
        String second = JsonPath.read(mockMvc.perform(multipart("/api/resumes").file(pdf()).param("userId", bobId.toString())
                        .with(as(ALICE)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
        assertThat(jdbcTemplate.queryForObject("SELECT user_id FROM resumes WHERE id = ?::uuid", UUID.class, second))
                .isEqualTo(aliceId);
    }

    @Test
    @DisplayName("the owner can use every resume feature")
    void ownerHasAccess() throws Exception {
        String id = upload(ALICE);

        for (String path : resumePaths(id)) {
            mockMvc.perform(get(path).with(as(ALICE))).andExpect(status().isOk());
        }
        mockMvc.perform(get("/api/resumes/{id}", id).with(as(ALICE)))
                .andExpect(jsonPath("$.skills[*].name").isNotEmpty());
    }

    @Test
    @DisplayName("another account gets 404 on the resume and everything derived from it")
    void otherAccountIsRefused() throws Exception {
        String id = upload(ALICE);

        for (String path : resumePaths(id)) {
            mockMvc.perform(get(path).with(as(BOB)))
                    .andExpect(status().isNotFound())
                    // Indistinguishable from an id that does not exist.
                    .andExpect(jsonPath("$.message").value("Resume not found: " + id));
        }
        mockMvc.perform(get("/api/resumes/{id}", UUID.randomUUID()).with(as(BOB))).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("the assistant cannot be used to read another account's resume")
    void assistantRespectsOwnership() throws Exception {
        String id = upload(ALICE);

        String asBob = mockMvc.perform(post("/api/assistant/query").with(as(BOB)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"how well does my resume match\",\"resumeId\":\"" + id + "\",\"jobId\":1}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String asAlice = mockMvc.perform(post("/api/assistant/query").with(as(ALICE)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"how well does my resume match\",\"resumeId\":\"" + id + "\",\"jobId\":1}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(asAlice).contains("Java");
        assertThat(asBob).doesNotContain("\"Java\"", "Docker", "matchPercentage");
    }

    @Test
    @DisplayName("a resume from before accounts existed is reachable by nobody")
    void unownedResumeIsHidden() throws Exception {
        String id = upload(ALICE);
        jdbcTemplate.update("UPDATE resumes SET user_id = NULL WHERE id = ?::uuid", id);

        mockMvc.perform(get("/api/resumes/{id}", id).with(as(ALICE))).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("without a session every resume route is still a 401")
    void anonymousIsBlocked() throws Exception {
        String id = upload(ALICE);

        for (String path : resumePaths(id)) {
            mockMvc.perform(get(path)).andExpect(status().isUnauthorized());
        }
        mockMvc.perform(multipart("/api/resumes").file(pdf())).andExpect(status().isUnauthorized());
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM resumes", Integer.class)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ helpers

    private static List<String> resumePaths(String id) {
        return List.of(
                "/api/resumes/" + id,
                "/api/resumes/" + id + "/skills",
                "/api/resumes/" + id + "/match/1",
                "/api/resumes/" + id + "/recommendations",
                // No category: it defaults to the top recommendation's. (A pre-encoded query here
                // would be encoded again by MockMvc.)
                "/api/resumes/" + id + "/career-insights");
    }

    private UUID account(String email) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO users (id, full_name, email, password_hash, role, email_verified_at)
                VALUES (?, 'Test', ?, '{bcrypt}not-a-real-hash', 'USER', now())
                """, id, email);
        return id;
    }

    /** Acts as a signed-in account, as its session would. */
    private static RequestPostProcessor as(String email) {
        return user(email).roles("USER");
    }

    private static MockMultipartFile pdf() throws Exception {
        return new MockMultipartFile("file", "cv.pdf", MediaType.APPLICATION_PDF_VALUE,
                PdfFixtures.singlePage(List.of("Backend developer", "Java and Docker in production")));
    }

    private String upload(String email) throws Exception {
        MockHttpServletRequestBuilder request = multipart("/api/resumes").file(pdf()).with(as(email));
        return JsonPath.read(mockMvc.perform(request).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.id");
    }
}
