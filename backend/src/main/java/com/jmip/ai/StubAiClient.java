package com.jmip.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Locale;

/**
 * A deterministic stand-in for a model provider, for tests and for running the application
 * without a key.
 *
 * <p>It is a keyword matcher, not a small language model, and it is not trying to be one.
 * Its value is that the same question always produces the same intent, so the tests around
 * validation, routing and grounding can assert exact outcomes without a network call, a
 * credential, or a bill.
 *
 * <p>It is worth being clear about what this does <em>not</em> weaken. The figures in an
 * answer never come from the provider under either implementation: they are read from the
 * database after the intent is validated. Swapping the real provider for this one changes
 * how well a question is understood and how the prose reads — never whether the numbers
 * are real.
 */
public class StubAiClient implements AiClient {

    private static final Logger log = LoggerFactory.getLogger(StubAiClient.class);

    /**
     * Ordered because the first match wins and some questions satisfy several rules. A
     * question mentioning both "compare" and "skills" is a comparison, so comparison is
     * tested first.
     */
    private static final List<Rule> RULES = List.of(
            // V7.6 copilot, before the general rules: "my target role" questions also say "skills".
            new Rule("APPLICATION_PROGRESS", "application progress", "my applications"),
            new Rule("SAVED_JOB_PRIORITY", "saved jobs should", "prioritize", "prioritise"),
            new Rule("RESUME_IMPROVEMENT", "improve in my resume", "improve my resume", "match score low"),
            new Rule("MY_JOB_MATCHES", "jobs match my resume", "match my resume"),
            new Rule("NEXT_SKILLS", "focus on next", "learn next"),
            new Rule("MY_SKILL_GAP", "missing for my target"),
            new Rule("TARGET_ROLE_DEMAND", "demand for my target"),
            new Rule("TARGET_ROLE_SKILLS", "requested for my target", "skills for my target"),
            new Rule("RESUME_MATCH", "my resume compare"),
            new Rule("CATEGORY_COMPARISON", "compare", "category"),
            new Rule("SKILL_COMPARISON", "compare"),
            new Rule("SKILL_TREND", "trend", "changing", "changed", "over time", "rising", "falling"),
            new Rule("SKILL_GAP", "missing", "should i learn", "skill gap"),
            new Rule("RESUME_MATCH", "my resume", "how well does"),
            new Rule("SALARY_ANALYSIS", "salary", "pay", "compensation"),
            new Rule("LOCATION_DEMAND", "city", "cities", "location", "where"),
            new Rule("COMPANY_DEMAND", "company", "companies", "hiring the most", "who is hiring"),
            new Rule("JOB_CATEGORY_DEMAND", "category", "categories", "role distribution"),
            new Rule("JOB_SEARCH", "show me jobs", "find jobs", "show jobs", "find me"),
            new Rule("SKILL_DEMAND", "skill", "skills", "in demand", "top"));

    public StubAiClient() {
        log.info("Stub AI client active — questions are matched by keyword, not by a model");
    }

    @Override
    public String providerName() {
        return "stub";
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public String complete(AiCompletionRequest request) {
        return switch (request.task()) {
            case INTENT_EXTRACTION -> extractIntent(request.user());
            case ANSWER_GENERATION -> describe(request.user());
        };
    }

    /**
     * Returns the same JSON shape the real provider is asked for, so the parser and the
     * validator are exercised identically under both.
     *
     * <p>Only the question is matched, never the context section. The previous turn is
     * background, and matching on it makes every follow-up inherit the intent of the
     * question before it — asking about categories after asking about cities would keep
     * answering about cities. A real model is told the same thing in the prompt; here it
     * has to be enforced, because a keyword matcher cannot read a heading.
     *
     * <p>Entities are left null on purpose. Pulling a skill name out of a sentence is the
     * part that genuinely needs a model, and guessing here would make the stub look more
     * capable than it is while hiding validation bugs behind lucky matches.
     */
    private String extractIntent(String userMessage) {
        String lower = PromptMarkers.questionOf(userMessage).toLowerCase(Locale.ROOT);
        String intent = RULES.stream()
                .filter(rule -> rule.matches(lower))
                .map(Rule::intent)
                .findFirst()
                .orElse("GENERAL_JOB_MARKET");

        return """
                {"intent":"%s","entities":{"skill":null,"secondSkill":null,"jobCategory":null,\
                "secondJobCategory":null,"company":null,"location":null,"title":null},\
                "timeRange":null,"limit":null}""".formatted(intent);
    }

    /**
     * Echoes the supplied rows back as a sentence.
     *
     * <p>It invents nothing, which is the property the grounding tests check: given no
     * rows it says so, and given rows it names only what it was handed.
     */
    private String describe(String payload) {
        if (payload.contains("\"rows\": []") || payload.contains("\"rows\":[]")) {
            return "The dataset does not contain matching data for that question.";
        }
        return "Based on the current dataset, here are the matching results.";
    }

    /** A keyword rule: one intent, and the phrases that select it. */
    private record Rule(String intent, String... keywords) {

        boolean matches(String lowerQuestion) {
            for (String keyword : keywords) {
                if (lowerQuestion.contains(keyword)) {
                    return true;
                }
            }
            return false;
        }
    }
}
