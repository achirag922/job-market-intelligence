package com.jmip.dto.assistant;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.Size;

/**
 * The things a question can be about.
 *
 * <p>Used in two places: as the model's extraction output, and as the memory of the
 * previous turn. In both it is untrusted — a model wrote one, a browser sent the other —
 * so every value is resolved against the database before it reaches a query.
 *
 * <p>The sizes are a first guard, not the real check. They stop an oversized payload
 * cheaply; the resolver is what decides a value is real.
 *
 * @param secondSkill       the other side of a skill comparison
 * @param secondJobCategory the other side of a category comparison
 * @param title             free text that is neither a skill nor a category
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record AssistantEntities(
        @Size(max = 100) String skill,
        @Size(max = 100) String secondSkill,
        @Size(max = 50) String jobCategory,
        @Size(max = 50) String secondJobCategory,
        @Size(max = 255) String company,
        @Size(max = 200) String location,
        @Size(max = 300) String title) {

    public static AssistantEntities empty() {
        return new AssistantEntities(null, null, null, null, null, null, null);
    }

    /** Blank is the same as absent, whether it came from a model or a browser. */
    public AssistantEntities {
        skill = blankToNull(skill);
        secondSkill = blankToNull(secondSkill);
        jobCategory = blankToNull(jobCategory);
        secondJobCategory = blankToNull(secondJobCategory);
        company = blankToNull(company);
        location = blankToNull(location);
        title = blankToNull(title);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
