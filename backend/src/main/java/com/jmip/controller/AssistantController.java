package com.jmip.controller;

import com.jmip.dto.assistant.AssistantIntent;
import com.jmip.dto.assistant.AssistantQueryRequest;
import com.jmip.dto.assistant.AssistantQueryResponse;
import com.jmip.service.assistant.AssistantService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;

/**
 * The natural-language assistant.
 *
 * <p>One endpoint takes a question and returns what was understood, what was retrieved and
 * how to draw it. The reply's {@code grounded} flag says whether there is data behind the
 * prose, so a caller never has to guess.
 *
 * <p>Almost nothing here is a client error. A question the assistant cannot answer comes
 * back 200 with an explanation, because "I don't know" is a successful answer to a question
 * and a 400 would tell the browser that the request was malformed when it was not. Only a
 * genuinely malformed body — no question at all, an oversized one — is rejected.
 */
@RestController
@RequestMapping("/api/assistant")
@Validated
public class AssistantController {

    private static final Logger log = LoggerFactory.getLogger(AssistantController.class);

    private final AssistantService assistantService;

    public AssistantController(AssistantService assistantService) {
        this.assistantService = assistantService;
    }

    /**
     * Ask a question about the dataset.
     *
     * <p>POST rather than GET because the question is a body, not an identifier: questions
     * are long, they are not a resource to be cached, and they have no business appearing
     * in a URL, a browser history or an access log.
     */
    @PostMapping("/query")
    public ResponseEntity<AssistantQueryResponse> query(
            @Valid @RequestBody AssistantQueryRequest request) {
        log.info("POST /api/assistant/query");
        return ResponseEntity.ok(assistantService.answer(request));
    }

    /**
     * The questions the assistant can answer.
     *
     * <p>Useful to a frontend that wants to show examples, and to anyone wondering why a
     * question was declined. {@code UNSUPPORTED} is left out: it is the internal marker for
     * "none of these", not something a caller can ask for.
     */
    @GetMapping("/intents")
    public ResponseEntity<List<String>> intents() {
        return ResponseEntity.ok(Arrays.stream(AssistantIntent.values())
                .filter(intent -> intent != AssistantIntent.UNSUPPORTED)
                .map(Enum::name)
                .toList());
    }
}
