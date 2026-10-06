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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** V7.4: career goals and their roadmaps, end to end against real category analytics. */
@SpringBootTest(properties = "jmip.ai.provider=stub")
@AutoConfigureMockMvc
@Testcontainers
class CareerGoalIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine");

    private static final String ALICE = "alice@example.com";
    private static final String BOB = "bob@example.com";
    private static final String GOAL = """
            {"targetRole":"  Backend Engineer ","targetCategory":"Backend Developer","targetLocation":"Berlin",
             "targetExperience":"2-5","targetSkills":["go"]}""";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID aliceId;

    @BeforeEach
    void seed() {
        jdbcTemplate.execute("TRUNCATE career_goal_skill_progress, career_goal_skills, career_goals, resume_skills, resumes, "
                + "job_skills, jobs, skills, companies, locations, users RESTART IDENTITY CASCADE");
        aliceId = account(ALICE);
        account(BOB);
        jdbcTemplate.update("INSERT INTO companies (id, name) VALUES (1, 'Acme Systems')");
        jdbcTemplate.update("""
                INSERT INTO skills (id, name, category) VALUES (1, 'Java', 'LANGUAGE'), (2, 'Docker', 'PLATFORM'),
                    (3, 'Kubernetes', 'PLATFORM'), (4, 'Python', 'LANGUAGE'), (5, 'Go', 'LANGUAGE')
                """);
        // Backend Developer: Java in 3 of 3 postings, Docker and Kubernetes in 1 each.
        for (int id = 1; id <= 3; id++) {
            jdbcTemplate.update("""
                    INSERT INTO jobs (id, title, company_id, description, source, source_url, content_fingerprint,
                                      job_category, classification_confidence, classified_at)
                    VALUES (?, 'Backend Engineer', 1, 'posting', 'itest', ?, ?, 'Backend Developer', 0.9, now())
                    """, id, "https://example.invalid/" + id, String.format("%064d", id));
        }
        jdbcTemplate.update("INSERT INTO job_skills (job_id, skill_id) VALUES (1, 1), (1, 2), (2, 1), (2, 3), (3, 1)");
        // Alice's default resume lists Java.
        UUID resumeId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO resumes (id, user_id, original_file_name, stored_file_name, content_type, file_size_bytes,
                                     processing_status, processed_at, title, is_default, updated_at)
                VALUES (?, ?, 'cv.pdf', ?, 'application/pdf', 100, 'COMPLETED', now(), 'Main CV', TRUE, now())
                """, resumeId, aliceId, resumeId + ".pdf");
        jdbcTemplate.update("INSERT INTO resume_skills (resume_id, skill_id) VALUES (?, 1)", resumeId);
    }

    @Test
    @DisplayName("a goal is created for the signed-in account, trimmed, ACTIVE, with its chosen skills resolved")
    void createGoal() throws Exception {
        String body = GOAL.replace("\"targetRole\"", "\"userId\":\"" + UUID.randomUUID() + "\",\"targetRole\"");
        mockMvc.perform(json(post("/api/career-goals"), body).with(as(ALICE)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.targetRole").value("Backend Engineer"))
                .andExpect(jsonPath("$.targetCategory").value("Backend Developer"))
                .andExpect(jsonPath("$.targetLocation").value("Berlin"))
                .andExpect(jsonPath("$.targetExperience").value("2-5"))
                .andExpect(jsonPath("$.targetSkills[0].name").value("Go"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.userId").doesNotExist());

        assertThat(jdbcTemplate.queryForObject("SELECT user_id FROM career_goals", UUID.class)).isEqualTo(aliceId);
    }

    @Test
    @DisplayName("invalid goals are refused with 400 and nothing is saved")
    void validation() throws Exception {
        List<String> invalid = List.of(
                "{\"targetRole\":\" \",\"targetCategory\":\"Backend Developer\"}",
                "{\"targetRole\":\"Engineer\"}",
                "{\"targetRole\":\"Engineer\",\"targetCategory\":\"Astronaut\"}",
                "{\"targetRole\":\"Engineer\",\"targetCategory\":\"Backend Developer\",\"targetExperience\":\"10+\"}",
                "{\"targetRole\":\"Engineer\",\"targetCategory\":\"Backend Developer\",\"targetSkills\":[\"Cobol\"]}",
                "{\"targetRole\":\"Engineer\",\"targetCategory\":\"Backend Developer\",\"targetSkills\":[\" \"]}",
                "{\"targetRole\":\"" + "r".repeat(101) + "\",\"targetCategory\":\"Backend Developer\"}");
        for (String body : invalid) {
            mockMvc.perform(json(post("/api/career-goals"), body).with(as(ALICE))).andExpect(status().isBadRequest());
        }
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM career_goals", Long.class)).isZero();
    }

    @Test
    @DisplayName("the owner can list, read, update, change the status of and delete a goal")
    void manageGoal() throws Exception {
        String id = create(ALICE, GOAL);
        create(ALICE, "{\"targetRole\":\"Platform\",\"targetCategory\":\"Backend Developer\"}");

        mockMvc.perform(get("/api/career-goals").with(as(ALICE))).andExpect(jsonPath("$", hasSize(2)));
        mockMvc.perform(json(put("/api/career-goals/" + id),
                        "{\"targetRole\":\"Senior Backend\",\"targetCategory\":\"Backend Developer\",\"targetSkills\":[\"Python\"]}")
                        .with(as(ALICE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targetRole").value("Senior Backend"))
                .andExpect(jsonPath("$.targetLocation").doesNotExist())
                .andExpect(jsonPath("$.targetSkills[0].name").value("Python"));

        mockMvc.perform(json(patch("/api/career-goals/" + id + "/status"), "{\"status\":\"ARCHIVED\"}").with(as(ALICE)))
                .andExpect(jsonPath("$.status").value("ARCHIVED"));
        mockMvc.perform(get("/api/career-goals").param("status", "ACTIVE").with(as(ALICE))).andExpect(jsonPath("$", hasSize(1)));
        mockMvc.perform(get("/api/career-goals").param("status", "ARCHIVED").with(as(ALICE)))
                .andExpect(jsonPath("$[0].id").value(id));
        mockMvc.perform(json(patch("/api/career-goals/" + id + "/status"), "{\"status\":\"DONE\"}").with(as(ALICE)))
                .andExpect(status().isBadRequest());

        mockMvc.perform(delete("/api/career-goals/" + id).with(as(ALICE))).andExpect(status().isNoContent());
        mockMvc.perform(get("/api/career-goals/" + id).with(as(ALICE))).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("the roadmap splits current, covered and missing skills from real category demand, in priority order")
    void roadmap() throws Exception {
        String id = create(ALICE, GOAL);

        mockMvc.perform(get("/api/career-goals/" + id + "/roadmap").with(as(ALICE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.basedOnResume.title").value("Main CV"))
                .andExpect(jsonPath("$.currentSkills[*].name", contains("Java")))
                .andExpect(jsonPath("$.marketSkills", hasSize(3)))
                .andExpect(jsonPath("$.marketSkills[0].skill").value("Java"))
                .andExpect(jsonPath("$.marketSkills[0].percentageOfJobs").value(100.0))
                .andExpect(jsonPath("$.marketSkills[0].onResume").value(true))
                .andExpect(jsonPath("$.coveredSkills[*].name", contains("Java")))
                .andExpect(jsonPath("$.roadmap[*].skill", contains("Docker", "Kubernetes", "Go")))
                .andExpect(jsonPath("$.roadmap[0].priority").value(1))
                .andExpect(jsonPath("$.roadmap[0].source").value("MARKET_DEMAND"))
                .andExpect(jsonPath("$.roadmap[0].percentageOfJobs").value(33.3))
                .andExpect(jsonPath("$.roadmap[0].reason", containsString("33.3% of Backend Developer postings")))
                .andExpect(jsonPath("$.roadmap[2].source").value("YOUR_CHOICE"))
                .andExpect(jsonPath("$.roadmap[2].percentageOfJobs").doesNotExist())
                .andExpect(jsonPath("$.roadmap[*].status", contains("NOT_STARTED", "NOT_STARTED", "NOT_STARTED")))
                .andExpect(jsonPath("$.progress.totalSkills").value(4))
                .andExpect(jsonPath("$.progress.onResume").value(1))
                .andExpect(jsonPath("$.progress.percentComplete").value(25.0))
                .andExpect(jsonPath("$.staged").value(false))
                .andExpect(jsonPath("$.note", containsString("by priority")));
    }

    @Test
    @DisplayName("progress on roadmap skills is stored per goal and counted in the overall progress")
    void progress() throws Exception {
        String id = create(ALICE, GOAL);

        mockMvc.perform(json(put("/api/career-goals/" + id + "/roadmap/skills/2"), "{\"status\":\"IN_PROGRESS\"}").with(as(ALICE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"));
        mockMvc.perform(json(put("/api/career-goals/" + id + "/roadmap/skills/5"), "{\"status\":\"COMPLETED\"}").with(as(ALICE)))
                .andExpect(status().isOk());
        mockMvc.perform(json(put("/api/career-goals/" + id + "/roadmap/skills/5"), "{\"status\":\"COMPLETED\"}").with(as(ALICE)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/career-goals/" + id + "/roadmap").with(as(ALICE)))
                .andExpect(jsonPath("$.roadmap[*].status", contains("IN_PROGRESS", "NOT_STARTED", "COMPLETED")))
                .andExpect(jsonPath("$.progress.completed").value(1))
                .andExpect(jsonPath("$.progress.inProgress").value(1))
                .andExpect(jsonPath("$.progress.notStarted").value(1))
                .andExpect(jsonPath("$.progress.percentComplete").value(50.0));

        // Python is neither in the category's demand nor chosen for this goal.
        mockMvc.perform(json(put("/api/career-goals/" + id + "/roadmap/skills/4"), "{\"status\":\"COMPLETED\"}").with(as(ALICE)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(json(put("/api/career-goals/" + id + "/roadmap/skills/2"), "{\"status\":\"DONE\"}").with(as(ALICE)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(json(put("/api/career-goals/" + UUID.randomUUID() + "/roadmap/skills/2"), "{\"status\":\"COMPLETED\"}")
                .with(as(ALICE))).andExpect(status().isNotFound());

        // Deleting the goal takes its progress with it.
        mockMvc.perform(delete("/api/career-goals/" + id).with(as(ALICE))).andExpect(status().isNoContent());
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM career_goal_skill_progress", Long.class)).isZero();
    }

    @Test
    @DisplayName("without a processed resume every skill is still to develop, and says so")
    void roadmapWithoutResume() throws Exception {
        String id = create(BOB, "{\"targetRole\":\"Backend\",\"targetCategory\":\"Backend Developer\"}");

        mockMvc.perform(get("/api/career-goals/" + id + "/roadmap").with(as(BOB)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.basedOnResume").doesNotExist())
                .andExpect(jsonPath("$.currentSkills", hasSize(0)))
                .andExpect(jsonPath("$.roadmap[*].skill", contains("Java", "Docker", "Kubernetes")))
                .andExpect(jsonPath("$.progress.percentComplete").value(0.0))
                .andExpect(jsonPath("$.note", containsString("No processed resume")));
    }

    @Test
    @DisplayName("another account's goal, roadmap, progress and resume are all a 404")
    void crossAccount() throws Exception {
        String id = create(ALICE, GOAL);
        String path = "/api/career-goals/" + id;
        String alicesResume = jdbcTemplate.queryForObject("SELECT id::text FROM resumes", String.class);

        mockMvc.perform(get(path).with(as(BOB))).andExpect(status().isNotFound());
        mockMvc.perform(json(put(path), GOAL).with(as(BOB))).andExpect(status().isNotFound());
        mockMvc.perform(json(patch(path + "/status"), "{\"status\":\"ARCHIVED\"}").with(as(BOB))).andExpect(status().isNotFound());
        mockMvc.perform(get(path + "/roadmap").with(as(BOB))).andExpect(status().isNotFound());
        mockMvc.perform(json(put(path + "/roadmap/skills/2"), "{\"status\":\"COMPLETED\"}").with(as(BOB)))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete(path).with(as(BOB))).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/career-goals").with(as(BOB))).andExpect(jsonPath("$", hasSize(0)));

        // Bob's own goal cannot be measured against Alice's resume either.
        String bobs = create(BOB, "{\"targetRole\":\"Backend\",\"targetCategory\":\"Backend Developer\"}");
        mockMvc.perform(get("/api/career-goals/" + bobs + "/roadmap").param("resumeId", alicesResume).with(as(BOB)))
                .andExpect(status().isNotFound());

        mockMvc.perform(get(path).with(as(ALICE))).andExpect(jsonPath("$.status").value("ACTIVE"));
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM career_goal_skill_progress", Long.class)).isZero();
        mockMvc.perform(get("/api/career-goals")).andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------ helpers

    private String create(String email, String body) throws Exception {
        return JsonPath.read(mockMvc.perform(json(post("/api/career-goals"), body).with(as(email)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
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
