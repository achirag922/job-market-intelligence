package com.jmip.controller;

import com.jmip.dto.market.MarketResponses.CompanyResponse;
import com.jmip.dto.market.MarketResponses.LocationResponse;
import com.jmip.dto.market.MarketResponses.RemoteResponse;
import com.jmip.dto.market.MarketResponses.SalaryResponse;
import com.jmip.dto.market.MarketResponses.SkillResponse;
import com.jmip.service.market.MarketFilter;
import com.jmip.service.market.MarketIntelligenceService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * V7.5 market intelligence. Every endpoint takes the same optional filters: {@code category}
 * (exact job category), {@code location} (city, state or country text), {@code experience}
 * (0-2, 2-5, 5-8, 8+, unspecified) and {@code months} (the last N posting months of the data).
 * Signed-in only, like all of /api.
 */
@RestController
@RequestMapping("/api/market")
@Validated
public class MarketController {

    private final MarketIntelligenceService service;

    public MarketController(MarketIntelligenceService service) {
        this.service = service;
    }

    @GetMapping("/salary")
    public SalaryResponse salary(@RequestParam(required = false) @Size(max = 50) String category,
                                 @RequestParam(required = false) @Size(max = 200) String location,
                                 @RequestParam(required = false) @Pattern(regexp = EXPERIENCE) String experience,
                                 @RequestParam(required = false) @Min(1) @Max(36) Integer months) {
        return service.salary(filter(category, location, experience, months));
    }

    @GetMapping("/locations")
    public LocationResponse locations(@RequestParam(required = false) @Size(max = 50) String category,
                                      @RequestParam(required = false) @Size(max = 200) String location,
                                      @RequestParam(required = false) @Pattern(regexp = EXPERIENCE) String experience,
                                      @RequestParam(required = false) @Min(1) @Max(36) Integer months) {
        return service.locations(filter(category, location, experience, months));
    }

    @GetMapping("/remote")
    public RemoteResponse remote(@RequestParam(required = false) @Size(max = 50) String category,
                                 @RequestParam(required = false) @Size(max = 200) String location,
                                 @RequestParam(required = false) @Pattern(regexp = EXPERIENCE) String experience,
                                 @RequestParam(required = false) @Min(1) @Max(36) Integer months) {
        return service.remote(filter(category, location, experience, months));
    }

    @GetMapping("/companies")
    public CompanyResponse companies(@RequestParam(required = false) @Size(max = 50) String category,
                                     @RequestParam(required = false) @Size(max = 200) String location,
                                     @RequestParam(required = false) @Pattern(regexp = EXPERIENCE) String experience,
                                     @RequestParam(required = false) @Min(1) @Max(36) Integer months) {
        return service.companies(filter(category, location, experience, months));
    }

    @GetMapping("/skills")
    public SkillResponse skills(@RequestParam(required = false) @Size(max = 50) String category,
                                @RequestParam(required = false) @Size(max = 200) String location,
                                @RequestParam(required = false) @Pattern(regexp = EXPERIENCE) String experience,
                                @RequestParam(required = false) @Min(1) @Max(36) Integer months) {
        return service.skills(filter(category, location, experience, months), months);
    }

    private static final String EXPERIENCE = "(?i)0-2|2-5|5-8|8\\+|unspecified";

    private MarketFilter filter(String category, String location, String experience, Integer months) {
        return service.filter(category, location, experience, months);
    }
}
