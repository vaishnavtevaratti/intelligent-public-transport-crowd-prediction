package com.transport.backend.exception;

/** A required external service (e.g. the Python ML service) cannot be reached. HTTP 503. */
public class ServiceUnavailableException extends RuntimeException {

    public ServiceUnavailableException(String message) {
        super(message);
    }

    public ServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
