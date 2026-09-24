package com.jmip.service.auth;

import com.jmip.common.exception.EmailAlreadyRegisteredException;
import com.jmip.common.exception.InvalidRequestException;
import com.jmip.dto.auth.RegisterRequest;
import com.jmip.dto.auth.UserResponse;
import com.jmip.entity.User;
import com.jmip.entity.UserRole;
import com.jmip.repository.UserRepository;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Creates and looks up user accounts. The only place a plain-text password is handled: it
 * is hashed here and never stored, returned or logged.
 */
@Service
@Validated
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    /** bcrypt ignores everything past 72 bytes, so a longer password would be silently weakened. */
    static final int MAX_PASSWORD_BYTES = 72;

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;
    private final EmailVerificationService emailVerificationService;

    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder, Clock clock,
                       EmailVerificationService emailVerificationService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
        this.emailVerificationService = emailVerificationService;
    }

    /**
     * Registers a new, unverified user with the default {@link UserRole#USER} role and emails
     * a verification code. The account cannot sign in until the code is entered.
     *
     * @throws EmailAlreadyRegisteredException when the email is taken, in any letter case
     */
    @Transactional
    public UserResponse register(@Valid RegisterRequest request) {
        if (request.password().getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
            throw new InvalidRequestException("password must be at most " + MAX_PASSWORD_BYTES + " bytes");
        }
        String email = normaliseEmail(request.email());
        PasswordPolicy.check(request.password(), email);
        if (userRepository.existsByEmail(email)) {
            throw new EmailAlreadyRegisteredException();
        }

        User user = new User(UUID.randomUUID(), request.fullName(), email, passwordEncoder.encode(request.password()),
                UserRole.USER, OffsetDateTime.now(clock));
        try {
            // Flushed here so a concurrent registration of the same email fails on the unique
            // constraint inside this call, as the same exception, not later at commit.
            userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException raceLost) {
            throw new EmailAlreadyRegisteredException();
        }
        // The id only: the email is personal data, and nothing about the password is logged.
        log.info("User {} registered with role {}", user.getId(), user.getRole());
        emailVerificationService.issueFor(user);
        return UserResponse.of(user);
    }

    @Transactional(readOnly = true)
    public Optional<User> findByEmail(String email) {
        return email == null ? Optional.empty() : userRepository.findByEmail(normaliseEmail(email));
    }

    /** Checks a plain-text password against the stored hash, in constant time. */
    public boolean passwordMatches(User user, String rawPassword) {
        return rawPassword != null && passwordEncoder.matches(rawPassword, user.getPasswordHash());
    }

    static String normaliseEmail(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }
}
