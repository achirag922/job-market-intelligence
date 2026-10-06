package com.jmip.service.auth;

import com.jmip.common.exception.AuthenticationFailedException;
import com.jmip.common.exception.InvalidRequestException;
import com.jmip.dto.auth.AccountDtos.PasswordChangeRequest;
import com.jmip.dto.auth.UserResponse;
import com.jmip.dto.resume.ResumeResponse;
import com.jmip.entity.User;
import com.jmip.repository.UserRepository;
import com.jmip.service.resume.ResumeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * V9.17: the signed-in user's own account: their name, their password and deleting the account.
 * Changing the password or deleting needs the current password. Deleting removes the stored resume
 * files through the existing resume deletion, then the account; everything else the account owns is
 * removed with it by the database's cascades.
 */
@Service
public class AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);
    private static final int MAX_PASSWORD_BYTES = 72;

    private final CurrentUser currentUser;
    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final ResumeService resumeService;
    private final Clock clock;

    public AccountService(CurrentUser currentUser, UserRepository users, PasswordEncoder passwordEncoder,
                          ResumeService resumeService, Clock clock) {
        this.currentUser = currentUser;
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.resumeService = resumeService;
        this.clock = clock;
    }

    @Transactional
    public UserResponse changeName(String fullName) {
        User user = require();
        user.changeFullName(fullName.strip(), OffsetDateTime.now(clock));
        return UserResponse.of(user);
    }

    @Transactional
    public void changePassword(PasswordChangeRequest request) {
        User user = require();
        requirePassword(user, request.currentPassword());
        if (request.newPassword().getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
            throw new InvalidRequestException("password must be at most " + MAX_PASSWORD_BYTES + " bytes");
        }
        if (passwordEncoder.matches(request.newPassword(), user.getPasswordHash())) {
            throw new InvalidRequestException("Choose a password different from your current one");
        }
        user.changePasswordHash(passwordEncoder.encode(request.newPassword()), OffsetDateTime.now(clock));
        log.info("User {} changed their password", user.getId());
    }

    @Transactional
    public void delete(String password) {
        User user = require();
        requirePassword(user, password);
        for (ResumeResponse resume : resumeService.list()) {
            resumeService.delete(resume.id());
        }
        UUID id = user.getId();
        users.delete(user);
        log.info("User {} deleted their account", id);
    }

    private void requirePassword(User user, String password) {
        // 400 rather than 401: the session is valid, only the confirmation is wrong.
        if (password == null || !passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new InvalidRequestException("Your current password is not correct");
        }
    }

    private User require() {
        return users.findById(currentUser.requireId())
                .orElseThrow(() -> new AuthenticationFailedException(AuthenticationFailedException.NOT_SIGNED_IN));
    }
}
