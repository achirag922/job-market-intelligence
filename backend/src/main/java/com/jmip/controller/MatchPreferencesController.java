package com.jmip.controller;

import com.jmip.dto.resume.MatchPreferences;
import com.jmip.repository.MatchPreferencesRepository;
import com.jmip.service.auth.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * V8.3: the signed-in user's own match preferences. The owner is always the session's
 * account; no id is taken from the request.
 */
@RestController
@RequestMapping("/api/match-preferences")
public class MatchPreferencesController {

    private final MatchPreferencesRepository repository;
    private final CurrentUser currentUser;

    public MatchPreferencesController(MatchPreferencesRepository repository, CurrentUser currentUser) {
        this.repository = repository;
        this.currentUser = currentUser;
    }

    @GetMapping
    public MatchPreferences get() {
        return repository.find(currentUser.requireId());
    }

    @PutMapping
    public MatchPreferences save(@Valid @RequestBody MatchPreferences preferences) {
        java.util.UUID userId = currentUser.requireId();
        repository.save(userId, preferences);
        return repository.find(userId);
    }
}
