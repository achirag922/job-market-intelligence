package com.jmip.dto.auth;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.jmip.entity.User;
import com.jmip.entity.UserRole;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A user as the API may show it. There is deliberately no password or hash field, so no
 * serialisation setting can ever include one.
 *
 * @param fullName      absent for accounts created before names were collected
 * @param emailVerified whether the email was confirmed with a one-time code
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record UserResponse(UUID id, String fullName, String email, UserRole role, boolean emailVerified,
                           OffsetDateTime createdAt) {

    public static UserResponse of(User user) {
        return new UserResponse(user.getId(), user.getFullName(), user.getEmail(), user.getRole(),
                user.isEmailVerified(), user.getCreatedAt());
    }
}
