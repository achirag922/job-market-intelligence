package com.jmip.integration;

import com.jayway.jsonpath.JsonPath;
import com.jmip.ai.AiClient;
import com.jmip.ai.AiCompletionRequest;
import com.jmip.ai.AiFailureException;
import com.jmip.ai.StubAiClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * V8.7: interview preparation. Job 1 (Backend Developer at Acme, 3–5 years) lists Java and
 * Docker and talks about microservices. Alice's resume has Java and Python; Bob has no resume.
 * The AI is the stub, wrapped so a test can make it fail or answer with something unreadable.
 */
@SpringBootTest(properties = {"jmip.ai.provider=stub", "jmip.alerts.enabled=false"})
@AutoConfigureMockMvc
@Testcontainers
class InterviewPreparationIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine");

    private static final String ALICE = "alice@example.test";
    private static final String BOB = "bob@example.test";

    static final class SwitchableAi implements AiClient {
        private final StubAiClient stub = new StubAiClient();
        volatile String mode = "ok";
        final List<String> payloads = new java.util.concurrent.CopyOnWriteArrayList<>();

        @Override
        public String providerName() {
            return "stub";
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public String complete(AiCompletionRequest request) {
            payloads.add(request.user());
            return switch (mode) {
                case "fail" -> throw new AiFailureException("provider down");
                case "garbage" -> "I think it was a good answer!";
                default -> stub.complete(request);
            };
        }
    }

    static final SwitchableAi AI = new SwitchableAi();

    @TestConfiguration
    static class Ai {
        @Bean
        @Primary
        AiClient switchableAiClient() {
            return AI;
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID aliceResume;

    @BeforeEach
    void seed() {
        AI.mode = "ok";
        AI.payloads.clear();
        jdbcTemplate.execute("TRUNCATE interview_questions, interview_sessions, match_preferences, resume_skills, resumes, "
                + "job_skills, jobs, skills, companies, locations, users RESTART IDENTITY CASCADE");
        UUID alice = account(ALICE);
        account(BOB);
        jdbcTemplate.update("INSERT INTO companies (id, name) VALUES (1, 'Acme Systems')");
        jdbcTemplate.update("INSERT INTO skills (id, name, category) VALUES (1, 'Java', 'LANGUAGE'), (2, 'Docker', 'PLATFORM'), "
                + "(3, 'Python', 'LANGUAGE')");
        jdbcTemplate.update("""
                INSERT INTO jobs (id, title, company_id, description, source, source_url, content_fingerprint,
                                  experience_min, experience_max)
                VALUES (1, 'Backend Developer', 1, 'Design microservices in Java. Run microservices in Docker containers.',
                        'itest', 'https://example.invalid/1', ?, 3, 5)
                """, String.format("%064d", 1));
        jdbcTemplate.update("INSERT INTO job_skills (job_id, skill_id) VALUES (1, 1), (1, 2)");
        aliceResume = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO resumes (id, user_id, original_file_name, stored_file_name, content_type, file_size_bytes,
                                     processing_status, processed_at, extracted_text, title, is_default, updated_at)
                VALUES (?, ?, 'cv.pdf', ?, 'application/pdf', 100, 'COMPLETED', now(), 'Skills\nJava, Python', 'CV', TRUE, now())
                """, aliceResume, alice, aliceResume + ".pdf");
        jdbcTemplate.update("INSERT INTO resume_skills (resume_id, skill_id) VALUES (?, 1), (?, 3)", aliceResume, aliceResume);
    }

    @Test
    @DisplayName("questions come from the job's skills, the resume's skills and gaps, the posting's terms and its experience")
    void questionsFromJobAndResume() throws Exception {
        start(ALICE)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.jobTitle").value("Backend Developer"))
                .andExpect(jsonPath("$.resumeId").value(aliceResume.toString()))
                .andExpect(jsonPath("$.questions", hasSize(8)))
                .andExpect(jsonPath("$.questions[0].category").value("TECHNICAL"))
                .andExpect(jsonPath("$.questions[0].focus").value("Java"))
                .andExpect(jsonPath("$.questions[1].focus").value("Docker"))
                .andExpect(jsonPath("$.questions[1].question", containsString("does not mention")))
                .andExpect(jsonPath("$.questions[2].question", containsString("microservices")))
                .andExpect(jsonPath("$.questions[*].question", hasItem(containsString("3–5 years"))))
                .andExpect(jsonPath("$.questions[*].question", hasItem(containsString("Your resume lists Python"))))
                .andExpect(jsonPath("$.questions[*].category", hasItem("BEHAVIORAL")))
                .andExpect(jsonPath("$.questions[*].feedbackStatus", everyItem(org.hamcrest.Matchers.is("NOT_ANSWERED"))));

        // Without a resume, skills are asked about neutrally and nothing is claimed about a resume.
        start(BOB)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.questions[0].question", containsString("How have you used it")))
                .andExpect(jsonPath("$.questions[*].category", not(hasItem("RESUME"))))
                .andExpect(jsonPath("$.questions[*].question", not(hasItem(containsString("your resume")))));
    }

    @Test
    @DisplayName("an answer is saved, evaluated through the AI provider with the job's data, and the feedback is stored")
    void answerAndFeedback() throws Exception {
        String session = sessionId(ALICE);
        answer(ALICE, session, 1, "At my last job I used Java to rebuild a slow reporting service. I profiled it, moved the "
                + "heavy queries to batch jobs and cut the response time in half. It now serves every team.")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.feedbackStatus").value("EVALUATED"))
                .andExpect(jsonPath("$.feedback.relevance").value(4))
                .andExpect(jsonPath("$.feedback.completeness").value(3))
                .andExpect(jsonPath("$.feedback.technicalCorrectness").value(4))
                .andExpect(jsonPath("$.feedback.strengths", hasSize(1)))
                .andExpect(jsonPath("$.feedback.improvements", hasSize(1)));

        String payload = AI.payloads.get(0);
        assertThat(payload).contains("JOB TITLE: Backend Developer", "JOB SKILLS: Docker, Java", "RESUME SKILLS: Java, Python",
                "QUESTION FOCUS: Java").doesNotContain(ALICE, aliceResume.toString());

        mockMvc.perform(get("/api/interviews/" + session).with(user(ALICE).roles("USER")))
                .andExpect(jsonPath("$.answered").value(1))
                .andExpect(jsonPath("$.evaluated").value(1))
                .andExpect(jsonPath("$.questions[0].answer", containsString("reporting service")))
                .andExpect(jsonPath("$.questions[0].feedback.score").exists());
        answer(ALICE, session, 2, " ").andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("when the AI fails or replies with something unreadable, the answer is kept and can be evaluated again")
    void aiFailure() throws Exception {
        String session = sessionId(ALICE);
        AI.mode = "fail";
        answer(ALICE, session, 2, "I have not used Docker yet, but I containerised a demo app while learning.")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.feedbackStatus").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.feedbackNote", containsString("not available right now")))
                .andExpect(jsonPath("$.answer", containsString("containerised")))
                .andExpect(jsonPath("$.feedback").doesNotExist());

        AI.mode = "garbage";
        evaluate(ALICE, session, 2).andExpect(jsonPath("$.feedbackStatus").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.feedbackNote", containsString("could not be read")));

        AI.mode = "ok";
        evaluate(ALICE, session, 2).andExpect(jsonPath("$.feedbackStatus").value("EVALUATED"))
                .andExpect(jsonPath("$.evaluationAttempts").value(3));
        // Evaluations per question are bounded.
        evaluate(ALICE, session, 2).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("completing a session stores a summary from the feedback; it then shows in history and takes no more answers")
    void completeAndHistory() throws Exception {
        String session = sessionId(ALICE);
        answer(ALICE, session, 1, "I used Java to build an order service with Spring Boot and tests.").andExpect(status().isOk());
        answer(ALICE, session, 7, "I learned Kotlin in a week to fix a release blocker, pairing with a colleague.")
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/interviews/" + session + "/complete").with(user(ALICE).roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.completedAt").exists())
                .andExpect(jsonPath("$.averageScore").exists())
                .andExpect(jsonPath("$.summary", containsString("You answered 2 of 8 questions; 2 were evaluated")));

        mockMvc.perform(get("/api/interviews").with(user(ALICE).roles("USER")))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].status").value("COMPLETED"))
                .andExpect(jsonPath("$[0].questions").doesNotExist());
        answer(ALICE, session, 3, "Too late.").andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("sessions and resumes are the owner's only")
    void ownership() throws Exception {
        String session = sessionId(ALICE);
        mockMvc.perform(get("/api/interviews/" + session).with(user(BOB).roles("USER"))).andExpect(status().isNotFound());
        answer(BOB, session, 1, "Not mine.").andExpect(status().isNotFound());
        mockMvc.perform(get("/api/interviews").with(user(BOB).roles("USER"))).andExpect(jsonPath("$", hasSize(0)));
        mockMvc.perform(post("/api/interviews").with(user(BOB).roles("USER")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jobId\": 1, \"resumeId\": \"" + aliceResume + "\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/interviews")).andExpect(status().isUnauthorized());
    }

    private ResultActions start(String email) throws Exception {
        return mockMvc.perform(post("/api/interviews").with(user(email).roles("USER")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"jobId\": 1}"));
    }

    private String sessionId(String email) throws Exception {
        return JsonPath.read(start(email).andReturn().getResponse().getContentAsString(), "$.id");
    }

    private ResultActions answer(String email, String session, int position, String text) throws Exception {
        return mockMvc.perform(post("/api/interviews/" + session + "/questions/" + position + "/answer")
                .with(user(email).roles("USER")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"answer\": \"" + text + "\"}"));
    }

    private ResultActions evaluate(String email, String session, int position) throws Exception {
        return mockMvc.perform(post("/api/interviews/" + session + "/questions/" + position + "/evaluate")
                .with(user(email).roles("USER")));
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
