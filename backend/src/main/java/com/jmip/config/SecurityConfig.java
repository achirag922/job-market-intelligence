package com.jmip.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jmip.common.exception.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.web.util.matcher.RequestMatcher;

import java.util.Map;
import java.util.Set;

/**
 * Spring Security for browser sign-in (V6.10.2).
 *
 * <p>Every endpoint is still permitted: protecting the existing APIs is a later step. What
 * is in place:
 *
 * <ul>
 *   <li><b>Server-side sessions.</b> Created only at sign-in, never for anonymous callers.
 *       The id travels in an HttpOnly, SameSite cookie configured under
 *       {@code server.servlet.session.cookie}; Secure is on in production.</li>
 *   <li><b>CSRF protection wherever the cookie could be abused.</b> A state-changing request
 *       from a signed-in session must carry the session's token in {@code X-CSRF-TOKEN}.
 *       Anonymous requests carry no credentials, so there is nothing to forge and they are
 *       not asked for one. Signup and login are exempt as well: they take JSON only, which
 *       a cross-site form cannot send, and the cookie is SameSite.</li>
 *   <li><b>CORS unchanged.</b> The Spring MVC CORS configuration applies here too.</li>
 * </ul>
 */
@Configuration
public class SecurityConfig {

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");
    private static final Set<String> CSRF_EXEMPT_PATHS = Set.of("/api/auth/login", "/api/auth/signup");

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   SecurityContextRepository securityContextRepository,
                                                   CsrfTokenRepository csrfTokenRepository,
                                                   ObjectMapper objectMapper) throws Exception {
        return http
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfTokenRepository)
                        // The plain handler: the header value is the token itself, so the SPA
                        // can echo exactly what it was given.
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                        .requireCsrfProtectionMatcher(requiresCsrfToken()))
                .cors(Customizer.withDefaults())
                .securityContext(context -> context.securityContextRepository(securityContextRepository))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .exceptionHandling(errors -> errors.accessDeniedHandler(jsonAccessDenied(objectMapper)))
                .authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
                .build();
    }

    /** A state-changing request that arrives with a live session, other than login and signup. */
    static RequestMatcher requiresCsrfToken() {
        return request -> !SAFE_METHODS.contains(request.getMethod())
                && request.getSession(false) != null
                && !CSRF_EXEMPT_PATHS.contains(pathOf(request));
    }

    private static String pathOf(HttpServletRequest request) {
        return request.getRequestURI().substring(request.getContextPath().length());
    }

    /** CSRF failures in the API's usual error shape instead of an empty 403. */
    private static AccessDeniedHandler jsonAccessDenied(ObjectMapper objectMapper) {
        return (request, response, denied) -> {
            response.setStatus(HttpStatus.FORBIDDEN.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            objectMapper.writeValue(response.getOutputStream(), ApiError.of(HttpStatus.FORBIDDEN.value(),
                    HttpStatus.FORBIDDEN.getReasonPhrase(),
                    "Missing or invalid CSRF token. Reload the page and try again", request.getRequestURI()));
        };
    }

    @Bean
    public SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    /** Stored in the session, sent by the client in the {@code X-CSRF-TOKEN} header. */
    @Bean
    public CsrfTokenRepository csrfTokenRepository() {
        return new HttpSessionCsrfTokenRepository();
    }

    /**
     * bcrypt, wrapped so each stored hash names its algorithm ("{bcrypt}..."). A stronger
     * algorithm can be added later and old hashes upgraded on next sign-in, with no migration.
     *
     * @param strength log2 of the work factor; 12 is roughly a quarter of a second per hash
     */
    @Bean
    public PasswordEncoder passwordEncoder(@Value("${jmip.security.password.bcrypt-strength:12}") int strength) {
        return new DelegatingPasswordEncoder("bcrypt", Map.of("bcrypt", new BCryptPasswordEncoder(strength)));
    }
}
