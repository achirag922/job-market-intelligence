package com.jmip.controller;

import com.jmip.dto.portfolio.PortfolioDtos.ImportDraft;
import com.jmip.dto.portfolio.PortfolioDtos.Portfolio;
import com.jmip.dto.portfolio.PortfolioDtos.PublicProfile;
import com.jmip.dto.portfolio.PortfolioDtos.SaveRequest;
import com.jmip.dto.portfolio.PortfolioDtos.SlugRequest;
import com.jmip.service.portfolio.PortfolioService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** V9.7: the signed-in user's own portfolio. Nothing here takes a user id; the session decides. */
@RestController
@RequestMapping("/api/portfolio")
public class PortfolioController {

    private final PortfolioService service;

    public PortfolioController(PortfolioService service) {
        this.service = service;
    }

    /** 404 until the user creates one. */
    @GetMapping
    public Portfolio get() {
        return service.get();
    }

    @PostMapping
    public ResponseEntity<Portfolio> create(@Valid @RequestBody SaveRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(request));
    }

    @PutMapping
    public Portfolio update(@Valid @RequestBody SaveRequest request) {
        return service.update(request);
    }

    @PatchMapping("/slug")
    public Portfolio changeSlug(@Valid @RequestBody SlugRequest request) {
        return service.changeSlug(request.slug());
    }

    @PostMapping("/publish")
    public Portfolio publish() {
        return service.setPublished(true);
    }

    @PostMapping("/unpublish")
    public Portfolio unpublish() {
        return service.setPublished(false);
    }

    /** The public page as it would look now, published or not. */
    @GetMapping("/preview")
    public PublicProfile preview() {
        return service.preview();
    }

    /** A draft from one of your resumes for the editor; nothing is saved. */
    @GetMapping("/import")
    public ImportDraft importDraft(@RequestParam(required = false) UUID resumeId) {
        return service.importDraft(resumeId);
    }

    @DeleteMapping
    public ResponseEntity<Void> delete() {
        service.delete();
        return ResponseEntity.noContent().build();
    }
}
