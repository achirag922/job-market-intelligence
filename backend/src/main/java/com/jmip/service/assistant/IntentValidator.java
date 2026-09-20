package com.jmip.service.assistant;

import com.jmip.config.AssistantProperties;
import com.jmip.dto.assistant.AssistantEntities;
import com.jmip.dto.assistant.AssistantIntent;
import com.jmip.dto.assistant.ConversationContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Decides whether an extracted intent may be acted on.
 *
 * <p>The model's output arrives here as a suggestion and leaves as either a
 * {@link ResolvedIntent} or a sentence explaining why not. Nothing is taken on trust: the
 * intent name must be a member of the enum, every entity must resolve to a stored row, the
 * limit is clamped whatever was asked for, and an intent that needs an entity it did not
 * get is rejected rather than run with a null filter.
 *
 * <p>Rejecting is cheap and being wrong is not. A question the assistant declines costs the
 * user one rephrase; a question it answers with the wrong filter costs them a wrong number
 * they have no way to spot.
 */
@Component
public class IntentValidator {

    private static final Logger log = LoggerFactory.getLogger(IntentValidator.class);

    /** The window the trend service accepts. Outside it, the request would be rejected. */
    private static final int MIN_TREND_MONTHS = 2;
    private static final int MAX_TREND_MONTHS = 36;

    private static final String REPHRASE =
            "I can answer questions about skills, job categories, companies, locations, "
                    + "salaries, trends and your resume against this dataset. "
                    + "Could you rephrase your question in those terms?";

    private final EntityResolver entityResolver;
    private final AssistantProperties properties;

    public IntentValidator(EntityResolver entityResolver, AssistantProperties properties) {
        this.entityResolver = entityResolver;
        this.properties = properties;
    }

    /**
     * @param extraction what the model produced, entirely untrusted
     * @param context    the previous turn, used to fill in what this question left unsaid
     */
    public IntentValidation validate(IntentExtraction extraction, ConversationContext context) {
        Optional<AssistantIntent> parsed = AssistantIntent.parse(extraction.intent());
        if (parsed.isEmpty()) {
            // The model returned a name outside the enum. Treated exactly like an
            // unsupported question: there is no nearest match worth guessing at.
            log.warn("AI returned an unrecognised intent name");
            return IntentValidation.rejected(REPHRASE);
        }

        AssistantIntent intent = parsed.get();
        if (intent == AssistantIntent.UNSUPPORTED) {
            return IntentValidation.rejected(REPHRASE);
        }

        AssistantEntities merged = carryForward(extraction.entitiesOrEmpty(), context);

        EntityResolver.Resolution resolution = entityResolver.resolve(merged);
        if (resolution.failed()) {
            return IntentValidation.rejected(resolution.message());
        }

        ResolvedEntities entities = resolution.entities();
        String missing = missingRequiredEntity(intent, entities);
        if (missing != null) {
            return IntentValidation.rejected(missing);
        }

        return IntentValidation.accepted(new ResolvedIntent(
                intent,
                entities,
                months(extraction.timeRange()),
                limitFor(intent, extraction.limit())));
    }

    /**
     * Fills in entity kinds this question did not mention from the previous turn.
     *
     * <p>This is what makes "what about Bengaluru?" mean "the same thing, in Bengaluru".
     * The model is given the previous turn too and is asked to carry values forward itself;
     * doing it here as well means the behaviour holds even when it does not.
     *
     * <p>The cost is that a question meant to widen the scope — "and what about skills
     * overall?" — keeps the previous filter, because nothing in the words distinguishes
     * widening from referring back. A fresh question with the scope named is the way out.
     */
    private static AssistantEntities carryForward(AssistantEntities current,
                                                  ConversationContext context) {
        if (context == null || context.isEmpty()) {
            return current;
        }
        AssistantEntities previous = context.entitiesOrEmpty();
        return new AssistantEntities(
                orPrevious(current.skill(), previous.skill()),
                // Comparison operands are never carried forward: half of a remembered
                // comparison silently changes what is being compared.
                current.secondSkill(),
                orPrevious(current.jobCategory(), previous.jobCategory()),
                current.secondJobCategory(),
                orPrevious(current.company(), previous.company()),
                orPrevious(current.location(), previous.location()),
                orPrevious(current.title(), previous.title()));
    }

    private static String orPrevious(String current, String previous) {
        return current != null ? current : previous;
    }

    /**
     * The entities an intent cannot run without.
     *
     * <p>Running one of these with a null filter would not fail — it would answer a much
     * broader question and present the result as if it were the answer to the narrow one.
     *
     * @return a sentence asking for what is missing, or null when nothing is
     */
    private static String missingRequiredEntity(AssistantIntent intent, ResolvedEntities entities) {
        return switch (intent) {
            case SKILL_TREND -> entities.hasSkill() ? null
                    : "Which skill would you like the trend for?";
            case SKILL_COMPARISON -> entities.hasSkill() && entities.hasSecondSkill() ? null
                    : "Which two skills would you like me to compare?";
            case CATEGORY_COMPARISON -> entities.hasCategory() && entities.hasSecondCategory() ? null
                    : "Which two job categories would you like me to compare?";
            default -> null;
        };
    }

    /** Job rows are heavier than analytics rows and have their own ceiling. */
    private int limitFor(AssistantIntent intent, Integer requested) {
        return intent == AssistantIntent.JOB_SEARCH
                ? properties.clampJobLimit(requested)
                : properties.clampLimit(requested);
    }

    /**
     * Parses the period, clamped to what the trend service accepts.
     *
     * <p>Unparseable text becomes null rather than an error: the model was asked for an
     * integer and "last year or so" is a failure to follow that, not a reason to refuse an
     * otherwise good question. The trend service then uses its own default.
     */
    private static Integer months(String timeRange) {
        if (timeRange == null || timeRange.isBlank()) {
            return null;
        }
        try {
            int months = Integer.parseInt(timeRange.trim());
            return Math.min(MAX_TREND_MONTHS, Math.max(MIN_TREND_MONTHS, months));
        } catch (NumberFormatException notANumber) {
            log.debug("AI returned an unparseable timeRange");
            return null;
        }
    }
}
