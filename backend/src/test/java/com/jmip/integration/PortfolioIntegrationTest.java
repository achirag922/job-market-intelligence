package com.jmip.integration;

import com.jayway.jsonpath.JsonPath;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * V9.7: the professional portfolio and its public profile. Alice and Bob are users; Alice has an
 * active and an archived career goal, and private learning data that must never be shown publicly.
 */
@SpringBootTest(properties = {"jmip.ai.provider=stub", "jmip.alerts.enabled=false"})
@AutoConfigureMockMvc
@Testcontainers
class PortfolioIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine");

    private static final String ALICE = "alice@example.test";
    private static final String BOB = "bob@example.test";
    private static final String PROFILE = """
            {"displayName": "Ana María Ruiz",
             "content": {"headline": "Backend Engineer‮", "about": "I build reliable services.",
               "skills": ["Java", "Docker"],
               "experience": [{"title": "Engineer", "company": "Acme", "start": "2021", "current": true, "bullets": ["Built APIs"]}],
               "education": [{"degree": "BSc Computer Science", "institution": "State University"}],
               "projects": [{"name": "Tracker", "url": "https://github.com/example/tracker", "description": "A side project"}],
               "certifications": [{"name": "Cloud Practitioner", "issuer": "Cloud Co"}],
               "achievements": ["Speaker at a local meetup"],
               "links": [{"label": "GitHub", "url": "https://github.com/example"}]},
             "sections": {"projects": false, "careerGoals": true}}
            """;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID alice;

    @BeforeEach
    void seed() {
        jdbcTemplate.execute("TRUNCATE portfolios, learning_resources, learning_items, career_goal_skills, career_goals, "
                + "resume_skills, resumes, skills, users RESTART IDENTITY CASCADE");
        alice = account(ALICE, "Alice Example");
        account(BOB, "Bob Example");
        jdbcTemplate.update("INSERT INTO skills (id, name, category) VALUES (1, 'Java', 'LANGUAGE'), (2, 'Docker', 'PLATFORM'), "
                + "(3, 'Kubernetes', 'PLATFORM')");
        jdbcTemplate.update("""
                INSERT INTO career_goals (id, user_id, target_role, target_category, status, created_at, updated_at)
                VALUES (?, ?, 'Staff Engineer', 'Backend Developer', 'ACTIVE', now(), now()),
                       (?, ?, 'Secret Old Goal', 'Backend Developer', 'ARCHIVED', now(), now())
                """, UUID.randomUUID(), alice, UUID.randomUUID(), alice);
        jdbcTemplate.update("""
                INSERT INTO learning_items (id, user_id, skill_id, skill_name, topic, priority, status, progress,
                                            created_at, updated_at, started_at, completed_at)
                VALUES (?, ?, 3, 'Kubernetes', 'Private learning topic', 'HIGH', 'COMPLETED', 100, now(), now(), now(), now())
                """, UUID.randomUUID(), alice);
    }

    @Test
    @DisplayName("create, read, update and delete the owner's portfolio; a second one is a conflict")
    void crud() throws Exception {
        mockMvc.perform(as(get("/api/portfolio"), ALICE)).andExpect(status().isNotFound());
        save(post("/api/portfolio"), ALICE, PROFILE)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.slug").value("ana-maria-ruiz"))
                .andExpect(jsonPath("$.publicPath").value("/profile/ana-maria-ruiz"))
                .andExpect(jsonPath("$.visibility").value("PRIVATE"))
                .andExpect(jsonPath("$.content.headline").value("Backend Engineer"))
                .andExpect(jsonPath("$.sections.projects").value(false))
                .andExpect(jsonPath("$.sections.skills").value(true))
                .andExpect(jsonPath("$.publishedAt").doesNotExist());
        save(post("/api/portfolio"), ALICE, PROFILE).andExpect(status().isConflict());

        save(put("/api/portfolio"), ALICE, "{\"displayName\": \"Ana Ruiz\", \"content\": {\"headline\": \"Platform Engineer\"}}")
                .andExpect(jsonPath("$.displayName").value("Ana Ruiz"))
                .andExpect(jsonPath("$.content.headline").value("Platform Engineer"))
                .andExpect(jsonPath("$.content.skills").isEmpty())
                .andExpect(jsonPath("$.sections.careerGoals").value(false))
                .andExpect(jsonPath("$.slug").value("ana-maria-ruiz"));

        mockMvc.perform(as(delete("/api/portfolio"), ALICE)).andExpect(status().isNoContent());
        mockMvc.perform(as(get("/api/portfolio"), ALICE)).andExpect(status().isNotFound());
        mockMvc.perform(as(delete("/api/portfolio"), ALICE)).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("only a published profile is public, with only its visible sections and no private data")
    void publishingAndPublicAccess() throws Exception {
        save(post("/api/portfolio"), ALICE, PROFILE).andExpect(status().isCreated());
        mockMvc.perform(get("/api/public/profiles/ana-maria-ruiz")).andExpect(status().isNotFound());
        mockMvc.perform(as(get("/api/portfolio/preview"), ALICE))
                .andExpect(jsonPath("$.displayName").value("Ana María Ruiz"))
                .andExpect(jsonPath("$.projects").doesNotExist());

        mockMvc.perform(as(post("/api/portfolio/publish"), ALICE))
                .andExpect(jsonPath("$.visibility").value("PUBLIC"))
                .andExpect(jsonPath("$.publishedAt").exists());
        String body = mockMvc.perform(get("/api/public/profiles/ana-maria-ruiz"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Ana María Ruiz"))
                .andExpect(jsonPath("$.headline").value("Backend Engineer"))
                .andExpect(jsonPath("$.experience[0].company").value("Acme"))
                .andExpect(jsonPath("$.links[0].url").value("https://github.com/example"))
                .andExpect(jsonPath("$.careerGoals", contains("Staff Engineer")))
                .andExpect(jsonPath("$.projects").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain(ALICE, alice.toString(), "Secret Old Goal", "Private learning topic", "userId",
                "visibility", "slug", "\"id\"");
        // Case in the address does not matter; nonsense is the same 404 as private.
        mockMvc.perform(get("/api/public/profiles/ANA-MARIA-RUIZ")).andExpect(status().isOk());
        mockMvc.perform(get("/api/public/profiles/no-such-person")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/public/profiles/../admin")).andExpect(status().is4xxClientError());

        mockMvc.perform(as(post("/api/portfolio/unpublish"), ALICE)).andExpect(jsonPath("$.visibility").value("PRIVATE"));
        mockMvc.perform(get("/api/public/profiles/ana-maria-ruiz")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("slugs are unique, validated and changeable; the old address stops working")
    void slugs() throws Exception {
        save(post("/api/portfolio"), ALICE, PROFILE).andExpect(status().isCreated());
        save(post("/api/portfolio"), BOB, "{\"displayName\": \"Bob\", \"slug\": \"ana-maria-ruiz\", \"content\": {}}")
                .andExpect(status().isConflict());
        save(post("/api/portfolio"), BOB, "{\"displayName\": \"Ana María Ruiz\", \"content\": {}}")
                .andExpect(jsonPath("$.slug").value("ana-maria-ruiz-2"));

        slug(ALICE, "ana-maria-ruiz-2").andExpect(status().isConflict());
        slug(ALICE, "Bad Slug").andExpect(status().isBadRequest());
        slug(ALICE, "-ab").andExpect(status().isBadRequest());
        slug(ALICE, "admin").andExpect(status().isBadRequest());
        mockMvc.perform(as(post("/api/portfolio/publish"), ALICE)).andExpect(status().isOk());
        slug(ALICE, "ana-dev").andExpect(jsonPath("$.slug").value("ana-dev"))
                .andExpect(jsonPath("$.publicPath").value("/profile/ana-dev"));
        mockMvc.perform(get("/api/public/profiles/ana-maria-ruiz")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/public/profiles/ana-dev")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("inputs are validated: web addresses must be http(s), required fields present, lengths bounded")
    void validation() throws Exception {
        save(post("/api/portfolio"), ALICE, "{\"displayName\": \" \", \"content\": {}}").andExpect(status().isBadRequest());
        save(post("/api/portfolio"), ALICE, "{\"displayName\": \"Ana\"}").andExpect(status().isBadRequest());
        save(post("/api/portfolio"), ALICE, "{\"displayName\": \"Ana\", \"content\": {\"links\": "
                + "[{\"label\": \"x\", \"url\": \"javascript:alert(1)\"}]}}").andExpect(status().isBadRequest());
        save(post("/api/portfolio"), ALICE, "{\"displayName\": \"Ana\", \"content\": {\"projects\": "
                + "[{\"name\": \"x\", \"url\": \"ftp://files.example/x\"}]}}").andExpect(status().isBadRequest());
        save(post("/api/portfolio"), ALICE, "{\"displayName\": \"Ana\", \"content\": {\"headline\": \"" + "x".repeat(161) + "\"}}")
                .andExpect(status().isBadRequest());
        save(post("/api/portfolio"), ALICE, "{\"displayName\": \"Ana\", \"content\": {\"experience\": [{\"title\": \"Engineer\"}]}}")
                .andExpect(status().isBadRequest());
        mockMvc.perform(as(get("/api/portfolio"), ALICE)).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("each account edits only its own portfolio; imports read only the owner's resumes")
    void ownershipAndImport() throws Exception {
        String resume = JsonPath.read(mockMvc.perform(as(post("/api/resumes/builder"), ALICE).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title": "Built", "content": {"personal": {"fullName": "Alice Example", "headline": "Engineer",
                                  "email": "alice@example.test", "phone": "123", "links": ["https://github.com/alice", "not a link"]},
                                  "summary": "Summary text", "skills": ["Java"],
                                  "experience": [{"title": "Engineer", "company": "Acme", "bullets": []}]}}
                                """))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.resume.id");

        mockMvc.perform(as(get("/api/portfolio/import").param("resumeId", resume), ALICE))
                .andExpect(jsonPath("$.source").value("BUILDER"))
                .andExpect(jsonPath("$.displayName").value("Alice Example"))
                .andExpect(jsonPath("$.content.about").value("Summary text"))
                .andExpect(jsonPath("$.content.experience[0].company").value("Acme"))
                .andExpect(jsonPath("$.content.links[0].label").value("github.com"))
                .andExpect(jsonPath("$.content.links.length()").value(1))
                .andExpect(jsonPath("$.learnedSkills", hasItem("Kubernetes")));
        // The draft is not saved, and never carries the resume's email or phone.
        mockMvc.perform(as(get("/api/portfolio"), ALICE)).andExpect(status().isNotFound());
        assertThat(mockMvc.perform(as(get("/api/portfolio/import"), ALICE)).andReturn().getResponse().getContentAsString())
                .doesNotContain(ALICE, "\"phone\"");

        mockMvc.perform(as(get("/api/portfolio/import").param("resumeId", resume), BOB)).andExpect(status().isNotFound());
        mockMvc.perform(as(get("/api/portfolio/import"), BOB)).andExpect(status().isBadRequest());

        save(post("/api/portfolio"), ALICE, PROFILE).andExpect(status().isCreated());
        mockMvc.perform(as(get("/api/portfolio"), BOB)).andExpect(status().isNotFound());
        save(put("/api/portfolio"), BOB, "{\"displayName\": \"Hijack\", \"content\": {}}").andExpect(status().isNotFound());
        mockMvc.perform(as(post("/api/portfolio/publish"), BOB)).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/portfolio")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/portfolio/publish")).andExpect(status().isUnauthorized());
        assertThat(jdbcTemplate.queryForObject("SELECT display_name FROM portfolios", String.class)).isEqualTo("Ana María Ruiz");
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, String email) {
        return request.with(user(email).roles("USER"));
    }

    private ResultActions save(MockHttpServletRequestBuilder request, String email, String json) throws Exception {
        return mockMvc.perform(as(request, email).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private ResultActions slug(String email, String slug) throws Exception {
        return save(patch("/api/portfolio/slug"), email, "{\"slug\": \"" + slug + "\"}");
    }

    private UUID account(String email, String name) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO users (id, full_name, email, password_hash, role, email_verified_at)
                VALUES (?, ?, ?, '{bcrypt}not-a-real-hash', 'USER', now())
                """, id, name, email);
        return id;
    }
}
