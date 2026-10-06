package com.jmip.common.exception;

/** V9.7: the request clashes with existing data, such as a profile address someone else uses (409). */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
