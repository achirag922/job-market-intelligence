package com.jmip.controller;

import com.jmip.dto.portfolio.PortfolioDtos.PublicProfile;
import com.jmip.service.portfolio.PortfolioService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** V9.7: published professional profiles, readable without signing in. Unpublished ones are 404. */
@RestController
@RequestMapping("/api/public/profiles")
public class PublicProfileController {

    private final PortfolioService service;

    public PublicProfileController(PortfolioService service) {
        this.service = service;
    }

    @GetMapping("/{slug}")
    public PublicProfile profile(@PathVariable String slug) {
        return service.publicProfile(slug);
    }
}
