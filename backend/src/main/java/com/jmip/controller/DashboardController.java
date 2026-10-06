package com.jmip.controller;

import com.jmip.dto.dashboard.DashboardResponse;
import com.jmip.dto.dashboard.UserAnalyticsResponse;
import com.jmip.service.dashboard.DashboardService;
import com.jmip.service.dashboard.UserAnalyticsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * V7.7: the signed-in user's career dashboard in one call. Signed-in only, like all of /api;
 * there is no user parameter: every section is the caller's own.
 */
@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final DashboardService dashboardService;
    private final UserAnalyticsService analyticsService;

    public DashboardController(DashboardService dashboardService, UserAnalyticsService analyticsService) {
        this.dashboardService = dashboardService;
        this.analyticsService = analyticsService;
    }

    /** @param goalId one of your goals to show instead of the most recently changed active one */
    @GetMapping
    public DashboardResponse dashboard(@RequestParam(required = false) UUID goalId) {
        return dashboardService.dashboard(goalId);
    }

    /** V9.8: how your job search, resume, interviews and learning changed over a range: 7D, 30D (default), 90D, 1Y or ALL. */
    @GetMapping("/analytics")
    public UserAnalyticsResponse analytics(@RequestParam(required = false) String range) {
        return analyticsService.analytics(range);
    }
}
