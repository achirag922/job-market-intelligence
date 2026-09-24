package com.jmip.controller;

import com.jmip.dto.saved.SavedJobNotesRequest;
import com.jmip.dto.saved.SavedJobResponse;
import com.jmip.dto.saved.SavedJobStatusRequest;
import com.jmip.entity.ApplicationStatus;
import com.jmip.service.saved.SavedJobService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Saved jobs and application tracking (V7.2). Signed-in only, like all of /api; the owner
 * always comes from the session, never from the request.
 */
@RestController
public class SavedJobController {

    private final SavedJobService savedJobService;

    public SavedJobController(SavedJobService savedJobService) {
        this.savedJobService = savedJobService;
    }

    /** 201 when newly saved, 200 with the existing record when it already was. */
    @PostMapping("/api/jobs/{jobId}/save")
    public ResponseEntity<SavedJobResponse> save(@PathVariable Long jobId) {
        SavedJobService.SaveResult result = savedJobService.save(jobId);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.savedJob());
    }

    /** 204 whether or not the job was saved. */
    @DeleteMapping("/api/jobs/{jobId}/save")
    public ResponseEntity<Void> unsave(@PathVariable Long jobId) {
        savedJobService.unsave(jobId);
        return ResponseEntity.noContent().build();
    }

    /** Most recently changed first, optionally only one status. */
    @GetMapping("/api/saved-jobs")
    public List<SavedJobResponse> list(@RequestParam(required = false) ApplicationStatus status) {
        return savedJobService.list(status);
    }

    @PatchMapping("/api/saved-jobs/{id}/status")
    public SavedJobResponse changeStatus(@PathVariable UUID id, @Valid @RequestBody SavedJobStatusRequest request) {
        return savedJobService.changeStatus(id, request.status());
    }

    @PatchMapping("/api/saved-jobs/{id}/notes")
    public SavedJobResponse changeNotes(@PathVariable UUID id, @Valid @RequestBody SavedJobNotesRequest request) {
        return savedJobService.changeNotes(id, request.notes());
    }

    @DeleteMapping("/api/saved-jobs/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        savedJobService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
