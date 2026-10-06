package com.jmip.integration;

import com.jayway.jsonpath.JsonPath;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** V9.4: resumes written in JMIP, managed and analysed like uploaded ones, exported as PDF. */
@SpringBootTest(properties = {"jmip.ai.provider=stub", "jmip.alerts.enabled=false"})
@AutoConfigureMockMvc
@Testcontainers
class ResumeBuilderIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine");

    private static final String ALICE = "alice@example.test";
    private static final String BOB = "bob@example.test";

    private static final String CONTENT = """
            {"template": "CLASSIC",
             "personal": {"fullName": "José Müller", "headline": "Backend Developer", "email": "jose@example.test",
                          "phone": "+49 30 1234", "location": "Berlin", "links": ["https://github.com/jose"]},
             "summary": "Backend developer building Java services. 🚀",
             "skills": ["Java", "PostgreSQL"],
             "experience": [{"title": "Software Engineer", "company": "Acme", "location": "Berlin", "start": "2021",
                             "current": true, "bullets": ["Built Java APIs for payments."]}],
             "education": [{"degree": "BSc Computer Science", "institution": "TU Berlin", "end": "2020"}],
             "projects": [{"name": "Job tracker", "description": "A small tracker.", "bullets": ["Used PostgreSQL."]}],
             "certifications": [{"name": "Java SE Developer", "issuer": "Oracle", "date": "2022"}],
             "achievements": ["Cut API latency in half."],
             "additional": [{"title": "Languages", "items": ["German", "English"]}]}
            """;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID alice;

    @BeforeEach
    void seed() {
        jdbcTemplate.execute("TRUNCATE resume_skills, resumes, skills, users RESTART IDENTITY CASCADE");
        alice = account(ALICE);
        account(BOB);
        jdbcTemplate.update("INSERT INTO skills (id, name, category) VALUES (1, 'Java', 'LANGUAGE'), "
                + "(2, 'PostgreSQL', 'DATABASE'), (3, 'Docker', 'PLATFORM')");
    }

    @Test
    @DisplayName("a built resume is created, read, edited section by section and re-analysed from its own text")
    void createAndEdit() throws Exception {
        String id = create(ALICE, "{\"title\": \"Main CV\", \"content\": " + CONTENT + "}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.resume.source").value("BUILDER"))
                .andExpect(jsonPath("$.resume.status").value("COMPLETED"))
                .andExpect(jsonPath("$.resume.isDefault").value(true))
                .andExpect(jsonPath("$.resume.title").value("Main CV"))
                .andExpect(jsonPath("$.resume.skills[*].name", containsInAnyOrder("Java", "PostgreSQL")))
                .andExpect(jsonPath("$.content.personal.fullName").value("José Müller"))
                .andReturn().getResponse().getContentAsString();
        String resumeId = JsonPath.read(id, "$.resume.id");

        mockMvc.perform(get("/api/resumes/" + resumeId + "/builder").with(user(ALICE).roles("USER")))
                .andExpect(jsonPath("$.content.experience[0].company").value("Acme"))
                .andExpect(jsonPath("$.content.additional[0].title").value("Languages"));

        String edited = CONTENT.replace("\"Built Java APIs for payments.\"",
                "\"Built Java APIs for payments.\", \"Ran services in Docker.\"");
        save(ALICE, resumeId, edited)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.experience[0].bullets", contains("Built Java APIs for payments.", "Ran services in Docker.")))
                .andExpect(jsonPath("$.resume.skills[*].name", hasItem("Docker")));
        // Removing a section's entries removes them, nothing else changes.
        save(ALICE, resumeId, edited.replace("\"certifications\": [{\"name\": \"Java SE Developer\", \"issuer\": \"Oracle\", \"date\": \"2022\"}],",
                "\"certifications\": [],"))
                .andExpect(jsonPath("$.content.certifications").isEmpty())
                .andExpect(jsonPath("$.content.projects[0].name").value("Job tracker"));
    }

    @Test
    @DisplayName("entries are validated; an uploaded resume cannot be edited in the builder")
    void validation() throws Exception {
        String resumeId = JsonPath.read(create(ALICE, "{}").andReturn().getResponse().getContentAsString(), "$.resume.id");
        save(ALICE, resumeId, "{\"personal\": {\"fullName\": \"A\"}, \"experience\": [{\"title\": \"Dev\"}]}")
                .andExpect(status().isBadRequest());
        save(ALICE, resumeId, "{\"experience\": []}").andExpect(status().isBadRequest());
        save(ALICE, resumeId, "{\"template\": \"FANCY\", \"personal\": {\"fullName\": \"A\"}}").andExpect(status().isBadRequest());
        save(ALICE, resumeId, "{\"personal\": {\"fullName\": \"A\", \"email\": \"not-an-email\"}}").andExpect(status().isBadRequest());

        UUID uploaded = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO resumes (id, user_id, original_file_name, stored_file_name, content_type, file_size_bytes,
                                     processing_status, processed_at, extracted_text, title, is_default, updated_at)
                VALUES (?, ?, 'cv.pdf', ?, 'application/pdf', 100, 'COMPLETED', now(), 'Java', 'Uploaded', FALSE, now())
                """, uploaded, alice, uploaded + ".pdf");
        mockMvc.perform(get("/api/resumes/" + uploaded + "/builder").with(user(ALICE).roles("USER")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/resumes").with(user(ALICE).roles("USER")))
                .andExpect(jsonPath("$[*].source", containsInAnyOrder("BUILDER", "UPLOAD")));
    }

    @Test
    @DisplayName("versions: rename, make default, duplicate and delete use the existing resume rules")
    void versions() throws Exception {
        String first = JsonPath.read(create(ALICE, "{\"title\": \"General\", \"content\": " + CONTENT + "}")
                .andReturn().getResponse().getContentAsString(), "$.resume.id");
        String second = JsonPath.read(create(ALICE, "{\"title\": \"Backend\"}")
                .andExpect(jsonPath("$.resume.isDefault").value(false))
                .andExpect(jsonPath("$.content.personal.fullName").value("Test"))
                .andReturn().getResponse().getContentAsString(), "$.resume.id");

        mockMvc.perform(patch("/api/resumes/" + second).with(user(ALICE).roles("USER")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\": \"Backend roles\", \"versionLabel\": \"v2\"}"))
                .andExpect(jsonPath("$.title").value("Backend roles"));
        mockMvc.perform(put("/api/resumes/" + second + "/default").with(user(ALICE).roles("USER")))
                .andExpect(jsonPath("$.isDefault").value(true));

        String copy = JsonPath.read(mockMvc.perform(post("/api/resumes/" + first + "/duplicate").with(user(ALICE).roles("USER")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.resume.title").value("Copy of General"))
                .andExpect(jsonPath("$.resume.isDefault").value(false))
                .andExpect(jsonPath("$.content.experience[0].company").value("Acme"))
                .andReturn().getResponse().getContentAsString(), "$.resume.id");
        assertThat(copy).isNotEqualTo(first);

        mockMvc.perform(delete("/api/resumes/" + second).with(user(ALICE).roles("USER"))).andExpect(status().isNoContent());
        mockMvc.perform(get("/api/resumes/" + second + "/builder").with(user(ALICE).roles("USER"))).andExpect(status().isNotFound());
        // Deleting the default hands it to the newest remaining one.
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM resumes WHERE user_id = ? AND is_default", Long.class, alice))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("another account can neither read, edit, duplicate, export nor delete the resume")
    void ownership() throws Exception {
        String id = JsonPath.read(create(ALICE, "{\"content\": " + CONTENT + "}").andReturn().getResponse().getContentAsString(),
                "$.resume.id");
        mockMvc.perform(get("/api/resumes/" + id + "/builder").with(user(BOB).roles("USER"))).andExpect(status().isNotFound());
        save(BOB, id, CONTENT).andExpect(status().isNotFound());
        mockMvc.perform(post("/api/resumes/" + id + "/duplicate").with(user(BOB).roles("USER"))).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/resumes/" + id + "/builder/pdf").with(user(BOB).roles("USER"))).andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/resumes/" + id).with(user(BOB).roles("USER"))).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/resumes/" + id + "/builder")).andExpect(status().isUnauthorized());
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM resumes", Long.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("the PDF has the user's content in the chosen template, no internal ids, and keeps entries on one page")
    void pdfExport() throws Exception {
        String id = JsonPath.read(create(ALICE, "{\"title\": \"Main CV\", \"content\": " + CONTENT + "}")
                .andReturn().getResponse().getContentAsString(), "$.resume.id");
        byte[] pdf = mockMvc.perform(get("/api/resumes/" + id + "/builder/pdf").with(user(ALICE).roles("USER")))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("Main-CV.pdf")))
                .andReturn().getResponse().getContentAsByteArray();
        try (PDDocument document = Loader.loadPDF(pdf)) {
            String text = new PDFTextStripper().getText(document);
            assertThat(text).contains("José Müller", "EXPERIENCE", "Software Engineer", "Built Java APIs for payments.",
                    "LANGUAGES", "2021 – Present").doesNotContain(id, alice.toString(), "JMIP", "🚀");
            assertThat(document.getDocumentInformation().getTitle()).isEqualTo("José Müller");
        }

        // A long resume in the modern template spans pages without splitting an entry.
        StringBuilder jobs = new StringBuilder();
        for (int j = 1; j <= 12; j++) {
            List<String> bullets = new ArrayList<>();
            for (int b = 1; b <= 6; b++) {
                bullets.add("\"Delivered outcome " + j + "-" + b + " for a long running programme of work with several teams.\"");
            }
            jobs.append(j > 1 ? "," : "").append("{\"title\": \"Role ").append(j).append("\", \"company\": \"Company ")
                    .append(j).append("\", \"bullets\": [").append(String.join(",", bullets)).append("]}");
        }
        save(ALICE, id, "{\"template\": \"MODERN\", \"personal\": {\"fullName\": \"Long Resume\"}, \"experience\": [" + jobs + "]}")
                .andExpect(status().isOk());
        byte[] longPdf = mockMvc.perform(get("/api/resumes/" + id + "/builder/pdf").with(user(ALICE).roles("USER")))
                .andReturn().getResponse().getContentAsByteArray();
        try (PDDocument document = Loader.loadPDF(longPdf)) {
            assertThat(document.getNumberOfPages()).isGreaterThan(1);
            assertThat(new PDFTextStripper().getText(document)).contains("Experience").doesNotContain("EXPERIENCE");
            for (int j = 1; j <= 12; j++) {
                assertThat(pageOf(document, "Role " + j)).as("role %d", j).isEqualTo(pageOf(document, "outcome " + j + "-6 "));
            }
        }
    }

    @Test
    @DisplayName("a built resume works with the existing V8.6 optimisation, recognising its section headings")
    void optimisation() throws Exception {
        jdbcTemplate.update("INSERT INTO companies (id, name) VALUES (1, 'Acme')");
        jdbcTemplate.update("""
                INSERT INTO jobs (id, title, company_id, description, source, source_url, content_fingerprint)
                VALUES (1, 'Backend Developer', 1, 'Java and Docker services. Docker in production.', 'itest', 'https://x.invalid/1', ?)
                """, String.format("%064d", 1));
        jdbcTemplate.update("INSERT INTO job_skills (job_id, skill_id) VALUES (1, 1), (1, 3)");
        String id = JsonPath.read(create(ALICE, "{\"content\": " + CONTENT + "}").andReturn().getResponse().getContentAsString(),
                "$.resume.id");
        mockMvc.perform(get("/api/resumes/" + id + "/optimize/1").with(user(ALICE).roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matchedSkills[*].name", contains("Java")))
                .andExpect(jsonPath("$.missingSkills[*].name", contains("Docker")))
                .andExpect(jsonPath("$.sectionsFound", hasItem("Experience")))
                .andExpect(jsonPath("$.sectionsFound", hasItem("Skills")));
    }

    private static int pageOf(PDDocument document, String needle) throws Exception {
        PDFTextStripper stripper = new PDFTextStripper();
        for (int page = 1; page <= document.getNumberOfPages(); page++) {
            stripper.setStartPage(page);
            stripper.setEndPage(page);
            if (stripper.getText(document).contains(needle)) {
                return page;
            }
        }
        return -1;
    }

    private ResultActions create(String email, String json) throws Exception {
        return mockMvc.perform(post("/api/resumes/builder").with(user(email).roles("USER"))
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private ResultActions save(String email, String id, String json) throws Exception {
        return mockMvc.perform(put("/api/resumes/" + id + "/builder").with(user(email).roles("USER"))
                .contentType(MediaType.APPLICATION_JSON).content(json));
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
