package com.jmip.common.exception;

/**
 * Thrown when a request is syntactically fine but asks for something the API cannot do,
 * such as sorting by a field that is not sortable.
 */
public class InvalidRequestException extends RuntimeException {

    public InvalidRequestException(String message) {
        super(message);
    }
}
