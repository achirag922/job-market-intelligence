package com.jmip.controller;

import com.jmip.dto.workspace.WorkspaceDtos.Matches;
import com.jmip.dto.workspace.WorkspaceDtos.SavedSearch;
import com.jmip.dto.workspace.WorkspaceDtos.SavedSearchRequest;
import com.jmip.dto.workspace.WorkspaceDtos.Workspace;
import com.jmip.service.workspace.WorkspaceService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** V9.14: the signed-in user's job-search workspace. Nothing here takes a user id; the session decides. */
@RestController
public class WorkspaceController {

    private final WorkspaceService service;

    public WorkspaceController(WorkspaceService service) {
        this.service = service;
    }

    /** Recommended, saved, applied, follow-ups, recently viewed, hidden and saved searches in one call. */
    @GetMapping("/api/workspace")
    public Workspace workspace() {
        return service.workspace();
    }

    /** Why each job matches your current resume (at most 50 ids). */
    @GetMapping("/api/workspace/matches")
    public Matches matches(@RequestParam List<Long> jobIds) {
        return service.matches(jobIds);
    }

    @GetMapping("/api/workspace/searches")
    public List<SavedSearch> searches() {
        return service.searches();
    }

    @PostMapping("/api/workspace/searches")
    public ResponseEntity<SavedSearch> saveSearch(@Valid @RequestBody SavedSearchRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.saveSearch(request));
    }

    @DeleteMapping("/api/workspace/searches/{id}")
    public ResponseEntity<Void> deleteSearch(@PathVariable UUID id) {
        service.deleteSearch(id);
        return ResponseEntity.noContent().build();
    }

    /** "Not interested": left out of recommendations and, when asked, of search results. */
    @PostMapping("/api/jobs/{jobId}/hide")
    public ResponseEntity<Void> hide(@PathVariable long jobId) {
        service.hide(jobId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/api/jobs/{jobId}/hide")
    public ResponseEntity<Void> unhide(@PathVariable long jobId) {
        service.unhide(jobId);
        return ResponseEntity.noContent().build();
    }

    /** Records that you opened a job, for "Recently viewed". */
    @PostMapping("/api/jobs/{jobId}/viewed")
    public ResponseEntity<Void> viewed(@PathVariable long jobId) {
        service.recordView(jobId);
        return ResponseEntity.noContent().build();
    }
}
