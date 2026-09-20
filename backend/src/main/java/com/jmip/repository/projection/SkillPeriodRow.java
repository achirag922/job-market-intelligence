package com.jmip.repository.projection;

import java.time.LocalDate;

/** One skill's demand in one period, as stored in the snapshot history. */
public record SkillPeriodRow(
        LocalDate periodStart,
        Long skillId,
        String skillName,
        String category,
        int jobCount,
        int totalJobs) {
}
