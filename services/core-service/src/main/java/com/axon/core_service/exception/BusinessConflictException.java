package com.axon.core_service.exception;

public class BusinessConflictException extends IllegalStateException {

    public BusinessConflictException(String message) {
        super(message);
    }
}
