package com.jmip.service.assistant;

import com.jmip.dto.assistant.AssistantIntent;

/**
 * A question that has been understood, checked, and is safe to act on.
 *
 * <p>Nothing reaches an analytics service except through one of these. Every field has
 * been through validation: the intent is a member of a closed enum, the entities name rows
 * that exist, the limit is inside the configured bounds and the period is inside the range
 * the trend service accepts.
 *
 * @param intent   which of the supported questions this is
 * @param entities resolved, canonical entity values
 * @param months   period for trend questions, already clamped, null when not asked for
 * @param limit    how many rows to return, already clamped
 */
public record ResolvedIntent(
        AssistantIntent intent,
        ResolvedEntities entities,
        Integer months,
        int limit) {
}
