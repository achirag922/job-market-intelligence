package com.jmip.controller;

import com.jmip.dto.progress.CareerProgressDtos.CareerProgress;
import com.jmip.service.progress.CareerProgressService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** V9.13: the signed-in user's career progress. There is no user parameter; the session decides. */
@RestController
@RequestMapping("/api/career-progress")
public class CareerProgressController {

    private final CareerProgressService service;

    public CareerProgressController(CareerProgressService service) {
        this.service = service;
    }

    /** Readiness score with its components, progress, achievements, next milestones and weekly streaks. */
    @GetMapping
    public CareerProgress progress() {
        return service.progress();
    }
}
