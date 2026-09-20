package com.jmip.controller;

import com.jmip.dto.LocationResponse;
import com.jmip.dto.PagedResponse;
import com.jmip.service.LocationService;
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

@RestController
@RequestMapping("/api/locations")
@Validated
public class LocationController {

    private static final Logger log = LoggerFactory.getLogger(LocationController.class);

    private final LocationService locationService;

    public LocationController(LocationService locationService) {
        this.locationService = locationService;
    }

    /** Sortable fields: {@code country}, {@code city}, {@code state}, {@code createdAt}. */
    @GetMapping
    public ResponseEntity<PagedResponse<LocationResponse>> list(
            @RequestParam(required = false) @Size(max = 100) String country,
            @PageableDefault(size = 20, sort = "country") Pageable pageable) {
        log.info("GET /api/locations country={} page={}", country, pageable.getPageNumber());
        return ResponseEntity.ok(locationService.list(country, pageable));
    }
}
