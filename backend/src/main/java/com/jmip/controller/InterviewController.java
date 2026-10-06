package com.jmip.controller;

import com.jmip.dto.interview.InterviewDtos.AnswerRequest;
import com.jmip.dto.interview.InterviewDtos.QuestionResponse;
import com.jmip.dto.interview.InterviewDtos.SessionResponse;
import com.jmip.dto.interview.InterviewDtos.StartRequest;
import com.jmip.service.interview.InterviewService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** V8.7: the signed-in user's interview preparation. The owner is always the session's account. */
@RestController
@Validated
@RequestMapping("/api/interviews")
public class InterviewController {

    private final InterviewService service;

    public InterviewController(InterviewService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<SessionResponse> start(@Valid @RequestBody StartRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.start(request));
    }

    /** Newest first, without questions. */
    @GetMapping
    public List<SessionResponse> list() {
        return service.list();
    }

    @GetMapping("/{id}")
    public SessionResponse get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PostMapping("/{id}/questions/{position}/answer")
    public QuestionResponse answer(@PathVariable UUID id, @PathVariable @Positive int position,
                                   @Valid @RequestBody AnswerRequest request) {
        return service.answer(id, position, request.answer());
    }

    @PostMapping("/{id}/questions/{position}/evaluate")
    public QuestionResponse reevaluate(@PathVariable UUID id, @PathVariable @Positive int position) {
        return service.reevaluate(id, position);
    }

    /** V9.6: sets an unanswered question aside. */
    @PostMapping("/{id}/questions/{position}/skip")
    public QuestionResponse skip(@PathVariable UUID id, @PathVariable @Positive int position) {
        return service.skip(id, position);
    }

    /** Ends the interview, answered or not, and returns the summary. */
    @PostMapping("/{id}/complete")
    public SessionResponse complete(@PathVariable UUID id) {
        return service.complete(id);
    }
}
