package com.jmip.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jmip.testsupport.PdfFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * V6.9 hardening, end to end through the real filters, validation and exception handling.
 * The rate limit itself is covered by RequestLimitFilterTest; tests run with it switched off.
 */
@SpringBootTest(properties = "jmip.ai.provider=stub")
@AutoConfigureMockMvc
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
// V6.10.3: the API requires a signed-in USER; these tests exercise behaviour behind that.
@WithMockUser(roles = "USER")
class SecurityHardeningIntegrationTest {

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
    void clear() throws Exception {
        jdbcTemplate.execute("TRUNCATE resume_skills, resumes RESTART IDENTITY CASCADE");
        try (var files = Files.list(storageDirectory)) {
            for (Path file : files.toList()) {
                Files.delete(file);
            }
        }
    }

    // ------------------------------------------------------------------ headers and CORS

    @Test
    @DisplayName("every API response carries the security headers")
    void securityHeaders() throws Exception {
        mockMvc.perform(get("/api/jobs"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string("Content-Security-Policy", containsString("default-src 'none'")))
                // Plain HTTP: HSTS would be meaningless and is not sent.
                .andExpect(header().doesNotExist("Strict-Transport-Security"));
    }

    @Test
    @DisplayName("resume responses are never cached")
    void resumeResponsesNotCached() throws Exception {
        mockMvc.perform(get("/api/resumes/{id}", "00000000-0000-0000-0000-000000000000"))
                .andExpect(status().isNotFound())
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    @DisplayName("CORS admits only configured origins and only the headers the frontend sends")
    void corsIsRestricted() throws Exception {
        mockMvc.perform(options("/api/assistant/query")
                        .header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"))
                // Credentials are allowed for exact origins only, so the session cookie can travel.
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"));

        mockMvc.perform(options("/api/assistant/query")
                        .header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "x-api-key"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/assistant/query")
                        .header("Origin", "https://evil.example")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"top skills\"}"))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------ uploads

    @Test
    @DisplayName("an executable renamed to .pdf with a PDF content type is refused and never stored")
    void executableDisguisedAsPdf() throws Exception {
        byte[] windowsExecutable = "MZ\u0090\u0000 this program cannot be run in DOS mode".getBytes(StandardCharsets.ISO_8859_1);

        mockMvc.perform(multipart("/api/resumes").file(
                        new MockMultipartFile("file", "resume.pdf", MediaType.APPLICATION_PDF_VALUE, windowsExecutable)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("The file is not a PDF document"));

        assertThat(countResumes()).isZero();
        assertThat(storedFiles()).isEmpty();
    }

    @Test
    @DisplayName("HTML and scripts are refused whatever they are called")
    void otherFileTypes() throws Exception {
        mockMvc.perform(multipart("/api/resumes").file(new MockMultipartFile("file", "resume.pdf",
                        MediaType.APPLICATION_PDF_VALUE, "<html><script>alert(1)</script>".getBytes())))
                .andExpect(status().isBadRequest());
        mockMvc.perform(multipart("/api/resumes").file(new MockMultipartFile("file", "run.sh",
                        "application/x-sh", "#!/bin/sh\nrm -rf /".getBytes())))
                .andExpect(status().isBadRequest());

        assertThat(countResumes()).isZero();
    }

    @Test
    @DisplayName("an oversized upload is refused and nothing is stored")
    void oversizedUpload() throws Exception {
        byte[] large = new byte[5 * 1024 * 1024 + 1];
        System.arraycopy("%PDF-1.7".getBytes(), 0, large, 0, 8);

        mockMvc.perform(multipart("/api/resumes").file(
                        new MockMultipartFile("file", "big.pdf", MediaType.APPLICATION_PDF_VALUE, large)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("exceeds")));

        assertThat(countResumes()).isZero();
    }

    @Test
    @DisplayName("a path-traversal filename cannot choose where the file is written or what it is called")
    void pathTraversalFilename() throws Exception {
        String body = mockMvc.perform(multipart("/api/resumes").file(new MockMultipartFile("file",
                        "../../../etc/cron.d/evil\r\nINJECTED.pdf", MediaType.APPLICATION_PDF_VALUE,
                        PdfFixtures.singlePage(List.of("Java developer")))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        JsonNode resume = objectMapper.readTree(body);

        String displayName = resume.get("fileName").asText();
        assertThat(displayName).doesNotContain("/", "\\", "\r", "\n");
        // The only file written is <uuid>.pdf, inside the storage directory.
        assertThat(storedFiles()).containsExactly(resume.get("id").asText() + ".pdf");
        assertThat(Files.exists(storageDirectory.resolve("../../../etc/cron.d").normalize()
                .resolve("evil"))).isFalse();
    }

    @Test
    @DisplayName("neither the uploaded file name nor the resume text reaches the logs")
    void uploadLogsNoPersonalData(CapturedOutput output) throws Exception {
        mockMvc.perform(multipart("/api/resumes").file(new MockMultipartFile("file", "jane-q-public-cv.pdf",
                        MediaType.APPLICATION_PDF_VALUE,
                        PdfFixtures.singlePage(List.of("Jane Q Public", "jane.public@example.com", "Java")))))
                .andExpect(status().isCreated());

        assertThat(output.getAll()).doesNotContain("jane-q-public-cv", "jane.public@example.com", "Jane Q Public");
    }

    // ------------------------------------------------------------------ assistant

    @Test
    @DisplayName("assistant input is validated: blank, too long and malformed questions are 400s")
    void assistantValidation() throws Exception {
        mockMvc.perform(post("/api/assistant/query").contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("question"));

        mockMvc.perform(post("/api/assistant/query").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"" + "a".repeat(2001) + "\"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/assistant/query").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"x\",\"jobId\":-5}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("jobId"));
    }

    @Test
    @DisplayName("an oversized assistant body is refused before it is parsed")
    void assistantBodyTooLarge() throws Exception {
        mockMvc.perform(post("/api/assistant/query").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"" + "a".repeat(20_000) + "\"}"))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.status").value(413));
    }

    @Test
    @DisplayName("SQL in a question is only ever text: it is answered, not executed")
    void sqlInQuestionIsInert() throws Exception {
        jdbcTemplate.update("INSERT INTO companies (name) VALUES ('Canary Co') ON CONFLICT DO NOTHING");

        mockMvc.perform(post("/api/assistant/query").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"top skills'; DROP TABLE companies; --\"}"))
                .andExpect(status().isOk());

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM companies WHERE name = 'Canary Co'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("the question text is not logged")
    void questionNotLogged(CapturedOutput output) throws Exception {
        mockMvc.perform(post("/api/assistant/query").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"which skills does canary-question-7731 need\"}"))
                .andExpect(status().isOk());

        assertThat(output.getAll()).doesNotContain("canary-question-7731");
    }

    // ------------------------------------------------------------------ error bodies

    @Test
    @DisplayName("malformed JSON is a 400 with a plain message, never parser internals or a trace")
    void malformedJson(CapturedOutput output) throws Exception {
        String body = mockMvc.perform(post("/api/assistant/query").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\": "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("The request body is missing or is not valid JSON"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("Exception", "jackson", "com.jmip", "at ");
        // A client mistake is not a server error, so nothing is logged at error level.
        assertThat(output.getAll()).doesNotContain("Unhandled exception");
    }

    @Test
    @DisplayName("unsupported media types and bad parameters get structured errors without internals")
    void otherClientErrors() throws Exception {
        String unsupported = mockMvc.perform(post("/api/assistant/query").contentType(MediaType.TEXT_PLAIN).content("hi"))
                .andExpect(status().isUnsupportedMediaType())
                .andReturn().getResponse().getContentAsString();
        assertThat(unsupported).contains("\"status\":415").doesNotContain("Exception", "at org.");

        String badId = mockMvc.perform(get("/api/resumes/{id}", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();
        assertThat(badId).doesNotContain("Exception", "java.util.UUID");
    }

    private int countResumes() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM resumes", Integer.class);
    }

    private List<String> storedFiles() throws Exception {
        try (var files = Files.list(storageDirectory)) {
            return files.map(path -> path.getFileName().toString()).toList();
        }
    }
}
