package com.jmip.controller;

import com.jmip.dto.dashboard.DashboardResponse;
import com.jmip.service.dashboard.DashboardService;
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

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    /** @param goalId one of your goals to show instead of the most recently changed active one */
    @GetMapping
    public DashboardResponse dashboard(@RequestParam(required = false) UUID goalId) {
        return dashboardService.dashboard(goalId);
    }
}
