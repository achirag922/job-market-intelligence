package com.jmip.controller;

import com.jmip.dto.PagedResponse;
import com.jmip.dto.analytics.CompanyDemandResponse;
import com.jmip.dto.analytics.LocationDemandResponse;
import com.jmip.dto.analytics.OverviewResponse;
import com.jmip.dto.analytics.SkillDemandResponse;
import com.jmip.service.AnalyticsService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/analytics")
@Validated
public class AnalyticsController {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsController.class);

    private final AnalyticsService analyticsService;

    public AnalyticsController(AnalyticsService analyticsService) {
        this.analyticsService = analyticsService;
    }

    @GetMapping("/overview")
    public ResponseEntity<OverviewResponse> overview() {
        log.info("GET /api/analytics/overview");
        return ResponseEntity.ok(analyticsService.overview());
    }

    /**
     * Skills ranked by how many postings mention them, each with its share of all
     * postings. Postings need several skills, so the percentages do not sum to 100.
     */
    @GetMapping("/skills")
    public ResponseEntity<PagedResponse<SkillDemandResponse>> skills(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        log.info("GET /api/analytics/skills page={} size={}", page, size);
        return ResponseEntity.ok(analyticsService.skillDemand(page, size));
    }

    /**
     * Locations ranked by posting count. Remote postings have no location, so they are
     * not counted here.
     */
    @GetMapping("/locations")
    public ResponseEntity<PagedResponse<LocationDemandResponse>> locations(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        log.info("GET /api/analytics/locations page={} size={}", page, size);
        return ResponseEntity.ok(analyticsService.locationDemand(page, size));
    }

    /** Companies ranked by posting count. */
    @GetMapping("/companies")
    public ResponseEntity<PagedResponse<CompanyDemandResponse>> companies(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        log.info("GET /api/analytics/companies page={} size={}", page, size);
        return ResponseEntity.ok(analyticsService.companyDemand(page, size));
    }
}
