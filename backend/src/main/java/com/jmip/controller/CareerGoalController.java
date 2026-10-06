package com.jmip.controller;

import com.jmip.dto.career.CareerGoalRequest;
import com.jmip.dto.career.CareerGoalResponse;
import com.jmip.dto.career.CareerGoalStatusRequest;
import com.jmip.dto.career.RoadmapResponse;
import com.jmip.dto.career.SkillProgressRequest;
import com.jmip.entity.CareerGoalStatus;
import com.jmip.service.career.CareerGoalService;
import com.jmip.service.career.RoadmapService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * V7.4: career goals and their skill roadmaps. Signed-in only, like all of /api; the owner
 * always comes from the session, never from the request.
 */
@RestController
@RequestMapping("/api/career-goals")
@Validated
public class CareerGoalController {

    private final CareerGoalService goalService;
    private final RoadmapService roadmapService;

    public CareerGoalController(CareerGoalService goalService, RoadmapService roadmapService) {
        this.goalService = goalService;
        this.roadmapService = roadmapService;
    }

    @PostMapping
    public ResponseEntity<CareerGoalResponse> create(@Valid @RequestBody CareerGoalRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(goalService.create(request));
    }

    /** Most recently changed first, optionally only one status. */
    @GetMapping
    public List<CareerGoalResponse> list(@RequestParam(required = false) CareerGoalStatus status) {
        return goalService.list(status);
    }

    @GetMapping("/{id}")
    public CareerGoalResponse get(@PathVariable UUID id) {
        return goalService.get(id);
    }

    /** Replaces the role, category, location, experience and chosen skills. Status has its own endpoint. */
    @PutMapping("/{id}")
    public CareerGoalResponse update(@PathVariable UUID id, @Valid @RequestBody CareerGoalRequest request) {
        return goalService.update(id, request);
    }

    @PatchMapping("/{id}/status")
    public CareerGoalResponse changeStatus(@PathVariable UUID id, @Valid @RequestBody CareerGoalStatusRequest request) {
        return goalService.changeStatus(id, request.status());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        goalService.delete(id);
        return ResponseEntity.noContent().build();
    }

    /** The goal's roadmap, against the default resume unless {@code resumeId} names another of yours. */
    @GetMapping("/{goalId}/roadmap")
    public RoadmapResponse roadmap(@PathVariable UUID goalId, @RequestParam(required = false) UUID resumeId) {
        return roadmapService.roadmap(goalId, resumeId);
    }

    @PutMapping("/{goalId}/roadmap/skills/{skillId}")
    public RoadmapService.RoadmapSkillProgress setProgress(@PathVariable UUID goalId, @PathVariable @Positive Long skillId,
                                                           @Valid @RequestBody SkillProgressRequest request) {
        return roadmapService.setProgress(goalId, skillId, request.status());
    }
}
