package com.jmip.ai.prompt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * The assistant's prompts, loaded once from versioned files.
 *
 * <p>They live in {@code src/main/resources/prompts} rather than inside the services that
 * use them. A prompt is the behaviour of this feature as much as the Java is: it wants to
 * be reviewable as a diff, not buried in a concatenated string halfway down a method.
 *
 * <p>The filenames carry a version. Changing a prompt changes answers, so a new version is
 * a new file and the constant moves, which leaves the old wording in history next to the
 * results it produced.
 */
@Component
public class PromptLibrary {

    private static final Logger log = LoggerFactory.getLogger(PromptLibrary.class);

    private static final String INTENT_EXTRACTION = "prompts/intent-extraction-v1.txt";
    private static final String ANSWER_GENERATION = "prompts/answer-generation-v1.txt";

    private final String intentExtraction;
    private final String answerGeneration;

    public PromptLibrary() {
        this.intentExtraction = read(INTENT_EXTRACTION);
        this.answerGeneration = read(ANSWER_GENERATION);
        log.info("Assistant prompts loaded: {}, {}", INTENT_EXTRACTION, ANSWER_GENERATION);
    }

    /** Instructions for turning a question into structured intent JSON. */
    public String intentExtraction() {
        return intentExtraction;
    }

    /** Instructions for describing rows that have already been retrieved. */
    public String answerGeneration() {
        return answerGeneration;
    }

    /** Which prompt versions are in use, for the logs and for support questions. */
    public String versions() {
        return INTENT_EXTRACTION + ", " + ANSWER_GENERATION;
    }

    /**
     * Read at construction, so a missing or unreadable prompt fails the application at
     * startup. Discovering it on the first question instead would mean one user seeing an
     * error for a packaging mistake.
     */
    private static String read(String path) {
        try {
            return StreamUtils.copyToString(
                    new ClassPathResource(path).getInputStream(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException("Cannot read assistant prompt " + path, exception);
        }
    }
}
