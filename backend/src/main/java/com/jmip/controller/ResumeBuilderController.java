package com.jmip.controller;

import com.jmip.dto.resume.BuilderContent;
import com.jmip.dto.resume.BuilderResumeRequest;
import com.jmip.dto.resume.BuilderResumeResponse;
import com.jmip.service.resume.ResumeBuilderService;
import jakarta.validation.Valid;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * V9.4: the resume builder. Rename, default and delete are the existing resume endpoints; these
 * create, edit, duplicate and export built resumes. The owner is always the session's account.
 */
@RestController
@RequestMapping("/api/resumes")
public class ResumeBuilderController {

    private final ResumeBuilderService service;

    public ResumeBuilderController(ResumeBuilderService service) {
        this.service = service;
    }

    @PostMapping(path = "/builder", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<BuilderResumeResponse> create(@Valid @RequestBody BuilderResumeRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(request));
    }

    @GetMapping("/{id}/builder")
    public BuilderResumeResponse get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PutMapping("/{id}/builder")
    public BuilderResumeResponse save(@PathVariable UUID id, @Valid @RequestBody BuilderContent content) {
        return service.save(id, content);
    }

    @PostMapping("/{id}/duplicate")
    public ResponseEntity<BuilderResumeResponse> duplicate(@PathVariable UUID id) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.duplicate(id));
    }

    @GetMapping(path = "/{id}/builder/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> pdf(@PathVariable UUID id) {
        ResumeBuilderService.Export export = service.export(id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(export.fileName()).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(MediaType.APPLICATION_PDF)
                .body(export.pdf());
    }
}
