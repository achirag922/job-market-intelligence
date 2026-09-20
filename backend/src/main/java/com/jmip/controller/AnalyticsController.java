package com.jmip.controller;

import com.jmip.dto.PagedResponse;
import com.jmip.dto.analytics.CategoryDemandResponse;
import com.jmip.dto.analytics.CompanyDemandResponse;
import com.jmip.dto.analytics.EntitySkillResponse;
import com.jmip.dto.analytics.ExperienceDistributionResponse;
import com.jmip.dto.analytics.LocationDemandResponse;
import com.jmip.dto.analytics.OverviewResponse;
import com.jmip.dto.analytics.SkillAnalyticsResponse;
import com.jmip.dto.analytics.SkillTrendResponse;
import com.jmip.dto.analytics.TitleCountResponse;
import com.jmip.dto.analytics.TitleDemandResponse;
import com.jmip.dto.analytics.TrendDirection;
import com.jmip.service.AnalyticsService;
import com.jmip.service.analytics.CategoryAnalyticsService;
import com.jmip.service.analytics.SkillAnalyticsService;
import com.jmip.service.analytics.SkillTrendService;
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
    private final SkillTrendService skillTrendService;
    private final CategoryAnalyticsService categoryAnalyticsService;
    private final TitleAnalyticsService titleAnalyticsService;

    public AnalyticsController(AnalyticsService analyticsService,
                               SkillAnalyticsService skillAnalyticsService,
                               SkillTrendService skillTrendService,
                               CategoryAnalyticsService categoryAnalyticsService,
                               TitleAnalyticsService titleAnalyticsService) {
        this.analyticsService = analyticsService;
        this.skillAnalyticsService = skillAnalyticsService;
        this.skillTrendService = skillTrendService;
        this.categoryAnalyticsService = categoryAnalyticsService;
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

    /**
     * Which skills are gaining or losing demand.
     *
     * <p>Demand is a skill's share of postings, not its raw count: if the number of
     * postings grows, every count grows with it, and a count-based trend would report the
     * whole market as rising. The window is split in half and the halves compared, so one
     * quiet month does not invent a trend.
     *
     * @param months    how many recent monthly periods to compare, capped to the history
     *                  that exists
     * @param minJobs   skills below this many postings in the window are left out, because
     *                  one posting becoming two is noise rather than a trend
     * @param direction {@code RISING} or {@code FALLING} to see one side only; omit for
     *                  the biggest movers in either direction
     */
    @GetMapping("/skills/trends")
    public ResponseEntity<SkillTrendResponse> skillTrends(
            @RequestParam(defaultValue = "6") @Min(2) @Max(36) int months,
            @RequestParam(defaultValue = "3") @Min(1) int minJobs,
            @RequestParam(required = false) TrendDirection direction,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        log.info("GET /api/analytics/skills/trends months={} minJobs={} direction={} limit={}",
                months, minJobs, direction, limit);
        return ResponseEntity.ok(skillTrendService.trends(months, minJobs, direction, limit));
    }

    // ---------------------------------------------------------------- V4: job categories

    /**
     * How postings are distributed across the rule-based job categories.
     *
     * <p>Percentages are of the classified postings, not of every posting: a corpus that
     * is only half classified would otherwise report shares that quietly sum to 50.
     */
    @GetMapping("/job-categories")
    public ResponseEntity<List<CategoryDemandResponse>> jobCategories() {
        log.info("GET /api/analytics/job-categories");
        return ResponseEntity.ok(categoryAnalyticsService.categoryDistribution());
    }

    /**
     * The skills most asked for within one category, as a share of that category.
     *
     * <p>The category is a query parameter rather than a path segment because category
     * names are data, and some of them contain a slash — "QA / Automation Engineer". An
     * encoded slash inside a path segment is rejected by Tomcat with a 400 before the
     * request reaches Spring, and the alternative, relaxing that check for the whole
     * application, trades a security control for a URL shape.
     */
    @GetMapping("/category/skills")
    public ResponseEntity<List<EntitySkillResponse>> categorySkills(
            @RequestParam @Size(max = 50) String category,
            @RequestParam(defaultValue = "10") @Min(1) @Max(50) int limit) {
        log.info("GET /api/analytics/category/skills category={} limit={}", category, limit);
        return ResponseEntity.ok(categoryAnalyticsService.skillsForCategory(category, limit));
    }

    /** Where postings in one category are concentrated. Remote postings have no location. */
    @GetMapping("/category/locations")
    public ResponseEntity<List<LocationDemandResponse>> categoryLocations(
            @RequestParam @Size(max = 50) String category,
            @RequestParam(defaultValue = "10") @Min(1) @Max(50) int limit) {
        log.info("GET /api/analytics/category/locations category={} limit={}", category, limit);
        return ResponseEntity.ok(categoryAnalyticsService.locationsForCategory(category, limit));
    }

    /** Which companies are hiring for one category. */
    @GetMapping("/category/companies")
    public ResponseEntity<List<CompanyDemandResponse>> categoryCompanies(
            @RequestParam @Size(max = 50) String category,
            @RequestParam(defaultValue = "10") @Min(1) @Max(50) int limit) {
        log.info("GET /api/analytics/category/companies category={} limit={}", category, limit);
        return ResponseEntity.ok(categoryAnalyticsService.companiesForCategory(category, limit));
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
