package com.jmip.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jmip.common.exception.ApiError;
import com.jmip.entity.UserRole;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.web.util.matcher.RequestMatcher;

import java.util.Map;
import java.util.Set;

/**
 * Spring Security for browser sign-in (V6.10.2) and API authorization (V6.10.3).
 *
 * <p>Every {@code /api/**} endpoint requires a signed-in user with the USER role, except
 * signup, login, logout and {@code /me}; an unauthenticated call gets a JSON 401 and never
 * creates a session. Health checks and CORS preflights stay public; anything else needs a
 * signed-in user. Beyond that,
 * the chain provides:
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
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint(jsonUnauthorized(objectMapper))
                        .accessDeniedHandler(jsonAccessDenied(objectMapper)))
                .authorizeHttpRequests(requests -> requests
                        // Error pages render whatever status got here; they must not turn it into a 401.
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers("/error").permitAll()
                        // CORS preflights never carry credentials; the CORS configuration answers them.
                        .requestMatchers(HttpMethod.OPTIONS, "/api/**").permitAll()
                        // Getting into an account, and finding out whether you are in one.
                        .requestMatchers(HttpMethod.POST, "/api/auth/signup", "/api/auth/login", "/api/auth/logout")
                        .permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/auth/me").permitAll()
                        // Container and load-balancer health checks carry no session.
                        .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**", "/actuator/info")
                        .permitAll()
                        // Everything else in the API is for signed-in users.
                        .requestMatchers("/api/**").hasRole(UserRole.USER.name())
                        // Anything else still needs a signed-in user; unknown paths then 404 as before.
                        .anyRequest().authenticated())
                .build();
    }

    /** 401 in the API's usual error shape. No WWW-Authenticate: there is no browser login dialog to offer. */
    private static AuthenticationEntryPoint jsonUnauthorized(ObjectMapper objectMapper) {
        return (request, response, failure) -> {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            objectMapper.writeValue(response.getOutputStream(), ApiError.of(HttpStatus.UNAUTHORIZED.value(),
                    HttpStatus.UNAUTHORIZED.getReasonPhrase(), "Sign in to use this feature", request.getRequestURI()));
        };
    }

    /**
     * A state-changing request that presents the cookie of a live session, other than login
     * and signup. That is exactly the request a forged cross-site call would be: the browser
     * attaches the cookie on its own. A session created during this very request does not count.
     */
    static RequestMatcher requiresCsrfToken() {
        return request -> !SAFE_METHODS.contains(request.getMethod())
                && request.getRequestedSessionId() != null
                && request.isRequestedSessionIdValid()
                && !CSRF_EXEMPT_PATHS.contains(pathOf(request));
    }

    private static String pathOf(HttpServletRequest request) {
        return request.getRequestURI().substring(request.getContextPath().length());
    }

    /** 403s in the API's usual error shape: a CSRF failure, or a signed-in user without the role. */
    private static AccessDeniedHandler jsonAccessDenied(ObjectMapper objectMapper) {
        return (request, response, denied) -> {
            response.setStatus(HttpStatus.FORBIDDEN.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            objectMapper.writeValue(response.getOutputStream(), ApiError.of(HttpStatus.FORBIDDEN.value(),
                    HttpStatus.FORBIDDEN.getReasonPhrase(),
                    denied instanceof CsrfException
                            ? "Missing or invalid CSRF token. Reload the page and try again"
                            : "You do not have access to this resource",
                    request.getRequestURI()));
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
