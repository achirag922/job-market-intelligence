package com.jmip.controller;

import com.jmip.dto.auth.AuthResponse;
import com.jmip.dto.auth.LoginRequest;
import com.jmip.dto.auth.RegisterRequest;
import com.jmip.dto.auth.ResendVerificationRequest;
import com.jmip.dto.auth.UserResponse;
import com.jmip.dto.auth.VerificationStatusResponse;
import com.jmip.dto.auth.VerifyEmailRequest;
import com.jmip.service.auth.AuthSessionService;
import com.jmip.service.auth.EmailVerificationService;
import com.jmip.service.auth.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sign up, sign in and sign out for the browser frontend. Request bodies are never logged:
 * they carry passwords.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final UserService userService;
    private final AuthSessionService authSessionService;
    private final EmailVerificationService emailVerificationService;

    public AuthController(UserService userService, AuthSessionService authSessionService,
                          EmailVerificationService emailVerificationService) {
        this.userService = userService;
        this.authSessionService = authSessionService;
        this.emailVerificationService = emailVerificationService;
    }

    /** Creates an unverified USER account and emails a code. It does not sign in. */
    @PostMapping("/signup")
    public ResponseEntity<UserResponse> signup(@Valid @RequestBody RegisterRequest request) {
        log.info("POST /api/auth/signup");
        return ResponseEntity.status(HttpStatus.CREATED).body(userService.register(request));
    }

    /** Starts a session: sets the HttpOnly session cookie and returns the user and CSRF token. */
    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request,
                                              HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        log.info("POST /api/auth/login");
        return ResponseEntity.ok(authSessionService.login(request, httpRequest, httpResponse));
    }

    /** Ends the session and clears the cookie. Requires the CSRF token when a session exists. */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        log.info("POST /api/auth/logout");
        authSessionService.logout(httpRequest, httpResponse);
        return ResponseEntity.noContent().build();
    }

    /**
     * Confirms the email with the 6-digit code. Does not sign in: the frontend signs in with
     * the credentials the user just typed, so a code alone never grants a session.
     */
    @PostMapping("/verify-email")
    public ResponseEntity<Void> verifyEmail(@Valid @RequestBody VerifyEmailRequest request) {
        log.info("POST /api/auth/verify-email");
        emailVerificationService.verify(request.email(), request.code());
        return ResponseEntity.noContent().build();
    }

    /** Sends a new code if one is due. Answers the same for every email, known or not. */
    @PostMapping("/resend-verification")
    public ResponseEntity<VerificationStatusResponse> resendVerification(
            @Valid @RequestBody ResendVerificationRequest request) {
        log.info("POST /api/auth/resend-verification");
        return ResponseEntity.accepted().body(emailVerificationService.resend(request.email()));
    }

    /** Who is signed in, for restoring state after a page load. 401 when nobody is. */
    @GetMapping("/me")
    public ResponseEntity<AuthResponse> me(HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        return ResponseEntity.ok(authSessionService.currentSession(httpRequest, httpResponse));
    }
}
