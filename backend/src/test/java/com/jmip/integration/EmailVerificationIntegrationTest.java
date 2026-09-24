package com.jmip.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jmip.service.auth.VerificationCodeSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
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
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Email verification by one-time code: issuing, checking, expiry, attempts and resend.
 * Deliveries are captured instead of emailed.
 */
@SpringBootTest(properties = {"jmip.ai.provider=stub", "jmip.security.password.bcrypt-strength=4",
        "jmip.verification.secret=test-secret-for-hmac"})
@AutoConfigureMockMvc
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
class EmailVerificationIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine");

    private static final String EMAIL = "jane@gmail.com";
    private static final String PASSWORD = "correct horse battery";

    record Delivery(String email, String fullName, String code) {
    }

    /** Captures every code "sent", in place of the log or SMTP sender. */
    @TestConfiguration
    static class RecordingSender {
        static final List<Delivery> SENT = new CopyOnWriteArrayList<>();

        @Bean
        @Primary
        VerificationCodeSender recordingVerificationCodeSender() {
            return (email, fullName, code, validFor) -> SENT.add(new Delivery(email, fullName, code));
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void clear() {
        jdbcTemplate.execute("TRUNCATE users CASCADE");
        RecordingSender.SENT.clear();
    }

    @Test
    @DisplayName("signup emails one 6-digit code to the new address and stores only its hash")
    void signupSendsCode() throws Exception {
        signup();

        assertThat(RecordingSender.SENT).hasSize(1);
        Delivery delivery = RecordingSender.SENT.get(0);
        assertThat(delivery.email()).isEqualTo(EMAIL);
        assertThat(delivery.fullName()).isEqualTo("Jane Doe");
        assertThat(delivery.code()).matches("\\d{6}");

        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT code_hash, expires_at, failed_attempts FROM email_verification_codes");
        assertThat((String) row.get("code_hash")).hasSize(64).doesNotContain(delivery.code());
        assertThat(jdbcTemplate.queryForObject("SELECT email_verified_at IS NULL FROM users", Boolean.class)).isTrue();
    }

    @Test
    @DisplayName("the right code verifies the email, after which login works; the code cannot be reused")
    void correctCodeVerifies() throws Exception {
        signup();
        String code = lastCode();

        verify(EMAIL.toUpperCase(), code).andExpect(status().isNoContent());

        assertThat(jdbcTemplate.queryForObject("SELECT email_verified_at IS NOT NULL FROM users", Boolean.class)).isTrue();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM email_verification_codes", Integer.class)).isZero();
        login().andExpect(status().isOk()).andExpect(jsonPath("$.user.emailVerified").value(true))
                .andExpect(jsonPath("$.user.fullName").value("Jane Doe"));
        verify(EMAIL, code).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("a wrong code is refused and counted; after five wrong guesses even the right one is refused")
    void wrongCodesAreCounted() throws Exception {
        signup();
        String code = lastCode();
        String wrong = code.equals("111111") ? "222222" : "111111";

        for (int attempt = 1; attempt <= 4; attempt++) {
            verify(EMAIL, wrong).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("That code is not correct. Check the email and try again."));
        }
        verify(EMAIL, wrong).andExpect(jsonPath("$.message").value("Too many incorrect attempts. Request a new code."));
        assertThat(jdbcTemplate.queryForObject("SELECT failed_attempts FROM email_verification_codes", Integer.class))
                .isEqualTo(5);

        verify(EMAIL, code).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Too many incorrect attempts. Request a new code."));
    }

    @Test
    @DisplayName("an expired code is refused with its own message")
    void expiredCode() throws Exception {
        signup();
        jdbcTemplate.update("UPDATE email_verification_codes SET expires_at = now() - interval '1 second'");

        verify(EMAIL, lastCode()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("That code has expired. Request a new one."));
    }

    @Test
    @DisplayName("an unknown email looks exactly like a wrong code")
    void unknownEmail() throws Exception {
        verify("nobody@gmail.com", "123456").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("That code is not correct. Check the email and try again."));
    }

    @Test
    @DisplayName("resend respects the cooldown, then sends a new code that replaces the old one")
    void resendCooldown() throws Exception {
        signup();
        String first = lastCode();

        resend(EMAIL).andExpect(status().isAccepted()).andExpect(jsonPath("$.resendAvailableInSeconds").value(
                org.hamcrest.Matchers.allOf(org.hamcrest.Matchers.greaterThan(0), org.hamcrest.Matchers.lessThanOrEqualTo(60))));
        assertThat(RecordingSender.SENT).hasSize(1);

        jdbcTemplate.update("UPDATE email_verification_codes SET last_sent_at = now() - interval '61 seconds', failed_attempts = 3");
        resend(EMAIL).andExpect(status().isAccepted()).andExpect(jsonPath("$.resendAvailableInSeconds").value(60));
        assertThat(RecordingSender.SENT).hasSize(2);
        assertThat(jdbcTemplate.queryForObject("SELECT failed_attempts FROM email_verification_codes", Integer.class)).isZero();

        String second = lastCode();
        if (!second.equals(first)) {
            verify(EMAIL, first).andExpect(status().isBadRequest());
        }
        verify(EMAIL, second).andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("resend answers the same for unknown and already-verified emails, and sends nothing")
    void resendDoesNotEnumerate() throws Exception {
        resend("nobody@gmail.com").andExpect(status().isAccepted())
                .andExpect(jsonPath("$.resendAvailableInSeconds").value(60))
                .andExpect(jsonPath("$.codeValidForSeconds").value(600));
        signup();
        verify(EMAIL, lastCode()).andExpect(status().isNoContent());
        RecordingSender.SENT.clear();

        resend(EMAIL).andExpect(status().isAccepted()).andExpect(jsonPath("$.resendAvailableInSeconds").value(60));
        assertThat(RecordingSender.SENT).isEmpty();
    }

    @Test
    @DisplayName("login of an unverified account sends a fresh code only when the last one is no longer usable")
    void loginReissuesOnlyWhenNeeded() throws Exception {
        signup();
        login().andExpect(status().isForbidden());
        assertThat(RecordingSender.SENT).hasSize(1);

        jdbcTemplate.update("UPDATE email_verification_codes SET expires_at = now() - interval '1 second', "
                + "last_sent_at = now() - interval '11 minutes'");
        login().andExpect(status().isForbidden());
        assertThat(RecordingSender.SENT).hasSize(2);
    }

    @Test
    @DisplayName("malformed codes are rejected by validation, and no code or email reaches the log")
    void validationAndLogs(CapturedOutput output) throws Exception {
        signup();
        mockMvc.perform(post("/api/auth/verify-email").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + EMAIL + "\",\"code\":\"12ab\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("code"));
        mockMvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\" \",\"email\":\"x@gmail.com\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("fullName"));

        assertThat(output.getAll()).doesNotContain(lastCode(), EMAIL, "Jane Doe", PASSWORD);
    }

    // ------------------------------------------------------------------ helpers

    private void signup() throws Exception {
        mockMvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("fullName", "Jane Doe", "email", EMAIL, "password", PASSWORD))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.emailVerified").value(false));
    }

    private ResultActions login() throws Exception {
        return mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("email", EMAIL, "password", PASSWORD))));
    }

    private ResultActions verify(String email, String code) throws Exception {
        return mockMvc.perform(post("/api/auth/verify-email").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("email", email, "code", code))));
    }

    private ResultActions resend(String email) throws Exception {
        return mockMvc.perform(post("/api/auth/resend-verification").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("email", email))));
    }

    private static String lastCode() {
        return RecordingSender.SENT.get(RecordingSender.SENT.size() - 1).code();
    }

}
