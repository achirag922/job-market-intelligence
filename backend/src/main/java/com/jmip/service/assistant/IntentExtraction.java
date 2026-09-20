package com.jmip.service.assistant;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.jmip.dto.assistant.AssistantEntities;

/**
 * What the model returned, before anything has been checked.
 *
 * <p>Every field is a suggestion. The intent may name something that does not exist, the
 * entities may name a skill nobody has heard of, the limit may be a million. This record
 * exists to give that output a shape so it can be validated; it is never used to query
 * anything.
 *
 * @param intent    an intent name, unvalidated
 * @param entities  entity values, unresolved
 * @param timeRange a number of months as text, unparsed
 * @param limit     how many rows the model thinks were asked for, unclamped
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record IntentExtraction(
        String intent,
        AssistantEntities entities,
        String timeRange,
        Integer limit) {

    public AssistantEntities entitiesOrEmpty() {
        return entities == null ? AssistantEntities.empty() : entities;
    }
}
