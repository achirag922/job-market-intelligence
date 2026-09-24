package com.jmip.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jmip.common.exception.EmailAlreadyRegisteredException;
import com.jmip.common.exception.InvalidRequestException;
import com.jmip.dto.auth.LoginRequest;
import com.jmip.dto.auth.RegisterRequest;
import com.jmip.dto.auth.UserResponse;
import com.jmip.entity.User;
import com.jmip.entity.UserRole;
import com.jmip.repository.UserRepository;
import com.jmip.service.auth.AppUserDetailsService;
import com.jmip.service.auth.UserService;
import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * V6.10.1: the user model, password hashing and the security chain, against the real schema.
 */
@SpringBootTest(properties = "jmip.ai.provider=stub")
@AutoConfigureMockMvc
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
class UserFoundationIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine");

    private static final String PASSWORD = "correct horse battery";

    @Autowired
    private UserService userService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AppUserDetailsService userDetailsService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MockMvc mockMvc;

    @BeforeEach
    void clear() {
        jdbcTemplate.execute("TRUNCATE users");
    }

    // ------------------------------------------------------------------ persistence and role

    @Test
    @DisplayName("a registered user is persisted with a normalised email, the USER role and timestamps")
    void persistsUser() {
        UserResponse created = userService.register(new RegisterRequest("  Jane.Doe@Example.COM ", PASSWORD));

        User stored = userRepository.findById(created.id()).orElseThrow();
        assertThat(stored.getEmail()).isEqualTo("jane.doe@example.com");
        assertThat(stored.getRole()).isEqualTo(UserRole.USER);
        assertThat(stored.getCreatedAt()).isNotNull();
        assertThat(stored.getUpdatedAt()).isEqualTo(stored.getCreatedAt());
        assertThat(created.role()).isEqualTo(UserRole.USER);
        assertThat(userService.findByEmail("JANE.DOE@example.com")).isPresent();
    }

    @Test
    @DisplayName("the role becomes a Spring Security authority, and an unknown email reveals nothing")
    void roleAsAuthority() {
        userService.register(new RegisterRequest("jane@example.com", PASSWORD));

        UserDetails details = userDetailsService.loadUserByUsername("Jane@Example.com");
        assertThat(details.getAuthorities()).extracting(GrantedAuthority::getAuthority).containsExactly("ROLE_USER");
        assertThat(UserRole.USER.authority()).isEqualTo("ROLE_USER");

        assertThatThrownBy(() -> userDetailsService.loadUserByUsername("nobody@example.com"))
                .isInstanceOf(UsernameNotFoundException.class)
                .hasMessage("Invalid credentials");
    }

    // ------------------------------------------------------------------ password hashing

    @Test
    @DisplayName("passwords are stored only as salted bcrypt hashes that verify the original")
    void passwordIsHashed() {
        UserResponse first = userService.register(new RegisterRequest("a@example.com", PASSWORD));
        UserResponse second = userService.register(new RegisterRequest("b@example.com", PASSWORD));

        String storedHash = jdbcTemplate.queryForObject(
                "SELECT password_hash FROM users WHERE id = ?", String.class, first.id());
        assertThat(storedHash).startsWith("{bcrypt}$2a$12$").doesNotContain(PASSWORD);
        // Salted: the same password never produces the same stored value.
        assertThat(userRepository.findById(second.id()).orElseThrow().getPasswordHash()).isNotEqualTo(storedHash);

        User user = userRepository.findById(first.id()).orElseThrow();
        assertThat(userService.passwordMatches(user, PASSWORD)).isTrue();
        assertThat(userService.passwordMatches(user, PASSWORD + "!")).isFalse();
        assertThat(userService.passwordMatches(user, null)).isFalse();
    }

    // ------------------------------------------------------------------ unique email

    @Test
    @DisplayName("an email can register once, whatever its letter case")
    void emailIsUnique() {
        userService.register(new RegisterRequest("jane@example.com", PASSWORD));

        assertThatThrownBy(() -> userService.register(new RegisterRequest("JANE@example.com", PASSWORD)))
                .isInstanceOf(EmailAlreadyRegisteredException.class)
                .hasMessageNotContaining("jane");
        assertThat(userRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("the database itself enforces uniqueness and normalised emails")
    void databaseConstraints() {
        userService.register(new RegisterRequest("jane@example.com", PASSWORD));

        assertThatThrownBy(() -> insertRaw("jane@example.com")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertRaw("Other@Example.com")).isInstanceOf(DataIntegrityViolationException.class);
    }

    // ------------------------------------------------------------------ validation

    @Test
    @DisplayName("invalid emails and weak or oversized passwords are rejected before anything is stored")
    void validation() {
        assertThatThrownBy(() -> userService.register(new RegisterRequest("not-an-email", PASSWORD)))
                .isInstanceOf(ConstraintViolationException.class);
        assertThatThrownBy(() -> userService.register(new RegisterRequest(" ", PASSWORD)))
                .isInstanceOf(ConstraintViolationException.class);
        assertThatThrownBy(() -> userService.register(new RegisterRequest("jane@example.com", "short")))
                .isInstanceOf(ConstraintViolationException.class);
        assertThatThrownBy(() -> userService.register(new RegisterRequest("jane@example.com", "x".repeat(73))))
                .isInstanceOf(ConstraintViolationException.class);
        // 25 characters but 75 bytes: within @Size, beyond what bcrypt would actually read.
        assertThatThrownBy(() -> userService.register(new RegisterRequest("jane@example.com", "€".repeat(25))))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("72 bytes");

        assertThat(userRepository.count()).isZero();
    }

    // ------------------------------------------------------------------ no exposure

    @Test
    @DisplayName("neither the password nor its hash appears in responses, toString or logs")
    void noPasswordExposure(CapturedOutput output) throws Exception {
        UserResponse created = userService.register(new RegisterRequest("jane@example.com", PASSWORD));
        User user = userRepository.findById(created.id()).orElseThrow();

        String json = objectMapper.writeValueAsString(created);
        assertThat(objectMapper.readValue(json, Map.class)).containsOnlyKeys("id", "email", "role", "createdAt");
        assertThat(json).doesNotContain("password", "$2a$");

        assertThat(user.toString()).doesNotContain(user.getPasswordHash(), "jane@example.com");
        assertThat(new RegisterRequest("jane@example.com", PASSWORD).toString()).doesNotContain(PASSWORD);
        assertThat(new LoginRequest("jane@example.com", PASSWORD).toString()).doesNotContain(PASSWORD);

        assertThat(output.getAll()).doesNotContain(PASSWORD, user.getPasswordHash(), "jane@example.com");
    }

    // ------------------------------------------------------------------ security chain

    @Test
    @DisplayName("anonymous API calls get a JSON 401 without a session or a login prompt; health stays public")
    void anonymousCallsAreRefused(CapturedOutput output) throws Exception {
        // V6.10.3: the application APIs require a signed-in user.
        mockMvc.perform(get("/api/jobs"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(header().doesNotExist("WWW-Authenticate"));
        mockMvc.perform(post("/api/assistant/query").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"top skills\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
        // Spring Boot's default user, and the generated password it would log, never exist.
        assertThat(output.getAll()).doesNotContain("Using generated security password");
    }

    private void insertRaw(String email) {
        jdbcTemplate.update("INSERT INTO users (id, email, password_hash, role) VALUES (?, ?, '{bcrypt}x', 'USER')",
                UUID.randomUUID(), email);
    }
}
