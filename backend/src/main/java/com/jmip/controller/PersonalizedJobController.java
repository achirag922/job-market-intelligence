package com.jmip.controller;

import com.jmip.dto.PersonalizedFeedResponse;
import com.jmip.service.PersonalizedFeedService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** V9.2: the signed-in user's personalized job feed. No user id is accepted; the session decides. */
@RestController
@Validated
public class PersonalizedJobController {

    private final PersonalizedFeedService service;

    public PersonalizedJobController(PersonalizedFeedService service) {
        this.service = service;
    }

    @GetMapping("/api/jobs/personalized")
    public PersonalizedFeedResponse feed(@RequestParam(defaultValue = "20") @Min(1) @Max(50) int limit) {
        return service.feed(limit);
    }
}
