package com.jmip.service.assistant;

import com.jmip.dto.assistant.AssistantEntities;

/**
 * Entities that have been checked against the database and replaced with the values it
 * actually holds.
 *
 * <p>The difference between this and {@link AssistantEntities} is the whole security
 * argument of the assistant. That one holds whatever a model or a browser said. This one
 * holds names that exist, and ids that were looked up — so by the time a value reaches a
 * query it is a row the database already had, not a string someone supplied.
 *
 * @param skill             canonical skill name, exactly as stored
 * @param secondSkill       the other side of a comparison
 * @param jobCategory       canonical category name, exactly as stored
 * @param secondJobCategory the other side of a comparison
 * @param companyId         resolved company, null when none was named
 * @param companyName       that company's stored name, for labelling
 * @param location          a place name matching some location's city, state or country
 * @param title             free text, length-bounded. The one entity with no dictionary to
 *                          check against, and therefore the one that only ever reaches the
 *                          database as a bound parameter
 */
public record ResolvedEntities(
        String skill,
        String secondSkill,
        String jobCategory,
        String secondJobCategory,
        Long companyId,
        String companyName,
        String location,
        String title) {

    public static ResolvedEntities empty() {
        return new ResolvedEntities(null, null, null, null, null, null, null, null);
    }

    public boolean hasSkill() {
        return skill != null;
    }

    public boolean hasSecondSkill() {
        return secondSkill != null;
    }

    public boolean hasCategory() {
        return jobCategory != null;
    }

    public boolean hasSecondCategory() {
        return secondJobCategory != null;
    }

    public boolean hasCompany() {
        return companyId != null;
    }

    public boolean hasLocation() {
        return location != null;
    }

    public boolean hasTitle() {
        return title != null;
    }

    /** Turns the resolved values back into the shape the conversation context carries. */
    public AssistantEntities toContextEntities() {
        return new AssistantEntities(
                skill, secondSkill, jobCategory, secondJobCategory, companyName, location, title);
    }
}
