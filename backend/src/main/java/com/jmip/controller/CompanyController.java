package com.jmip.controller;

import com.jmip.dto.CompanyDetailResponse;
import com.jmip.dto.CompanyResponse;
import com.jmip.dto.PagedResponse;
import com.jmip.service.CompanyService;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/companies")
@Validated
public class CompanyController {

    private static final Logger log = LoggerFactory.getLogger(CompanyController.class);

    private final CompanyService companyService;

    public CompanyController(CompanyService companyService) {
        this.companyService = companyService;
    }

    /** Sortable fields: {@code name}, {@code industry}, {@code createdAt}. */
    @GetMapping
    public ResponseEntity<PagedResponse<CompanyResponse>> list(
            @RequestParam(required = false) @Size(max = 255) String name,
            @PageableDefault(size = 20, sort = "name") Pageable pageable) {
        log.info("GET /api/companies name={} page={}", name, pageable.getPageNumber());
        return ResponseEntity.ok(companyService.list(name, pageable));
    }

    @GetMapping("/{id}")
    public ResponseEntity<CompanyDetailResponse> findById(@PathVariable @Positive Long id) {
        log.info("GET /api/companies/{}", id);
        return ResponseEntity.ok(companyService.findById(id));
    }
}
