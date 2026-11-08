package com.transport.backend.exception;

/** Credentials (e.g. a sensor device key) are missing or invalid. HTTP 401. */
public class UnauthorizedException extends RuntimeException {

    public UnauthorizedException(String message) {
        super(message);
    }
}
