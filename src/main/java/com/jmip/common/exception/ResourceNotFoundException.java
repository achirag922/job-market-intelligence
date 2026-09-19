package com.jmip.common.exception;

/**
 * Thrown when a requested resource (job, company, location, skill) does not exist.
 */
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String message) {
        super(message);
    }

    public static ResourceNotFoundException of(String resourceName, Object identifier) {
        return new ResourceNotFoundException("%s not found: %s".formatted(resourceName, identifier));
    }
}
