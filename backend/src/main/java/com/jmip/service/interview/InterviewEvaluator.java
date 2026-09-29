package com.jmip.service.interview;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jmip.ai.AiClient;
import com.jmip.ai.AiCompletionRequest;
import com.jmip.ai.AiFailureException;
import com.jmip.ai.AiTask;
import com.jmip.ai.AiUnavailableException;
import com.jmip.ai.prompt.PromptLibrary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * V8.7: scores one practice answer through the configured {@link AiClient}. The model only
 * returns scores and short feedback as JSON; it runs nothing and sees no database, schema or
 * resume text, only the job's title, company and skills, the resume's skill names, the question
 * and the answer. Anything that is not the expected JSON is treated as a failed evaluation.
 */
@Component
public class InterviewEvaluator {

    private static final Logger log = LoggerFactory.getLogger(InterviewEvaluator.class);

    static final int TOKEN_BUDGET = 600;
    private static final int MAX_ITEMS = 3;
    private static final int MAX_ITEM_LENGTH = 300;

    private final AiClient aiClient;
    private final PromptLibrary prompts;
    private final ObjectMapper objectMapper;

    public InterviewEvaluator(AiClient aiClient, PromptLibrary prompts, ObjectMapper objectMapper) {
        this.aiClient = aiClient;
        this.prompts = prompts;
        this.objectMapper = objectMapper;
    }

    /** What the job and the user supplied, for grounding. */
    public record Context(String jobTitle, String company, List<String> jobSkills, List<String> resumeSkills,
                          String category, String focus, String question, String answer) {
    }

    public record Evaluation(int relevance, int completeness, int clarity, Integer technicalCorrectness,
                             List<String> strengths, List<String> improvements) {
    }

    /** Either an evaluation, or why there is none. */
    public record Outcome(Optional<Evaluation> evaluation, String unavailableReason) {
    }

    public Outcome evaluate(Context context) {
        if (!aiClient.isAvailable()) {
            return new Outcome(Optional.empty(), "AI feedback is not configured on this server. Your answer is saved.");
        }
        try {
            String reply = aiClient.complete(new AiCompletionRequest(AiTask.INTERVIEW_EVALUATION,
                    prompts.interviewEvaluation(), payload(context), TOKEN_BUDGET));
            return parse(reply).map(evaluation -> new Outcome(Optional.of(evaluation), null))
                    .orElseGet(() -> new Outcome(Optional.empty(),
                            "The AI reply could not be read. Your answer is saved; try the evaluation again."));
        } catch (AiUnavailableException unavailable) {
            return new Outcome(Optional.empty(), "AI feedback is not configured on this server. Your answer is saved.");
        } catch (AiFailureException | IllegalArgumentException failure) {
            log.warn("Interview answer evaluation failed: {}", failure.getClass().getSimpleName());
            return new Outcome(Optional.empty(), "AI feedback is not available right now. Your answer is saved; try again later.");
        }
    }

    static String payload(Context context) {
        return """
                DATA:
                JOB TITLE: %s
                COMPANY: %s
                JOB SKILLS: %s
                RESUME SKILLS: %s
                QUESTION CATEGORY: %s
                QUESTION FOCUS: %s
                QUESTION: %s
                ANSWER:
                %s""".formatted(context.jobTitle(), context.company(), names(context.jobSkills()),
                names(context.resumeSkills()), context.category(), context.focus() == null ? "" : context.focus(),
                context.question(), context.answer());
    }

    Optional<Evaluation> parse(String reply) {
        if (reply == null) {
            return Optional.empty();
        }
        int start = reply.indexOf('{');
        int end = reply.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return Optional.empty();
        }
        try {
            JsonNode json = objectMapper.readTree(reply.substring(start, end + 1));
            Integer relevance = score(json.get("relevance"));
            Integer completeness = score(json.get("completeness"));
            Integer clarity = score(json.get("clarity"));
            if (relevance == null || completeness == null || clarity == null) {
                return Optional.empty();
            }
            return Optional.of(new Evaluation(relevance, completeness, clarity, score(json.get("technicalCorrectness")),
                    items(json.get("strengths")), items(json.get("improvements"))));
        } catch (java.io.IOException invalid) {
            return Optional.empty();
        }
    }

    private static Integer score(JsonNode node) {
        if (node == null || !node.isIntegralNumber()) {
            return null;
        }
        int value = node.intValue();
        return value >= 1 && value <= 5 ? value : null;
    }

    private static List<String> items(JsonNode node) {
        List<String> items = new ArrayList<>();
        if (node != null && node.isArray()) {
            for (JsonNode item : node) {
                if (item.isTextual() && !item.asText().isBlank() && items.size() < MAX_ITEMS) {
                    String text = item.asText().strip();
                    items.add(text.length() > MAX_ITEM_LENGTH ? text.substring(0, MAX_ITEM_LENGTH) : text);
                }
            }
        }
        return items;
    }

    private static String names(List<String> names) {
        return names.isEmpty() ? "(none)" : String.join(", ", names);
    }
}
