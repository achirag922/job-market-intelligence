package com.jmip.dto.auth;

import com.jmip.entity.User;
import com.jmip.entity.UserRole;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A user as the API may show it. There is deliberately no password or hash field, so no
 * serialisation setting can ever include one.
 */
public record UserResponse(UUID id, String email, UserRole role, OffsetDateTime createdAt) {

    public static UserResponse of(User user) {
        return new UserResponse(user.getId(), user.getEmail(), user.getRole(), user.getCreatedAt());
    }
}
