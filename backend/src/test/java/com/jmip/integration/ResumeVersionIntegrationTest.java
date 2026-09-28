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
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** V7.3: resume versions, job-specific analysis and version comparison, end to end. */
@SpringBootTest(properties = "jmip.ai.provider=stub")
@AutoConfigureMockMvc
@Testcontainers
class ResumeVersionIntegrationTest {

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
        jdbcTemplate.update("""
                INSERT INTO skills (id, name, category)
                VALUES (1, 'Java', 'LANGUAGE'), (2, 'Docker', 'PLATFORM'), (3, 'Kubernetes', 'PLATFORM'), (4, 'Python', 'LANGUAGE')
                """);
        job(1, "Platform Engineer", 3, 5);
        jdbcTemplate.update("INSERT INTO job_skills (job_id, skill_id) VALUES (1, 1), (1, 2), (1, 3)");
        job(2, "Generalist", null, null);
    }

    @Test
    @DisplayName("an account keeps several resumes; the first is the default, titles start from the file name")
    void multipleResumes() throws Exception {
        String first = upload(ALICE, "Backend CV.pdf", "Java and Docker in production");
        String second = upload(ALICE, "Platform CV.pdf", "Java, Kubernetes and Python");

        mockMvc.perform(get("/api/resumes").with(as(ALICE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].id").value(second))
                .andExpect(jsonPath("$[0].title").value("Platform CV"))
                .andExpect(jsonPath("$[0].isDefault").value(false))
                .andExpect(jsonPath("$[1].id").value(first))
                .andExpect(jsonPath("$[1].isDefault").value(true))
                .andExpect(jsonPath("$[1].skills[*].name", containsInAnyOrder("Docker", "Java")))
                // Internal storage details and the document text never leave the server.
                .andExpect(jsonPath("$[0].storedFileName").doesNotExist())
                .andExpect(jsonPath("$[0].extractedText").doesNotExist());

        // The existing single-resume endpoints still work, and carry the new metadata.
        mockMvc.perform(get("/api/resumes/" + first).with(as(ALICE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.title").value("Backend CV"))
                .andExpect(jsonPath("$.updatedAt").exists());
        mockMvc.perform(get("/api/resumes/" + first + "/skills").with(as(ALICE))).andExpect(status().isOk());
        mockMvc.perform(get("/api/resumes/" + first + "/match/1").with(as(ALICE))).andExpect(status().isOk());
    }

    @Test
    @DisplayName("title and version label can be changed and are validated")
    void renameAndRelabel() throws Exception {
        String id = upload(ALICE, "cv.pdf", "Java and Docker in production");

        mockMvc.perform(json(patch("/api/resumes/" + id), "{\"title\":\"  Backend focus \",\"versionLabel\":\"v2\"}").with(as(ALICE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Backend focus"))
                .andExpect(jsonPath("$.versionLabel").value("v2"));
        mockMvc.perform(json(patch("/api/resumes/" + id), "{\"title\":\"Backend focus\",\"versionLabel\":\" \"}").with(as(ALICE)))
                .andExpect(jsonPath("$.versionLabel").doesNotExist());

        for (String bad : List.of("{\"title\":\"  \"}", "{\"versionLabel\":\"v3\"}",
                "{\"title\":\"" + "t".repeat(101) + "\"}", "{\"title\":\"ok\",\"versionLabel\":\"" + "v".repeat(51) + "\"}")) {
            mockMvc.perform(json(patch("/api/resumes/" + id), bad).with(as(ALICE))).andExpect(status().isBadRequest());
        }
    }

    @Test
    @DisplayName("choosing a default leaves exactly one; deleting the default hands it to the newest remaining")
    void defaultResume() throws Exception {
        String first = upload(ALICE, "a.pdf", "Java and Docker in production");
        String second = upload(ALICE, "b.pdf", "Java, Kubernetes and Python");
        String third = upload(ALICE, "c.pdf", "Python only");

        mockMvc.perform(put("/api/resumes/" + second + "/default").with(as(ALICE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isDefault").value(true));
        assertThat(defaultIds()).containsExactly(UUID.fromString(second));
        mockMvc.perform(put("/api/resumes/" + second + "/default").with(as(ALICE))).andExpect(status().isOk());
        assertThat(defaultIds()).containsExactly(UUID.fromString(second));

        // The existing V6.10.5 delete, unchanged for the caller.
        mockMvc.perform(delete("/api/resumes/" + second).with(as(ALICE))).andExpect(status().isNoContent());
        assertThat(defaultIds()).containsExactly(UUID.fromString(third));
        mockMvc.perform(get("/api/resumes").with(as(ALICE))).andExpect(jsonPath("$", hasSize(2)));
        assertThat(first).isNotBlank();
    }

    @Test
    @DisplayName("job analysis reuses the V3 match and adds experience and data-based suggestions")
    void analyzeJob() throws Exception {
        String id = upload(ALICE, "Backend CV.pdf", "Java and Docker in production");
        Double v3 = JsonPath.read(mockMvc.perform(get("/api/resumes/" + id + "/match/1").with(as(ALICE)))
                .andReturn().getResponse().getContentAsString(), "$.matchPercentage");

        mockMvc.perform(get("/api/resumes/" + id + "/analyze-job/1").with(as(ALICE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resumeTitle").value("Backend CV"))
                .andExpect(jsonPath("$.jobTitle").value("Platform Engineer"))
                .andExpect(jsonPath("$.matchPercentage").value(v3))
                .andExpect(jsonPath("$.matchPercentage").value(66.7))
                .andExpect(jsonPath("$.matchedSkills[*].name", containsInAnyOrder("Java", "Docker")))
                .andExpect(jsonPath("$.missingSkills[*].name", containsInAnyOrder("Kubernetes")))
                .andExpect(jsonPath("$.otherResumeSkills", hasSize(0)))
                .andExpect(jsonPath("$.experience.required.min").value(3))
                .andExpect(jsonPath("$.experience.required.max").value(5))
                .andExpect(jsonPath("$.suggestions", hasItem(containsString("Kubernetes"))))
                .andExpect(jsonPath("$.suggestions", hasItem(containsString("3–5 years"))))
                .andExpect(jsonPath("$.disclaimer", containsString("not a prediction")))
                .andExpect(jsonPath("$.suggestions[*]", not(hasItem(containsString("chance")))));

        mockMvc.perform(get("/api/resumes/" + id + "/analyze-job/2").with(as(ALICE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matchPercentage").doesNotExist())
                .andExpect(jsonPath("$.experience.required").doesNotExist())
                .andExpect(jsonPath("$.suggestions[0]", containsString("lists no skills")));
    }

    @Test
    @DisplayName("unknown or invalid resume and job ids are refused")
    void invalidIds() throws Exception {
        String id = upload(ALICE, "cv.pdf", "Java and Docker in production");

        mockMvc.perform(get("/api/resumes/" + id + "/analyze-job/999").with(as(ALICE))).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/resumes/" + UUID.randomUUID() + "/analyze-job/1").with(as(ALICE))).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/resumes/" + id + "/analyze-job/0").with(as(ALICE))).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/resumes/not-a-uuid/analyze-job/1").with(as(ALICE))).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/resumes/compare").param("resumeId1", id).param("resumeId2", id).with(as(ALICE)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/resumes/compare").param("resumeId1", id).with(as(ALICE))).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/resumes/compare").param("resumeId1", id).param("resumeId2", UUID.randomUUID().toString())
                .with(as(ALICE))).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("comparison shows skills added, removed and shared, and which metadata differs")
    void compareVersions() throws Exception {
        String first = upload(ALICE, "Backend CV.pdf", "Java and Docker in production");
        String second = upload(ALICE, "Platform CV.pdf", "Java, Kubernetes and Python");

        mockMvc.perform(get("/api/resumes/compare").param("resumeId1", first).param("resumeId2", second).with(as(ALICE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.first.id").value(first))
                .andExpect(jsonPath("$.second.title").value("Platform CV"))
                .andExpect(jsonPath("$.skillsAdded[*].name", containsInAnyOrder("Kubernetes", "Python")))
                .andExpect(jsonPath("$.skillsRemoved[*].name", containsInAnyOrder("Docker")))
                .andExpect(jsonPath("$.commonSkills[*].name", containsInAnyOrder("Java")))
                .andExpect(jsonPath("$.differentFields", hasItem("title")))
                .andExpect(jsonPath("$.differentFields", hasItem("isDefault")))
                .andExpect(jsonPath("$.differentFields", hasItem("skillCount")));
    }

    @Test
    @DisplayName("another account's resume is a 404 for listing, renaming, default, analysis and comparison")
    void crossAccount() throws Exception {
        String alices = upload(ALICE, "cv.pdf", "Java and Docker in production");
        String bobs = upload(BOB, "bob.pdf", "Java, Kubernetes and Python");

        mockMvc.perform(get("/api/resumes").with(as(BOB)))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(bobs));
        mockMvc.perform(json(patch("/api/resumes/" + alices), "{\"title\":\"Taken\"}").with(as(BOB))).andExpect(status().isNotFound());
        mockMvc.perform(put("/api/resumes/" + alices + "/default").with(as(BOB))).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/resumes/" + alices + "/analyze-job/1").with(as(BOB))).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/resumes/compare").param("resumeId1", bobs).param("resumeId2", alices).with(as(BOB)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/resumes/compare").param("resumeId1", alices).param("resumeId2", bobs).with(as(BOB)))
                .andExpect(status().isNotFound());

        // Bob's own default is untouched by his attempts, and Alice's resume is unchanged.
        mockMvc.perform(get("/api/resumes/" + alices).with(as(ALICE)))
                .andExpect(jsonPath("$.title").value("cv"))
                .andExpect(jsonPath("$.isDefault").value(true));
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM resumes WHERE is_default", Long.class)).isEqualTo(2);
        mockMvc.perform(get("/api/resumes")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("an account can keep at most 20 resumes")
    void perAccountLimit() throws Exception {
        for (int i = 0; i < 20; i++) {
            jdbcTemplate.update("""
                    INSERT INTO resumes (id, user_id, original_file_name, stored_file_name, content_type, file_size_bytes,
                                         processing_status, title, updated_at)
                    VALUES (?, ?, 'old.pdf', ?, 'application/pdf', 10, 'UPLOADED', 'Old', now())
                    """, UUID.randomUUID(), aliceId, "old-" + i + ".pdf");
        }

        mockMvc.perform(multipart("/api/resumes").file(pdf("new.pdf", "Java")).with(as(ALICE)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("at most 20")));
        upload(BOB, "bob.pdf", "Java");
    }

    // ------------------------------------------------------------------ helpers

    private void job(long id, String title, Integer experienceMin, Integer experienceMax) {
        jdbcTemplate.update("""
                INSERT INTO jobs (id, title, company_id, description, source, source_url, content_fingerprint,
                                  experience_min, experience_max, job_category, classification_confidence, classified_at)
                VALUES (?, ?, 1, 'posting', 'itest', ?, ?, ?, ?, 'Backend Developer', 0.9, now())
                """, id, title, "https://example.invalid/" + id, String.format("%064d", id), experienceMin, experienceMax);
    }

    private List<UUID> defaultIds() {
        return jdbcTemplate.queryForList("SELECT id FROM resumes WHERE user_id = ? AND is_default", UUID.class, aliceId);
    }

    private String upload(String email, String fileName, String text) throws Exception {
        MockHttpServletRequestBuilder request = multipart("/api/resumes").file(pdf(fileName, text)).with(as(email));
        return JsonPath.read(mockMvc.perform(request).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.id");
    }

    private static MockMultipartFile pdf(String fileName, String text) throws Exception {
        return new MockMultipartFile("file", fileName, MediaType.APPLICATION_PDF_VALUE, PdfFixtures.singlePage(List.of(text)));
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private UUID account(String email) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO users (id, full_name, email, password_hash, role, email_verified_at)
                VALUES (?, 'Test', ?, '{bcrypt}not-a-real-hash', 'USER', now())
                """, id, email);
        return id;
    }

    private static RequestPostProcessor as(String email) {
        return user(email).roles("USER");
    }
}
