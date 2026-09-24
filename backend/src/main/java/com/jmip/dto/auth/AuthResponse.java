package com.jmip.dto.auth;

/**
 * The signed-in user, plus the CSRF token for this session.
 *
 * <p>The session itself travels only in the HttpOnly cookie, which scripts cannot read. The
 * CSRF token is the opposite: the frontend keeps it in memory — never in localStorage — and
 * echoes it in an {@code X-CSRF-TOKEN} header on requests that change state. A forged
 * cross-site request carries the cookie at most, never the token.
 */
public record AuthResponse(UserResponse user, String csrfToken) {
}
