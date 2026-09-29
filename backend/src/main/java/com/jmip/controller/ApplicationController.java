package com.jmip.controller;

import com.jmip.dto.application.ApplicationAnalysisResponse;
import com.jmip.dto.application.ApplicationInsightsResponse;
import com.jmip.service.application.ApplicationIntelligenceService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * V8.5: the signed-in user's application intelligence. Read-only; the account always comes
 * from the session, and no user id is accepted.
 */
@RestController
@RequestMapping("/api/applications")
public class ApplicationController {

    private final ApplicationIntelligenceService service;

    public ApplicationController(ApplicationIntelligenceService service) {
        this.service = service;
    }

    /** Every tracked job with its status, dates, notes, follow-up and V8.3 match. */
    @GetMapping
    public List<ApplicationAnalysisResponse> applications() {
        return service.applications();
    }

    @GetMapping("/insights")
    public ApplicationInsightsResponse insights() {
        return service.insights();
    }
}
