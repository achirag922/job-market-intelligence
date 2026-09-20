package com.jmip.dto.assistant;

/**
 * One plotted value.
 *
 * @param label what it is — a skill, a company, a month
 * @param value the measured number, already read from the database
 */
public record ChartPoint(String label, double value) {
}
