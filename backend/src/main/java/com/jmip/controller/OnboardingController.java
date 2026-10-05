package com.jmip.controller;

import com.jmip.dto.onboarding.OnboardingDtos.PreferencesRequest;
import com.jmip.dto.onboarding.OnboardingDtos.ProfileRequest;
import com.jmip.dto.onboarding.OnboardingDtos.Status;
import com.jmip.service.onboarding.OnboardingService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * V9.12: the signed-in user's onboarding. Nothing here takes a user id; the session decides. The
 * resume and career-goal steps use the existing /api/resumes and /api/career-goals endpoints.
 */
@RestController
@RequestMapping("/api/onboarding")
public class OnboardingController {

    private final OnboardingService service;

    public OnboardingController(OnboardingService service) {
        this.service = service;
    }

    /** Progress, the next step and what the profile and preference steps already hold. */
    @GetMapping
    public Status status() {
        return service.status();
    }

    @PutMapping("/profile")
    public Status saveProfile(@Valid @RequestBody ProfileRequest request) {
        return service.saveProfile(request);
    }

    @PutMapping("/preferences")
    public Status savePreferences(@Valid @RequestBody PreferencesRequest request) {
        return service.savePreferences(request);
    }

    @PostMapping("/skip")
    public Status skip() {
        return service.skip();
    }

    @PostMapping("/complete")
    public Status complete() {
        return service.complete();
    }
}
