package com.jmip.repository.projection;

/** Aggregate row behind skill analytics. Built by a JPQL constructor expression. */
public record SkillDemandRow(Long skillId, String name, String category, long jobCount) {
}
