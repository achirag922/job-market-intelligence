package com.jmip.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The whole resume workflow against a real PostgreSQL: upload, extraction, skill
 * matching, persistence, retrieval and comparison against a job.
 *
 * <p>Files are written to a temporary directory supplied by the test, which proves the
 * storage location really is configuration rather than a hardcoded path.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ResumeIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine");

    @TempDir
    static Path storageDirectory;

    @DynamicPropertySource
    static void resumeStorage(DynamicPropertyRegistry registry) {
        registry.add("jmip.resume.storage.directory", () -> storageDirectory.toString());
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void seed() {
        jdbcTemplate.execute("TRUNCATE resume_skills, resumes, job_skills, jobs, skills, companies, locations "
                + "RESTART IDENTITY CASCADE");

        jdbcTemplate.update("INSERT INTO companies (id, name) VALUES (1, 'Acme Systems')");
        jdbcTemplate.update("INSERT INTO skills (id, name, category) VALUES "
                + "(1, 'Java', 'LANGUAGE'), (2, 'Spring Boot', 'FRAMEWORK'), (3, 'Kafka', 'DATA'), "
                + "(4, 'Docker', 'PLATFORM'), (5, 'Kubernetes', 'PLATFORM'), (6, 'Python', 'LANGUAGE')");

        // The specification's worked example: the job needs five skills.
        insertJob(1, "Senior Backend Engineer");
        jdbcTemplate.update("INSERT INTO job_skills (job_id, skill_id) VALUES (1,1),(1,2),(1,3),(1,4),(1,5)");

        // A posting with no skills at all, for the divide-by-zero case.
        insertJob(2, "Office Manager");
    }

    @Test
    @DisplayName("uploading a PDF stores it, extracts its skills and reports them")
    void uploadExtractsSkills() throws Exception {
        String body = upload(resumePdf()).getResponse().getContentAsString();
        JsonNode resume = objectMapper.readTree(body);

        assertThat(resume.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(resume.get("fileName").asText()).isEqualTo("jane-developer.pdf");
        assertThat(resume.get("fileSizeBytes").asLong()).isPositive();
        // Present on the upload response itself, not only on a later read.
        assertThat(resume.hasNonNull("uploadedAt")).isTrue();
        assertThat(skillNames(resume.get("skills")))
                .containsExactlyInAnyOrder("Java", "Spring Boot", "Docker");
    }

    @Test
    @DisplayName("the upload is persisted, with its skills linked to the shared skills table")
    void uploadIsPersisted() throws Exception {
        UUID resumeId = uploadAndGetId();

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM resumes", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT processing_status FROM resumes WHERE id = ?", String.class, resumeId))
                .isEqualTo("COMPLETED");
        // The resume points at the same skill rows the jobs use, which is what makes the
        // comparison an id comparison rather than string matching.
        assertThat(jdbcTemplate.queryForList(
                "SELECT s.name FROM resume_skills rs JOIN skills s ON s.id = rs.skill_id "
                        + "WHERE rs.resume_id = ? ORDER BY s.name", String.class, resumeId))
                .containsExactly("Docker", "Java", "Spring Boot");
    }

    @Test
    @DisplayName("the stored file is written into the configured directory, named by id")
    void fileIsStoredUnderTheConfiguredDirectory() throws Exception {
        UUID resumeId = uploadAndGetId();

        Path stored = storageDirectory.resolve(resumeId + ".pdf");
        assertThat(Files.exists(stored)).isTrue();
        // The uploaded name never reaches the filesystem.
        assertThat(Files.list(storageDirectory).map(path -> path.getFileName().toString()).toList())
                .doesNotContain("jane-developer.pdf");
    }

    @Test
    @DisplayName("the API never exposes the stored path or the extracted text")
    void apiHidesStorageDetail() throws Exception {
        UUID resumeId = uploadAndGetId();

        String body = mockMvc.perform(get("/api/resumes/{id}", resumeId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body)
                .doesNotContain("storedFileName")
                .doesNotContain("storagePath")
                .doesNotContain("extractedText")
                .doesNotContain(storageDirectory.toString());
    }

    @Test
    @DisplayName("a resume can be fetched, and an unknown id is a 404")
    void fetchResume() throws Exception {
        UUID resumeId = uploadAndGetId();

        mockMvc.perform(get("/api/resumes/{id}", resumeId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(resumeId.toString()))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.uploadedAt").exists());

        mockMvc.perform(get("/api/resumes/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    @DisplayName("the skills endpoint returns the extracted skills with a count")
    void fetchSkills() throws Exception {
        UUID resumeId = uploadAndGetId();

        mockMvc.perform(get("/api/resumes/{id}/skills", resumeId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.skillCount").value(3))
                .andExpect(jsonPath("$.skills[0].name").value("Docker"));
    }

    @Test
    @DisplayName("matching a resume against a job reports the overlap and the gap")
    void matchAgainstJob() throws Exception {
        UUID resumeId = uploadAndGetId();

        // The job needs Java, Spring Boot, Kafka, Docker and Kubernetes. The resume shows
        // Java, Spring Boot and Docker, so three of five match: 60%.
        mockMvc.perform(get("/api/resumes/{resumeId}/match/{jobId}", resumeId, 1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobTitle").value("Senior Backend Engineer"))
                .andExpect(jsonPath("$.companyName").value("Acme Systems"))
                .andExpect(jsonPath("$.totalJobSkills").value(5))
                .andExpect(jsonPath("$.matchedSkillCount").value(3))
                .andExpect(jsonPath("$.missingSkillCount").value(2))
                .andExpect(jsonPath("$.matchPercentage").value(60.0))
                .andExpect(jsonPath("$.matchedSkills[*].name")
                        .value(org.hamcrest.Matchers.containsInAnyOrder("Docker", "Java", "Spring Boot")))
                .andExpect(jsonPath("$.missingSkills[*].name")
                        .value(org.hamcrest.Matchers.containsInAnyOrder("Kafka", "Kubernetes")));
    }

    @Test
    @DisplayName("skills the resume has that the job does not ask for are reported separately")
    void reportsResumeOnlySkills() throws Exception {
        UUID resumeId = uploadAndGetId(resumePdfWith(List.of("Java", "Python")));

        mockMvc.perform(get("/api/resumes/{resumeId}/match/{jobId}", resumeId, 1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resumeOnlySkills[*].name")
                        .value(org.hamcrest.Matchers.contains("Python")));
    }

    @Test
    @DisplayName("a job with no skills yields no percentage rather than a zero or an error")
    void jobWithoutSkills() throws Exception {
        UUID resumeId = uploadAndGetId();

        mockMvc.perform(get("/api/resumes/{resumeId}/match/{jobId}", resumeId, 2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalJobSkills").value(0))
                .andExpect(jsonPath("$.matchPercentage").doesNotExist())
                .andExpect(jsonPath("$.matchNote").value(
                        org.hamcrest.Matchers.containsString("lists no skills")));
    }

    @Test
    @DisplayName("matching against an unknown job is a 404")
    void matchAgainstUnknownJob() throws Exception {
        UUID resumeId = uploadAndGetId();

        mockMvc.perform(get("/api/resumes/{resumeId}/match/{jobId}", resumeId, 9999))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Job not found: 9999"));
    }

    @Test
    @DisplayName("an unreadable PDF is recorded as FAILED with a reason, not lost")
    void unreadablePdfIsRecordedAsFailed() throws Exception {
        MockMultipartFile notAPdf = new MockMultipartFile(
                "file", "broken.pdf", MediaType.APPLICATION_PDF_VALUE,
                "this is not a PDF at all".getBytes(StandardCharsets.UTF_8));

        String body = upload(notAPdf).getResponse().getContentAsString();
        JsonNode resume = objectMapper.readTree(body);

        // The upload itself succeeded, so the failure is visible rather than a 500.
        assertThat(resume.get("status").asText()).isEqualTo("FAILED");
        assertThat(resume.get("errorMessage").asText()).contains("could not be read as a PDF");
        assertThat(resume.get("skills")).isEmpty();
    }

    @Test
    @DisplayName("a failed resume cannot be matched, and says why")
    void failedResumeCannotBeMatched() throws Exception {
        MockMultipartFile notAPdf = new MockMultipartFile(
                "file", "broken.pdf", MediaType.APPLICATION_PDF_VALUE,
                "not a PDF".getBytes(StandardCharsets.UTF_8));
        UUID resumeId = UUID.fromString(objectMapper.readTree(
                upload(notAPdf).getResponse().getContentAsString()).get("id").asText());

        mockMvc.perform(get("/api/resumes/{resumeId}/match/{jobId}", resumeId, 1))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("processing failed")));

        mockMvc.perform(get("/api/resumes/{id}/skills", resumeId))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("an empty file is rejected and nothing is stored")
    void rejectsEmptyFile() throws Exception {
        MockMultipartFile empty = new MockMultipartFile(
                "file", "empty.pdf", MediaType.APPLICATION_PDF_VALUE, new byte[0]);

        mockMvc.perform(multipart("/api/resumes").file(empty))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("empty")));

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM resumes", Integer.class)).isZero();
    }

    @Test
    @DisplayName("a file that is not a PDF is rejected by content type")
    void rejectsWrongContentType() throws Exception {
        MockMultipartFile word = new MockMultipartFile(
                "file", "resume.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "content".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/api/resumes").file(word))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("accepted")));

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM resumes", Integer.class)).isZero();
    }

    @Test
    @DisplayName("a request with no file part is a 400, not a server error")
    void rejectsMissingFilePart() throws Exception {
        mockMvc.perform(multipart("/api/resumes"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    @DisplayName("a resume with no recognisable skills still completes, with none listed")
    void resumeWithNoKnownSkills() throws Exception {
        UUID resumeId = uploadAndGetId(resumePdfWith(List.of("Enthusiastic team player", "Good communicator")));

        mockMvc.perform(get("/api/resumes/{id}", resumeId))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.skills").isEmpty());

        // Matching still works; everything the job wants is simply missing.
        mockMvc.perform(get("/api/resumes/{resumeId}/match/{jobId}", resumeId, 1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matchPercentage").value(0.0))
                .andExpect(jsonPath("$.missingSkillCount").value(5));
    }

    @Test
    @DisplayName("existing job endpoints still behave as before")
    void existingEndpointsStillWork() throws Exception {
        mockMvc.perform(get("/api/jobs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));
        mockMvc.perform(get("/api/analytics/overview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalJobs").value(2));
    }

    // ------------------------------------------------------------------ helpers

    private org.springframework.test.web.servlet.MvcResult upload(MockMultipartFile file) throws Exception {
        return mockMvc.perform(multipart("/api/resumes").file(file))
                .andExpect(status().isCreated())
                .andReturn();
    }

    private UUID uploadAndGetId() throws Exception {
        return uploadAndGetId(resumePdf());
    }

    private UUID uploadAndGetId(MockMultipartFile file) throws Exception {
        String body = upload(file).getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(body).get("id").asText());
    }

    private static MockMultipartFile resumePdf() throws IOException {
        return resumePdfWith(List.of("Java", "Spring Boot", "Docker"));
    }

    private static MockMultipartFile resumePdfWith(List<String> lines) throws IOException {
        byte[] pdf = PdfFixtures.pdf(List.of(
                java.util.stream.Stream.concat(
                        java.util.stream.Stream.of("Jane Developer", "Skills:"),
                        lines.stream()).toList()));
        return new MockMultipartFile("file", "jane-developer.pdf", MediaType.APPLICATION_PDF_VALUE, pdf);
    }

    private static List<String> skillNames(JsonNode skills) {
        return java.util.stream.StreamSupport.stream(skills.spliterator(), false)
                .map(skill -> skill.get("name").asText())
                .toList();
    }

    private void insertJob(long id, String title) {
        jdbcTemplate.update("""
                        INSERT INTO jobs (id, title, company_id, description, source, source_url,
                                          content_fingerprint)
                        VALUES (?, ?, 1, 'Description', 'itest', ?, ?)
                        """,
                id, title, "https://example.invalid/jobs/" + id, String.format("%064d", id));
    }
}
