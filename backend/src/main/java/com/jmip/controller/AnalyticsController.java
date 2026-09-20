package com.jmip.controller;

import com.jmip.dto.PagedResponse;
import com.jmip.dto.analytics.CompanyDemandResponse;
import com.jmip.dto.analytics.EntitySkillResponse;
import com.jmip.dto.analytics.ExperienceDistributionResponse;
import com.jmip.dto.analytics.LocationDemandResponse;
import com.jmip.dto.analytics.OverviewResponse;
import com.jmip.dto.analytics.SkillAnalyticsResponse;
import com.jmip.dto.analytics.TitleCountResponse;
import com.jmip.dto.analytics.TitleDemandResponse;
import com.jmip.service.AnalyticsService;
import com.jmip.service.analytics.SkillAnalyticsService;
import com.jmip.service.analytics.TitleAnalyticsService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * Analytics over the ingested postings.
 *
 * <p>Every response separates three kinds of figure, and names them accordingly:
 * {@code jobCount} is a raw count read from stored rows, {@code percentageOf*} is that
 * count over a stated total, and {@code rank} is derived from the ordering. Where a
 * percentage could be ambiguous, the denominator is returned alongside it.
 */
@RestController
@RequestMapping("/api/analytics")
@Validated
public class AnalyticsController {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsController.class);

    private final AnalyticsService analyticsService;
    private final SkillAnalyticsService skillAnalyticsService;
    private final TitleAnalyticsService titleAnalyticsService;

    public AnalyticsController(AnalyticsService analyticsService,
                               SkillAnalyticsService skillAnalyticsService,
                               TitleAnalyticsService titleAnalyticsService) {
        this.analyticsService = analyticsService;
        this.skillAnalyticsService = skillAnalyticsService;
        this.titleAnalyticsService = titleAnalyticsService;
    }

    @GetMapping("/overview")
    public ResponseEntity<OverviewResponse> overview() {
        log.info("GET /api/analytics/overview");
        return ResponseEntity.ok(analyticsService.overview());
    }

    /**
     * Skills ranked by demand, optionally within a location, a date range or a title.
     *
     * <p>Percentages are against the postings the filters match, and that total is
     * returned in {@code scope.totalJobsInScope}.
     *
     * @param location matched against city, state or country
     * @param fromDate earliest posted date, inclusive, as yyyy-MM-dd
     * @param toDate   latest posted date, inclusive, as yyyy-MM-dd
     * @param title    substring of the job title
     */
    @GetMapping("/skills")
    public ResponseEntity<SkillAnalyticsResponse> skills(
            @RequestParam(required = false) @Size(max = 200) String location,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(required = false) @Size(max = 300) String title,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        log.info("GET /api/analytics/skills location={} from={} to={} title={} page={}",
                location, fromDate, toDate, title, page);
        return ResponseEntity.ok(
                skillAnalyticsService.skillDemand(location, fromDate, toDate, title, page, size));
    }

    /** How required experience is distributed across every posting. */
    @GetMapping("/experience")
    public ResponseEntity<ExperienceDistributionResponse> experience() {
        log.info("GET /api/analytics/experience");
        return ResponseEntity.ok(analyticsService.experienceDistribution());
    }

    /**
     * Locations ranked by posting count. Remote postings have no location, so they are
     * not counted here and the percentages sum to less than 100.
     */
    @GetMapping("/locations")
    public ResponseEntity<PagedResponse<LocationDemandResponse>> locations(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        log.info("GET /api/analytics/locations page={} size={}", page, size);
        return ResponseEntity.ok(analyticsService.locationDemand(page, size));
    }

    /** The skills most asked for in one location, as a share of that location's postings. */
    @GetMapping("/locations/{id}/skills")
    public ResponseEntity<List<EntitySkillResponse>> locationSkills(
            @PathVariable @Positive Long id,
            @RequestParam(defaultValue = "10") @Min(1) @Max(50) int limit) {
        log.info("GET /api/analytics/locations/{}/skills limit={}", id, limit);
        return ResponseEntity.ok(skillAnalyticsService.skillsForLocation(id, limit));
    }

    /** The most common job titles in one location, after similar titles are grouped. */
    @GetMapping("/locations/{id}/titles")
    public ResponseEntity<List<TitleCountResponse>> locationTitles(
            @PathVariable @Positive Long id,
            @RequestParam(defaultValue = "10") @Min(1) @Max(50) int limit) {
        log.info("GET /api/analytics/locations/{}/titles limit={}", id, limit);
        return ResponseEntity.ok(titleAnalyticsService.titlesForLocation(id, limit));
    }

    /** Companies ranked by posting count. */
    @GetMapping("/companies")
    public ResponseEntity<PagedResponse<CompanyDemandResponse>> companies(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        log.info("GET /api/analytics/companies page={} size={}", page, size);
        return ResponseEntity.ok(analyticsService.companyDemand(page, size));
    }

    /** The skills most asked for by one company, as a share of that company's postings. */
    @GetMapping("/companies/{id}/skills")
    public ResponseEntity<List<EntitySkillResponse>> companySkills(
            @PathVariable @Positive Long id,
            @RequestParam(defaultValue = "10") @Min(1) @Max(50) int limit) {
        log.info("GET /api/analytics/companies/{}/skills limit={}", id, limit);
        return ResponseEntity.ok(skillAnalyticsService.skillsForCompany(id, limit));
    }

    /**
     * The most common job titles, with similar titles grouped, and the skills each role
     * asks for. Each group lists the stored titles it folded in, so the grouping can be
     * checked rather than taken on trust.
     */
    @GetMapping("/titles")
    public ResponseEntity<PagedResponse<TitleDemandResponse>> titles(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        log.info("GET /api/analytics/titles page={} size={}", page, size);
        return ResponseEntity.ok(titleAnalyticsService.titleDemand(page, size));
    }
}
