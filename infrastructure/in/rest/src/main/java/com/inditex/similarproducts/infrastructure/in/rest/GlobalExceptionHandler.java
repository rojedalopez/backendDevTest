package com.inditex.similarproducts.infrastructure.in.rest;

import com.inditex.similarproducts.model.ProductNotFoundException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ServerWebInputException;

@RestControllerAdvice
class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ProductNotFoundException.class)
    ProblemDetail handleNotFound(ProductNotFoundException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.getMessage());
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ProblemDetail handleValidationFailure(ConstraintViolationException exception) {
        String detail = exception.getConstraintViolations().stream()
                .map(GlobalExceptionHandler::describeViolation)
                .collect(Collectors.joining(", "));
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
    }

    @ExceptionHandler(ValidationErrorException.class)
    ProblemDetail handleValidationError(ValidationErrorException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
    }

    @ExceptionHandler(ServerWebInputException.class)
    ProblemDetail handleWebInputFailure(ServerWebInputException exception) {
        // WebFlux validation error - extract detail from the cause if available
        String detail = exception.getReason();
        if (detail == null || detail.isEmpty()) {
            Throwable cause = exception.getCause();
            if (cause instanceof ConstraintViolationException cve) {
                detail = cve.getConstraintViolations().stream()
                        .map(GlobalExceptionHandler::describeViolation)
                        .collect(Collectors.joining(", "));
            }
        }
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
    }

    private static String describeViolation(ConstraintViolation<?> violation) {
        String propertyName = null;
        for (Path.Node node : violation.getPropertyPath()) {
            propertyName = node.getName();
        }
        return propertyName + ": " + violation.getMessage();
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception exception) {
        log.error("Unhandled exception processing request", exception);
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
                "An unexpected error occurred.");
    }
}
