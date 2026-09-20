package com.jmip.controller;

import com.jmip.dto.PagedResponse;
import com.jmip.dto.SkillResponse;
import com.jmip.dto.analytics.SkillDemandResponse;
import com.jmip.service.SkillService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/skills")
@Validated
public class SkillController {

    private static final Logger log = LoggerFactory.getLogger(SkillController.class);

    private final SkillService skillService;

    public SkillController(SkillService skillService) {
        this.skillService = skillService;
    }

    /** Sortable fields: {@code name}, {@code category}, {@code createdAt}. */
    @GetMapping
    public ResponseEntity<PagedResponse<SkillResponse>> list(
            @RequestParam(required = false) @Size(max = 100) String name,
            @PageableDefault(size = 20, sort = "name") Pageable pageable) {
        log.info("GET /api/skills name={} page={}", name, pageable.getPageNumber());
        return ResponseEntity.ok(skillService.list(name, pageable));
    }

    /**
     * The most in-demand skills. Not paginated: a shortlist is what "top" means, so a
     * capped limit fits better than a page number.
     */
    @GetMapping("/top")
    public ResponseEntity<List<SkillDemandResponse>> top(
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int limit) {
        log.info("GET /api/skills/top limit={}", limit);
        return ResponseEntity.ok(skillService.top(limit));
    }
}
