package com.jmip.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;

/**
 * A salary range. Either bound may be absent; the currency is always present when an
 * amount is.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SalaryResponse(BigDecimal min, BigDecimal max, String currency) {

    /** @return null when the posting disclosed no salary, so the field is simply omitted */
    public static SalaryResponse of(BigDecimal min, BigDecimal max, String currency) {
        if (min == null && max == null) {
            return null;
        }
        return new SalaryResponse(min, max, currency == null ? null : currency.trim());
    }
}
