package com.transport.backend.exception;

/** The request conflicts with existing data (duplicate, or still referenced by other records). HTTP 409. */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
