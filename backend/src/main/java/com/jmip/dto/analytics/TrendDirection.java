package com.jmip.dto.analytics;

/** Which way a skill is moving, once the noise band is applied. */
public enum TrendDirection {
    RISING,
    FALLING,
    /** Inside the noise band: the change is too small to call either way. */
    STABLE
}
