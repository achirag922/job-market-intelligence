package com.jmip.common.exception;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mapping.PropertyReferenceException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;

/**
 * Translates exceptions into the {@link ApiError} response format so that callers
 * never receive a stack trace or an unstructured error body.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiError> handleResourceNotFound(ResourceNotFoundException exception,
                                                           HttpServletRequest request) {
        log.debug("Resource not found for {}: {}", request.getRequestURI(), exception.getMessage());
        return build(HttpStatus.NOT_FOUND, exception.getMessage(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleRequestBodyValidation(MethodArgumentNotValidException exception,
                                                                HttpServletRequest request) {
        List<ApiError.FieldError> fieldErrors = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new ApiError.FieldError(error.getField(), error.getDefaultMessage()))
                .toList();
        log.debug("Validation failed for {}: {}", request.getRequestURI(), fieldErrors);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiError.of(HttpStatus.BAD_REQUEST.value(), HttpStatus.BAD_REQUEST.getReasonPhrase(),
                        "Request validation failed", request.getRequestURI(), fieldErrors));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> handleParameterValidation(ConstraintViolationException exception,
                                                              HttpServletRequest request) {
        List<ApiError.FieldError> fieldErrors = exception.getConstraintViolations().stream()
                .map(violation -> new ApiError.FieldError(lastPathNode(violation), violation.getMessage()))
                .toList();
        log.debug("Parameter validation failed for {}: {}", request.getRequestURI(), fieldErrors);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiError.of(HttpStatus.BAD_REQUEST.value(), HttpStatus.BAD_REQUEST.getReasonPhrase(),
                        "Request validation failed", request.getRequestURI(), fieldErrors));
    }

    @ExceptionHandler(InvalidRequestException.class)
    public ResponseEntity<ApiError> handleInvalidRequest(InvalidRequestException exception,
                                                         HttpServletRequest request) {
        log.debug("Invalid request for {}: {}", request.getRequestURI(), exception.getMessage());
        return build(HttpStatus.BAD_REQUEST, exception.getMessage(), request);
    }

    /**
     * Raised by Spring Data when a sort names a field that does not exist. It is a bad
     * request, not a server fault, and would otherwise surface as a 500.
     */
    @ExceptionHandler(PropertyReferenceException.class)
    public ResponseEntity<ApiError> handleUnknownProperty(PropertyReferenceException exception,
                                                          HttpServletRequest request) {
        log.debug("Unknown property in request to {}: {}", request.getRequestURI(), exception.getMessage());
        return build(HttpStatus.BAD_REQUEST,
                "Unknown field '" + exception.getPropertyName() + "' in sort or filter", request);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleTypeMismatch(MethodArgumentTypeMismatchException exception,
                                                       HttpServletRequest request) {
        String message = "Parameter '%s' has an invalid value: %s".formatted(exception.getName(), exception.getValue());
        log.debug("Type mismatch for {}: {}", request.getRequestURI(), message);
        return build(HttpStatus.BAD_REQUEST, message, request);
    }

    /**
     * A request to a path that has no handler. Without this the catch-all below would
     * report a mistyped URL as a server fault.
     */
    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<ApiError> handleNoHandler(Exception exception, HttpServletRequest request) {
        log.debug("No handler for {} {}", request.getMethod(), request.getRequestURI());
        return build(HttpStatus.NOT_FOUND,
                "No endpoint for %s %s".formatted(request.getMethod(), request.getRequestURI()), request);
    }

    /**
     * Anything else.
     *
     * <p>Spring's own web exceptions carry the status they deserve, and this advice runs
     * before Spring's default resolver, so that status has to be honoured here or it is
     * lost. Only genuinely unrecognised failures become a 500, and only those are logged
     * at error level: a 404 from a typo is not worth an alert.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception exception, HttpServletRequest request) {
        if (exception instanceof ErrorResponse errorResponse) {
            HttpStatus status = HttpStatus.valueOf(errorResponse.getStatusCode().value());
            log.debug("{} for {}: {}", status, request.getRequestURI(), exception.getMessage());
            String detail = errorResponse.getBody().getDetail();
            return build(status, detail == null ? status.getReasonPhrase() : detail, request);
        }
        log.error("Unhandled exception for {}", request.getRequestURI(), exception);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred", request);
    }

    private static String lastPathNode(ConstraintViolation<?> violation) {
        String path = violation.getPropertyPath().toString();
        int separator = path.lastIndexOf('.');
        return separator < 0 ? path : path.substring(separator + 1);
    }

    private static ResponseEntity<ApiError> build(HttpStatus status, String message, HttpServletRequest request) {
        return ResponseEntity.status(status)
                .body(ApiError.of(status.value(), status.getReasonPhrase(), message, request.getRequestURI()));
    }
}
