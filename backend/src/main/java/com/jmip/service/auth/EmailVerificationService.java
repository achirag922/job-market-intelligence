package com.jmip.service.auth;

import com.jmip.common.exception.VerificationCodeException;
import com.jmip.config.VerificationProperties;
import com.jmip.dto.auth.VerificationStatusResponse;
import com.jmip.entity.EmailVerificationCode;
import com.jmip.entity.User;
import com.jmip.repository.EmailVerificationCodeRepository;
import com.jmip.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;

/**
 * Email verification by 6-digit one-time code.
 *
 * <p>Codes come from {@link SecureRandom} and are stored only as an HMAC-SHA256 keyed by a
 * server secret, so a copy of the database does not reveal them. Each code expires, allows a
 * handful of wrong guesses, and can be replaced only after a cooldown; the auth rate limit
 * adds a per-client ceiling on top. Codes and emails are never logged.
 */
@Service
public class EmailVerificationService {

    private static final Logger log = LoggerFactory.getLogger(EmailVerificationService.class);
    private static final int CODE_LENGTH = 6;

    private final EmailVerificationCodeRepository codeRepository;
    private final UserRepository userRepository;
    private final VerificationCodeSender sender;
    private final VerificationProperties properties;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final SecretKeySpec hmacKey;

    public EmailVerificationService(EmailVerificationCodeRepository codeRepository, UserRepository userRepository,
                                    VerificationCodeSender sender, VerificationProperties properties, Clock clock) {
        this.codeRepository = codeRepository;
        this.userRepository = userRepository;
        this.sender = sender;
        this.properties = properties;
        this.clock = clock;
        byte[] key;
        if (properties.secret().isBlank()) {
            // Outside production only (RequiredProductionSettings demands JMIP_OTP_SECRET there).
            key = new byte[32];
            random.nextBytes(key);
            log.warn("JMIP_OTP_SECRET is not set; using a random key, so pending codes will not survive a restart");
        } else {
            key = properties.secret().getBytes(StandardCharsets.UTF_8);
        }
        this.hmacKey = new SecretKeySpec(key, "HmacSHA256");
    }

    /**
     * Sends a code for a new account, or for an explicit resend, unless one was sent within
     * the cooldown.
     */
    @Transactional
    public VerificationStatusResponse issueFor(User user) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        Optional<EmailVerificationCode> existing = codeRepository.findById(user.getId());
        long wait = existing.map(code -> secondsUntilResend(code, now)).orElse(0L);
        if (wait > 0) {
            return status(user.getEmail(), wait);
        }

        String code = newCode();
        OffsetDateTime expiresAt = now.plus(properties.codeTtl());
        EmailVerificationCode stored = existing
                .map(current -> {
                    current.replace(hash(user, code), expiresAt, now);
                    return current;
                })
                .orElseGet(() -> new EmailVerificationCode(user.getId(), hash(user, code), expiresAt, now));
        codeRepository.save(stored);

        try {
            sender.send(user.getEmail(), user.getFullName(), code, properties.codeTtl());
            log.info("Verification code sent for user {}", user.getId());
        } catch (RuntimeException failure) {
            // The account and code still stand; the user can ask for another email.
            log.warn("Verification email for user {} could not be sent: {}", user.getId(),
                    failure.getClass().getSimpleName());
        }
        return status(user.getEmail(), properties.resendCooldown().toSeconds());
    }

    /** At sign-in of an unverified account: send a code only if there is no usable one. */
    @Transactional
    public void issueIfNoValidCode(User user) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        boolean usable = codeRepository.findById(user.getId())
                .filter(code -> code.getExpiresAt().isAfter(now) && code.getFailedAttempts() < properties.maxAttempts())
                .isPresent();
        if (!usable) {
            issueFor(user);
        }
    }

    /**
     * The same answer for every email, known or not, verified or not; a code is only sent to
     * an account that is actually waiting for one.
     */
    @Transactional
    public VerificationStatusResponse resend(String email) {
        String normalised = normalise(email);
        return userRepository.findByEmail(normalised)
                .filter(user -> !user.isEmailVerified())
                .map(this::issueFor)
                .orElseGet(() -> status(email, properties.resendCooldown().toSeconds()));
    }

    /**
     * Confirms the email. A wrong guess is counted even though the call fails, hence no
     * rollback for the refusal.
     *
     * @throws VerificationCodeException wrong, expired, or out of attempts
     */
    @Transactional(noRollbackFor = VerificationCodeException.class)
    public void verify(String email, String code) {
        User user = userRepository.findByEmail(normalise(email))
                .filter(candidate -> !candidate.isEmailVerified())
                .orElseThrow(() -> new VerificationCodeException(VerificationCodeException.INVALID));
        EmailVerificationCode stored = codeRepository.findById(user.getId())
                .orElseThrow(() -> new VerificationCodeException(VerificationCodeException.INVALID));

        OffsetDateTime now = OffsetDateTime.now(clock);
        if (!stored.getExpiresAt().isAfter(now)) {
            throw new VerificationCodeException(VerificationCodeException.EXPIRED);
        }
        if (stored.getFailedAttempts() >= properties.maxAttempts()) {
            throw new VerificationCodeException(VerificationCodeException.TOO_MANY_ATTEMPTS);
        }
        boolean matches = MessageDigest.isEqual(
                hash(user, code).getBytes(StandardCharsets.US_ASCII),
                stored.getCodeHash().getBytes(StandardCharsets.US_ASCII));
        if (!matches) {
            stored.recordFailedAttempt();
            codeRepository.save(stored);
            throw new VerificationCodeException(stored.getFailedAttempts() >= properties.maxAttempts()
                    ? VerificationCodeException.TOO_MANY_ATTEMPTS
                    : VerificationCodeException.INVALID);
        }

        user.markEmailVerified(now);
        userRepository.save(user);
        codeRepository.delete(stored);
        log.info("User {} verified their email", user.getId());
    }

    private long secondsUntilResend(EmailVerificationCode code, OffsetDateTime now) {
        Duration since = Duration.between(code.getLastSentAt(), now);
        return Math.max(0, properties.resendCooldown().minus(since).toSeconds());
    }

    private VerificationStatusResponse status(String email, long resendInSeconds) {
        return new VerificationStatusResponse(email, resendInSeconds, properties.codeTtl().toSeconds());
    }

    private String newCode() {
        return String.format("%0" + CODE_LENGTH + "d", random.nextInt((int) Math.pow(10, CODE_LENGTH)));
    }

    /** Bound to the user, so a code's hash is useless for any other account. */
    private String hash(User user, String code) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(hmacKey);
            return HexFormat.of().formatHex(
                    mac.doFinal((user.getId() + ":" + code).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException("HmacSHA256 is unavailable", impossible);
        }
    }

    private static String normalise(String email) {
        return email == null ? "" : email.strip().toLowerCase(Locale.ROOT);
    }
}
