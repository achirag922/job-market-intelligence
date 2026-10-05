package com.jmip.dto.notification;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotNull;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** V9.16: the notification center. The owner always comes from the session. */
public final class NotificationDtos {

    private NotificationDtos() {
    }

    /** @param type JOB_MATCH, FOLLOW_UP, INTERVIEW, LEARNING or CAREER */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Notification(UUID id, String type, String title, String body, String link, OffsetDateTime createdAt,
                               OffsetDateTime readAt) {
    }

    public record Inbox(List<Notification> items, int unreadCount) {
    }

    public record UnreadCount(int unreadCount) {
    }

    /** Which kinds are created and shown; all on by default. */
    public record Preferences(@NotNull Boolean jobMatches, @NotNull Boolean followUps, @NotNull Boolean interviews,
                              @NotNull Boolean learning, @NotNull Boolean career) {
    }
}
