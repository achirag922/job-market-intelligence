package com.jmip.dto.alert;

import jakarta.validation.constraints.NotNull;

/** Turn an alert on or off without touching its criteria. */
public record JobAlertStatusRequest(@NotNull(message = "active is required (true or false)") Boolean active) {
}
