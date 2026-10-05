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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * V9.5: the learning plan. Backend Developer postings ask for Docker in 3 of 3, Java and Kubernetes
 * in 1 each; Alice's resume has Java, her active goal is Backend Developer (plus SQL, her choice),
 * and she saved jobs 1 and 2, both asking for Docker. Bob has no goal.
 */
@SpringBootTest(properties = {"jmip.ai.provider=stub", "jmip.alerts.enabled=false"})
@AutoConfigureMockMvc
@Testcontainers
class LearningPlanIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine");

    private static final String ALICE = "alice@example.test";
    private static final String BOB = "bob@example.test";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID goal;

    @BeforeEach
    void seed() {
        jdbcTemplate.execute("TRUNCATE learning_resources, learning_items, career_goal_skill_progress, career_goal_skills, "
                + "career_goals, saved_jobs, resume_skills, resumes, job_skills, jobs, skills, companies, users RESTART IDENTITY CASCADE");
        UUID alice = account(ALICE);
        account(BOB);
        jdbcTemplate.update("INSERT INTO companies (id, name) VALUES (1, 'Acme')");
        jdbcTemplate.update("INSERT INTO skills (id, name, category) VALUES (1, 'Java', 'LANGUAGE'), (2, 'Docker', 'PLATFORM'), "
                + "(3, 'Kubernetes', 'PLATFORM'), (4, 'SQL', 'LANGUAGE')");
        for (int id = 1; id <= 3; id++) {
            jdbcTemplate.update("""
                    INSERT INTO jobs (id, title, company_id, description, source, source_url, content_fingerprint,
                                      job_category, classification_confidence, classified_at)
                    VALUES (?, 'Backend Engineer', 1, 'posting', 'itest', ?, ?, 'Backend Developer', 0.9, now())
                    """, id, "https://example.invalid/" + id, String.format("%064d", id));
        }
        jdbcTemplate.update("INSERT INTO job_skills (job_id, skill_id) VALUES (1, 1), (1, 2), (2, 2), (2, 3), (3, 2)");
        UUID resume = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO resumes (id, user_id, original_file_name, stored_file_name, content_type, file_size_bytes,
                                     processing_status, processed_at, title, is_default, updated_at)
                VALUES (?, ?, 'cv.pdf', ?, 'application/pdf', 100, 'COMPLETED', now(), 'CV', TRUE, now())
                """, resume, alice, resume + ".pdf");
        jdbcTemplate.update("INSERT INTO resume_skills (resume_id, skill_id) VALUES (?, 1)", resume);
        goal = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO career_goals (id, user_id, target_role, target_category, status, created_at, updated_at)
                VALUES (?, ?, 'Backend Developer', 'Backend Developer', 'ACTIVE', now(), now())
                """, goal, alice);
        jdbcTemplate.update("INSERT INTO career_goal_skills (goal_id, skill_id) VALUES (?, 4)", goal);
        for (long job : new long[]{1, 2}) {
            jdbcTemplate.update("INSERT INTO saved_jobs (id, user_id, job_id, status, saved_at, updated_at) "
                    + "VALUES (?, ?, ?, 'SAVED', now(), now())", UUID.randomUUID(), alice, job);
        }
    }

    @Test
    @DisplayName("priority skills are the career roadmap's own ranking, and items start from them")
    void priorities() throws Exception {
        mockMvc.perform(get("/api/learning").with(user(ALICE).roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.goalRole").value("Backend Developer"))
                .andExpect(jsonPath("$.priorities[0].skill").value("Docker"))
                .andExpect(jsonPath("$.priorities[0].rank").value(1))
                .andExpect(jsonPath("$.priorities[0].suggestedPriority").value("HIGH"))
                .andExpect(jsonPath("$.priorities[0].itemId").doesNotExist())
                .andExpect(jsonPath("$.priorities[*].skill", org.hamcrest.Matchers.hasItem("SQL")))
                .andExpect(jsonPath("$.priorities[*].skill", org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("Java"))))
                .andExpect(jsonPath("$.items", hasSize(0)))
                .andExpect(jsonPath("$.progress.averageProgress").doesNotExist());

        createItem(ALICE, "{\"skillId\": 2, \"topic\": \"Containers basics\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.skill").value("Docker"))
                .andExpect(jsonPath("$.priority").value("HIGH"))
                .andExpect(jsonPath("$.goalId").value(goal.toString()))
                .andExpect(jsonPath("$.status").value("NOT_STARTED"));
        createItem(ALICE, "{\"skillName\": \"Rust\", \"topic\": \"The Rust book\", \"targetDate\": \"2027-01-31\"}")
                .andExpect(jsonPath("$.skillId").doesNotExist())
                .andExpect(jsonPath("$.priority").value("MEDIUM"))
                .andExpect(jsonPath("$.targetDate").value("2027-01-31"));
        mockMvc.perform(get("/api/learning").with(user(ALICE).roles("USER")))
                .andExpect(jsonPath("$.priorities[0].itemId").exists())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].skill").value("Docker"));

        createItem(ALICE, "{\"skillId\": 2, \"topic\": \" \"}").andExpect(status().isBadRequest());
        createItem(ALICE, "{\"topic\": \"No skill\"}").andExpect(status().isBadRequest());
        createItem(ALICE, "{\"skillId\": 2, \"topic\": \"x\", \"priority\": \"URGENT\"}").andExpect(status().isBadRequest());
        createItem(ALICE, "{\"skillId\": 999, \"topic\": \"x\"}").andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("starting and completing an item updates the roadmap skill; the resume is never changed")
    void progressAndRoadmap() throws Exception {
        String id = itemId(createItem(ALICE, "{\"skillId\": 2, \"topic\": \"Containers basics\"}"));

        setStatus(ALICE, id, "IN_PROGRESS").andExpect(jsonPath("$.startedAt").exists())
                .andExpect(jsonPath("$.roadmapStatus").value("IN_PROGRESS"));
        mockMvc.perform(put("/api/learning/items/" + id).with(user(ALICE).roles("USER")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"topic\": \"Docker in depth\", \"priority\": \"LOW\", \"progress\": 40, \"targetDate\": \"2026-12-01\", \"notes\": \"Weekends\"}"))
                .andExpect(jsonPath("$.progress").value(40))
                .andExpect(jsonPath("$.priority").value("LOW"))
                .andExpect(jsonPath("$.notes").value("Weekends"));
        mockMvc.perform(put("/api/learning/items/" + id).with(user(ALICE).roles("USER")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"topic\": \"x\", \"priority\": \"LOW\", \"progress\": 101}"))
                .andExpect(status().isBadRequest());

        setStatus(ALICE, id, "COMPLETED")
                .andExpect(jsonPath("$.progress").value(100))
                .andExpect(jsonPath("$.completedAt").exists())
                .andExpect(jsonPath("$.roadmapStatus").value("COMPLETED"));
        mockMvc.perform(get("/api/career-goals/" + goal + "/roadmap").with(user(ALICE).roles("USER")))
                .andExpect(jsonPath("$.roadmap[?(@.skill == 'Docker')].status", contains("COMPLETED")));
        mockMvc.perform(get("/api/learning").with(user(ALICE).roles("USER")))
                .andExpect(jsonPath("$.progress.completed").value(1))
                .andExpect(jsonPath("$.progress.completedSkills", contains("Docker")))
                .andExpect(jsonPath("$.progress.averageProgress").value(100.0))
                .andExpect(jsonPath("$.impact.completedNotOnResume", contains("Docker")))
                .andExpect(jsonPath("$.impact.savedJobDemand[0].skill").value("Docker"))
                .andExpect(jsonPath("$.impact.savedJobDemand[0].savedJobs").value(2))
                .andExpect(jsonPath("$.impact.note").exists());
        // The resume still only shows Java: nothing is added for the user.
        assertThat(jdbcTemplate.queryForList("SELECT skill_id FROM resume_skills", Long.class)).containsExactly(1L);

        setStatus(ALICE, id, "IN_PROGRESS").andExpect(jsonPath("$.completedAt").doesNotExist());
        setStatus(ALICE, id, "DONE").andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("resources are added, edited and removed, with a real http(s) address and a known type")
    void resources() throws Exception {
        String id = itemId(createItem(ALICE, "{\"skillId\": 2, \"topic\": \"Containers basics\"}"));
        String resource = JsonPath.read(addResource(ALICE, id, "{\"title\": \"Docker docs\", \"url\": \"https://docs.docker.com/\", \"type\": \"DOCUMENTATION\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("DOCUMENTATION"))
                .andReturn().getResponse().getContentAsString(), "$.id");
        addResource(ALICE, id, "{\"title\": \"x\", \"url\": \"ftp://files.example/x\", \"type\": \"OTHER\"}").andExpect(status().isBadRequest());
        addResource(ALICE, id, "{\"title\": \"x\", \"url\": \"https://example.com\", \"type\": \"PODCAST\"}").andExpect(status().isBadRequest());

        mockMvc.perform(put("/api/learning/resources/" + resource).with(user(ALICE).roles("USER")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\": \"Docker getting started\", \"url\": \"https://docs.docker.com/get-started/\", \"type\": \"COURSE\", \"notes\": \"Part 1\"}"))
                .andExpect(jsonPath("$.title").value("Docker getting started"))
                .andExpect(jsonPath("$.type").value("COURSE"));
        mockMvc.perform(get("/api/learning").with(user(ALICE).roles("USER")))
                .andExpect(jsonPath("$.items[0].resources[0].notes").value("Part 1"));
        mockMvc.perform(delete("/api/learning/resources/" + resource).with(user(ALICE).roles("USER"))).andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/learning/items/" + id).with(user(ALICE).roles("USER"))).andExpect(status().isNoContent());
        mockMvc.perform(get("/api/learning").with(user(ALICE).roles("USER"))).andExpect(jsonPath("$.items", hasSize(0)));
    }

    @Test
    @DisplayName("a plan is the owner's only; without a goal it still works, with a note")
    void ownershipAndMissingData() throws Exception {
        String id = itemId(createItem(ALICE, "{\"skillId\": 2, \"topic\": \"Containers basics\"}"));
        String resource = JsonPath.read(addResource(ALICE, id, "{\"title\": \"Docs\", \"url\": \"https://docs.docker.com/\", \"type\": \"ARTICLE\"}")
                .andReturn().getResponse().getContentAsString(), "$.id");

        mockMvc.perform(get("/api/learning").with(user(BOB).roles("USER")))
                .andExpect(jsonPath("$.items", hasSize(0)))
                .andExpect(jsonPath("$.priorities", hasSize(0)))
                .andExpect(jsonPath("$.goalRole").doesNotExist())
                .andExpect(jsonPath("$.note").exists());
        setStatus(BOB, id, "COMPLETED").andExpect(status().isNotFound());
        mockMvc.perform(put("/api/learning/items/" + id).with(user(BOB).roles("USER")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"topic\": \"x\", \"priority\": \"LOW\", \"progress\": 1}")).andExpect(status().isNotFound());
        addResource(BOB, id, "{\"title\": \"x\", \"url\": \"https://x.example\", \"type\": \"OTHER\"}").andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/learning/resources/" + resource).with(user(BOB).roles("USER"))).andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/learning/items/" + id).with(user(BOB).roles("USER"))).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/learning")).andExpect(status().isUnauthorized());

        // Bob, with no goal and no resume, can still plan by skill name.
        createItem(BOB, "{\"skillName\": \"docker\", \"topic\": \"Basics\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.skill").value("Docker"))
                .andExpect(jsonPath("$.goalId").doesNotExist());
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM learning_items WHERE user_id <> (SELECT id FROM users WHERE email = ?)",
                Long.class, BOB)).isEqualTo(1);
    }

    private ResultActions createItem(String email, String json) throws Exception {
        return mockMvc.perform(post("/api/learning/items").with(user(email).roles("USER"))
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private ResultActions setStatus(String email, String id, String status) throws Exception {
        return mockMvc.perform(patch("/api/learning/items/" + id + "/status").with(user(email).roles("USER"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\": \"" + status + "\"}"));
    }

    private ResultActions addResource(String email, String itemId, String json) throws Exception {
        return mockMvc.perform(post("/api/learning/items/" + itemId + "/resources").with(user(email).roles("USER"))
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private static String itemId(ResultActions result) throws Exception {
        return JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.id");
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
