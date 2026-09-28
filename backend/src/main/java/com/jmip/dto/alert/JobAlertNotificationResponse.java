package com.jmip.dto.alert;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * V8.4: one job an alert recorded for its owner, and whether its digest went out.
 *
 * @param status PENDING (waiting for the next digest), SENT or FAILED (retried on later passes)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record JobAlertNotificationResponse(
        long jobId,
        String jobTitle,
        String companyName,
        BigDecimal matchPercentage,
        String status,
        OffsetDateTime recordedAt,
        OffsetDateTime sentAt) {
}
