package com.jmip.controller;

import com.jmip.dto.learning.LearningDtos.Item;
import com.jmip.dto.learning.LearningDtos.ItemRequest;
import com.jmip.dto.learning.LearningDtos.ItemUpdate;
import com.jmip.dto.learning.LearningDtos.Plan;
import com.jmip.dto.learning.LearningDtos.Resource;
import com.jmip.dto.learning.LearningDtos.ResourceRequest;
import com.jmip.dto.learning.LearningDtos.StatusRequest;
import com.jmip.service.learning.LearningService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** V9.5: the signed-in user's learning plan. Nothing here accepts a user id; the session decides. */
@RestController
@RequestMapping("/api/learning")
public class LearningController {

    private final LearningService service;

    public LearningController(LearningService service) {
        this.service = service;
    }

    /** Priority skills, items with their resources, progress and what it changes for the career goal. */
    @GetMapping
    public Plan plan() {
        return service.plan();
    }

    @PostMapping("/items")
    public ResponseEntity<Item> create(@Valid @RequestBody ItemRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(request));
    }

    @PutMapping("/items/{id}")
    public Item update(@PathVariable UUID id, @Valid @RequestBody ItemUpdate update) {
        return service.update(id, update);
    }

    @PatchMapping("/items/{id}/status")
    public Item changeStatus(@PathVariable UUID id, @Valid @RequestBody StatusRequest request) {
        return service.changeStatus(id, request);
    }

    @DeleteMapping("/items/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/items/{id}/resources")
    public ResponseEntity<Resource> addResource(@PathVariable UUID id, @Valid @RequestBody ResourceRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.addResource(id, request));
    }

    @PutMapping("/resources/{id}")
    public Resource updateResource(@PathVariable UUID id, @Valid @RequestBody ResourceRequest request) {
        return service.updateResource(id, request);
    }

    @DeleteMapping("/resources/{id}")
    public ResponseEntity<Void> deleteResource(@PathVariable UUID id) {
        service.deleteResource(id);
        return ResponseEntity.noContent().build();
    }
}
