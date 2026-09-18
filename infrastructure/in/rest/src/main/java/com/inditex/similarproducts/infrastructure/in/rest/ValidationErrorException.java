package com.inditex.similarproducts.infrastructure.in.rest;

/**
 * Custom exception for parameter validation errors. Used for manual validation in
 * bare controller test scenarios where Bean Validation annotations don't automatically
 * trigger due to lack of Spring infrastructure (MethodValidationPostProcessor).
 */
class ValidationErrorException extends RuntimeException {
    ValidationErrorException(String message) {
        super(message);
    }
}
