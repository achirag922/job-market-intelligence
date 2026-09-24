package com.jmip.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Signup, login and logout over real HTTP, so the session cookie is the one Tomcat actually
 * sends: MockMvc never emits a session cookie, and its attributes are the point here.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"jmip.ai.provider=stub", "jmip.security.password.bcrypt-strength=4"})
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
class AuthIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine");

    private static final String EMAIL = "jane@example.com";
    private static final String PASSWORD = "correct horse battery";

    /** NIO2 works in every environment this runs in, including restricted sandboxes. */
    @TestConfiguration
    static class Nio2Connector {
        @Bean
        WebServerFactoryCustomizer<TomcatServletWebServerFactory> nio2() {
            return factory -> factory.setProtocol("org.apache.coyote.http11.Http11Nio2Protocol");
        }
    }

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void clear() {
        jdbcTemplate.execute("TRUNCATE users");
    }

    // ------------------------------------------------------------------ signup

    @Test
    @DisplayName("signup creates a USER account and returns only safe fields")
    void signupSucceeds() throws Exception {
        Resp response = post("/api/auth/signup", credentials(" Jane@Example.com ", PASSWORD), null, null);

        assertThat(response.statusCode()).isEqualTo(201);
        JsonNode user = objectMapper.readTree(response.body());
        assertThat(fieldNames(user)).containsExactlyInAnyOrder("id", "email", "role", "createdAt");
        assertThat(user.get("email").asText()).isEqualTo(EMAIL);
        assertThat(user.get("role").asText()).isEqualTo("USER");
        assertThat(response.body()).doesNotContain("password", "$2a$");
        // Signing up does not sign in.
        assertThat(response.setCookies()).isEmpty();
        assertThat(jdbcTemplate.queryForObject("SELECT password_hash FROM users", String.class)).startsWith("{bcrypt}");
    }

    @Test
    @DisplayName("a duplicate email is refused, whatever its case")
    void duplicateEmail() throws Exception {
        signup();

        Resp response = post("/api/auth/signup", credentials("JANE@example.com", PASSWORD), null, null);

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(message(response)).isEqualTo("An account with this email already exists");
    }

    @Test
    @DisplayName("an invalid email is a 400 naming the field")
    void invalidEmail() throws Exception {
        Resp response = post("/api/auth/signup", credentials("not-an-email", PASSWORD), null, null);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(objectMapper.readTree(response.body()).at("/fieldErrors/0/field").asText()).isEqualTo("email");
    }

    @Test
    @DisplayName("weak passwords are refused: too short, repetitive, common, or containing the email")
    void weakPasswords() throws Exception {
        assertThat(post("/api/auth/signup", credentials(EMAIL, "short1!"), null, null).statusCode()).isEqualTo(400);

        Resp repetitive = post("/api/auth/signup", credentials(EMAIL, "abababababab"), null, null);
        assertThat(repetitive.statusCode()).isEqualTo(400);
        assertThat(message(repetitive)).contains("repetitive");

        Resp common = post("/api/auth/signup", credentials(EMAIL, "MyPassword2026!"), null, null);
        assertThat(common.statusCode()).isEqualTo(400);
        assertThat(message(common)).contains("commonly used");

        Resp ownEmail = post("/api/auth/signup", credentials(EMAIL, "jane-rocks-2026!"), null, null);
        assertThat(ownEmail.statusCode()).isEqualTo(400);
        assertThat(message(ownEmail)).contains("email");

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM users", Integer.class)).isZero();
    }

    // ------------------------------------------------------------------ login

    @Test
    @DisplayName("login sets an HttpOnly, SameSite=Strict session cookie and returns the user and a CSRF token")
    void loginSucceeds() throws Exception {
        signup();

        Resp response = post("/api/auth/login", credentials("JANE@example.com", PASSWORD), null, null);

        assertThat(response.statusCode()).isEqualTo(200);
        String setCookie = sessionSetCookie(response);
        assertThat(setCookie).startsWith("JMIP_SESSION=").contains("HttpOnly", "SameSite=Strict", "Path=/");
        // Plain HTTP in the default profile; the prod profile adds Secure.
        assertThat(setCookie).doesNotContain("Secure");

        JsonNode body = objectMapper.readTree(response.body());
        assertThat(body.at("/user/email").asText()).isEqualTo(EMAIL);
        assertThat(body.get("csrfToken").asText()).isNotBlank();
        assertThat(response.body()).doesNotContain("password", "$2a$");
    }

    @Test
    @DisplayName("a wrong password and an unknown email get the same 401 and no cookie")
    void loginFailuresLookAlike() throws Exception {
        signup();

        Resp wrongPassword = post("/api/auth/login", credentials(EMAIL, "not the password"), null, null);
        Resp unknownUser = post("/api/auth/login", credentials("nobody@example.com", PASSWORD), null, null);

        for (Resp response : List.of(wrongPassword, unknownUser)) {
            assertThat(response.statusCode()).isEqualTo(401);
            assertThat(message(response)).isEqualTo("Invalid email or password");
            assertThat(response.setCookies()).noneMatch(value -> value.startsWith("JMIP_SESSION="));
        }
    }

    // ------------------------------------------------------------------ session behaviour

    @Test
    @DisplayName("the session cookie identifies the user on later requests; no cookie means not signed in")
    void sessionRestoresUser() throws Exception {
        signup();
        Session session = login();

        Resp me = get("/api/auth/me", session.cookie());
        assertThat(me.statusCode()).isEqualTo(200);
        assertThat(objectMapper.readTree(me.body()).at("/user/email").asText()).isEqualTo(EMAIL);
        assertThat(objectMapper.readTree(me.body()).get("csrfToken").asText()).isEqualTo(session.csrfToken());

        Resp anonymous = get("/api/auth/me", null);
        assertThat(anonymous.statusCode()).isEqualTo(401);
        // An anonymous visit never creates a session.
        assertThat(anonymous.setCookies()).isEmpty();
    }

    @Test
    @DisplayName("signing in again issues a new session and voids the old one")
    void newSessionOnLogin() throws Exception {
        signup();
        Session first = login();

        Resp again = post("/api/auth/login", credentials(EMAIL, PASSWORD), first.cookie(), null);
        String secondCookie = cookiePair(sessionSetCookie(again));

        assertThat(secondCookie).isNotEqualTo(first.cookie());
        assertThat(get("/api/auth/me", first.cookie()).statusCode()).isEqualTo(401);
        assertThat(get("/api/auth/me", secondCookie).statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("a signed-in session must send its CSRF token to change state; anonymous callers need none")
    void csrfProtection() throws Exception {
        signup();
        Session session = login();
        String question = "{\"question\":\"top skills\"}";

        Resp forged = post("/api/assistant/query", question, session.cookie(), null);
        assertThat(forged.statusCode()).isEqualTo(403);
        assertThat(message(forged)).contains("CSRF");

        assertThat(post("/api/assistant/query", question, session.cookie(), "wrong-token").statusCode()).isEqualTo(403);
        assertThat(post("/api/assistant/query", question, session.cookie(), session.csrfToken()).statusCode())
                .isEqualTo(200);
        // Existing behaviour for callers without a session is unchanged.
        assertThat(post("/api/assistant/query", question, null, null).statusCode()).isEqualTo(200);
    }

    // ------------------------------------------------------------------ logout

    @Test
    @DisplayName("logout needs the CSRF token, ends the session and clears the cookie")
    void logout() throws Exception {
        signup();
        Session session = login();

        assertThat(post("/api/auth/logout", "", session.cookie(), null).statusCode()).isEqualTo(403);
        assertThat(get("/api/auth/me", session.cookie()).statusCode()).isEqualTo(200);

        Resp loggedOut = post("/api/auth/logout", "", session.cookie(), session.csrfToken());
        assertThat(loggedOut.statusCode()).isEqualTo(204);
        assertThat(loggedOut.setCookies())
                .anyMatch(value -> value.startsWith("JMIP_SESSION=") && value.contains("Max-Age=0"));

        assertThat(get("/api/auth/me", session.cookie()).statusCode()).isEqualTo(401);
        // Signing out when already signed out is harmless.
        assertThat(post("/api/auth/logout", "", null, null).statusCode()).isEqualTo(204);
    }

    @Test
    @DisplayName("neither the password nor the email is logged during signup, login or failure")
    void nothingSensitiveLogged(CapturedOutput output) throws Exception {
        signup();
        login();
        post("/api/auth/login", credentials(EMAIL, "not the password"), null, null);

        assertThat(output.getAll()).doesNotContain(PASSWORD, "not the password", EMAIL, "$2a$");
    }

    // ------------------------------------------------------------------ helpers

    private record Session(String cookie, String csrfToken) {
    }

    private void signup() throws Exception {
        assertThat(post("/api/auth/signup", credentials(EMAIL, PASSWORD), null, null).statusCode()).isEqualTo(201);
    }

    private Session login() throws Exception {
        Resp response = post("/api/auth/login", credentials(EMAIL, PASSWORD), null, null);
        assertThat(response.statusCode()).isEqualTo(200);
        return new Session(cookiePair(sessionSetCookie(response)),
                objectMapper.readTree(response.body()).get("csrfToken").asText());
    }

    private String credentials(String email, String password) throws Exception {
        return objectMapper.writeValueAsString(Map.of("email", email, "password", password));
    }

    /** Status, body and every Set-Cookie header of one response. */
    private record Resp(int statusCode, String body, List<String> setCookies) {
    }

    private Resp post(String path, String json, String cookie, String csrfToken) throws Exception {
        return send("POST", path, json, cookie, csrfToken);
    }

    private Resp get(String path, String cookie) throws Exception {
        return send("GET", path, null, cookie, null);
    }

    /** Blocking HttpURLConnection: the NIO-based java.net.http client fails in some sandboxes. */
    private Resp send(String method, String path, String json, String cookie, String csrfToken) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) uri(path).toURL().openConnection();
        connection.setRequestMethod(method);
        connection.setInstanceFollowRedirects(false);
        if (cookie != null) {
            connection.setRequestProperty("Cookie", cookie);
        }
        if (csrfToken != null) {
            connection.setRequestProperty("X-CSRF-TOKEN", csrfToken);
        }
        if (json != null) {
            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            try (OutputStream out = connection.getOutputStream()) {
                out.write(body);
            }
        }
        int status = connection.getResponseCode();
        InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        String responseBody = stream == null ? "" : new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        List<String> setCookies = connection.getHeaderFields().getOrDefault("Set-Cookie", List.of());
        connection.disconnect();
        return new Resp(status, responseBody, setCookies);
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private static String sessionSetCookie(Resp response) {
        return response.setCookies().stream()
                .filter(value -> value.startsWith("JMIP_SESSION="))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no session cookie in " + response.setCookies()));
    }

    /** "JMIP_SESSION=abc; Path=/; HttpOnly" becomes "JMIP_SESSION=abc", as a browser would send it. */
    private static String cookiePair(String setCookie) {
        return setCookie.split(";", 2)[0];
    }

    private String message(Resp response) throws Exception {
        JsonNode message = objectMapper.readTree(response.body()).get("message");
        assertThat(message).as("error body %s", response.body()).isNotNull();
        return message.asText();
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new java.util.ArrayList<>();
        for (Iterator<String> it = node.fieldNames(); it.hasNext(); ) {
            names.add(it.next());
        }
        return names;
    }
}
