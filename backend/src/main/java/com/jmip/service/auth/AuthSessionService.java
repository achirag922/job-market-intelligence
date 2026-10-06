package com.jmip.service.auth;

import com.jmip.common.exception.AuthenticationFailedException;
import com.jmip.common.exception.EmailNotVerifiedException;
import com.jmip.dto.auth.AuthResponse;
import com.jmip.dto.auth.LoginRequest;
import com.jmip.dto.auth.UserResponse;
import com.jmip.entity.User;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.CookieClearingLogoutHandler;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.stereotype.Service;

/**
 * Browser sign-in, backed by a server-side session.
 *
 * <p>The session id lives only in an HttpOnly cookie (see {@code server.servlet.session} in
 * application.yml): scripts cannot read it, so an XSS bug cannot steal it, and nothing is
 * ever put in localStorage. Signing in always issues a brand-new session, so a session id
 * planted before login is worthless afterwards (session fixation).
 */
@Service
public class AuthSessionService {

    private static final Logger log = LoggerFactory.getLogger(AuthSessionService.class);

    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository securityContextRepository;
    private final CsrfTokenRepository csrfTokenRepository;
    private final UserService userService;
    private final String sessionCookieName;
    private final EmailVerificationService emailVerificationService;

    public AuthSessionService(AuthenticationManager authenticationManager,
                              SecurityContextRepository securityContextRepository,
                              CsrfTokenRepository csrfTokenRepository,
                              UserService userService,
                              ServerProperties serverProperties,
                              EmailVerificationService emailVerificationService) {
        this.authenticationManager = authenticationManager;
        this.securityContextRepository = securityContextRepository;
        this.csrfTokenRepository = csrfTokenRepository;
        this.userService = userService;
        this.emailVerificationService = emailVerificationService;
        String configured = serverProperties.getServlet().getSession().getCookie().getName();
        this.sessionCookieName = configured == null ? "JSESSIONID" : configured;
    }

    /**
     * @throws AuthenticationFailedException with one message for every failure, so an
     *                                       unknown email and a wrong password look alike
     */
    public AuthResponse login(LoginRequest request, HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        Authentication authentication;
        try {
            // For an unknown email, the provider still runs a dummy hash comparison, so the
            // response takes as long as for a real account.
            authentication = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(request.email(), request.password()));
        } catch (AuthenticationException failed) {
            // No email in the log: it is personal data, and attempts are counted by the rate limit.
            log.info("Sign-in failed");
            throw new AuthenticationFailedException(AuthenticationFailedException.INVALID_CREDENTIALS);
        }

        // The password was right, but the email is unconfirmed: no session. A code is sent if
        // the user has none that still works, and the UI moves to the code screen.
        User user = currentUser(authentication);
        if (!user.isEmailVerified()) {
            emailVerificationService.issueIfNoValidCode(user);
            log.info("Sign-in for user {} is waiting on email verification", user.getId());
            throw new EmailNotVerifiedException();
        }

        HttpSession previous = httpRequest.getSession(false);
        if (previous != null) {
            previous.invalidate();
        }
        httpRequest.getSession(true);

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, httpRequest, httpResponse);

        log.info("User {} signed in", user.getId());
        return new AuthResponse(UserResponse.of(user), issueCsrfToken(httpRequest, httpResponse));
    }

    /** The signed-in user and this session's CSRF token, for restoring state on page load. */
    public AuthResponse currentSession(HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            throw new AuthenticationFailedException(AuthenticationFailedException.NOT_SIGNED_IN);
        }
        User user = currentUser(authentication);
        CsrfToken token = csrfTokenRepository.loadToken(httpRequest);
        return new AuthResponse(UserResponse.of(user),
                token != null ? token.getToken() : issueCsrfToken(httpRequest, httpResponse));
    }

    /** Ends the session and tells the browser to drop its cookie. Safe to call when signed out. */
    public void logout(HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        new SecurityContextLogoutHandler().logout(httpRequest, httpResponse, authentication);
        new CookieClearingLogoutHandler(sessionCookieName).logout(httpRequest, httpResponse, authentication);
        log.info("Signed out");
    }

    private User currentUser(Authentication authentication) {
        // The account may have been removed since the session began; then the session is void.
        return userService.findByEmail(authentication.getName())
                .orElseThrow(() -> new AuthenticationFailedException(AuthenticationFailedException.NOT_SIGNED_IN));
    }

    private String issueCsrfToken(HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        CsrfToken token = csrfTokenRepository.generateToken(httpRequest);
        csrfTokenRepository.saveToken(token, httpRequest, httpResponse);
        return token.getToken();
    }
}
