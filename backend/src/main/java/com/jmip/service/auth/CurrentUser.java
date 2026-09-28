package com.jmip.service.auth;

import com.jmip.common.RequestMemo;
import com.jmip.common.exception.AuthenticationFailedException;
import com.jmip.entity.User;
import com.jmip.repository.UserRepository;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.UUID;

/**
 * The signed-in account, taken from the server-side session. Services ask this, never the
 * request: an id sent by the frontend is only ever a claim, the session is the fact.
 */
@Component
public class CurrentUser {

    private final UserRepository userRepository;

    public CurrentUser(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /** @throws AuthenticationFailedException (401) when nobody is signed in or the account is gone */
    public UUID requireId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            throw new AuthenticationFailedException(AuthenticationFailedException.NOT_SIGNED_IN);
        }
        String email = authentication.getName().strip().toLowerCase(Locale.ROOT);
        // V7.9: one lookup per request, keyed by the signed-in email, however many services ask.
        UUID id = RequestMemo.get("currentUserId:" + email, () -> userRepository.findByEmail(email).map(User::getId).orElse(null));
        if (id == null) {
            throw new AuthenticationFailedException(AuthenticationFailedException.NOT_SIGNED_IN);
        }
        return id;
    }
}
