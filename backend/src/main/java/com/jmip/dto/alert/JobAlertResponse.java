package com.jmip.dto.alert;

import com.jmip.entity.AlertFrequency;
import com.jmip.entity.JobAlert;

import java.time.OffsetDateTime;
import java.util.UUID;

/** A job alert as its owner sees it. The owning account is implied, never echoed back. */
public record JobAlertResponse(
        UUID id,
        String name,
        String keywords,
        String category,
        String location,
        String experience,
        String skill,
        AlertFrequency frequency,
        boolean active,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public static JobAlertResponse of(JobAlert alert) {
        return new JobAlertResponse(alert.getId(), alert.getName(), alert.getKeywords(), alert.getCategory(),
                alert.getLocation(), alert.getExperience(), alert.getSkill(), alert.getFrequency(),
                alert.isActive(), alert.getCreatedAt(), alert.getUpdatedAt());
    }
}
