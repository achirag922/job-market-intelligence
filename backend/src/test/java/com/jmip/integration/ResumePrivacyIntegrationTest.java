package com.jmip.integration;

import com.jayway.jsonpath.JsonPath;
import com.jmip.config.ResumePrivacyProperties;
import com.jmip.service.resume.ResumeRetentionJob;
import com.jmip.service.resume.ResumeService;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * V6.10.5: deleting a resume, retention, and encryption at rest, with a key configured.
 */
@SpringBootTest(properties = "jmip.ai.provider=stub")
@AutoConfigureMockMvc
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
class ResumePrivacyIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine");

    @TempDir
    static Path storageDirectory;

    private static final String KEY = Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes());

    @DynamicPropertySource
    static void settings(DynamicPropertyRegistry registry) {
        registry.add("jmip.resume.storage.directory", () -> storageDirectory.toString());
        registry.add("jmip.resume.privacy.encryption-key", () -> KEY);
    }

    private static final String ALICE = "alice@example.com";
    private static final String BOB = "bob@example.com";
    private static final String RESUME_TEXT = "Jane Q Public — senior Java developer";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ResumeService resumeService;

    @BeforeEach
    void seed() throws Exception {
        jdbcTemplate.execute("TRUNCATE resume_skills, resumes, skills, users RESTART IDENTITY CASCADE");
        try (var files = Files.list(storageDirectory)) {
            for (Path file : files.toList()) {
                Files.delete(file);
            }
        }
        account(ALICE);
        account(BOB);
        jdbcTemplate.update("INSERT INTO skills (id, name, category) VALUES (1, 'Java', 'LANGUAGE')");
    }

    // ------------------------------------------------------------------ deletion

    @Test
    @DisplayName("the owner deletes a resume: the row, its skills and its file are all gone")
    void ownerDeletes() throws Exception {
        String id = upload(ALICE);
        assertThat(storedFile(id)).exists();
        assertThat(count("resume_skills")).isPositive();

        mockMvc.perform(delete("/api/resumes/{id}", id).with(as(ALICE)))
                .andExpect(status().isNoContent())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString()).isEmpty());

        assertThat(count("resumes")).isZero();
        assertThat(count("resume_skills")).isZero();
        assertThat(storedFile(id)).doesNotExist();
        mockMvc.perform(get("/api/resumes/{id}", id).with(as(ALICE))).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("another account cannot delete it: 404, and nothing is removed")
    void otherAccountCannotDelete() throws Exception {
        String id = upload(ALICE);

        mockMvc.perform(delete("/api/resumes/{id}", id).with(as(BOB)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Resume not found: " + id));

        assertThat(count("resumes")).isEqualTo(1);
        assertThat(storedFile(id)).exists();
        mockMvc.perform(get("/api/resumes/{id}", id).with(as(ALICE))).andExpect(status().isOk());
    }

    @Test
    @DisplayName("missing and already deleted resumes are a plain 404; a malformed id is a 400; no session is a 401")
    void missingAndInvalid() throws Exception {
        String id = upload(ALICE);
        mockMvc.perform(delete("/api/resumes/{id}", id).with(as(ALICE))).andExpect(status().isNoContent());

        mockMvc.perform(delete("/api/resumes/{id}", id).with(as(ALICE))).andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/resumes/{id}", UUID.randomUUID()).with(as(ALICE))).andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/resumes/{id}", "not-a-uuid").with(as(ALICE))).andExpect(status().isBadRequest());
        mockMvc.perform(delete("/api/resumes/{id}", UUID.randomUUID())).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a file that is already gone does not stop the resume from being deleted")
    void missingFileIsTolerated() throws Exception {
        String id = upload(ALICE);
        Files.delete(storedFile(id));

        mockMvc.perform(delete("/api/resumes/{id}", id).with(as(ALICE))).andExpect(status().isNoContent());
        assertThat(count("resumes")).isZero();
    }

    // ------------------------------------------------------------------ encryption at rest

    @Test
    @DisplayName("the stored file and the extracted text are encrypted, and the API still reads them")
    void encryptedAtRest() throws Exception {
        String id = upload(ALICE);

        String onDisk = new String(Files.readAllBytes(storedFile(id)), StandardCharsets.ISO_8859_1);
        assertThat(onDisk).startsWith("JMIPENC1").doesNotContain("%PDF", "Jane", "Java developer");

        String column = jdbcTemplate.queryForObject("SELECT extracted_text FROM resumes", String.class);
        assertThat(column).startsWith("enc:v1:").doesNotContain("Jane", "Java");

        mockMvc.perform(get("/api/resumes/{id}/skills", id).with(as(ALICE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.skills[0].name").value("Java"));
    }

    // ------------------------------------------------------------------ retention

    @Test
    @DisplayName("retention deletes only resumes older than the period, with their files; off by default")
    void retention() throws Exception {
        String old = upload(ALICE);
        String recent = upload(ALICE);
        jdbcTemplate.update("UPDATE resumes SET uploaded_at = now() - interval '400 days' WHERE id = ?::uuid", old);

        ResumePrivacyProperties off = new ResumePrivacyProperties(KEY, Duration.ZERO, "0 30 3 * * *");
        assertThat(new ResumeRetentionJob(resumeService, off, Clock.systemUTC()).sweep()).isZero();
        assertThat(count("resumes")).isEqualTo(2);

        ResumePrivacyProperties yearly = new ResumePrivacyProperties(KEY, Duration.ofDays(365), "0 30 3 * * *");
        assertThat(new ResumeRetentionJob(resumeService, yearly, Clock.systemUTC()).sweep()).isEqualTo(1);

        assertThat(jdbcTemplate.queryForList("SELECT id::text FROM resumes", String.class)).containsExactly(recent);
        assertThat(storedFile(old)).doesNotExist();
        assertThat(storedFile(recent)).exists();
    }

    // ------------------------------------------------------------------ exposure

    @Test
    @DisplayName("no resume text, file name or storage path reaches responses or logs")
    void nothingSensitiveExposed(CapturedOutput output) throws Exception {
        String id = upload(ALICE);
        String body = mockMvc.perform(get("/api/resumes/{id}", id).with(as(ALICE)))
                .andReturn().getResponse().getContentAsString();
        mockMvc.perform(delete("/api/resumes/{id}", id).with(as(ALICE))).andExpect(status().isNoContent());

        assertThat(body).doesNotContain("Jane", "extractedText", "storedFileName", storageDirectory.toString());
        assertThat(output.getAll()).doesNotContain("Jane Q Public", "jane-cv.pdf", storageDirectory.toString(), KEY);
    }

    // ------------------------------------------------------------------ helpers

    private void account(String email) {
        jdbcTemplate.update("""
                INSERT INTO users (id, full_name, email, password_hash, role, email_verified_at)
                VALUES (?, 'Test', ?, '{bcrypt}not-a-real-hash', 'USER', now())
                """, UUID.randomUUID(), email);
    }

    private static RequestPostProcessor as(String email) {
        return user(email).roles("USER");
    }

    private String upload(String email) throws Exception {
        MockMultipartFile pdf = new MockMultipartFile("file", "jane-cv.pdf", MediaType.APPLICATION_PDF_VALUE,
                PdfFixtures.singlePage(List.of(RESUME_TEXT)));
        return JsonPath.read(mockMvc.perform(multipart("/api/resumes").file(pdf).with(as(email)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
    }

    private Path storedFile(String id) {
        return storageDirectory.resolve(id + ".pdf");
    }

    private int count(String table) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }
}
