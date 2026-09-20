package com.jmip.repository.projection;

/** Raw count of postings pairing one stored title with one skill. */
public record TitleSkillCountRow(String title, Long skillId, String skillName, String category, long jobCount) {
}
