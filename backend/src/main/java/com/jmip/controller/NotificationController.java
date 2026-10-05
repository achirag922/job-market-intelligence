package com.jmip.controller;

import com.jmip.dto.notification.NotificationDtos.Inbox;
import com.jmip.dto.notification.NotificationDtos.Preferences;
import com.jmip.dto.notification.NotificationDtos.UnreadCount;
import com.jmip.service.notification.NotificationService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** V9.16: the signed-in user's notifications. Nothing here takes a user id; the session decides. */
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationService service;

    public NotificationController(NotificationService service) {
        this.service = service;
    }

    @GetMapping
    public Inbox inbox(@RequestParam(defaultValue = "false") boolean unreadOnly) {
        return service.inbox(unreadOnly);
    }

    @GetMapping("/unread-count")
    public UnreadCount unreadCount() {
        return service.unreadCount();
    }

    @PostMapping("/{id}/read")
    public ResponseEntity<Void> markRead(@PathVariable UUID id) {
        service.markRead(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/read-all")
    public UnreadCount markAllRead() {
        return service.markAllRead();
    }

    @GetMapping("/preferences")
    public Preferences preferences() {
        return service.preferences();
    }

    @PutMapping("/preferences")
    public Preferences savePreferences(@Valid @RequestBody Preferences preferences) {
        return service.savePreferences(preferences);
    }
}
