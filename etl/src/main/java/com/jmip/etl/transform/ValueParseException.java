package com.jmip.etl.transform;

/**
 * Raised when a field is present but cannot be understood. Distinct from a field simply
 * being absent, which is normal and produces {@code null} rather than an exception.
 */
public class ValueParseException extends RuntimeException {

    public ValueParseException(String message) {
        super(message);
    }
}
