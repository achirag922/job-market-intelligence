package com.jmip.repository.projection;

/** Raw count of postings sharing one minimum-experience value. */
public record ExperienceCountRow(Short experienceMin, long jobCount) {
}
